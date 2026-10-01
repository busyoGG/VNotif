package dev.busyo.vnotif;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Best-effort back-channel: posts one line of phone-side state to the PC so it shows up in its log.
 *
 * <p>There is no adb link to this phone, so anything only the phone knows — which package an icon
 * was finally taken from, what the system says about our notification channels — used to be visible
 * on its screen alone and had to be relayed by hand. Reports are fire-and-forget on a daemon thread:
 * every failure is swallowed and the bridge must behave identically whether or not one gets through.
 *
 * <p>Reports are fire-and-forget on a daemon thread: every failure is swallowed and the bridge must
 * behave identically whether or not one gets through.
 *
 * <p><b>自签证书端点必须跟着端点一起放行</b>：走 FRP 公网入口时是自签 HTTPS，拉流那条连接带了
 * {@code permissiveSocketFactory}（见 {@link BridgeService}），上报这条如果只 {@code openConnection()}
 * 就会在握手阶段被拒——而且失败是静默的，表现为"手机在跑、日志里却一条上报都没有"。
 */
public final class Report {

    private static volatile String baseUrl = "";
    private static volatile String token = "";
    /** 端点是否勾了"允许自签证书"，上报要跟拉流用同一套 TLS 放行策略。 */
    private static volatile boolean allowInsecure;

    private Report() {
    }

    /** Called once the bridge knows an endpoint that actually works. */
    static void configure(String base, String bearer, boolean insecure) {
        baseUrl = base == null ? "" : base;
        token = bearer == null ? "" : bearer;
        allowInsecure = insecure;
    }

    static void send(String tag, String text) {
        final String base = baseUrl;
        final String bearer = token;
        final boolean insecure = allowInsecure;
        if (base.isEmpty()) {
            return;
        }
        final String payload;
        try {
            JSONObject o = new JSONObject();
            o.put("tag", tag);
            o.put("text", text == null ? "" : text);
            payload = o.toString();
        } catch (Exception e) {
            return;
        }
        Thread t = new Thread(() -> post(base, bearer, insecure, payload), "vnotif-report");
        t.setDaemon(true);
        t.start();
    }

    private static void post(String base, String bearer, boolean insecure, String payload) {
        HttpURLConnection conn = null;
        try {
            String url = base + (base.endsWith("/") ? "" : "/") + "report";
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (insecure && conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(BridgeService.permissiveSocketFactory());
                https.setHostnameVerifier(BridgeService.permissiveHostnameVerifier());
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!bearer.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
            // Diagnostics only; nothing to recover.
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
