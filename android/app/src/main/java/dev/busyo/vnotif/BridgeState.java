package dev.busyo.vnotif;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Process-wide snapshot of the bridge state, polled by {@link MainActivity}.
 *
 * <p>Deliberately uses plain static fields instead of LocalBroadcastManager: LocalBroadcastManager
 * lives in AndroidX, and this project is intentionally dependency-free.
 */
public final class BridgeState {

    public static final int MAX_LOGS = 200;

    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    /**
     * Notified from whichever thread appended a log line, so an open {@link LogActivity} can follow
     * along without polling every second.
     *
     * <p>The listener must not block: it runs on the bridge reader thread, so it should only post to
     * a main-thread {@code Handler}.
     */
    private static volatile Runnable changeListener;

    /** True while the worker thread is alive. */
    public static volatile boolean running = false;
    /** Display name of the endpoint currently in use. */
    public static volatile String endpointName = "";
    /** Base URL of the endpoint currently in use. */
    public static volatile String endpointUrl = "";
    /** Human readable connection state. */
    public static volatile String status = "未运行";
    /** Epoch millis of the last successfully parsed server message (including ping). */
    public static volatile long lastMessageAt = 0L;
    /** Last error string, empty when there is none. */
    public static volatile String lastError = "";

    private BridgeState() {
    }

    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    /**
     * Swallows listener failures instead of logging them: a listener that logs would call back into
     * here and loop forever.
     */
    private static void fireChanged() {
        Runnable r = changeListener;
        if (r != null) {
            try {
                r.run();
            } catch (Exception ignored) {
                // See above — deliberately not logged.
            }
        }
    }

    public static void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        synchronized (LOCK) {
            LOGS.addLast(stamped);
            while (LOGS.size() > MAX_LOGS) {
                LOGS.removeFirst();
            }
        }
        fireChanged();
    }

    public static void setError(String error) {
        lastError = error == null ? "" : error;
        if (error != null && !error.isEmpty()) {
            log("错误: " + error);
        }
    }

    /** 已缓存的日志行数，给主界面显示用（避免为了一个数字复制整个列表）。 */
    public static int logCount() {
        synchronized (LOCK) {
            return LOGS.size();
        }
    }

    public static List<String> snapshotLogs() {
        synchronized (LOCK) {
            return new ArrayList<>(LOGS);
        }
    }

    public static void clearLogs() {
        synchronized (LOCK) {
            LOGS.clear();
        }
        fireChanged();
    }
}
