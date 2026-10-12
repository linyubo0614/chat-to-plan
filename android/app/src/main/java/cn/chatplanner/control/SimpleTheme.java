package cn.chatplanner.control;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
/** Shared restrained colours and comfortably sized controls. */
final class SimpleTheme {
    static final int PAPER=0xfff5f6f2, INK=0xff243c3b, GREEN=0xff29796b;
    static int dp(Activity a,int v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
    static void window(Activity a){
        a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        a.getWindow().setStatusBarColor(PAPER);a.getWindow().setNavigationBarColor(PAPER);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    static void button(Activity a,Button b){
        GradientDrawable shape=new GradientDrawable();shape.setColor(0xffe8efea);shape.setCornerRadius(dp(a,12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0xffd4e3da),shape,null));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff9aaba2,GREEN}));
        b.setAllCaps(false);b.setTextSize(14);b.setElevation(0);b.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));
        b.setMinHeight(dp(a,48));b.setMinimumHeight(dp(a,48));
    }
    static void buttons(Activity a,View v){
        if(v instanceof Button && !(v instanceof android.widget.CompoundButton))button(a,(Button)v);
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)buttons(a,((ViewGroup)v).getChildAt(i));
    }
}
