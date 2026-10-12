package cn.chatplanner.control;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The phone owns the records. Network calls never hold a database transaction. */
final class LocalStore extends SQLiteOpenHelper {
  private static LocalStore instance;

  static synchronized LocalStore get(Context context) {
    if (instance == null) instance = new LocalStore(context.getApplicationContext());
    return instance;
  }

  private LocalStore(Context context) {
    super(context, "planner-local.db", null, 1);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.enableWriteAheadLogging();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE records (id TEXT PRIMARY KEY, data TEXT NOT NULL, kind TEXT NOT NULL, title"
            + " TEXT NOT NULL, body TEXT NOT NULL, revision INTEGER NOT NULL, deleted INTEGER NOT"
            + " NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
    db.execSQL("CREATE INDEX record_kind ON records(kind,deleted,updated_at)");
    db.execSQL(
        "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content"
            + " TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL)");
    db.execSQL("CREATE TABLE values_store (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, result TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    throw new IllegalStateException("Unsupported database version");
  }

  synchronized JSONObject createRecord(JSONObject input, String operationId) {
    JSONObject action = json("operation", "create", "record", input);
    return applyAction(action, operationId).optJSONObject("record");
  }

  synchronized JSONObject updateRecord(String id, int revision, JSONObject input) {
    return applyAction(
            json(
                "operation",
                "update",
                "record_id",
                id,
                "expected_revision",
                revision,
                "record",
                input),
            UUID.randomUUID().toString())
        .optJSONObject("record");
  }

  synchronized JSONObject deleteRecord(String id, int revision) {
    return applyAction(
        json("operation", "delete", "record_id", id, "expected_revision", revision),
        UUID.randomUUID().toString());
  }

  /** Operation ID and mutation commit together, including update and delete receipts. */
  synchronized JSONObject applyAction(JSONObject action, String operationId) {
    if (operationId == null || operationId.trim().isEmpty() || operationId.length() > 160)
      throw new IllegalArgumentException("操作编号不正确。");
    String operation = action.optString("operation");
    if (!operation.equals("create") && !operation.equals("update") && !operation.equals("delete"))
      throw new IllegalArgumentException("操作不受支持。");
    JSONObject normalized =
        operation.equals("delete")
            ? null
            : RecordValidator.normalize(action.optJSONObject("record"));
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      JSONObject previous = operationResult(operationId);
      if (previous != null) {
        db.setTransactionSuccessful();
        return previous;
      }
      String now = OffsetDateTime.now().toString();
      String id =
          operation.equals("create")
              ? UUID.randomUUID().toString().replace("-", "")
              : action.optString("record_id");
      JSONObject old = operation.equals("create") ? null : getRecord(id);
      if (!operation.equals("create")) {
        if (old == null) throw new IllegalArgumentException("这条记录已不存在，请刷新后重试。");
        if (!action.has("expected_revision")
            || action.optInt("expected_revision", -1) != old.optInt("revision"))
          throw new IllegalArgumentException("这条记录已更新，请查看最新内容后重试。");
      }
      int revision = old == null ? 0 : old.optInt("revision") + 1;
      String created = old == null ? now : old.optString("created_at");
      JSONObject result;
      if (operation.equals("delete")) {
        ContentValues tombstone = new ContentValues();
        tombstone.put("deleted", 1);
        tombstone.put("revision", revision);
        tombstone.put("updated_at", now);
        db.update("records", tombstone, "id=?", new String[] {id});
        result =
            json(
                "status",
                "ok",
                "target",
                "local",
                "operation",
                operation,
                "record_id",
                id,
                "record",
                old);
      } else {
        normalized
            .put("id", id)
            .put("revision", revision)
            .put("created_at", created)
            .put("updated_at", now);
        ContentValues values = row(normalized);
        if (operation.equals("create")) db.insertOrThrow("records", null, values);
        else db.update("records", values, "id=?", new String[] {id});
        result =
            json("status", "ok", "target", "local", "operation", operation, "record", normalized);
      }
      rememberOperation(operationId, result);
      db.setTransactionSuccessful();
      return result;
    } catch (JSONException e) {
      throw new IllegalArgumentException("记录格式不正确。", e);
    } finally {
      db.endTransaction();
    }
  }

  synchronized JSONObject getRecord(String id) {
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT data FROM records WHERE id=? AND deleted=0", new String[] {id})) {
      return cursor.moveToFirst() ? parse(cursor.getString(0)) : null;
    }
  }

  synchronized boolean containsRecordId(String id) {
    try (Cursor cursor =
        getReadableDatabase().rawQuery("SELECT 1 FROM records WHERE id=?", new String[] {id})) {
      return cursor.moveToFirst();
    }
  }

  synchronized JSONArray records(String kind) {
    String where = kind == null ? "deleted=0" : "deleted=0 AND kind=?";
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery(
                "SELECT data FROM records WHERE " + where + " ORDER BY updated_at DESC,id",
                kind == null ? null : new String[] {kind})) {
      return array(cursor);
    }
  }

  synchronized JSONArray searchRecords(String query, int limit) {
    String clean = query == null ? "" : query.trim();
    String pattern =
        "%" + clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery(
                "SELECT data FROM records WHERE deleted=0 AND (title LIKE ? ESCAPE '\\' OR body"
                    + " LIKE ? ESCAPE '\\') ORDER BY updated_at DESC,id LIMIT ?",
                new String[] {
                  pattern, pattern, Integer.toString(Math.max(1, Math.min(200, limit)))
                })) {
      return array(cursor);
    }
  }

  synchronized JSONArray messages() {
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT id,role,content,result,created_at FROM messages ORDER BY id", null)) {
      JSONArray result = new JSONArray();
      while (cursor.moveToNext()) {
        JSONObject message =
            json(
                "id",
                cursor.getLong(0),
                "role",
                cursor.getString(1),
                "content",
                cursor.getString(2),
                "created",
                cursor.getString(4),
                "created_at",
                cursor.getString(4));
        if (!cursor.isNull(3)) put(message, "result", parse(cursor.getString(3)));
        result.put(message);
      }
      return result;
    }
  }

  synchronized void appendMessage(String role, String content) {
    appendMessage(role, content, null);
  }

  synchronized void appendMessage(String role, String content, JSONObject result) {
    if (!role.equals("user") && !role.equals("assistant"))
      throw new IllegalArgumentException("消息角色不受支持。");
    ContentValues row = new ContentValues();
    row.put("role", role);
    row.put("content", content);
    row.put("created_at", OffsetDateTime.now().toString());
    if (result != null) row.put("result", result.toString());
    getWritableDatabase().insertOrThrow("messages", null, row);
  }

  synchronized void clearMessages() {
    getWritableDatabase().delete("messages", null, null);
  }

  synchronized String getValue(String key) {
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT value FROM values_store WHERE key=?", new String[] {key})) {
      return cursor.moveToFirst() ? cursor.getString(0) : "";
    }
  }

  synchronized void putValue(String key, String value) {
    if (value == null || value.isEmpty()) {
      getWritableDatabase().delete("values_store", "key=?", new String[] {key});
      return;
    }
    ContentValues row = new ContentValues();
    row.put("key", key);
    row.put("value", value);
    getWritableDatabase()
        .insertWithOnConflict("values_store", null, row, SQLiteDatabase.CONFLICT_REPLACE);
  }

  synchronized JSONObject operationResult(String operationId) {
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT result FROM operations WHERE id=?", new String[] {operationId})) {
      return cursor.moveToFirst() ? parse(cursor.getString(0)) : null;
    }
  }

  synchronized void rememberOperation(String operationId, JSONObject result) {
    ContentValues row = new ContentValues();
    row.put("id", operationId);
    row.put("result", result.toString());
    getWritableDatabase()
        .insertWithOnConflict("operations", null, row, SQLiteDatabase.CONFLICT_IGNORE);
  }

  /** Preserve IDs; never resurrect a deleted record or overwrite an existing one. */
  synchronized JSONObject importRecords(JSONArray input) {
    if (input == null || input.length() > 50000) throw new IllegalArgumentException("备份记录为空或过多。");
    JSONArray validated = new JSONArray();
    try {
      for (int i = 0; i < input.length(); i++) {
        JSONObject original = input.getJSONObject(i);
        JSONObject record = RecordValidator.normalize(original);
        String id = original.optString("id");
        if (!id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("备份中的记录编号不正确。");
        Object rawRevision = original.opt("revision");
        if (rawRevision != null
            && (!(rawRevision instanceof Number)
                || ((Number) rawRevision).doubleValue() != ((Number) rawRevision).intValue()
                || ((Number) rawRevision).intValue() < 0))
          throw new IllegalArgumentException("备份中的版本编号不正确。");
        String now = OffsetDateTime.now().toString();
        String created = original.optString("created_at", now),
            updated = original.optString("updated_at", now);
        OffsetDateTime.parse(created);
        OffsetDateTime.parse(updated);
        record
            .put("id", id)
            .put("revision", original.optInt("revision", 0))
            .put("created_at", created)
            .put("updated_at", updated);
        validated.put(record);
      }
    } catch (JSONException | java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("备份记录格式不正确，未导入任何记录。", e);
    }
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      int imported = 0, skipped = 0;
      for (int i = 0; i < validated.length(); i++) {
        JSONObject record = validated.getJSONObject(i);
        if (containsRecordId(record.getString("id"))) {
          skipped++;
          continue;
        }
        db.insertOrThrow("records", null, row(record));
        imported++;
      }
      db.setTransactionSuccessful();
      return json("imported", imported, "skipped", skipped);
    } catch (JSONException e) {
      throw new IllegalArgumentException("备份格式不正确。", e);
    } finally {
      db.endTransaction();
    }
  }

  private ContentValues row(JSONObject record) {
    ContentValues row = new ContentValues();
    row.put("id", record.optString("id"));
    row.put("data", record.toString());
    row.put("kind", record.optString("kind"));
    row.put("title", record.optString("title"));
    row.put("body", record.optString("body"));
    row.put("revision", record.optInt("revision"));
    row.put("deleted", 0);
    row.put("created_at", record.optString("created_at"));
    row.put("updated_at", record.optString("updated_at"));
    return row;
  }

  private static JSONArray array(Cursor cursor) {
    JSONArray result = new JSONArray();
    while (cursor.moveToNext()) result.put(parse(cursor.getString(0)));
    return result;
  }

  static JSONObject parse(String value) {
    try {
      return new JSONObject(value);
    } catch (JSONException e) {
      throw new IllegalStateException("保存的数据无法读取。", e);
    }
  }

  static JSONObject json(Object... pairs) {
    JSONObject result = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) put(result, pairs[i].toString(), pairs[i + 1]);
    return result;
  }

  static void put(JSONObject object, String key, Object value) {
    try {
      object.put(key, value == null ? JSONObject.NULL : value);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
