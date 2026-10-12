package cn.chatplanner.control;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;
import org.json.JSONObject;

/** Rebuilds alarms after restart and posts due reminders without background AI calls. */
public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    PendingResult result = goAsync();
    new Thread(
            () -> {
              try {
                if (ReminderScheduler.ACTION.equals(intent.getAction())) {
                  show(context, intent);
                  ReminderScheduler.refresh(context);
                } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                    || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
                  ReminderScheduler.refresh(context);
              } catch (RuntimeException ignored) {
                // A damaged local database or notification permission change
                // must not crash the application from a background broadcast.
                android.util.Log.w("LocalReminder", "本地提醒暂时无法处理，请打开应用检查记录与通知设置。");
              } finally {
                result.finish();
              }
            },
            "local-reminders")
        .start();
  }

  @android.annotation.SuppressLint(
      "MissingPermission") // notificationsAllowed checks POST_NOTIFICATIONS before posting.
  private static void show(Context context, Intent intent) {
    String id = intent.getStringExtra("record_id");
    String expected = intent.getStringExtra("signature");
    if (id == null || expected == null || !ReminderScheduler.notificationsAllowed(context)) return;
    JSONObject record = LocalStore.get(context).getRecord(id);
    if (record == null || record.optBoolean("completed")) return;
    long target = ReminderScheduler.targetTime(record, ZoneId.systemDefault());
    long trigger = ReminderScheduler.reminderTime(record, ZoneId.systemDefault());
    String actual = ReminderScheduler.signature(record, target);
    long now = System.currentTimeMillis();
    // An edit may occur just as an old alarm is delivered. Never notify a
    // deleted/completed record, a replaced date or an already delivered alert.
    if (trigger < 0
        || !expected.equals(actual)
        || trigger > now + 1000
        || ReminderScheduler.alreadyDelivered(context, id, actual)) return;
    ReminderScheduler.ensureChannel(context);
    Intent open =
        new Intent(context, PlannerActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .setData(intent.getData())
            .putExtra("record_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent tap =
        PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String body = record.optString("body", "").trim();
    String time = record.optString("starts_at", "");
    if (time.isEmpty() || "null".equals(time)) time = record.optString("due_at", "");
    if (time.isEmpty() || "null".equals(time)) time = record.optString("task_date", "") + " 09:00";
    String description = time.replace('T', ' ') + (body.isEmpty() ? "" : "\n" + body);
    if (description.length() > 350) description = description.substring(0, 350);
    Notification notification =
        new Notification.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(record.optString("title", "事项提醒"))
            .setContentText(description)
            .setStyle(new Notification.BigTextStyle().bigText(description))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setShowWhen(true)
            .build();
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.notify("record:" + id, 0, notification);
      ReminderScheduler.delivered(context, id, actual);
    }
  }
}
