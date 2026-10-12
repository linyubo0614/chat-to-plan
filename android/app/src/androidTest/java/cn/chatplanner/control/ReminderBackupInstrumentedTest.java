package cn.chatplanner.control;

import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises actual phone SQLite and file imports, not UI placeholders. */
public final class ReminderBackupInstrumentedTest extends InstrumentationTestCase {
  private Context context() {
    return getInstrumentation().getTargetContext();
  }

  private JSONObject record(String title) throws Exception {
    return new JSONObject()
        .put("kind", "todo")
        .put("record_type", "ddl")
        .put("title", title)
        .put("body", "test record")
        .put("category", "学习")
        .put("important", true)
        .put("urgent", false)
        .put("completed", false)
        .put("task_date", "2035-05-10")
        .put("due_at", "2035-05-10T18:00:00+08:00");
  }

  private JSONObject backup(JSONArray records) throws Exception {
    return new JSONObject()
        .put("format", "chat-to-plan-backup")
        .put("version", 1)
        .put("records", records);
  }

  public void testReminderPreservesInstantAndRequiresExplicitReminder() throws Exception {
    JSONObject record = record("offset reminder").put("reminder_minutes", 30);
    long due = Instant.parse("2035-05-10T10:00:00Z").toEpochMilli();
    assertEquals(
        due - 30 * 60_000L, ReminderScheduler.reminderTime(record, ZoneId.of("America/New_York")));
    assertEquals(
        due - 30 * 60_000L, ReminderScheduler.reminderTime(record, ZoneId.of("Asia/Shanghai")));
    record.put("starts_at", "2035-05-10T17:00:00+08:00");
    assertEquals(
        due - 90 * 60_000L, ReminderScheduler.reminderTime(record, ZoneId.of("Asia/Shanghai")));
    record.put("reminder_minutes", JSONObject.NULL);
    assertEquals(-1L, ReminderScheduler.reminderTime(record, ZoneId.of("Asia/Shanghai")));
    record.put("reminder_minutes", 30).put("starts_at", "not a date");
    assertEquals(-1L, ReminderScheduler.reminderTime(record, ZoneId.of("Asia/Shanghai")));
    record
        .put("starts_at", JSONObject.NULL)
        .put("due_at", JSONObject.NULL)
        .put("reminder_minutes", 0);
    assertEquals(
        Instant.parse("2035-05-10T01:00:00Z").toEpochMilli(),
        ReminderScheduler.reminderTime(record, ZoneId.of("Asia/Shanghai")));
  }

  public void testBackupExcludesCredentialsAndReimportKeepsEditsAndDeletion() throws Exception {
    LocalStore store = LocalStore.get(context());
    AppSettings settings = new AppSettings(context());
    String previousKey = settings.apiKey();
    String secret = "sk-local-backup-test-" + UUID.randomUUID().toString();
    JSONObject saved = null;
    try {
      settings.setApiKey(secret);
      saved =
          store.createRecord(
              record("backup-idempotence-" + UUID.randomUUID()), UUID.randomUUID().toString());
      String exported = BackupManager.exportJson(context());
      assertFalse("API Key must not be in exported records", exported.contains(secret));
      JSONObject envelope = new JSONObject(exported);
      assertFalse(envelope.has("settings"));
      assertFalse(envelope.has("messages"));
      String single =
          backup(new JSONArray().put(saved))
              .put("settings", new JSONObject().put("api_key", "replace-me"))
              .toString();
      JSONObject changed = record("locally edited");
      store.updateRecord(saved.getString("id"), saved.getInt("revision"), changed);
      JSONObject summary = BackupManager.importJson(context(), single);
      assertEquals(0, summary.getInt("imported"));
      assertEquals(1, summary.getInt("skipped"));
      assertEquals("locally edited", store.getRecord(saved.getString("id")).getString("title"));
      assertEquals(secret, settings.apiKey());
      JSONObject current = store.getRecord(saved.getString("id"));
      store.deleteRecord(current.getString("id"), current.getInt("revision"));
      summary = BackupManager.importJson(context(), single);
      assertEquals(1, summary.getInt("skipped"));
      assertNull("Reimport must not resurrect a deletion", store.getRecord(saved.getString("id")));
      assertEquals(secret, settings.apiKey());
    } finally {
      settings.setApiKey(previousKey);
      if (saved != null) {
        JSONObject current = store.getRecord(saved.optString("id"));
        if (current != null)
          store.deleteRecord(current.optString("id"), current.optInt("revision"));
      }
    }
  }

  public void testInvalidBackupDoesNotPartiallyImport() throws Exception {
    LocalStore store = LocalStore.get(context());
    String id = "atomic_" + UUID.randomUUID().toString().replace("-", "");
    JSONArray records =
        new JSONArray()
            .put(record("valid before invalid").put("id", id))
            .put(record("invalid date").put("id", "invalid_" + id).put("due_at", "nonsense"));
    try {
      BackupManager.importJson(context(), backup(records).toString());
      fail("Invalid record accepted");
    } catch (IllegalArgumentException expected) {
      assertNull(store.getRecord(id));
    }
  }

  public void testLegacySqliteUriImportFiltersDeletedAndDeduplicates() throws Exception {
    File file = File.createTempFile("legacy-planner-test-", ".db", context().getCacheDir());
    String id = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    LocalStore store = LocalStore.get(context());
    int beforeTemps = importTemps();
    try {
      try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) {
        database.execSQL(
            "CREATE TABLE planner_records (id TEXT PRIMARY KEY,kind TEXT,title TEXT,body"
                + " TEXT,category TEXT,important INTEGER,urgent INTEGER,completed INTEGER,task_date"
                + " TEXT,deleted INTEGER,revision INTEGER,record_type TEXT,due_at TEXT,created"
                + " REAL,updated REAL)");
        ContentValues row = new ContentValues();
        row.put("id", id);
        row.put("kind", "todo");
        row.put("title", "legacy task");
        row.put("body", "migrated locally");
        row.put("category", "课程");
        row.put("important", 1);
        row.put("urgent", 0);
        row.put("completed", 0);
        row.put("task_date", "2035-05-10");
        row.put("deleted", 0);
        row.put("revision", 4);
        row.put("record_type", "ddl");
        row.put("due_at", "2035-05-10T18:00:00+08:00");
        row.put("created", 1700000000d);
        row.put("updated", 1700000050d);
        database.insertOrThrow("planner_records", null, row);
        row.put("id", id + "_deleted");
        row.put("deleted", 1);
        database.insertOrThrow("planner_records", null, row);
      }
      JSONObject result = BackupManager.importUri(context(), Uri.fromFile(file));
      assertEquals(1, result.getInt("imported"));
      assertEquals(1, result.getInt("ignored_deleted"));
      JSONObject migrated = store.getRecord(id);
      assertEquals(4, migrated.getInt("revision"));
      assertEquals("migrated locally", migrated.getString("body"));
      assertTrue(migrated.getBoolean("important"));
      assertFalse(migrated.getBoolean("urgent"));
      assertNull(store.getRecord(id + "_deleted"));
      result = BackupManager.importUri(context(), Uri.fromFile(file));
      assertEquals(0, result.getInt("imported"));
      assertEquals(1, result.getInt("skipped"));
      assertTrue("Source database must remain intact", file.isFile());
      assertEquals("Import scratch files must be cleaned", beforeTemps, importTemps());
    } finally {
      JSONObject migrated = store.getRecord(id);
      if (migrated != null) store.deleteRecord(id, migrated.optInt("revision"));
      file.delete();
      new File(file.getPath() + "-wal").delete();
      new File(file.getPath() + "-shm").delete();
    }
  }

  public void testActualNotificationIgnoresStaleAlarmAndDoesNotRepeat() throws Exception {
    if (Build.VERSION.SDK_INT >= 33) {
      try (ParcelFileDescriptor descriptor =
              getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand(
                      "pm grant cn.chatplanner.control android.permission.POST_NOTIFICATIONS");
          java.io.InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        byte[] buffer = new byte[512];
        while (output.read(buffer) != -1) {}
      }
    }
    ReminderScheduler.ensureChannel(context());
    assertTrue(
        "Test emulator must allow local notifications",
        ReminderScheduler.notificationsAllowed(context()));
    LocalStore store = LocalStore.get(context());
    NotificationManager manager = context().getSystemService(NotificationManager.class);
    OffsetDateTime due = OffsetDateTime.now().plusHours(1);
    JSONObject input =
        record("notification-test-" + UUID.randomUUID())
            .put("task_date", due.toLocalDate().toString())
            .put("due_at", due.toString())
            .put("reminder_minutes", 0);
    JSONObject saved = store.createRecord(input, UUID.randomUUID().toString());
    String id = saved.getString("id"), tag = "record:" + id;
    try {
      sendReminder(id, "stale-signature");
      Thread.sleep(300);
      assertNull("A stale future alarm must not notify", notification(tag));
      input.put("reminder_minutes", 60);
      saved = store.updateRecord(id, saved.getInt("revision"), input);
      String signature =
          ReminderScheduler.signature(
              saved, ReminderScheduler.targetTime(saved, ZoneId.systemDefault()));
      sendReminder(id, signature);
      long deadline = System.currentTimeMillis() + 5000;
      StatusBarNotification posted;
      while (((posted = notification(tag)) == null
              || !ReminderScheduler.alreadyDelivered(context(), id, signature))
          && System.currentTimeMillis() < deadline) Thread.sleep(50);
      assertNotNull("Local receiver must post a real notification", posted);
      long postedAt = posted.getPostTime();
      assertTrue(ReminderScheduler.alreadyDelivered(context(), id, signature));
      sendReminder(id, signature);
      Thread.sleep(300);
      assertEquals(
          "Repeated alarm must not repost notification", postedAt, notification(tag).getPostTime());
      input.put("completed", true);
      saved = store.updateRecord(id, saved.getInt("revision"), input);
      ReminderScheduler.refresh(context());
      assertNotificationRemoved("Completed records must remove their active notification", tag);

      due = OffsetDateTime.now().plusMinutes(30);
      input
          .put("completed", false)
          .put("task_date", due.toLocalDate().toString())
          .put("due_at", due.toString());
      saved = store.updateRecord(id, saved.getInt("revision"), input);
      signature =
          ReminderScheduler.signature(
              saved, ReminderScheduler.targetTime(saved, ZoneId.systemDefault()));
      sendReminder(id, signature);
      deadline = System.currentTimeMillis() + 5000;
      while (((posted = notification(tag)) == null
              || !ReminderScheduler.alreadyDelivered(context(), id, signature))
          && System.currentTimeMillis() < deadline) Thread.sleep(50);
      assertNotNull("Changed reminder must post a real notification before deletion", posted);
      assertTrue(ReminderScheduler.alreadyDelivered(context(), id, signature));
      store.deleteRecord(id, saved.getInt("revision"));
      ReminderScheduler.refresh(context());
      assertNotificationRemoved("Deleted records must remove their active notification", tag);
    } finally {
      manager.cancel(tag, 0);
      JSONObject current = store.getRecord(id);
      if (current != null) store.deleteRecord(id, current.optInt("revision"));
      ReminderScheduler.refresh(context());
    }
  }

  private void sendReminder(String id, String signature) {
    context()
        .sendBroadcast(
            new Intent(context(), ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION)
                .putExtra("record_id", id)
                .putExtra("signature", signature));
  }

  private StatusBarNotification notification(String tag) {
    for (StatusBarNotification notification :
        context().getSystemService(NotificationManager.class).getActiveNotifications())
      if (tag.equals(notification.getTag())) return notification;
    return null;
  }

  private void assertNotificationRemoved(String message, String tag) throws InterruptedException {
    // NotificationManager.cancel queues work in the system service; refresh returning
    // does not mean getActiveNotifications has already observed the cancellation.
    long deadline = SystemClock.elapsedRealtime() + 3000;
    StatusBarNotification active;
    while ((active = notification(tag)) != null) {
      long remaining = deadline - SystemClock.elapsedRealtime();
      if (remaining <= 0) break;
      Thread.sleep(Math.min(50, remaining));
    }
    assertNull(message, active);
  }

  private int importTemps() {
    String[] names =
        context().getCacheDir().list((directory, name) -> name.startsWith("planner-import-"));
    return names == null ? 0 : names.length;
  }
}
