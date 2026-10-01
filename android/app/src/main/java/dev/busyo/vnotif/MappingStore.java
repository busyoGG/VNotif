package dev.busyo.vnotif;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Persists two things:
 *
 * <ul>
 *   <li>the "seen desktop apps" index, fed by the {@code apps} message and by every
 *       {@code notify} that carries an app identifier;</li>
 *   <li>the user-made map {@code desktop app key -> phone package name}. The PC never dictates
 *       this mapping; {@code pkg_hint} from the wire is only a fallback used when the user has
 *       not chosen anything.</li>
 * </ul>
 */
public final class MappingStore {

    private static final String PREF = "vnotif_mapping";
    private static final String KEY_SEEN = "seen";
    private static final String KEY_MAP = "map";

    /**
     * Notified from whichever thread changed the store, so an open mapping screen can follow along
     * instead of waiting for a manual refresh.
     *
     * <p>The listener must not block: it runs on the bridge reader thread, so it should only post
     * to a main-thread {@code Handler}.
     */
    private static volatile Runnable changeListener;

    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void fireChanged() {
        Runnable r = changeListener;
        if (r != null) {
            try {
                r.run();
            } catch (Exception e) {
                BridgeState.log("映射列表刷新回调失败: " + e);
            }
        }
    }

    /** A desktop application we have heard about. */
    public static final class SeenApp {
        public String key;
        public String app;
        public String appId;
        public String pkgHint;
        public int seen;
        public long last;
    }

    private MappingStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Stable identifier for a desktop app: {@code app_id} when present, else the display name. */
    public static String appKey(String appId, String app) {
        if (appId != null && !appId.trim().isEmpty()) {
            return appId.trim();
        }
        return app == null ? "" : app.trim();
    }

    /** Records one real sighting: a notification from that desktop app was actually relayed. */
    public static synchronized void recordSeen(Context ctx, String appId, String app, String pkgHint) {
        if (touch(ctx, appId, app, pkgHint, true)) {
            fireChanged();
        }
    }

    /**
     * Registers a whole {@code apps} payload.
     *
     * <p>This is <b>not</b> a sighting: the payload is re-sent on every reconnect, so counting it
     * would inflate "收到 N 次" and reset every "最近" to now on each reconnect. Unknown apps are
     * added, known ones are left alone.
     */
    public static synchronized void recordAppsMessage(Context ctx, JSONArray list) {
        if (list == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            changed |= touch(ctx, o.optString("app_id", ""), o.optString("app", ""),
                    o.optString("pkg_hint", ""), false);
        }
        if (changed) {
            fireChanged();
        }
    }

    /**
     * Adds or updates one entry.
     *
     * @param sighting {@code true} for a notification we relayed (bumps the counters),
     *                 {@code false} for the bulk {@code apps} payload.
     * @return {@code true} when anything about the stored entry changed.
     */
    private static boolean touch(Context ctx, String appId, String app, String pkgHint, boolean sighting) {
        String key = appKey(appId, app);
        if (key.isEmpty()) {
            return false;
        }
        List<SeenApp> list = seen(ctx);
        SeenApp found = null;
        for (SeenApp s : list) {
            if (key.equals(s.key)) {
                found = s;
                break;
            }
        }
        boolean changed = false;
        if (found == null) {
            found = new SeenApp();
            found.key = key;
            found.app = (app == null || app.trim().isEmpty()) ? key : app.trim();
            found.appId = appId == null ? "" : appId;
            found.pkgHint = pkgHint == null ? "" : pkgHint;
            list.add(found);
            changed = true;
        }
        String trimmedApp = app == null ? "" : app.trim();
        if (!trimmedApp.isEmpty() && !trimmedApp.equals(found.app)) {
            found.app = trimmedApp;
            changed = true;
        }
        if (pkgHint != null && !pkgHint.isEmpty() && !pkgHint.equals(found.pkgHint)) {
            found.pkgHint = pkgHint;
            changed = true;
        }
        if (sighting) {
            found.seen += 1;
            found.last = System.currentTimeMillis() / 1000L;
            changed = true;
        }
        if (changed) {
            writeSeen(ctx, list);
        }
        return changed;
    }

    public static synchronized List<SeenApp> seen(Context ctx) {
        List<SeenApp> out = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(KEY_SEEN, null);
            if (raw == null) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                SeenApp s = new SeenApp();
                s.key = o.optString("key", "");
                s.app = o.optString("app", "");
                s.appId = o.optString("app_id", "");
                s.pkgHint = o.optString("pkg_hint", "");
                s.seen = o.optInt("seen", 0);
                s.last = o.optLong("last", 0L);
                if (!s.key.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            BridgeState.setError("应用清单解析失败: " + e);
        }
        Collections.sort(out, new Comparator<SeenApp>() {
            @Override
            public int compare(SeenApp a, SeenApp b) {
                int byTime = Long.compare(b.last, a.last);
                // Without a secondary key the comparator is not total: entries that have never been
                // seen (last == 0) would come back in JSON order and shuffle on every reload.
                return byTime != 0 ? byTime : a.app.compareToIgnoreCase(b.app);
            }
        });
        return out;
    }

    private static void writeSeen(Context ctx, List<SeenApp> list) {
        JSONArray arr = new JSONArray();
        for (SeenApp s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("key", s.key);
                o.put("app", s.app);
                o.put("app_id", s.appId);
                o.put("pkg_hint", s.pkgHint);
                o.put("seen", s.seen);
                o.put("last", s.last);
                arr.put(o);
            } catch (Exception ignored) {
                // All values are primitives; nothing to recover from.
            }
        }
        prefs(ctx).edit().putString(KEY_SEEN, arr.toString()).apply();
    }

    /** @return the user-chosen package name, or {@code null} when unmapped. */
    public static synchronized String mapping(Context ctx, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            String raw = prefs(ctx).getString(KEY_MAP, "{}");
            JSONObject o = new JSONObject(raw);
            String pkg = o.optString(key, "");
            return pkg.isEmpty() ? null : pkg;
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void setMapping(Context ctx, String key, String pkg) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString(KEY_MAP, "{}"));
            if (pkg == null || pkg.isEmpty()) {
                o.remove(key);
            } else {
                o.put(key, pkg);
            }
            prefs(ctx).edit().putString(KEY_MAP, o.toString()).apply();
            fireChanged();
        } catch (Exception e) {
            BridgeState.setError("保存映射失败: " + e);
        }
    }

    public static synchronized void clearMapping(Context ctx, String key) {
        setMapping(ctx, key, null);
    }
}
