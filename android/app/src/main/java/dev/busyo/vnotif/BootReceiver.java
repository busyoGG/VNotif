package dev.busyo.vnotif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the bridge after a reboot when the user has enabled "start on boot". */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        if (!EndpointStore.isAutostart(context)) {
            BridgeState.log("开机自启未开启，忽略");
            return;
        }
        if (EndpointStore.load(context).isEmpty()) {
            BridgeState.log("未配置端点，开机不自启");
            return;
        }
        BridgeState.log("开机自启：拉起服务");
        try {
            BridgeService.start(context);
        } catch (Exception e) {
            BridgeState.setError("开机自启失败: " + e);
        }
    }
}
