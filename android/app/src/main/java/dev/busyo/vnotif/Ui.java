package dev.busyo.vnotif;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全应用共用的 UI 零件：语义色、卡片、行、按钮、系统栏 Insets。
 *
 * <p>为什么手搓而不是用 Material 库：本工程刻意不依赖 AndroidX（见 README），
 * 所以拿不到 {@code colorSurface}/{@code colorPrimaryContainer} 这些库属性。
 * 这里把 Material 3 的角色名映射成 {@code res/values/colors.xml} 里的同名 token，
 * 深浅色由 {@code values-night/} 提供；交互色用框架主题的 {@code colorAccent}
 * ——Android 12+ 上它就是系统 Monet 取色结果。
 */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int colorRes) {
        return c.getResources().getColor(colorRes, c.getTheme());
    }

    static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode;
        return (mode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 取主题里的颜色属性（如 {@code android.R.attr.colorAccent}）。 */
    static int attrColor(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (!c.getTheme().resolveAttribute(attr, tv, true)) {
            return 0;
        }
        if (tv.resourceId != 0) {
            return c.getResources().getColor(tv.resourceId, c.getTheme());
        }
        return tv.data;
    }

    /** 圆角实心背景。 */
    static Drawable rounded(Context c, int colorRes, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(color(c, colorRes));
        return d;
    }

    /** 圆角描边背景（用于次要容器）。 */
    static Drawable outlined(Context c, int fillRes, int strokeRes, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(color(c, fillRes));
        d.setStroke(dp(c, 1), color(c, strokeRes));
        return d;
    }

    /** 给任意背景叠一层水波纹，保证点击有 Material 反馈。 */
    static Drawable ripple(Context c, Drawable content, float alpha) {
        int base = color(c, R.color.vnotif_on_surface);
        int rc = (Math.round(255 * alpha) << 24) | (base & 0x00FFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(rc), content, null);
    }

    // ------------------------------------------------------------ 容器

    /** 卡片：圆角容器 + 内边距 + 下间距。 */
    static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(c, R.color.vnotif_surface_container, 18));
        int p = dp(c, 16);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        box.setLayoutParams(lp);
        return box;
    }

    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.vnotif_outline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, 10);
        lp.bottomMargin = dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    /** 占满剩余宽度：放进 {@link #row} 里把后面的控件顶到右侧。 */
    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    // ------------------------------------------------------------ 文本

    static TextView text(Context c, CharSequence s, float sp, int colorRes, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color(c, colorRes));
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    static TextView title(Context c, CharSequence s) {
        TextView tv = text(c, s, 15, R.color.vnotif_on_surface, true);
        tv.setPadding(0, dp(c, 2), 0, dp(c, 2));
        return tv;
    }

    static TextView body(Context c, CharSequence s) {
        TextView tv = text(c, s, 13, R.color.vnotif_on_surface, false);
        tv.setPadding(0, dp(c, 2), 0, dp(c, 2));
        return tv;
    }

    static TextView caption(Context c, CharSequence s) {
        return text(c, s, 12, R.color.vnotif_on_surface_variant, false);
    }

    static TextView mono(Context c, CharSequence s, float sp) {
        TextView tv = text(c, s, sp, R.color.vnotif_on_surface, false);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    // ------------------------------------------------------------ 控件

    /** 无边框文字按钮：次要操作（编辑、删除、测试…）。 */
    static Button textButton(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(attrColor(c, android.R.attr.colorAccent));
        b.setBackground(ripple(c, null, 0.12f));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        int ph = dp(c, 10);
        b.setPadding(ph, 0, ph, 0);
        b.setOnClickListener(l);
        return b;
    }

    /** 主按钮：填充色，一屏最多一个。 */
    static Button filledButton(Context c, String label, View.OnClickListener l) {
        return styledButton(c, label, R.color.vnotif_primary, R.color.vnotif_on_primary, l);
    }

    /** 次按钮：容器色，跟主按钮配对。 */
    static Button tonalButton(Context c, String label, View.OnClickListener l) {
        return styledButton(c, label, R.color.vnotif_primary_container,
                R.color.vnotif_on_primary_container, l);
    }

    private static Button styledButton(Context c, String label, int bgRes, int fgRes,
                                       View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(color(c, fgRes));
        b.setBackground(ripple(c, rounded(c, bgRes, 22), 0.16f));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    /**
     * 可点击行的两半：行本身（加进卡片用）与右侧状态文本（刷新时改文字/颜色用）。
     */
    static final class ActionRow {
        final LinearLayout row;
        final TextView status;

        ActionRow(LinearLayout row, TextView status) {
            this.row = row;
            this.status = status;
        }
    }

    /**
     * 可点击行：左标题 + 副标题（可空），右侧一个状态文本，整行都有水波纹。
     */
    static ActionRow actionRow(Context c, String title, String sub, View.OnClickListener l) {
        LinearLayout row = row(c);
        row.setBackground(ripple(c, null, 0.10f));
        int pv = dp(c, 10);
        row.setPadding(dp(c, 4), pv, dp(c, 4), pv);
        row.setMinimumHeight(dp(c, 52));

        LinearLayout col = column(c);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(title(c, title));
        if (sub != null && !sub.isEmpty()) {
            col.addView(caption(c, sub));
        }
        row.addView(col);

        TextView status = caption(c, "");
        status.setPadding(dp(c, 8), 0, 0, 0);
        row.addView(status);

        row.setOnClickListener(l);
        return new ActionRow(row, status);
    }

    // ------------------------------------------------------------ 系统栏

    /**
     * 给根 View 补上系统栏（状态栏 / 导航栏）的 Insets。
     *
     * <p>Android 15 起 targetSdk 35+ 强制 edge-to-edge，内容会画到系统栏底下，必须自己留白；
     * 同时把状态栏/导航栏图标的明暗跟当前深浅色对齐，否则浅色主题上会出现白底白图标。
     */
    static void applySystemBars(final Activity act, final View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            act.getWindow().setDecorFitsSystemWindows(false);
        }
        // 记下设计时的 padding：Insets 回调会来好几次，不能每次都往上加，否则越加越大。
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                //noinspection deprecation
                top = insets.getSystemWindowInsetTop();
                //noinspection deprecation
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom);
            return insets;
        });

        boolean night = isNight(act);
        View decor = act.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (night) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}
