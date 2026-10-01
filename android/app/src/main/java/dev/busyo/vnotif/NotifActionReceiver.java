package dev.busyo.vnotif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 处理"用户划掉单条通知"：让 {@link NotifRenderer} 的内部状态跟上，别让组摘要挂着已消失的条数。 */
public class NotifActionReceiver extends BroadcastReceiver {

    /** 子通知 {@code setDeleteIntent} 用的 action。 */
    static final String ACTION_DISMISS_ONE = "dev.busyo.vnotif.DISMISS_ONE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_DISMISS_ONE.equals(action)) {
            NotifRenderer r = NotifRenderer.instance();
            String key = intent.getStringExtra("key");
            if (r != null && key != null) {
                r.handleUserDismissOne(key);
            }
        }
    }
}
