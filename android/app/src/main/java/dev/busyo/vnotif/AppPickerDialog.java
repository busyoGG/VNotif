package dev.busyo.vnotif;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists the launchable apps installed on the phone so the user can pick the target for a desktop app.
 *
 * <p>Backed by {@code queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)}; the manifest
 * {@code <queries>} declaration is what makes this return anything on Android 11+.
 */
public final class AppPickerDialog {

    public interface Listener {
        void onPicked(String pkg);
    }

    private static final class Entry {
        String pkg;
        String label;
        Drawable icon;
    }

    private AppPickerDialog() {
    }

    public static void show(final Activity act, String title, final Listener listener) {
        Context ctx = act;

        final EditText search = new EditText(ctx);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);

        final ListView list = new ListView(ctx);
        // 高度取"屏幕一半"与 420dp 的较小值：写死 380dp 在小屏 + 键盘弹出时会顶出可视区。
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int listHeight = Math.min(dp(ctx, 420), dm.heightPixels / 2);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        list.setLayoutParams(listLp);
        list.setDivider(null);
        list.setDividerHeight(dp(ctx, 2));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 12);
        box.setPadding(pad, pad, pad, pad);
        box.addView(search);
        box.addView(list);

        final Adapter adapter = new Adapter(ctx);
        list.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("使用输入的包名", (d, w) -> {
                    String typed = search.getText().toString().trim();
                    if (!typed.isEmpty()) {
                        listener.onPicked(typed);
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry e = adapter.getItem(position);
            if (e != null) {
                listener.onPicked(e.pkg);
            }
            dialog.dismiss();
        });

        dialog.show();
        adapter.setItems(loadApps(ctx));
    }

    private static List<Entry> loadApps(Context ctx) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) {
                    continue;
                }
                Entry e = new Entry();
                e.pkg = pkg;
                CharSequence label = ri.loadLabel(pm);
                e.label = label == null ? pkg : label.toString();
                out.add(e);
            }
        } catch (Exception e) {
            BridgeState.setError("列出已安装应用失败: " + e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics());
    }

    private static final class Adapter extends BaseAdapter {
        private final Context ctx;
        private final List<Entry> all = new ArrayList<>();
        private final List<Entry> shown = new ArrayList<>();
        private String query = "";

        Adapter(Context ctx) {
            this.ctx = ctx;
        }

        void setItems(List<Entry> items) {
            all.clear();
            all.addAll(items);
            applyFilter();
        }

        void filter(String q) {
            query = q == null ? "" : q.trim().toLowerCase(Locale.US);
            applyFilter();
        }

        private void applyFilter() {
            shown.clear();
            if (query.isEmpty()) {
                shown.addAll(all);
            } else {
                for (Entry e : all) {
                    if (e.label.toLowerCase(Locale.US).contains(query)
                            || e.pkg.toLowerCase(Locale.US).contains(query)) {
                        shown.add(e);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Entry getItem(int position) {
            return position < 0 || position >= shown.size() ? null : shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Entry e = getItem(position);
            int pad = dp(ctx, 8);
            int iconSize = dp(ctx, 40);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            // 行要能被点：给个水波纹反馈，否则长列表里点下去毫无回应。
            row.setBackground(Ui.ripple(ctx, null, 0.10f));

            ImageView icon = new ImageView(ctx);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            if (e != null) {
                if (e.icon == null) {
                    try {
                        e.icon = ctx.getPackageManager().getApplicationIcon(e.pkg);
                    } catch (Exception ignored) {
                        // Missing icon just leaves the ImageView blank.
                    }
                }
                if (e.icon != null) {
                    icon.setImageDrawable(e.icon);
                }
            }
            row.addView(icon);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = pad;
            col.setLayoutParams(colLp);

            col.addView(Ui.text(ctx, e == null ? "" : e.label, 15,
                    R.color.vnotif_on_surface, false));
            col.addView(Ui.caption(ctx, e == null ? "" : e.pkg));

            row.addView(col);
            row.setMinimumHeight(dp(ctx, 56));
            return row;
        }
    }
}
