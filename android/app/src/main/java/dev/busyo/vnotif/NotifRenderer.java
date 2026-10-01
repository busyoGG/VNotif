package dev.busyo.vnotif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.provider.Settings;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renders relayed desktop notifications.
 *
 * <p>聚合模型：<b>每条桌面通知 = 一条 Android 通知</b>，同一桌面应用的通知用
 * {@code setGroup("vnotif:<appKey>")} 归到一组，并额外发一条 {@code setGroupSummary(true)}
 * 的组摘要。通知栏里表现为"一个应用一叠通知，点一下展开"。
 *
 * <p>紧急度映射（踩过的坑）：D-Bus 的 {@code urgency} 是 0=低、1=普通、2=紧急，
 * 早期把 {@code urgency <= 1} 当成"低"，结果普通通知全被打成 IMPORTANCE_LOW 的静默通知，
 * 在 Android 11+ 会掉进通知栏底部的"无声通知"里——既不弹、也看不清内容。现在只有 0 才是低。
 *
 * <p>渠道与聚合无关：固定三条渠道只按紧急度分档（低 / 默认 / 高）。早期版本"每个桌面应用
 * 一条渠道"会让渠道列表无限膨胀，已废弃并自动清理。
 *
 * <p>通知的小图标用映射到的手机应用的 launcher 资源（解析不到才回退到铃铛）；状态栏与通知栏都由系统着色。
 */
public final class NotifRenderer {

    /** Shared with {@link MappingActivity} so a remap can refresh click targets immediately. */
    private static volatile NotifRenderer active;

    static final String CH_LOW = "vnotif_low2";
    static final String CH_DEFAULT = "vnotif_default2";
    static final String CH_HIGH = "vnotif_high2";

    /** Ids of channels created by versions that used one channel per desktop app. */
    private static final String LEGACY_CHANNEL_PREFIX = "app:";

    private final Context ctx;
    private final NotificationManager nm;

    private final Set<String> channels = new HashSet<>();
    private final Map<String, Rec> recs = new HashMap<>();
    private final Map<String, Group> groups = new HashMap<>();

    /** appKey 已经上报过图标解析结果，避免每条通知都发一次请求。 */
    private final Set<String> reportedIcons = new HashSet<>();

    /** One relayed notification, keyed by the wire {@code key}. */
    private static final class Rec {
        String key;
        String appId = "";
        String appName = "";
        String pkgHint = "";
        String title = "";
        String body = "";
        int urgency = 1;
        long ts;
    }

    /** Aggregation for one desktop application. */
    private static final class Group {
        String groupKey;
        String appKey;
        String appName;
        String pkgHint = "";
        String channelId;
        final LinkedHashMap<String, Rec> children = new LinkedHashMap<>();
    }

    public NotifRenderer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.nm = (NotificationManager) this.ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        active = this;
        migrateLegacyChannels();
    }

    static void refreshActive() {
        NotifRenderer r = active;
        if (r != null) {
            r.refreshAll();
        }
    }

    /** 当前活动的渲染器，供 {@link TrampolineActivity} / {@link NotifActionReceiver} 反查。 */
    static NotifRenderer instance() {
        return active;
    }

    // ---------------------------------------------------------------- ids

    /**
     * Child ids live in [0x40000000, 0x7FFFFFFF], summary ids in [0x20000000, 0x3FFFFFFF] and the
     * foreground-service notification uses 1, so the three families can never collide.
     */
    static int childId(String key) {
        return 0x40000000 | (key.hashCode() & 0x3FFFFFFF);
    }

    static int summaryId(String groupKey) {
        return 0x20000000 | (groupKey.hashCode() & 0x1FFFFFFF);
    }

    // ------------------------------------------------------------ handlers

    public void handleNotify(JSONObject o) {
        render(o);
    }

    public void handleUpdate(JSONObject o) {
        render(o);
    }

    public void handleDismiss(JSONObject o) {
        String key = o.optString("key", "");
        if (key.isEmpty()) {
            return;
        }
        synchronized (this) {
            Rec rec = recs.remove(key);
            nm.cancel(childId(key));
            if (rec == null) {
                return;
            }
            Group g = groups.get(groupKeyOf(rec));
            if (g != null) {
                g.children.remove(key);
                postSummary(g);
            }
        }
        BridgeState.log("dismiss " + key);
    }

    /** 用户划掉/点掉了单条通知（autoCancel 也会走这里）。把内部状态和组摘要对齐，别让摘要还挂着已消失的条数。 */
    public void handleUserDismissOne(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        synchronized (this) {
            Rec rec = recs.remove(key);
            nm.cancel(childId(key));
            if (rec == null) {
                return;
            }
            Group g = groups.get(groupKeyOf(rec));
            if (g != null) {
                g.children.remove(key);
                postSummary(g);
            }
        }
        BridgeState.log("用户划掉 " + key);
    }

    /** 用户点通知打开映射应用：把该应用整组通知（子通知 + 摘要）全部清掉。 */
    public void clearGroup(String appKey) {
        if (appKey == null || appKey.isEmpty()) {
            return;
        }
        synchronized (this) {
            Group g = groups.remove("vnotif:" + appKey);
            if (g == null) {
                return;
            }
            for (String key : g.children.keySet()) {
                nm.cancel(childId(key));
                recs.remove(key);
            }
            nm.cancel(summaryId(g.groupKey));
            g.children.clear();
        }
        BridgeState.log("已清掉 " + appKey + " 的整组通知");
    }

    private void render(JSONObject o) {
        String key = o.optString("key", "");
        if (key.isEmpty()) {
            BridgeState.log("忽略缺少 key 的消息");
            return;
        }

        String appKey;
        String channelId;
        String pkg;
        int n;
        synchronized (this) {
            Rec rec = recs.get(key);
            boolean existed = rec != null;
            if (rec == null) {
                rec = new Rec();
                rec.key = key;
                rec.ts = System.currentTimeMillis();
            }

            if (o.has("app_id")) {
                rec.appId = o.optString("app_id", rec.appId);
            }
            if (o.has("app")) {
                String a = o.optString("app", "").trim();
                if (!a.isEmpty()) {
                    rec.appName = a;
                }
            }
            if (o.has("pkg_hint")) {
                String h = o.optString("pkg_hint", "").trim();
                if (!h.isEmpty()) {
                    rec.pkgHint = h;
                }
            }
            if (o.has("urgency")) {
                rec.urgency = o.optInt("urgency", rec.urgency);
            }
            double ts = o.optDouble("ts", 0d);
            if (ts > 0d) {
                rec.ts = (long) (ts * 1000d);
            }
            if (o.has("title")) {
                rec.title = o.optString("title", "");
            }
            if (o.has("body")) {
                rec.body = o.optString("body", "");
            }
            if (rec.appName.isEmpty()) {
                rec.appName = rec.appId.isEmpty() ? "未知应用" : rec.appId;
            }

            appKey = MappingStore.appKey(rec.appId, rec.appName);
            if (appKey.isEmpty()) {
                appKey = "unknown";
            }
            channelId = ensureChannel(rec.urgency);
            String gk = "vnotif:" + appKey;

            pkg = NotifLauncher.resolvePackage(ctx, appKey, rec.pkgHint);
            if (ForegroundWatcher.isSuppressed(pkg)) {
                // 用户正在手机上看这个映射应用，别再推了。
                BridgeState.log("前台抑制：丢弃 [" + appKey + "] " + pkg);
                return;
            }

            Group g = groups.get(gk);
            if (g == null) {
                g = new Group();
                g.groupKey = gk;
                g.appKey = appKey;
                groups.put(gk, g);
            }
            g.appName = rec.appName;
            g.pkgHint = rec.pkgHint;
            g.channelId = channelId;
            g.children.put(key, rec);
            recs.put(key, rec);

            post(rec, appKey, channelId, gk, existed, pkg);
            postSummary(g);
            n = g.children.size();
        }

        BridgeState.log("通知 [" + appKey + "] 渠道 " + channelId + " 组内 " + n + " 条 · 图标 "
                + (pkg == null || pkg.isEmpty() ? "默认铃铛（未映射且无可用 pkg_hint）" : pkg));
    }

    private static String groupKeyOf(Rec rec) {
        String appKey = MappingStore.appKey(rec.appId, rec.appName);
        return "vnotif:" + (appKey.isEmpty() ? "unknown" : appKey);
    }

    // -------------------------------------------------------------- output

    /**
     * 三条固定渠道，按紧急度分档：
     * D-Bus urgency 0=低 → LOW（静默），1..3=普通 → DEFAULT（会响、有提示），
     * 2/≥4（critical）→ HIGH（横幅）。只有 0 才落进"无声通知"。
     *
     * <p>渠道 id 带 {@code _2} 后缀是因为 Android 不允许应用在渠道创建后修改用户可见的声音设置：
     * {@code createNotificationChannel} 对已存在的渠道不会覆盖用户保存过的声音，所以想给默认/高优先级
     * 渠道补上显式声音，只能换一个新 id 重建渠道。LOW 渠道设计上保持静默，不设声音。
     */
    private String ensureChannel(int urgency) {
        boolean high = urgency >= 4 || urgency == 2;
        boolean low = urgency <= 0;
        String id = low ? CH_LOW : (high ? CH_HIGH : CH_DEFAULT);
        if (channels.contains(id)) {
            return id;
        }
        int importance = low
                ? NotificationManager.IMPORTANCE_LOW
                : (high ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
        String name = low ? "低优先级通知" : (high ? "高优先级通知" : "通知");
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setShowBadge(true);
        ch.setDescription("转发自桌面端；通知按桌面应用聚合，渠道只区分优先级");
        if (!low) {
            ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 150, 100, 150});
        }
        nm.createNotificationChannel(ch);
        channels.add(id);
        BridgeState.log("创建通知渠道 " + id + "（importance=" + importance + "）");
        if (channels.size() == 1) {
            // First channel of this process: snapshot the system's view right after creation. The
            // startup snapshot in BridgeService usually runs before any channel exists, and
            // "sound is on but nothing plays" is decided by exactly these fields.
            NotifDiagnostics.log(ctx);
        }
        return id;
    }

    /**
     * 删掉旧版本留下的渠道（同时会清掉那些渠道上的旧通知）：以 {@code app:} 开头的"每应用一条渠道"，
     * 以及上一代固定渠道 {@code vnotif_low}/{@code vnotif_default}/{@code vnotif_high}（已换成带
     * {@code _2} 的新 id）。前台服务的 {@code vnotif_service} 渠道绝不能删。
     */
    private void migrateLegacyChannels() {
        try {
            for (NotificationChannel ch : nm.getNotificationChannels()) {
                String id = ch.getId();
                boolean legacyPerApp = id.startsWith(LEGACY_CHANNEL_PREFIX);
                boolean legacyFixed = "vnotif_low".equals(id)
                        || "vnotif_default".equals(id)
                        || "vnotif_high".equals(id);
                if (legacyPerApp || legacyFixed) {
                    nm.deleteNotificationChannel(id);
                    BridgeState.log("清理旧渠道 " + id);
                }
            }
        } catch (Exception e) {
            BridgeState.log("清理旧渠道失败: " + e);
        }
    }

    /** 系统时间戳被大图标挤掉了，所以自己把时间写进文字里。用系统的时间格式（跟随 12/24 小时设置）。 */
    private String timeText(long ts) {
        try {
            return DateFormat.getTimeFormat(ctx).format(new Date(ts));
        } catch (Exception e) {
            return "";
        }
    }

    private void post(Rec rec, String appKey, String channelId, String groupKey, boolean existed, String pkg) {
        // 点击先过一层无界面 Activity：清掉该应用整组通知，再打开映射应用。
        Intent open = new Intent(ctx, TrampolineActivity.class);
        open.putExtra("app_key", appKey);
        open.putExtra("app_name", rec.appName);
        open.putExtra("pkg_hint", rec.pkgHint);
        PendingIntent pi = PendingIntent.getActivity(
                ctx,
                childId(rec.key),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = rec.body == null ? "" : rec.body;
        // 标题行放「来源应用 · 时间」；桌面通知原来的标题（通常是发送人）并进正文。
        String time = timeText(rec.ts);
        String titleLine = rec.appName + (time.isEmpty() ? "" : " · " + time);
        String bodyLine;
        if (rec.title.isEmpty()) {
            bodyLine = body;
        } else if (body.isEmpty()) {
            bodyLine = rec.title;
        } else {
            bodyLine = rec.title + " · " + body;
        }
        if (bodyLine.isEmpty()) {
            bodyLine = rec.appName;
        }

        Notification.Builder b = new Notification.Builder(ctx, channelId)
                .setContentTitle(titleLine)
                .setContentText(bodyLine)
                .setStyle(new Notification.BigTextStyle().bigText(bodyLine.isEmpty() ? titleLine : bodyLine))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setWhen(rec.ts)
                .setShowWhen(true)
                .setGroup(groupKey)
                // Only the children alert; the summary stays silent (no double buzz).
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(existed);

        // 划掉通知（含 autoCancel）时同步内部状态，否则组摘要会挂着已经消失的条数。
        Intent del = new Intent(ctx, NotifActionReceiver.class);
        del.setAction(NotifActionReceiver.ACTION_DISMISS_ONE);
        del.putExtra("key", rec.key);
        PendingIntent delPi = PendingIntent.getBroadcast(
                ctx,
                childId(rec.key),
                del,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setDeleteIntent(delPi);

        b.setSmallIcon(NotifLauncher.notificationIcon(ctx, pkg));
        reportIcon(appKey, pkg);

        nm.notify(childId(rec.key), b.build());
    }

    /**
     * Reports which package the left-hand icon came from, and how it turned out.
     *
     * <p>Deduplicated per app: both {@code render} and {@code refreshAll} hold this object's monitor
     * when they call in, so a plain HashSet is enough.
     */
    private void reportIcon(String appKey, String pkg) {
        if (reportedIcons.add(appKey)) {
            Report.send("icon", appKey + " -> " + (pkg == null || pkg.isEmpty() ? "无" : pkg)
                    + " [" + NotifLauncher.lastIconDecision + "]");
        }
    }

    private void postSummary(Group g) {
        int sid = summaryId(g.groupKey);
        if (g.children.isEmpty()) {
            nm.cancel(sid);
            groups.remove(g.groupKey);
            return;
        }

        Notification.InboxStyle style = new Notification.InboxStyle();
        String firstLine = null;
        int count = 0;
        for (Rec r : g.children.values()) {
            String line = r.title.isEmpty() ? r.appName : r.title;
            if (r.body != null && !r.body.isEmpty()) {
                line = line + " — " + r.body;
            }
            if (firstLine == null) {
                firstLine = line;
            }
            if (count < 5) {
                style.addLine(line);
            }
            count++;
        }
        style.setBigContentTitle(g.appName + " · " + count + " 条通知");
        if (count > 5) {
            style.setSummaryText("另有 " + (count - 5) + " 条");
        }

        // 摘要自己没有来源通知，用组内最新一条子通知的时间，避免时间戳变成"摘要发布那一刻"。
        long latest = 0L;
        for (Rec r : g.children.values()) {
            if (r.ts > latest) {
                latest = r.ts;
            }
        }

        // 摘要不设 contentIntent：让系统默认行为生效——点摘要就是展开/收起这一组。
        String spkg = NotifLauncher.resolvePackage(ctx, g.appKey, g.pkgHint);
        Notification.Builder b = new Notification.Builder(ctx, g.channelId)
                .setContentTitle(g.appName + (timeText(latest).isEmpty() ? "" : " · " + timeText(latest)))
                .setContentText(count == 1 ? firstLine : count + " 条通知 · 点击展开")
                .setWhen(latest)
                .setShowWhen(true)
                .setStyle(style)
                // 摘要不能 autoCancel：点掉摘要会让整组失去折叠，子通知就散成平铺了。
                .setAutoCancel(false)
                .setGroup(g.groupKey)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true);
        b.setSmallIcon(NotifLauncher.notificationIcon(ctx, spkg));
        nm.notify(sid, b.build());
    }

    /**
     * Re-posts every tracked notification so already-shown ones pick up a newly chosen mapping.
     * Called by {@link MappingActivity} right after the user changes a mapping.
     */
    public void refreshAll() {
        int n;
        synchronized (this) {
            n = recs.size();
            for (Rec rec : new ArrayList<>(recs.values())) {
                String appKey = MappingStore.appKey(rec.appId, rec.appName);
                if (appKey.isEmpty()) {
                    appKey = "unknown";
                }
                Group g = groups.get("vnotif:" + appKey);
                if (g == null) {
                    continue;
                }
                post(rec, appKey, ensureChannel(rec.urgency), g.groupKey, true,
                        NotifLauncher.resolvePackage(ctx, appKey, rec.pkgHint));
                postSummary(g);
            }
        }
        if (n > 0) {
            BridgeState.log("已按最新映射刷新 " + n + " 条通知");
        }
    }
}
