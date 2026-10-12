package cn.chatplanner.control;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-device reminders. Uses inexact alarms: no exact-alarm permission or network. */
public final class ReminderScheduler {
  static final String CHANNEL = "local-plan-reminders";
  static final String ACTION = "cn.chatplanner.control.LOCAL_REMINDER";
  private static final String PREFS = "local-reminder-state";
  private static final String SCHEDULED = "scheduled-records";
  // Android caps concurrently scheduled alarms per app. Keep the nearest
  // reminders and advance the window when an alarm fires or the app opens.
  private static final int MAX_ALARMS = 200;

  private ReminderScheduler() {}

  public static boolean notificationsAllowed(Context context) {
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  static void ensureChannel(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, "日程与截止提醒", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription("手机本地提醒；省电设置可能使提醒延后。无需联网。");
    manager.createNotificationChannel(channel);
  }

  /** Replaces alarms after edits, import, app startup, reboot or a clock change. */
  public static synchronized void refresh(Context supplied) {
    Context context = supplied.getApplicationContext();
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    if (alarms == null) return;
    SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    for (String id : new HashSet<>(state.getStringSet(SCHEDULED, new HashSet<>()))) {
      PendingIntent previous = pending(context, id, null, PendingIntent.FLAG_NO_CREATE);
      if (previous != null) {
        alarms.cancel(previous);
        previous.cancel();
      }
    }
    ensureChannel(context);
    JSONArray records = LocalStore.get(context).records(null);
    Set<String> active = new HashSet<>();
    List<JSONObject> candidates = new ArrayList<>();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.optJSONObject(i);
      if (record == null || record.optBoolean("completed")) continue;
      active.add(record.optString("id", ""));
      candidates.add(record);
    }
    NotificationManager notifications = context.getSystemService(NotificationManager.class);
    if (notifications != null) {
      for (android.service.notification.StatusBarNotification notification :
          notifications.getActiveNotifications()) {
        String tag = notification.getTag();
        if (tag != null && tag.startsWith("record:") && !active.contains(tag.substring(7)))
          notifications.cancel(tag, notification.getId());
      }
    }
    candidates.sort(
        Comparator.comparingLong(record -> reminderTime(record, ZoneId.systemDefault())));
    Set<String> scheduled = new HashSet<>();
    if (notificationsAllowed(context)) {
      long now = System.currentTimeMillis();
      for (JSONObject record : candidates) {
        long target = targetTime(record, ZoneId.systemDefault());
        long trigger = reminderTime(record, ZoneId.systemDefault());
        String id = record.optString("id", "");
        String signature = signature(record, target);
        if (id.isEmpty()
            || target <= now
            || trigger < 0
            || record.optBoolean("completed")
            || signature.equals(state.getString("delivered:" + id, ""))) continue;
        Intent intent = reminderIntent(context, id).putExtra("signature", signature);
        // If the app was closed when the reminder became due, deliver it
        // once on the next refresh while the event is still in the future.
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            Math.max(now + 1000, trigger),
            pending(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT));
        scheduled.add(id);
        if (scheduled.size() >= MAX_ALARMS) break;
      }
    }
    state.edit().putStringSet(SCHEDULED, scheduled).apply();
  }

  static long targetTime(JSONObject record, ZoneId timezone) {
    String value = record.optString("starts_at", "");
    if (value.isEmpty() || "null".equals(value)) value = record.optString("due_at", "");
    if (value.isEmpty() || "null".equals(value)) {
      String day = record.optString("task_date", "");
      if (day.isEmpty() || "null".equals(day)) return -1;
      try {
        return LocalDate.parse(day).atTime(9, 0).atZone(timezone).toInstant().toEpochMilli();
      } catch (DateTimeParseException | ArithmeticException ex) {
        return -1;
      }
    }
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (DateTimeParseException | ArithmeticException ignored) {
    }
    try {
      return OffsetDateTime.parse(value).toInstant().toEpochMilli();
    } catch (DateTimeParseException | ArithmeticException ignored) {
    }
    // Local timestamps are interpreted in the current device timezone.
    try {
      return LocalDateTime.parse(value).atZone(timezone).toInstant().toEpochMilli();
    } catch (DateTimeParseException | ArithmeticException ex) {
      return -1;
    }
  }

  static long reminderTime(JSONObject record, ZoneId timezone) {
    long target = targetTime(record, timezone);
    int minutes = record.optInt("reminder_minutes", -1);
    if (target < 0 || minutes < 0 || minutes > 525600) return -1;
    return target - minutes * 60_000L;
  }

  static String signature(JSONObject record, long target) {
    String value = target + ":" + record.optInt("reminder_minutes", -1);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static boolean alreadyDelivered(Context context, String id, String signature) {
    return signature.equals(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("delivered:" + id, ""));
  }

  static void delivered(Context context, String id, String signature) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("delivered:" + id, signature)
        .apply();
  }

  private static Intent reminderIntent(Context context, String id) {
    return new Intent(context, ReminderReceiver.class)
        .setAction(ACTION)
        .setData(
            new Uri.Builder()
                .scheme("chatplanner-reminder")
                .authority("record")
                .appendPath(id)
                .build())
        .putExtra("record_id", id);
  }

  private static PendingIntent pending(Context context, String id, Intent intent, int flags) {
    return PendingIntent.getBroadcast(
        context,
        0,
        intent == null ? reminderIntent(context, id) : intent,
        flags | PendingIntent.FLAG_IMMUTABLE);
  }
}
