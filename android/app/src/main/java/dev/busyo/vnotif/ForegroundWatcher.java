package dev.busyo.vnotif;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前台抑制：只要某个**映射应用**停在前台，就抑制它的通知，并把它已经堆在通知栏里的整组通知清掉。
 *
 * <p>注意"停在前台"跟"怎么进来的"无关：点通知进来、从桌面图标手动进来，对用户来说是同一件事。
 * 所以这里在前台轮询里自动判定并抑制，{@link TrampolineActivity} 里那次 {@link #suppress} 只是
 * 让点击那条路立即生效（不用等下一次轮询）。
 *
 * <p><b>为什么轮询：</b>系统没有给第三方应用提供"前台应用变化"的轻量回调
 * （{@code ActivityManager.getRunningAppProcesses} 对普通应用早已不可用，{@code UsageStatsManager}
 * 只能查询），所以只能每 {@link #POLL_MS} 毫秒查一次最近的使用事件。
 *
 * <p>需要「使用情况访问」权限（用户手动授予）。没授予时 {@code queryEvents} 返回空、前台包名恒为
 * {@code null}，而且 {@link #isSuppressed} 直接返回 false——不抑制、不抛异常、不弹任何东西。
 */
public final class ForegroundWatcher {

    private static final String TAG = "VNotif";

    private static final long POLL_MS = 2000L;
    /** 只看最近这段时间的使用事件，够判断"现在谁在前台"。 */
    private static final long RECENT_WINDOW_MS = 10000L;
    /** 刚点开的一小段时间里目标应用可能还没起来，这期间先别解除抑制。 */
    private static final long GRACE_MS = 3000L;
    /** 离开前台超过这么久才解除抑制，避免应用间切换时抖动。 */
    private static final long LEAVE_MS = 5000L;
    /** 抑制生效期间回传「探到谁在前台」的间隔。 */
    private static final long REPORT_MS = 10000L;
    /** 「映射包名」快照的刷新间隔。映射是用户在界面上改的，没必要每 2 秒重算一遍（每次都要问 PackageManager）。 */
    private static final long SNAPSHOT_MS = 10000L;

    /** 抑制集合：包名 -> {suppressedAt, lastSeenForeground}。只有轮询线程会改值，读方只判断 key 存在。 */
    private static final ConcurrentHashMap<String, long[]> SUPPRESSED = new ConcurrentHashMap<>();

    /**
     * 映射应用包名 -> 它对应的桌面应用 appKey（一个包名可能对应多个桌面应用，比如 Discord/discord）。
     *
     * <p>解析方式与 {@link NotifRenderer} 判定抑制时用的完全相同（{@link NotifLauncher#resolvePackage}），
     * 否则两边算出来的包名会对不上，抑制就会失效。
     */
    private static final ConcurrentHashMap<String, Set<String>> MAPPED = new ConcurrentHashMap<>();

    private static volatile Context appCtx;
    private static volatile boolean attached;
    /** 最近一次轮询判定的前台包名，判定不出来时为 null。 */
    private static volatile String foregroundPkg;
    private static volatile boolean usageAccessGranted;
    /** 上次回传抑制状态的时间，见 {@link #reportHeartbeat}。 */
    private static volatile long lastHeartbeatAt;
    /** 上一次因「前台就是映射应用」处理过的包名，用来识别"刚切进来"这个瞬间，避免反复清组。 */
    private static volatile String autoHandledPkg;

    private ForegroundWatcher() {
    }

    /** 开始轮询。重复调用无副作用。 */
    static void attach(Context ctx) {
        if (ctx == null) {
            return;
        }
        if (appCtx == null) {
            appCtx = ctx.getApplicationContext();
        }
        synchronized (ForegroundWatcher.class) {
            if (attached) {
                return;
            }
            attached = true;
        }
        usageAccessGranted = hasUsageAccess(appCtx);
        Thread t = new Thread(ForegroundWatcher::loop, "vnotif-foreground");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把某个包名加入前台抑制：它在前台期间，{@link NotifRenderer} 会丢弃它的通知。
     *
     * <p>点通知那条路调这里，为的是立刻生效——不然要等下一次前台轮询（最多 {@link #POLL_MS}）。
     * 清组那件事 {@link TrampolineActivity} 已经做了，所以顺手把 {@link #autoHandledPkg} 标成它，
     * 免得轮询再清一次、再上报一条重复的「抑制=开」。
     */
    static void suppress(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        SUPPRESSED.put(pkg, new long[]{now, now});
        autoHandledPkg = pkg;
        // 抑制能不能真的生效取决于权限和前台判定，这两件事只有手机知道，开的时候顺手回传一次。
        Report.send("fg", pkg + " 抑制=开（权限=" + (usageAccessGranted ? "有" : "无")
                + "，此刻探到前台=" + describe(foregroundPkg) + "）");
        BridgeState.log("前台抑制开启：" + pkg);
    }

    /** pkg 现在是否处于前台抑制状态。pkg 为 null/空时返回 false。 */
    static boolean isSuppressed(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        // 没权限就完全不抑制（优雅退化），别让点一次通知导致几秒内丢通知。
        return usageAccessGranted && SUPPRESSED.containsKey(pkg);
    }

    /** 使用情况访问权限是否已授予（用来在主界面提示用户）。 */
    static boolean hasUsageAccess(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static void loop() {
        boolean warnedNoAccess = false;
        long lastSnapshotAt = 0L;
        while (true) {
            try {
                usageAccessGranted = hasUsageAccess(appCtx);
                if (!usageAccessGranted && !warnedNoAccess) {
                    BridgeState.log("前台抑制：未授予使用情况访问权限，暂不抑制");
                    warnedNoAccess = true;
                }
                long now = System.currentTimeMillis();
                if (now - lastSnapshotAt >= SNAPSHOT_MS) {
                    lastSnapshotAt = now;
                    refreshMappedPackages();
                }
                String fg = currentForegroundPackage();
                foregroundPkg = fg;
                autoSuppressIfMapped(fg);
                pruneSuppressed(fg);
            } catch (Exception e) {
                Log.w(TAG, "前台轮询失败", e);
            }
            SystemClock.sleep(POLL_MS);
        }
    }

    /**
     * 重算「哪些手机包名算映射应用」。
     *
     * <p>每个桌面应用都用 {@link NotifLauncher#resolvePackage} 解析成包名——与点通知时打开的那个包、
     * 与 {@link NotifRenderer} 查抑制时用的包，是同一个函数，三处必须一致。
     * 没映射也没 {@code pkg_hint} 的桌面应用直接跳过，省掉一次 PackageManager 查询。
     */
    private static void refreshMappedPackages() {
        Context ctx = appCtx;
        if (ctx == null) {
            return;
        }
        ConcurrentHashMap<String, Set<String>> fresh = new ConcurrentHashMap<>();
        try {
            for (MappingStore.SeenApp s : MappingStore.seen(ctx)) {
                String mapped = MappingStore.mapping(ctx, s.key);
                boolean hasHint = s.pkgHint != null && !s.pkgHint.isEmpty();
                if ((mapped == null || mapped.isEmpty()) && !hasHint) {
                    continue;
                }
                String pkg = NotifLauncher.resolvePackage(ctx, s.key, s.pkgHint);
                if (pkg == null || pkg.isEmpty()) {
                    continue;
                }
                Set<String> keys = fresh.get(pkg);
                if (keys == null) {
                    keys = new LinkedHashSet<>();
                    fresh.put(pkg, keys);
                }
                keys.add(s.key);
            }
        } catch (Exception e) {
            Log.w(TAG, "刷新映射包名失败", e);
        }
        MAPPED.clear();
        MAPPED.putAll(fresh);
    }

    /**
     * 前台就是映射应用 → 抑制它，并清掉它已经堆在通知栏里的整组通知。
     *
     * <p>只在这个应用**刚切到前台**那一次动手（靠 {@link #autoHandledPkg} 记住上一个前台包名），
     * 否则每 2 秒都会重清一遍，用户刚展开的通知也会被反复抹掉。
     */
    private static void autoSuppressIfMapped(String fg) {
        if (fg == null || !usageAccessGranted) {
            // 判定不出前台（熄屏/锁屏/无权限）：下次再切进来要重新清一次。
            autoHandledPkg = null;
            return;
        }
        if (fg.equals(autoHandledPkg)) {
            return;
        }
        autoHandledPkg = fg;
        Set<String> keys = MAPPED.get(fg);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        SUPPRESSED.put(fg, new long[]{now, now});
        NotifRenderer renderer = NotifRenderer.instance();
        if (renderer != null) {
            for (String appKey : keys) {
                renderer.clearGroup(appKey);
            }
        }
        Report.send("fg", fg + " 抑制=开（前台自动，映射 " + keys + "）");
        BridgeState.log("前台就是映射应用，已清组并抑制：" + fg + " ← " + keys);
    }

    /**
     * 取窗口内**最新的一条与前后台相关的事件**，据此判断当前前台应用。
     *
     * <p>必须先用 {@link #isRelevant} 把无关事件滤掉：usage 事件里数量最多的是用户交互、通知中断、
     * 屏幕开关之类与"谁在前台"无关的事件，而它们几乎总比目标应用那条 {@code ACTIVITY_RESUMED} 更新。
     * 早期版本直接取"窗口内最新的一条事件"，于是刚点开应用就被这些事件顶掉、退回上一次的结论，
     * 表现为抑制总在 5~8 秒后自己解除（PC 日志里每次都是这个时长）。
     */
    private static String currentForegroundPackage() {
        Context ctx = appCtx;
        if (ctx == null) {
            return null;
        }
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - RECENT_WINDOW_MS, now);
        if (events == null) {
            return foregroundPkg;
        }
        UsageEvents.Event ev = new UsageEvents.Event();
        String latestPkg = null;
        int latestType = -1;
        long latestTs = -1L;
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            int type = ev.getEventType();
            if (!isRelevant(type)) {
                continue;
            }
            long ts = ev.getTimeStamp();
            if (ts >= latestTs) {
                latestTs = ts;
                latestType = type;
                latestPkg = ev.getPackageName();
            }
        }
        if (latestPkg == null) {
            // 窗口内没有相关事件：沿用上次结论。用户可能已经在目标应用里停留超过 10 秒没动过，
            // 这时"没有新事件"恰恰说明没人顶替它，不能当成"前台为空"。
            return foregroundPkg;
        }
        switch (latestType) {
            case UsageEvents.Event.ACTIVITY_RESUMED:
                return latestPkg;
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
            case UsageEvents.Event.KEYGUARD_SHOWN:
                // 熄屏 / 锁屏：用户没在看，解除抑制，否则锁屏期间的消息全被丢掉。
                return null;
            default:
                // ACTIVITY_PAUSED（应用自己弹窗、任务预览等）与亮屏、解锁：都不足以判定"离开了"，
                // 真正离开时会有一条别的应用的 ACTIVITY_RESUMED 顶上来。
                return foregroundPkg;
        }
    }

    /**
     * 只有这几类事件能改变"谁在前台"的结论：Activity 的前后台切换，以及熄屏 / 锁屏 / 亮屏。
     *
     * <p>这些常量是编译期内联的 {@code static final int}，所以 minSdk 26 上引用 API 28/29 才公开的
     * 枚举值也不会在运行时炸。
     */
    private static boolean isRelevant(int type) {
        switch (type) {
            case UsageEvents.Event.ACTIVITY_RESUMED:
            case UsageEvents.Event.ACTIVITY_PAUSED:
            case UsageEvents.Event.SCREEN_INTERACTIVE:
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
            case UsageEvents.Event.KEYGUARD_SHOWN:
            case UsageEvents.Event.KEYGUARD_HIDDEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * 抑制集合的生命周期：还在前台就续期；刚点开给一段宽限；离开前台超过 {@link #LEAVE_MS} 才解除。
     * 这样即使权限没授权、目标应用一直没被检测到，也会在宽限+离开时间后自动解除，不会永久抑制。
     */
    private static void pruneSuppressed(String fg) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, long[]>> it = SUPPRESSED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, long[]> e = it.next();
            String pkg = e.getKey();
            long[] v = e.getValue();
            if (pkg.equals(fg)) {
                v[1] = now;
            } else if (now - v[0] < GRACE_MS) {
                // 刚点开，目标应用可能还没起来，先留着。
            } else if (now - v[1] > LEAVE_MS) {
                it.remove();
                // 解除原因要写清楚：是"别的应用顶上来了"还是"压根没探到过它"，两者修法完全不同。
                Report.send("fg", pkg + " 抑制=关（此刻探到前台=" + describe(fg)
                        + "，最后见到它=" + ((now - v[1]) / 1000) + "s 前）");
                BridgeState.log("前台抑制解除：" + pkg);
            }
        }
        reportHeartbeat(now, fg);
    }

    /**
     * 抑制生效期间每 {@link #REPORT_MS} 回传一次轮询结论。
     *
     * <p>抑制不生效时手机侧看不出原因：权限没给、usage 事件读不到、判定认错了前台，三种情况在 PC
     * 日志里长得一样，只能来回问用户。把轮询结论定时回传，下次直接看日志。
     */
    private static void reportHeartbeat(long now, String fg) {
        if (SUPPRESSED.isEmpty() || now - lastHeartbeatAt < REPORT_MS) {
            return;
        }
        lastHeartbeatAt = now;
        StringBuilder sb = new StringBuilder("抑制中");
        for (String pkg : SUPPRESSED.keySet()) {
            sb.append(' ').append(pkg);
        }
        sb.append(" · 权限=").append(usageAccessGranted ? "有" : "无")
                .append(" · 探到前台=").append(describe(fg));
        Report.send("fg", sb.toString());
    }

    /** 日志里 {@code null} 会被误读成"没上报"，统一写成"(空)"。 */
    private static String describe(String pkg) {
        return pkg == null || pkg.isEmpty() ? "(空)" : pkg;
    }
}
