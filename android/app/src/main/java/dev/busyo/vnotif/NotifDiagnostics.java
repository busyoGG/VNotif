package dev.busyo.vnotif;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

/**
 * Logs what the system currently thinks about our notification channels.
 *
 * <p>"The channel has a sound but nothing plays" can come from the channel itself (sound unset), from
 * the system default notification sound being set to "none", from silent/vibrate mode, from Do Not
 * Disturb, or from Android 15+'s notification cooldown. None of that is visible from inside the app,
 * so this dumps all of it into the in-app log instead of guessing.
 */
public final class NotifDiagnostics {

    static final String[] CHANNELS = {"vnotif_low2", "vnotif_default2", "vnotif_high2"};

    private NotifDiagnostics() {
    }

    public static void log(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("通知诊断：总开关=").append(nm.areNotificationsEnabled() ? "开" : "关");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sb.append("，系统重要性=").append(nm.getImportance());
            }
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                // 0=silent 1=vibrate 2=normal: a phone in silent mode swallows every channel sound.
                sb.append("，铃声模式=").append(am.getRingerMode());
                sb.append("，通知音量=").append(am.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
                sb.append("，通知流静音=").append(am.isStreamMute(AudioManager.STREAM_NOTIFICATION));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.append("，勿扰过滤=").append(nm.getCurrentInterruptionFilter());
            }
            BridgeState.log(sb.toString());
            Report.send("diag", sb.toString());

            StringBuilder ch = new StringBuilder("渠道：");
            for (String id : CHANNELS) {
                NotificationChannel c = nm.getNotificationChannel(id);
                if (c == null) {
                    ch.append(id).append("=不存在；");
                    continue;
                }
                ch.append(id)
                        .append("(importance=").append(c.getImportance())
                        .append(", 声音=").append(c.getSound() == null ? "无(静音)" : c.getSound())
                        .append(", 振动=").append(c.shouldVibrate())
                        .append(") ");
            }
            BridgeState.log(ch.toString());
            Report.send("diag", ch.toString());
        } catch (Exception e) {
            BridgeState.log("通知诊断失败: " + e);
        }
    }
}
