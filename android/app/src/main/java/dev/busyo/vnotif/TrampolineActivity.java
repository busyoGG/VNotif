package dev.busyo.vnotif;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 点通知的中转站：无界面，被拉起后清掉该应用的整组通知，再打开映射到的手机应用，然后立刻结束。
 *
 * <p>用 {@code Theme.NoDisplay}，所以必须在 {@code onCreate} 里就 {@code finish()}；
 * 真正的跳转交给 {@link NotifLauncher#buildClickIntent}（映射不到时它会指向应用商店）。
 */
public class TrampolineActivity extends Activity {

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        String appKey = getIntent().getStringExtra("app_key");
        String appName = getIntent().getStringExtra("app_name");
        String pkgHint = getIntent().getStringExtra("pkg_hint");
        ForegroundWatcher.attach(this);
        try {
            NotifRenderer r = NotifRenderer.instance();
            if (r != null && appKey != null) {
                r.clearGroup(appKey);
            }
            // 打开映射应用后抑制它：用户正在看它，PC 再推就没意义了。
            String targetPkg = NotifLauncher.resolvePackage(this, appKey, pkgHint);
            if (targetPkg != null && !targetPkg.isEmpty()) {
                ForegroundWatcher.suppress(targetPkg);
            }
            Intent target = NotifLauncher.buildClickIntent(this, appKey, appName, pkgHint);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(target);
        } catch (Exception e) {
            Log.w("VNotif", "trampoline failed", e);
        }
        finish();
    }
}
