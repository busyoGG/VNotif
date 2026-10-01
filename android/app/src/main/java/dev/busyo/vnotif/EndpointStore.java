package dev.busyo.vnotif;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists the list of bridge endpoints (name / base URL / token / self-signed allowance)
 * and the "start on boot" flag.
 *
 * <p>The FRP tunnel address is deliberately never hard-coded: only the LAN endpoint is pre-seeded,
 * everything else is typed in by the user from the UI.
 *
 * <p>连接顺序 = 这里的存储顺序：服务端每次重连都从第一条开始试，第一个连通的就用它。
 * 用户想换优先级就在界面上调顺序（{@link #move}），没有"上次成功优先"这种隐式重排——
 * 那会让"我明明把 FRP 放在第一条"变成一句空话。
 */
public final class EndpointStore {

    private static final String PREF = "vnotif_endpoints";
    private static final String KEY_LIST = "list";
    private static final String KEY_AUTOSTART = "autostart";

    /** Default LAN endpoint pre-seeded on first launch. */
    public static final String DEFAULT_URL = "http://192.168.1.100:8765";

    public static final class Endpoint {
        public String id;
        public String name;
        public String url;
        public String token;
        public boolean allowInsecure;

        public Endpoint(String id, String name, String url, String token, boolean allowInsecure) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.token = token == null ? "" : token;
            this.allowInsecure = allowInsecure;
        }

        public String host() {
            try {
                String h = new java.net.URL(url.trim()).getHost();
                return h == null ? url : h;
            } catch (Exception e) {
                return url;
            }
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("token", token);
            o.put("insecure", allowInsecure);
            return o;
        }

        public static Endpoint fromJson(JSONObject o) {
            return new Endpoint(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("name", ""),
                    o.optString("url", ""),
                    o.optString("token", ""),
                    o.optBoolean("insecure", false));
        }

        @Override
        public String toString() {
            return name == null || name.isEmpty() ? url : name;
        }
    }

    private EndpointStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Returns the stored endpoints; seeds the default LAN endpoint the first time. */
    public static synchronized List<Endpoint> load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        List<Endpoint> out = new ArrayList<>();
        String raw = p.getString(KEY_LIST, null);
        if (raw == null) {
            Endpoint def = new Endpoint(UUID.randomUUID().toString(), "局域网", DEFAULT_URL, "", false);
            out.add(def);
            save(ctx, out);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(Endpoint.fromJson(o));
                }
            }
        } catch (Exception e) {
            BridgeState.setError("端点列表解析失败: " + e);
        }
        return out;
    }

    public static synchronized void save(Context ctx, List<Endpoint> endpoints) {
        JSONArray arr = new JSONArray();
        for (Endpoint e : endpoints) {
            try {
                arr.put(e.toJson());
            } catch (Exception ignored) {
                // JSONObject.put only fails on non-serializable values; our fields are all strings.
            }
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static synchronized Endpoint upsert(Context ctx, Endpoint ep) {
        List<Endpoint> all = load(ctx);
        if (ep.id == null || ep.id.isEmpty()) {
            ep.id = UUID.randomUUID().toString();
        }
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (ep.id.equals(all.get(i).id)) {
                all.set(i, ep);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(ep);
        }
        save(ctx, all);
        return ep;
    }

    public static synchronized void remove(Context ctx, String id) {
        List<Endpoint> all = load(ctx);
        List<Endpoint> kept = new ArrayList<>();
        for (Endpoint e : all) {
            if (!e.id.equals(id)) {
                kept.add(e);
            }
        }
        save(ctx, kept);
    }

    /**
     * 把 {@code id} 这条端点在列表里上/下移一位（{@code delta} 为 -1 / +1）。
     *
     * <p>顺序即优先级，所以这是"换线路"的唯一手段；到边界就什么都不做，不循环。
     */
    public static synchronized void move(Context ctx, String id, int delta) {
        List<Endpoint> all = load(ctx);
        int from = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                from = i;
                break;
            }
        }
        int to = from + delta;
        if (from < 0 || to < 0 || to >= all.size()) {
            return;
        }
        all.add(to, all.remove(from));
        save(ctx, all);
    }

    public static boolean isAutostart(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTOSTART, false);
    }

    public static void setAutostart(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART, on).apply();
    }
}
