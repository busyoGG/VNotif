package dev.busyo.vnotif;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * 主界面：连接状态、权限/保活、端点列表、日志。
 *
 * <p>布局全是代码搭的（工程不依赖 AndroidX，没有 XML 布局与 ViewBinding），
 * 但零件统一走 {@link Ui}：语义色卡片、可点击行、文字按钮，深浅色由主题切换。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 连接卡片
    private TextView stState;
    private TextView stEndpoint;
    private TextView stAddress;
    private TextView stMessage;
    private TextView stError;
    // 权限卡片
    private TextView pNotif;
    private TextView pBattery;
    private TextView pUsage;
    // 端点卡片
    private LinearLayout endpointBox;
    // 日志卡片
    private TextView logStatus;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshDynamic();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderEndpoints();
        refreshDynamic();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.color(this, R.color.vnotif_surface));
        scroll.setFillViewport(true);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView appName = Ui.text(this, "VNotif", 22, R.color.vnotif_on_surface, true);
        root.addView(appName);
        root.addView(Ui.caption(this, "把桌面通知按应用转发到这台手机"));

        root.addView(buildStatusCard());
        root.addView(buildPermissionCard());
        root.addView(buildEndpointCard());
        root.addView(buildMappingCard());
        root.addView(buildLogCard());

        Ui.applySystemBars(this, scroll);
        return scroll;
    }

    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "连接"));

        stState = statusRow(card, "状态");
        stEndpoint = statusRow(card, "端点");
        stAddress = statusRow(card, "地址");
        stMessage = statusRow(card, "最后消息");
        stError = statusRow(card, "最后错误");

        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);
        buttons.addView(Ui.filledButton(this, "启动服务", v -> BridgeService.start(this)));
        buttons.addView(Ui.textButton(this, "停止服务", v -> BridgeService.stop(this)));
        card.addView(buttons);
        return card;
    }

    /** 一行「标签 → 值」，返回右侧的值 TextView 供刷新。 */
    private TextView statusRow(LinearLayout card, String label) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));

        TextView name = Ui.caption(this, label);
        name.setWidth(Ui.dp(this, 68));
        row.addView(name);

        TextView value = Ui.mono(this, "—", 13);
        value.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);

        card.addView(row);
        return value;
    }

    private View buildPermissionCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "权限与保活"));
        card.addView(Ui.caption(this, "缺任何一项都会表现为「过一阵就收不到」"));

        Ui.ActionRow notif = Ui.actionRow(this, "通知权限", "Android 13+ 需要手动授予",
                v -> requestNotificationPermission());
        pNotif = notif.status;
        card.addView(notif.row);
        card.addView(Ui.divider(this));

        Ui.ActionRow battery = Ui.actionRow(this, "忽略电池优化", "否则后台长连接会被冻结",
                v -> requestIgnoreBatteryOptimizations());
        pBattery = battery.status;
        card.addView(battery.row);
        card.addView(Ui.divider(this));

        Ui.ActionRow usage = Ui.actionRow(this, "使用情况访问", "打开映射应用时不再弹它的通知",
                v -> openUsageAccessSettings());
        pUsage = usage.status;
        card.addView(usage.row);
        card.addView(Ui.divider(this));

        CheckBox autostart = new CheckBox(this);
        autostart.setText("开机自启");
        autostart.setTextColor(Ui.color(this, R.color.vnotif_on_surface));
        autostart.setChecked(EndpointStore.isAutostart(this));
        autostart.setOnCheckedChangeListener(
                (v, checked) -> EndpointStore.setAutostart(this, checked));
        card.addView(autostart);
        return card;
    }

    private View buildEndpointCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "端点"));
        card.addView(Ui.caption(this, "按顺序尝试，第一个连通的就用它"));

        endpointBox = Ui.column(this);
        endpointBox.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(endpointBox);

        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 8), 0, 0);
        buttons.addView(Ui.filledButton(this, "添加端点", v -> editEndpoint(null)));
        buttons.addView(Ui.textButton(this, "按顺序测试", v -> testInOrder()));
        card.addView(buttons);
        return card;
    }

    private View buildMappingCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "映射"));
        card.addView(Ui.caption(this, "决定点通知打开哪个手机 App"));

        Ui.ActionRow row = Ui.actionRow(this, "桌面应用 → 手机应用",
                "点条目选择，长按清除；打开映射应用时自动清组并抑制",
                v -> startActivity(new Intent(this, MappingActivity.class)));
        card.addView(row.row);
        return card;
    }

    private View buildLogCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "日志"));
        card.addView(Ui.caption(this, "连接、通知、前台抑制的每一步"));

        Ui.ActionRow row = Ui.actionRow(this, "运行日志",
                "二级界面，最新的在最上面；可清空",
                v -> startActivity(new Intent(this, LogActivity.class)));
        logStatus = row.status;
        card.addView(row.row);
        return card;
    }

    private void renderEndpoints() {
        endpointBox.removeAllViews();
        List<EndpointStore.Endpoint> endpoints = EndpointStore.load(this);
        if (endpoints.isEmpty()) {
            endpointBox.addView(Ui.caption(this, "（还没有端点，点「添加端点」）"));
            return;
        }
        String activeUrl = BridgeState.endpointUrl;
        for (int i = 0; i < endpoints.size(); i++) {
            final EndpointStore.Endpoint ep = endpoints.get(i);
            final int index = i;

            LinearLayout item = Ui.column(this);
            item.setBackground(Ui.rounded(this, R.color.vnotif_surface_container_low, 14));
            int p = Ui.dp(this, 12);
            item.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 8);
            item.setLayoutParams(lp);

            LinearLayout head = Ui.row(this);
            head.addView(Ui.text(this, (index + 1) + ". " + ep.name, 14,
                    R.color.vnotif_on_surface, true));
            head.addView(Ui.spacer(this));
            head.addView(Ui.textButton(this, "↑", v -> moveEndpoint(ep, -1)));
            head.addView(Ui.textButton(this, "↓", v -> moveEndpoint(ep, +1)));
            item.addView(head);

            boolean isActive = ep.url != null && ep.url.equals(activeUrl);
            if (isActive) {
                item.addView(Ui.text(this, "正在使用", 12, R.color.vnotif_ok, false));
            }
            item.addView(Ui.mono(this, ep.url, 12));

            StringBuilder meta = new StringBuilder(
                    ep.token == null || ep.token.isEmpty() ? "token 未设置" : "token 已设置");
            if (ep.allowInsecure) {
                meta.append(" · 允许自签证书");
            }
            item.addView(Ui.caption(this, meta.toString()));

            LinearLayout actions = Ui.row(this);
            actions.addView(Ui.textButton(this, "测试", v -> testEndpoint(ep)));
            actions.addView(Ui.textButton(this, "编辑", v -> editEndpoint(ep)));
            actions.addView(Ui.textButton(this, "删除", v -> confirmDelete(ep)));
            item.addView(actions);

            endpointBox.addView(item);
        }
    }

    private void refreshDynamic() {
        stState.setText(BridgeState.running ? BridgeState.status : "未运行");
        stState.setTextColor(Ui.color(this, BridgeState.running
                ? R.color.vnotif_on_surface : R.color.vnotif_on_surface_variant));
        stEndpoint.setText(BridgeState.endpointName.isEmpty() ? "—" : BridgeState.endpointName);
        stAddress.setText(BridgeState.endpointUrl.isEmpty() ? "—" : BridgeState.endpointUrl);
        stMessage.setText(formatTime(BridgeState.lastMessageAt));
        stError.setText(BridgeState.lastError.isEmpty() ? "—" : BridgeState.lastError);
        stError.setTextColor(Ui.color(this, BridgeState.lastError.isEmpty()
                ? R.color.vnotif_on_surface : R.color.vnotif_error));

        setPermissionState(pNotif, hasNotificationPermission());
        setPermissionState(pBattery, isIgnoringBatteryOptimizations());
        setPermissionState(pUsage, ForegroundWatcher.hasUsageAccess(this));

        int logs = BridgeState.logCount();
        logStatus.setText(logs == 0 ? "暂无" : logs + " 条");
    }

    private void setPermissionState(TextView view, boolean granted) {
        view.setText(granted ? "已授予" : "未授予");
        view.setTextColor(Ui.color(this, granted ? R.color.vnotif_ok : R.color.vnotif_warn));
    }

    // ----------------------------------------------------------- endpoints

    private void moveEndpoint(EndpointStore.Endpoint ep, int delta) {
        EndpointStore.move(this, ep.id, delta);
        renderEndpoints();
        toast("已调整顺序，重启服务后生效");
    }

    private void editEndpoint(final EndpointStore.Endpoint existing) {
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);

        final EditText name = new EditText(this);
        name.setHint("名称，如 局域网 / FRP");
        name.setSingleLine(true);

        final EditText url = new EditText(this);
        url.setHint("http://192.168.1.100:8765");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        final EditText token = new EditText(this);
        token.setHint("token（可留空）");
        token.setSingleLine(true);

        final CheckBox insecure = new CheckBox(this);
        insecure.setText("允许自签证书（仅此端点）");

        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
            token.setText(existing.token);
            insecure.setChecked(existing.allowInsecure);
        } else {
            name.setText("FRP");
            url.setText("https://");
        }

        box.addView(name);
        box.addView(url);
        box.addView(token);
        box.addView(insecure);
        box.addView(Ui.caption(this, "公网地址（如 https://example.com:37070/vnotif）在这里手填；"
                + "预置的那条只是局域网地址。"));

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加端点" : "编辑端点")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "http://" + u;
                    }
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        n = u;
                    }
                    EndpointStore.Endpoint ep = new EndpointStore.Endpoint(
                            existing == null ? "" : existing.id,
                            n,
                            u,
                            token.getText().toString().trim(),
                            insecure.isChecked());
                    EndpointStore.upsert(this, ep);
                    renderEndpoints();
                    if (BridgeState.running) {
                        toast("已保存，重启服务后生效");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final EndpointStore.Endpoint ep) {
        new AlertDialog.Builder(this)
                .setTitle("删除端点")
                .setMessage(ep.name + "\n" + ep.url)
                .setPositiveButton("删除", (d, w) -> {
                    EndpointStore.remove(this, ep.id);
                    renderEndpoints();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按配置顺序逐条试，遇到第一条能收到数据的就停——跟服务真正的选择逻辑一致。 */
    private void testInOrder() {
        final List<EndpointStore.Endpoint> eps = EndpointStore.load(this);
        if (eps.isEmpty()) {
            toast("请先添加端点");
            return;
        }
        toast("正在按顺序测试 " + eps.size() + " 条端点…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (EndpointStore.Endpoint ep : eps) {
                String result = probe(ep, 3000);
                sb.append("【").append(ep.name).append("】\n").append(result).append("\n\n");
                if (result.startsWith("连接成功，收到")) {
                    sb.append("→ 服务会用这一条。");
                    break;
                }
            }
            final String text = sb.toString();
            handler.post(() -> dialog("按顺序测试", text));
        }, "vnotif-probe").start();
    }

    private void testEndpoint(final EndpointStore.Endpoint ep) {
        toast("正在测试 " + ep.name + " …");
        new Thread(() -> {
            final String result = probe(ep, 3000);
            handler.post(() -> dialog("测试 " + ep.name, result));
        }, "vnotif-probe").start();
    }

    /** Pulls the stream for a few seconds and reports what came back. */
    private String probe(EndpointStore.Endpoint ep, int millis) {
        HttpURLConnection conn = null;
        try {
            URL url = BridgeService.buildUrl(ep);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection && ep.allowInsecure) {
                // Mirrors BridgeService: permissive TLS only when the user opted in for this endpoint.
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(BridgeService.permissiveSocketFactory());
                https.setHostnameVerifier(BridgeService.permissiveHostnameVerifier());
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(millis);
            conn.setRequestProperty("Accept", "application/x-ndjson");
            if (ep.token != null && !ep.token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + ep.token);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                return "HTTP " + code
                        + (code == 401 || code == 403 ? "\n鉴权失败：检查 token" : "");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            long deadline = System.currentTimeMillis() + millis;
            int lines = 0;
            String first = null;
            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException ste) {
                    // Android 7+ 的 HttpURLConnection 由 OkHttp 支撑：读超时后底层 socket 已被
                    // 关闭，再读只会得到 "SocketException: Socket closed"。这段时间没有新数据
                    // 就是正常空闲（服务端只在有通知或 20s 心跳时才写），结束采样即可。
                    break;
                }
                if (line == null) {
                    return "连接成功（HTTP 200），但流已结束（EOF）。";
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                lines++;
                if (first == null) {
                    first = line;
                }
            }
            if (lines == 0) {
                return "连接成功（HTTP 200），但 " + (millis / 1000) + " 秒内没有收到数据。\n"
                        + "服务端是否在运行？token 是否正确？";
            }
            return "连接成功，收到 " + lines + " 行数据。\n\n首行：\n" + truncate(first, 400);
        } catch (Exception e) {
            return "失败：" + e;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                    // Nothing to do.
                }
            }
        }
    }

    // ------------------------------------------------------------ permissions

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                toast("通知权限已授予");
                return;
            }
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            toast("当前系统版本无需单独申请通知权限");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "通知权限已授予" : "通知权限被拒绝，通知将无法显示");
            refreshDynamic();
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("无法打开电池优化设置: " + e2);
            }
        }
    }

    /** 前台抑制依赖「使用情况访问」这个特殊权限，只能把用户引导到系统设置里手动开。 */
    private void openUsageAccessSettings() {
        if (ForegroundWatcher.hasUsageAccess(this)) {
            toast("使用情况访问权限已授予");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            toast("无法打开使用情况访问设置: " + e);
        }
    }

    // ----------------------------------------------------------------- helpers

    private void dialog(String title, String message) {
        TextView tv = Ui.mono(this, message, 12);
        int p = Ui.dp(this, 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String formatTime(long millis) {
        if (millis <= 0L) {
            return "—";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
