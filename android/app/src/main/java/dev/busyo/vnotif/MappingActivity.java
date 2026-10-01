package dev.busyo.vnotif;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shows every desktop application the bridge has heard about, together with the phone app it is
 * currently mapped to, and lets the user change that mapping from the installed-app list.
 */
public class MappingActivity extends Activity {

    private ListView listView;
    private TextView emptyView;
    private TextView hintView;
    private final List<MappingStore.SeenApp> items = new ArrayList<>();
    private Adapter adapter;

    /** Coalesces the burst of change callbacks (one per relayed notification) into one reload. */
    private static final long RELOAD_DEBOUNCE_MS = 350L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable reloadSoon = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    /** Runs on the bridge reader thread — only schedules work, never touches views directly. */
    private final Runnable onChange = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(reloadSoon);
            handler.postDelayed(reloadSoon, RELOAD_DEBOUNCE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The bridge keeps learning about desktop apps while this screen is open; follow it live.
        MappingStore.setChangeListener(onChange);
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MappingStore.setChangeListener(null);
        handler.removeCallbacks(reloadSoon);
    }

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.vnotif_surface));
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "映射", 20, R.color.vnotif_on_surface, true));
        head.addView(Ui.spacer(this));
        head.addView(Ui.textButton(this, "刷新", v -> reload()));
        root.addView(head);

        hintView = Ui.caption(this, "");
        root.addView(hintView);

        emptyView = Ui.text(this,
                "还没有见到任何桌面应用。\n把电脑端的 VNotif 服务跑起来，手机连上后这里会自动出现。",
                13, R.color.vnotif_on_surface_variant, false);
        emptyView.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(emptyView);

        adapter = new Adapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(Ui.dp(this, 8));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) {
                pick(items.get(position));
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            clear(items.get(position));
            return true;
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Ui.applySystemBars(this, root);
        return root;
    }

    private void reload() {
        items.clear();
        items.addAll(MappingStore.seen(this));
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        hintView.setText(empty
                ? "点条目选择手机 App，长按清除映射。"
                : items.size() + " 个桌面应用 · 点条目选择手机 App，长按清除映射。");
    }

    private void pick(final MappingStore.SeenApp app) {
        AppPickerDialog.show(this, "选择「" + app.app + "」对应的手机 App", pkg -> {
            MappingStore.setMapping(this, app.key, pkg);
            toast("已映射到 " + describe(pkg));
            reload();
            // Re-post live notifications so their tap targets follow the new mapping at once.
            new Thread(NotifRenderer::refreshActive, "vnotif-refresh").start();
        });
    }

    private void clear(final MappingStore.SeenApp app) {
        if (MappingStore.mapping(this, app.key) == null) {
            toast("「" + app.app + "」当前没有映射");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除映射")
                .setMessage("「" + app.app + "」将回到未映射状态（点击时跳应用商店搜索）。")
                .setPositiveButton("清除", (d, w) -> {
                    MappingStore.clearMapping(this, app.key);
                    reload();
                    new Thread(NotifRenderer::refreshActive, "vnotif-refresh").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String describe(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? pkg : label + " (" + pkg + ")";
        } catch (Exception e) {
            return pkg;
        }
    }

    private String label(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未映射";
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(info);
            return name == null ? pkg : name.toString();
        } catch (Exception e) {
            return pkg + "（未安装）";
        }
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String formatTime(long seconds) {
        if (seconds <= 0L) {
            return "—";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(seconds * 1000L));
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MappingStore.SeenApp app = items.get(position);
            PackageManager pm = getPackageManager();

            LinearLayout row = Ui.column(MappingActivity.this);
            row.setBackground(Ui.rounded(MappingActivity.this,
                    R.color.vnotif_surface_container, 14));
            int pad = Ui.dp(MappingActivity.this, 14);
            row.setPadding(pad, pad, pad, pad);

            row.addView(Ui.text(MappingActivity.this, app.app, 15,
                    R.color.vnotif_on_surface, true));

            String mapped = MappingStore.mapping(MappingActivity.this, app.key);
            boolean missing = false;
            String target;
            if (mapped == null || mapped.isEmpty()) {
                if (app.pkgHint != null && !app.pkgHint.isEmpty()) {
                    target = "未映射 → 点击时回退到建议包名 " + app.pkgHint;
                } else {
                    target = "未映射 → 点击时跳应用商店搜索";
                }
            } else if (!isInstalled(pm, mapped)) {
                missing = true;
                target = "→ " + mapped + "（未安装）";
            } else if (!NotifLauncher.isLaunchable(MappingActivity.this, mapped)) {
                missing = true;
                target = "→ " + label(mapped) + "（没有可启动的入口）";
            } else {
                target = "→ " + label(mapped);
            }
            row.addView(Ui.text(MappingActivity.this, target, 13,
                    missing ? R.color.vnotif_warn : R.color.vnotif_on_surface, false));

            String meta = "app_id: " + (app.appId == null || app.appId.isEmpty() ? "—" : app.appId)
                    + " · 收到 " + app.seen + " 次"
                    + " · 最近 " + formatTime(app.last);
            row.addView(Ui.caption(MappingActivity.this, meta));

            return row;
        }
    }
}
