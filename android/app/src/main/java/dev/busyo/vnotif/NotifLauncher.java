package dev.busyo.vnotif;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;

/**
 * Turns a desktop notification into a tap target, and resolves the small icon shown next to it.
 *
 * <p>Resolution order is: user mapping (phone side) &gt; {@code pkg_hint} (from the PC)
 * &gt; app-store search for the desktop app name. Whenever a package cannot be resolved to an
 * installed, launchable app we fall back to the store search as well.
 *
 * <p>左侧小图标只有一种做法：把目标应用的 launcher 资源 id 交给系统
 * （{@link Icon#createWithResource}）。ColorOS 不认位图小图标（{@code createWithBitmap}），
 * 会回落成发通知的应用——也就是我们自己——的 launcher 图标，于是永远显示成铃铛。
 * 早期那套"彩色位图 / 单色剪影 / 资源 / 铃铛"四档切换已经删掉，只留资源图标 + 取不到时的铃铛兜底。
 */
public final class NotifLauncher {

    private static final String TAG = "VNotif";

    /**
     * 最近一次生成小图标的结果，仅用于诊断上报。
     *
     * <p>只有桥接线程会调 {@link #notificationIcon}，且调用后立刻读取，所以一个普通静态字段
     * 就够了，不需要同步。
     */
    static volatile String lastIconDecision = "(未生成)";

    /** 最近一次 {@link #resourceAppIcon} 命中的资源 id，仅为让 {@link #lastIconDecision} 能印出它。 */
    static volatile int lastResourceIconRes = 0;

    private NotifLauncher() {
    }

    /** {@code true} when {@code pkg} is installed and has a launcher activity. */
    public static boolean isLaunchable(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return the package to launch, or {@code null} when nothing usable was found. */
    public static String resolvePackage(Context ctx, String appKey, String pkgHint) {
        String mapped = MappingStore.mapping(ctx, appKey);
        if (mapped != null && isLaunchable(ctx, mapped)) {
            return mapped;
        }
        if (pkgHint != null && !pkgHint.isEmpty() && isLaunchable(ctx, pkgHint)) {
            return pkgHint;
        }
        return null;
    }

    /**
     * Builds the intent fired when the user taps a relayed notification.
     * Never returns {@code null} — worst case it points at the app store.
     */
    public static Intent buildClickIntent(Context ctx, String appKey, String appName, String pkgHint) {
        String pkg = resolvePackage(ctx, appKey, pkgHint);
        if (pkg != null) {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                // Required when starting an Activity from a Service context.
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return launch;
            }
        }

        String query = appName == null || appName.isEmpty() ? appKey : appName;
        if (query == null || query.isEmpty()) {
            query = "android app";
        }
        Uri market = Uri.parse("market://search?q=" + Uri.encode(query));
        Intent storeIntent = new Intent(Intent.ACTION_VIEW, market);
        storeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canHandle(ctx, storeIntent)) {
            return storeIntent;
        }

        Uri web = Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(query) + "&c=apps");
        Intent webIntent = new Intent(Intent.ACTION_VIEW, web);
        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canHandle(ctx, webIntent)) {
            return webIntent;
        }

        // Last resort: open our own main screen rather than throwing.
        Intent self = new Intent(ctx, MainActivity.class);
        self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return self;
    }

    private static boolean canHandle(Context ctx, Intent intent) {
        try {
            return ctx.getPackageManager().queryIntentActivities(intent, 0).size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Best-effort launch; failures are swallowed (the service must never die from a click). */
    public static void startSafely(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no activity for " + intent, e);
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed for " + intent, e);
        }
    }

    /** 目标应用的 launcher 图标，以「资源引用」形式交给系统。 */
    public static Icon resourceAppIcon(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai == null || ai.icon == 0) {
                return null;
            }
            // 确认这个资源真能被解析出来（拿不到就是没有可用的图标资源）
            Drawable d = pm.getResourcesForApplication(ai).getDrawable(ai.icon, null);
            if (d == null) {
                return null;
            }
            lastResourceIconRes = ai.icon;
            return Icon.createWithResource(pkg, ai.icon);
        } catch (Exception e) {
            Log.w(TAG, "cannot build resource icon for " + pkg, e);
            return null;
        }
    }

    /** 映射应用的图标；解析不到就退回我们自己的铃铛，绝不返回 {@code null}。 */
    public static Icon notificationIcon(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            lastIconDecision = "解析不到应用→铃铛";
            return Icon.createWithResource(ctx, R.drawable.ic_stat_vnotif);
        }
        Icon res = resourceAppIcon(ctx, pkg);
        if (res == null) {
            lastIconDecision = "资源图标失败（取不到 launcher 资源）→铃铛";
            return Icon.createWithResource(ctx, R.drawable.ic_stat_vnotif);
        }
        lastIconDecision = "资源图标 OK (res=0x" + Integer.toHexString(lastResourceIconRes) + ")";
        return res;
    }
}
