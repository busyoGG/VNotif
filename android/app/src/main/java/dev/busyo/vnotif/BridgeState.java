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

    public static void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        synchronized (LOCK) {
            LOGS.addLast(stamped);
            while (LOGS.size() > MAX_LOGS) {
                LOGS.removeFirst();
            }
        }
    }

    public static void setError(String error) {
        lastError = error == null ? "" : error;
        if (error != null && !error.isEmpty()) {
            log("错误: " + error);
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
    }
}
