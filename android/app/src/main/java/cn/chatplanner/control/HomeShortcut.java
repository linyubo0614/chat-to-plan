package cn.chatplanner.control;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.widget.Toast;

final class HomeShortcut {
    static void offerOnce(Activity activity) {
        android.content.SharedPreferences p=activity.getSharedPreferences("home-icon",0);
        if(!p.getBoolean("offered",false)) { p.edit().putBoolean("offered",true).apply();request(activity); }
    }
    static void request(Activity activity) {
        ShortcutManager manager=activity.getSystemService(ShortcutManager.class);
        if(manager==null||!manager.isRequestPinShortcutSupported()) {
            Toast.makeText(activity,"请在应用列表长按“对话计划”，拖到桌面。",Toast.LENGTH_LONG).show();return;
        }
        for(ShortcutInfo item:manager.getPinnedShortcuts())if(item.getId().equals("planner-home")) {
            Toast.makeText(activity,"桌面图标已添加",Toast.LENGTH_SHORT).show();return;
        }
        ShortcutInfo shortcut=new ShortcutInfo.Builder(activity,"planner-home").setShortLabel("对话计划")
            .setIcon(Icon.createWithResource(activity,R.drawable.ic_app))
            .setIntent(new Intent(activity,PlannerActivity.class).setAction(Intent.ACTION_VIEW)).build();
        if(!manager.requestPinShortcut(shortcut,null))Toast.makeText(activity,"请在系统桌面设置中允许添加图标",Toast.LENGTH_LONG).show();
    }
}
