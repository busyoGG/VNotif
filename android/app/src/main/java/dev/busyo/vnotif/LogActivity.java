package dev.busyo.vnotif;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 日志二级界面：最新的在最上面。
 *
 * <p>刷新是事件驱动的——{@link BridgeState#setChangeListener} 在每次追加日志时回调，
 * 这里只把回调转成主线程上一次防抖重绘，不轮询、不在桥接线程碰 View。
 */
public class LogActivity extends Activity {

    /** 合并突发：一条通知常常瞬间写好行日志，没必要每行都重绑一次列表。 */
    private static final long RELOAD_DEBOUNCE_MS = 250L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> items = new ArrayList<>();

    private ListView listView;
    private TextView emptyView;
    private TextView hintView;
    private Adapter adapter;

    /** Runs on the main thread after the debounce. */
    private final Runnable reloadNow = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    /** Runs on the bridge reader thread — only schedules work, never touches views directly. */
    private final Runnable onLogChanged = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(reloadNow);
            handler.postDelayed(reloadNow, RELOAD_DEBOUNCE_MS);
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
        // 服务在后台一直写日志，这个界面开着的时候就跟着它走。
        BridgeState.setChangeListener(onLogChanged);
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeState.setChangeListener(null);
        handler.removeCallbacks(reloadNow);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.vnotif_surface));
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "日志", 20, R.color.vnotif_on_surface, true));
        head.addView(Ui.spacer(this));
        head.addView(Ui.textButton(this, "清空", v -> clear()));
        root.addView(head);

        hintView = Ui.caption(this, "");
        hintView.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        root.addView(hintView);

        emptyView = Ui.text(this, "（暂无日志）\n把服务跑起来，连接和通知的每一步都会记在这里。",
                13, R.color.vnotif_on_surface_variant, false);
        emptyView.setPadding(0, Ui.dp(this, 24), 0, 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        adapter = new Adapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(Ui.dp(this, 6));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Ui.applySystemBars(this, root);
        return root;
    }

    // --------------------------------------------------------------- 数据

    private void reload() {
        List<String> newestFirst = BridgeState.snapshotLogs();
        Collections.reverse(newestFirst);

        // 列表锚在顶部（也就是最新的一条）时，来新日志就跟着往下推；用户翻到下面看历史时不打扰他。
        boolean stayAtTop = isAtTop();
        if (!newestFirst.equals(items)) {
            items.clear();
            items.addAll(newestFirst);
            adapter.notifyDataSetChanged();
            if (stayAtTop) {
                listView.setSelectionFromTop(0, 0);
            }
        }

        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        hintView.setText(empty
                ? "最新的在最上面"
                : items.size() + " 条 · 最新的在最上面");
    }

    private boolean isAtTop() {
        if (listView.getChildCount() == 0) {
            return true;
        }
        View first = listView.getChildAt(0);
        return listView.getFirstVisiblePosition() == 0 && first.getTop() >= -Ui.dp(this, 4);
    }

    private void clear() {
        if (BridgeState.logCount() == 0) {
            toast("没有日志可清");
            return;
        }
        BridgeState.clearLogs();
        reload();
        toast("日志已清空");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 一条日志拆成「时间戳」+「正文」两行显示，长正文换行，不做截断。 */
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
            LinearLayout row;
            TextView stampView;
            TextView bodyView;
            if (convertView == null) {
                row = Ui.column(LogActivity.this);
                row.setBackground(Ui.rounded(LogActivity.this,
                        R.color.vnotif_surface_container_low, 10));
                int pad = Ui.dp(LogActivity.this, 10);
                row.setPadding(pad, pad, pad, pad);

                stampView = Ui.caption(LogActivity.this, "");
                bodyView = Ui.mono(LogActivity.this, "", 12);
                row.addView(stampView);
                row.addView(bodyView);
                row.setTag(new Holder(stampView, bodyView));
            } else {
                row = (LinearLayout) convertView;
                Holder holder = (Holder) row.getTag();
                stampView = holder.stamp;
                bodyView = holder.body;
            }

            String line = items.get(position);
            String stamp = "";
            String message = line;
            int split = line.indexOf("  ");
            if (split > 0 && split <= 10) {
                stamp = line.substring(0, split);
                message = line.substring(split + 2);
            }
            stampView.setText(stamp);
            stampView.setVisibility(stamp.isEmpty() ? View.GONE : View.VISIBLE);
            bodyView.setText(message);
            return row;
        }
    }

    private static final class Holder {
        final TextView stamp;
        final TextView body;

        Holder(TextView stamp, TextView body) {
            this.stamp = stamp;
            this.body = body;
        }
    }
}
