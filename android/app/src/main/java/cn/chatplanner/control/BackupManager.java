package cn.chatplanner.control;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** User-selected record backups; excludes credentials, settings and AI history. */
public final class BackupManager {
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final int MAX_RECORDS = 20_000;
  private static final String FORMAT = "chat-to-plan-backup";
  private static final byte[] SQLITE_HEADER =
      "SQLite format 3\000".getBytes(StandardCharsets.US_ASCII);
  private static final String[] RECORD_FIELDS = {
    "id", "revision", "created_at", "updated_at", "kind", "record_type", "title", "body",
    "category", "important", "urgent", "completed", "task_date", "due_at", "starts_at", "ends_at",
    "location", "reminder_minutes"
  };

  private BackupManager() {}

  public static String exportJson(Context context) {
    try {
      JSONArray records = LocalStore.get(context).records(null);
      if (records.length() > MAX_RECORDS)
        throw new IllegalArgumentException("记录超过单次备份上限（20000 条）。");
      JSONArray clean = new JSONArray();
      for (int i = 0; i < records.length(); i++) clean.put(recordOnly(records.getJSONObject(i)));
      String output =
          new JSONObject()
              .put("format", FORMAT)
              .put("version", 1)
              .put("exported_at", Instant.now().toString())
              .put("records", clean)
              .toString(2);
      if (output.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
        throw new IllegalArgumentException("备份超过 16 MB，请先整理记录后再导出。");
      return output;
    } catch (JSONException e) {
      throw new IllegalStateException("无法生成记录备份。", e);
    }
  }

  /** Adds missing IDs atomically. Existing edits and deletion tombstones win. */
  public static JSONObject importJson(Context context, String text) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("备份为空或超过 16 MB 上限。");
    try {
      if (text.startsWith("\uFEFF")) text = text.substring(1);
      JSONTokener tokener = new JSONTokener(text);
      Object parsed = tokener.nextValue();
      if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
        throw new IllegalArgumentException("请选择随手记事 JSON 备份，或旧电脑版 SQLite 数据库。");
      JSONObject root = (JSONObject) parsed;
      if (root.has("format")) {
        if (!FORMAT.equals(root.optString("format")) || root.optInt("version", -1) != 1)
          throw new IllegalArgumentException("不支持此备份格式或版本，请使用兼容版本导出的备份。");
      }
      JSONArray records = root.optJSONArray("records");
      if (records == null || records.length() > MAX_RECORDS)
        throw new IllegalArgumentException("备份缺少 records 数组，或超过 20000 条上限。");
      JSONArray clean = new JSONArray();
      int deleted = 0;
      for (int i = 0; i < records.length(); i++) {
        JSONObject original = records.getJSONObject(i);
        // Desktop JSON may contain tombstones. Never restore them.
        Object tombstone = original.opt("deleted");
        if (Boolean.TRUE.equals(tombstone)
            || (tombstone instanceof Number && ((Number) tombstone).intValue() != 0)) {
          deleted++;
          continue;
        }
        clean.put(recordOnly(original));
      }
      JSONObject result = LocalStore.get(context).importRecords(clean);
      result.put("source", "json").put("ignored_deleted", deleted);
      ReminderScheduler.refresh(context);
      return result;
    } catch (JSONException e) {
      throw new IllegalArgumentException("备份不是有效的记录 JSON；未导入任何记录。", e);
    }
  }

  /** Reads a Storage Access Framework URI; never modifies the source file. */
  public static JSONObject importUri(Context context, Uri uri) throws IOException {
    if (uri == null) throw new IllegalArgumentException("未选择备份文件。");
    File temporary = File.createTempFile("planner-import-", ".data", context.getCacheDir());
    try {
      try (InputStream input = context.getContentResolver().openInputStream(uri);
          FileOutputStream output = new FileOutputStream(temporary)) {
        if (input == null) throw new IOException("无法读取所选文件，请重新选择。");
        byte[] buffer = new byte[8192];
        int total = 0, size;
        while ((size = input.read(buffer)) != -1) {
          total += size;
          if (total > MAX_BYTES) throw new IOException("文件超过 16 MB 上限，未进行导入。");
          output.write(buffer, 0, size);
        }
        if (total == 0) throw new IOException("所选文件为空。");
      }
      byte[] header = new byte[SQLITE_HEADER.length];
      try (InputStream input = new FileInputStream(temporary)) {
        if (input.read(header) == header.length && Arrays.equals(header, SQLITE_HEADER))
          return importLegacyDatabase(context, temporary);
      }
      try (InputStream input = new FileInputStream(temporary);
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        return importJson(context, text);
      } catch (CharacterCodingException e) {
        throw new IOException("文件不是 UTF-8 JSON 或旧电脑版 SQLite 数据库。", e);
      }
    } finally {
      // Imported databases are private scratch files, including any sidecars
      // created by the platform SQLite implementation.
      temporary.delete();
      new File(temporary.getPath() + "-wal").delete();
      new File(temporary.getPath() + "-shm").delete();
      new File(temporary.getPath() + "-journal").delete();
    }
  }

  private static JSONObject importLegacyDatabase(Context context, File file) throws IOException {
    try (SQLiteDatabase database =
        SQLiteDatabase.openDatabase(
            file.getPath(),
            null,
            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
      Set<String> columns = new HashSet<>();
      try (Cursor schema = database.rawQuery("PRAGMA table_info(planner_records)", null)) {
        while (schema.moveToNext())
          columns.add(schema.getString(schema.getColumnIndexOrThrow("name")));
      }
      if (!columns.containsAll(
          Arrays.asList(
              "id", "kind", "title", "body", "category", "important", "urgent", "completed")))
        throw new IOException("此数据库不包含旧电脑版 planner_records 记录，无法导入。HITA 私有数据库不支持此迁移。");
      JSONArray records = new JSONArray();
      int deleted = 0;
      try (Cursor cursor = database.rawQuery("SELECT * FROM planner_records", null)) {
        while (cursor.moveToNext()) {
          if (columns.contains("deleted")
              && cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) != 0) {
            deleted++;
            continue;
          }
          if (records.length() >= MAX_RECORDS) throw new IOException("数据库记录超过 20000 条，未进行导入。");
          JSONObject record = new JSONObject();
          for (String field : new String[] {"id", "kind", "title", "body", "category"})
            record.put(field, cursor.getString(cursor.getColumnIndexOrThrow(field)));
          for (String field : new String[] {"important", "urgent", "completed"})
            record.put(field, cursor.getInt(cursor.getColumnIndexOrThrow(field)) != 0);
          record.put("record_type", optional(cursor, columns, "record_type", "task"));
          for (String field : new String[] {"task_date", "due_at", "starts_at", "ends_at"})
            record.put(field, optional(cursor, columns, field, JSONObject.NULL));
          record.put("location", optional(cursor, columns, "location", ""));
          if (columns.contains("revision"))
            record.put("revision", cursor.getInt(cursor.getColumnIndexOrThrow("revision")));
          if (columns.contains("reminder_minutes")
              && !cursor.isNull(cursor.getColumnIndexOrThrow("reminder_minutes")))
            record.put(
                "reminder_minutes",
                cursor.getInt(cursor.getColumnIndexOrThrow("reminder_minutes")));
          for (String field : new String[] {"created", "updated"}) {
            if (columns.contains(field) && !cursor.isNull(cursor.getColumnIndexOrThrow(field))) {
              double seconds = cursor.getDouble(cursor.getColumnIndexOrThrow(field));
              if (!Double.isFinite(seconds)) throw new IOException("旧数据库包含无效的记录时间，未进行导入。");
              record.put(
                  field + "_at",
                  Instant.ofEpochMilli(Math.round(seconds * 1000))
                      .atOffset(ZoneOffset.UTC)
                      .toString());
            }
          }
          records.put(record);
        }
      }
      JSONObject result = LocalStore.get(context).importRecords(records);
      result
          .put("source", "legacy_sqlite")
          .put("ignored_deleted", deleted)
          .put("note", "已导入所选数据库中的任务与备忘。聊天、连接配置和 HITA 授权不会导入；旧电脑服务应停止后再复制数据库。");
      ReminderScheduler.refresh(context);
      return result;
    } catch (SQLiteException e) {
      throw new IOException("旧数据库无法读取或已损坏；请停止旧电脑服务后，重新复制完整的数据库文件。", e);
    } catch (JSONException e) {
      throw new IOException("旧数据库记录格式不正确，未导入任何记录。", e);
    }
  }

  private static Object optional(Cursor cursor, Set<String> columns, String name, Object fallback) {
    if (!columns.contains(name)) return fallback;
    int index = cursor.getColumnIndexOrThrow(name);
    return cursor.isNull(index) ? fallback : cursor.getString(index);
  }

  private static JSONObject recordOnly(JSONObject original) throws JSONException {
    JSONObject clean = new JSONObject();
    for (String field : RECORD_FIELDS)
      if (original.has(field)) clean.put(field, original.get(field));
    return clean;
  }
}
