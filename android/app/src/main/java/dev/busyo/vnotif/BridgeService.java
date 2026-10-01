package dev.busyo.vnotif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Foreground service holding one long-lived {@code GET /stream} NDJSON connection.
 *
 * <p>Reconnect strategy: endpoints are tried in order with the last known-good one first; a
 * successful-but-dropped stream reconnects after ~1s, a failed endpoint set backs off
 * exponentially (1s → 60s, jittered), and a 401/403 backs off for a full minute because hammering
 * a bad token is pointless.
 */
public class BridgeService extends Service {

    public static final String ACTION_START = "dev.busyo.vnotif.START";

    /**
     * Sent as {@code User-Agent: VNotif/<tag>} on every stream connect so the PC log shows which
     * build is actually running on the phone. Bump it whenever the rendering model changes — that
     * is the only way to tell "old APK still installed" apart from "new APK misbehaves".
     */
    static final String BUILD_TAG = "report19";

    private static final int FG_ID = 1;
    private static final String FG_CHANNEL = "vnotif_service";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final long BACKOFF_MIN_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;
    private static final long AUTH_BACKOFF_MS = 60_000L;
    /** A stream must survive at least this long to count as a healthy connection. */
    private static final long HEALTHY_STREAM_MS = 3_000L;

    private static final int RESULT_CONNECTED = 0;
    private static final int RESULT_FAILED = 1;
    private static final int RESULT_AUTH_FAILED = 2;

    private volatile boolean running;
    private Thread worker;
    private NotifRenderer renderer;
    private final AtomicInteger pingCounter = new AtomicInteger();

    // ------------------------------------------------------------ lifecycle

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BridgeService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BridgeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        renderer = new NotifRenderer(this);
        // 前台抑制的轮询线程：服务活着时一直跑。
        ForegroundWatcher.attach(this);
        createForegroundChannel();
        // Snapshot of the system's channel/permission state, so "why is it silent" has real data.
        NotifDiagnostics.log(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildForegroundNotification("VNotif 正在启动…"));

        if (worker == null || !worker.isAlive()) {
            running = true;
            BridgeState.running = true;
            worker = new Thread(this::runLoop, "vnotif-stream");
            worker.setDaemon(true);
            worker.start();
            BridgeState.log("服务已启动");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        BridgeState.running = false;
        BridgeState.status = "未运行";
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        BridgeState.log("服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -------------------------------------------------------------- worker

    private void runLoop() {
        long backoff = BACKOFF_MIN_MS;
        while (running) {
            // 顺序 = 用户在界面上排的顺序：从上往下试，第一个连通的就用它。
            List<EndpointStore.Endpoint> endpoints = EndpointStore.load(this);
            if (endpoints.isEmpty()) {
                BridgeState.status = "未配置端点";
                BridgeState.log("未配置任何端点");
                sleep(10_000L);
                continue;
            }

            boolean connected = false;
            boolean anyStream = false;
            boolean allAuthFailures = true;
            for (EndpointStore.Endpoint ep : endpoints) {
                if (!running) {
                    return;
                }
                long startedAt = System.currentTimeMillis();
                int r = connectAndRead(ep);
                if (r == RESULT_CONNECTED) {
                    allAuthFailures = false;
                    anyStream = true;
                    // A stream that dies within a couple of seconds is not "healthy"; back off
                    // instead of hot-looping against a server that closes right away.
                    connected = System.currentTimeMillis() - startedAt >= HEALTHY_STREAM_MS;
                    break;
                }
                if (r != RESULT_AUTH_FAILED) {
                    allAuthFailures = false;
                }
            }
            if (!running) {
                return;
            }

            if (connected) {
                backoff = BACKOFF_MIN_MS;
                sleep(BACKOFF_MIN_MS);
                continue;
            }

            long wait = allAuthFailures ? AUTH_BACKOFF_MS : backoff;
            BridgeState.status = "重连中（" + (wait / 1000) + "s 后）";
            BridgeState.log((anyStream ? "连接不稳定，" : "全部端点失败，")
                    + (wait / 1000) + "s 后重试");
            sleep(jitter(wait));
            backoff = Math.min(backoff * 2L, BACKOFF_MAX_MS);
        }
    }

    private int connectAndRead(EndpointStore.Endpoint ep) {
        HttpURLConnection conn = null;
        try {
            URL url = buildUrl(ep);
            BridgeState.endpointName = ep.name;
            BridgeState.endpointUrl = ep.url;
            BridgeState.status = "连接中 " + ep.name;
            // Log the base URL only: the full URL would spill the token into the in-app log.
            BridgeState.log("连接 " + ep.name + " → " + ep.url + "/stream");

            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection && ep.allowInsecure) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(permissiveSocketFactory());
                https.setHostnameVerifier(permissiveHostnameVerifier());
                BridgeState.log("该端点已放行自签证书（仅此端点）");
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoInput(true);
            conn.setRequestProperty("Accept", "application/x-ndjson");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent",
                    "VNotif/" + BUILD_TAG + " android/" + Build.VERSION.SDK_INT);
            if (ep.token != null && !ep.token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + ep.token);
            }

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                BridgeState.status = "鉴权失败 " + ep.name + "（60s 后重试）";
                BridgeState.setError("HTTP " + code + " 鉴权失败（token 是否正确？）");
                return RESULT_AUTH_FAILED;
            }
            if (code != 200) {
                BridgeState.status = "HTTP " + code + " " + ep.name;
                BridgeState.setError("HTTP " + code + " from " + ep.name);
                return RESULT_FAILED;
            }

            BridgeState.status = "已连接 " + ep.name;
            BridgeState.lastError = "";
            BridgeState.log("已连接 " + ep.name);
            Report.configure(ep.url, ep.token, ep.allowInsecure);
            // One snapshot per successful connect: the phone's channel/ringer state reaches the PC
            // log without the user having to open the app and read it out.
            NotifDiagnostics.log(this);
            updateForegroundNotification("VNotif 已连接 " + ep.host());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                handleLine(line);
            }
            BridgeState.log("流已结束（EOF）");
            return RESULT_CONNECTED;
        } catch (SocketTimeoutException e) {
            // 45s without even a ping: the stream is silently dead, reconnect promptly.
            BridgeState.log("读超时（" + (READ_TIMEOUT_MS / 1000) + "s 无数据），重连");
            return RESULT_CONNECTED;
        } catch (Exception e) {
            BridgeState.status = "连接失败 " + ep.name;
            BridgeState.setError(e.toString());
            return RESULT_FAILED;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                    // Disconnect failures are not actionable.
                }
            }
        }
    }

    private void handleLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String op = o.optString("op", "");
            BridgeState.lastMessageAt = System.currentTimeMillis();

            if ("notify".equals(op)) {
                recordApp(o);
                renderer.handleNotify(o);
            } else if ("update".equals(op)) {
                recordApp(o);
                renderer.handleUpdate(o);
            } else if ("dismiss".equals(op)) {
                renderer.handleDismiss(o);
            } else if ("apps".equals(op)) {
                JSONArray list = o.optJSONArray("list");
                MappingStore.recordAppsMessage(this, list);
                BridgeState.log("收到应用清单 " + (list == null ? 0 : list.length()) + " 项");
            } else if ("ping".equals(op)) {
                // Log only every ~2 minutes so the ring buffer stays useful.
                if (pingCounter.incrementAndGet() % 6 == 1) {
                    BridgeState.log("ping");
                }
            } else if ("error".equals(op)) {
                BridgeState.setError("服务端错误: " + o.optString("msg", ""));
            } else {
                BridgeState.log("未知 op: " + op);
            }
        } catch (Exception e) {
            BridgeState.log("无法解析消息: " + e);
        }
    }

    /**
     * Feeds the phone-side app index from a relayed message, so a desktop app that shows up for the
     * first time after connect appears in the mapping screen without waiting for a reconnect.
     */
    private void recordApp(JSONObject o) {
        MappingStore.recordSeen(this, o.optString("app_id", ""), o.optString("app", ""),
                o.optString("pkg_hint", ""));
    }

    // ------------------------------------------------------------- helpers

    /** Builds {@code <base>/stream?token=<token>} from an endpoint definition. */
    public static URL buildUrl(EndpointStore.Endpoint ep) throws MalformedURLException {
        String base = ep.url == null ? "" : ep.url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder sb = new StringBuilder(base).append("/stream");
        if (ep.token != null && !ep.token.isEmpty()) {
            sb.append("?token=").append(encode(ep.token));
        }
        return new URL(sb.toString());
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static long jitter(long base) {
        return base + (long) (Math.random() * base * 0.25d);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Trust-all factory, used only for endpoints where the user ticked "allow self-signed". */
    static SSLSocketFactory permissiveSocketFactory() throws Exception {
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return sc.getSocketFactory();
    }

    static HostnameVerifier permissiveHostnameVerifier() {
        return new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                return true;
            }
        };
    }

    // ---------------------------------------------------- foreground notify

    private void createForegroundChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                FG_CHANNEL, "VNotif 服务状态", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("保持与桌面端的长连接");
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FG_ID, notification);
        }
    }

    private Notification buildForegroundNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, FG_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vnotif)
                .setContentTitle("VNotif")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateForegroundNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(FG_ID, buildForegroundNotification(text));
    }
}
