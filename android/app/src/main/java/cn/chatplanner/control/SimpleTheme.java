package cn.chatplanner.control;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One quiet visual language for conversation, records and settings. */
final class SimpleTheme {
  static final int PAPER = 0xfffafbfc,
      INK = 0xff202632,
      MUTED = 0xff768090,
      BLUE = 0xff4d6bfe,
      GREEN = BLUE,
      LINE = 0xffe7eaf0,
      SOFT = 0xfff0f3f8;

  static int dp(Activity a, int v) {
    return Math.round(v * a.getResources().getDisplayMetrics().density);
  }

  static void window(Activity a) {
    a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    a.getWindow().setStatusBarColor(PAPER);
    a.getWindow().setNavigationBarColor(PAPER);
    a.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    a.getWindow()
        .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  static GradientDrawable shape(Activity a, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(a, radius));
    return d;
  }

  static LinearLayout column(Activity a) {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  static LinearLayout row(Activity a) {
    LinearLayout v = new LinearLayout(a);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  static TextView text(Activity a, String s, int size, int color, boolean bold) {
    TextView v = new TextView(a);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(dp(a, 3), 1);
    if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  static void gap(Activity a, LinearLayout p, int size) {
    p.addView(new View(a), new LinearLayout.LayoutParams(1, dp(a, size)));
  }

  static void weight(LinearLayout p, View v) {
    p.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }

  static void button(Activity a, Button b) {
    b.setBackground(
        new RippleDrawable(ColorStateList.valueOf(0xffe2e7f8), shape(a, SOFT, 14), null));
    b.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {0xffa5adbc, BLUE}));
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
    b.setMinHeight(dp(a, 46));
    b.setMinimumHeight(dp(a, 46));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
  }

  static Button button(Activity a, String title, Runnable action) {
    Button b = new Button(a);
    b.setText(title);
    button(a, b);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  static LinearLayout card(Activity a) {
    LinearLayout c = column(a);
    c.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
    GradientDrawable s = shape(a, 0xffffffff, 18);
    s.setStroke(dp(a, 1), LINE);
    c.setBackground(s);
    return c;
  }

  static void insets(Activity a, View root, int padding) {
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              dp(a, padding) + i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop() + dp(a, 8),
              dp(a, padding) + i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom() + dp(a, 4));
          return i;
        });
    root.requestApplyInsets();
  }

  static void navigation(Activity a, LinearLayout p, String selected) {
    gap(a, p, 6);
    LinearLayout bar = row(a);
    String[] labels = {"对话", "计划", "备忘"};
    for (String label : labels) {
      Button b =
          button(
              a,
              label,
              () -> {
                if (label.equals(selected)) return;
                Intent intent =
                    label.equals("对话")
                        ? new Intent(a, ChatActivity.class)
                        : new Intent(a, PlannerActivity.class)
                            .putExtra("page", label.equals("备忘") ? "memos" : "tasks");
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                a.startActivity(intent);
                if (!(a instanceof ChatActivity)) a.finish();
              });
      b.setBackgroundColor(PAPER);
      b.setTextColor(label.equals(selected) ? BLUE : MUTED);
      weight(bar, b);
    }
    p.addView(bar);
  }

  static void buttons(Activity a, View v) {
    if (v instanceof Button && !(v instanceof android.widget.CompoundButton)) button(a, (Button) v);
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
        buttons(a, ((ViewGroup) v).getChildAt(i));
  }
}
