package cn.chatplanner.control;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** One validation boundary for manual editing, AI operations and imported records. */
final class RecordValidator {
  private static final Set<String> FIELDS =
      new HashSet<>(
          Arrays.asList(
              "id",
              "revision",
              "created_at",
              "updated_at",
              "kind",
              "record_type",
              "title",
              "body",
              "category",
              "important",
              "urgent",
              "completed",
              "task_date",
              "due_at",
              "starts_at",
              "ends_at",
              "location",
              "reminder_minutes"));

  static JSONObject normalize(JSONObject input) {
    if (input == null) throw new IllegalArgumentException("记录内容不能为空。");
    try {
      Iterator<String> keys = input.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!FIELDS.contains(key)) throw new IllegalArgumentException("记录含有不支持的字段：" + key);
      }
      String kind = string(input, "kind", "todo", 10, true);
      if (!kind.equals("todo") && !kind.equals("memo"))
        throw new IllegalArgumentException("记录类型必须为任务或备忘。");
      String type = string(input, "record_type", "task", 30, true);
      if (!Arrays.asList("task", "ddl", "exam", "important_date", "schedule").contains(type))
        throw new IllegalArgumentException("事项分类不受支持。");
      JSONObject record = new JSONObject();
      record
          .put("kind", kind)
          .put("record_type", type)
          .put("title", string(input, "title", "", 200, true))
          .put("body", string(input, "body", "", 8000, false))
          .put("category", string(input, "category", "其他", 80, true))
          .put("important", bool(input, "important", false))
          .put("urgent", bool(input, "urgent", false))
          .put("completed", bool(input, "completed", false))
          .put("location", string(input, "location", "", 500, false));
      String date = optionalString(input, "task_date");
      if (date != null) {
        try {
          if (!LocalDate.parse(date).toString().equals(date))
            throw new DateTimeParseException("", date, 0);
        } catch (DateTimeParseException e) {
          throw new IllegalArgumentException("日期应为 YYYY-MM-DD。");
        }
        record.put("task_date", date);
      } else record.put("task_date", JSONObject.NULL);
      OffsetDateTime due = timestamp(input, record, "due_at");
      OffsetDateTime start = timestamp(input, record, "starts_at");
      OffsetDateTime end = timestamp(input, record, "ends_at");
      if (due != null) {
        String actual = due.toLocalDate().toString();
        if (date != null
            && !date.equals(
                OffsetDateTime.parse(input.getString("due_at")).toLocalDate().toString()))
          throw new IllegalArgumentException("日期与具体截止时间不是同一天。");
        record.put("task_date", actual);
      }
      if (end != null && start == null) throw new IllegalArgumentException("结束时间需要对应的开始时间。");
      if (start != null && end != null && !end.toInstant().isAfter(start.toInstant()))
        throw new IllegalArgumentException("结束时间必须晚于开始时间。");
      if (type.equals("schedule")) {
        if (start == null || end == null) throw new IllegalArgumentException("日程需要开始和结束时间。");
        String startDate = start.toLocalDate().toString();
        if (date != null
            && !date.equals(
                OffsetDateTime.parse(input.getString("starts_at")).toLocalDate().toString()))
          throw new IllegalArgumentException("日程日期与开始时间不一致。");
        record.put("task_date", startDate);
      } else if (start != null || end != null) throw new IllegalArgumentException("开始和结束时间只用于日程。");
      if (kind.equals("memo")) {
        if (!type.equals("task") || date != null || due != null || start != null || end != null)
          throw new IllegalArgumentException("备忘不能带截止日期或日程时间；请保存为任务或重要日期。");
        record.put("important", false).put("urgent", false).put("completed", false);
      } else if (!type.equals("task") && !type.equals("schedule") && record.isNull("task_date")) {
        throw new IllegalArgumentException("DDL、考试和重要日期需要明确日期。");
      }
      if (!input.has("reminder_minutes") || input.isNull("reminder_minutes"))
        record.put("reminder_minutes", JSONObject.NULL);
      else {
        Object raw = input.get("reminder_minutes");
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).intValue())
          throw new IllegalArgumentException("提醒提前量必须为整数分钟。");
        int minutes = ((Number) raw).intValue();
        if (minutes < 0 || minutes > 10080)
          throw new IllegalArgumentException("提醒提前量必须为 0 至 10080 分钟。");
        if (kind.equals("memo") || (record.isNull("task_date") && due == null && start == null))
          throw new IllegalArgumentException("需要先提供日期，才能设置提醒。");
        record.put("reminder_minutes", minutes);
      }
      return record;
    } catch (JSONException e) {
      throw new IllegalArgumentException("记录格式不正确。", e);
    }
  }

  static String quadrant(JSONObject record) {
    boolean important = record.optBoolean("important"), urgent = record.optBoolean("urgent");
    return important ? (urgent ? "重要且紧急" : "重要不紧急") : (urgent ? "紧急不重要" : "不重要不紧急");
  }

  static String typeLabel(JSONObject record) {
    if (record.optString("kind").equals("memo")) return "备忘";
    switch (record.optString("record_type", "task")) {
      case "ddl":
        return "DDL";
      case "exam":
        return "考试";
      case "important_date":
        return "重要日期";
      case "schedule":
        return "日程";
      default:
        return "任务";
    }
  }

  private static String string(
      JSONObject input, String key, String fallback, int max, boolean nonblank)
      throws JSONException {
    Object value = input.has(key) ? input.get(key) : fallback;
    if (!(value instanceof String)) throw new IllegalArgumentException(key + " 必须为文本。");
    String result = ((String) value).trim();
    if ((nonblank && result.isEmpty()) || result.length() > max)
      throw new IllegalArgumentException(key + " 为空或超过允许长度。");
    return result;
  }

  private static boolean bool(JSONObject input, String key, boolean fallback) throws JSONException {
    if (!input.has(key)) return fallback;
    Object value = input.get(key);
    if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " 必须为布尔值。");
    return (Boolean) value;
  }

  private static String optionalString(JSONObject input, String key) throws JSONException {
    if (!input.has(key) || input.isNull(key)) return null;
    Object value = input.get(key);
    if (!(value instanceof String) || ((String) value).trim().isEmpty())
      throw new IllegalArgumentException(key + " 必须为有效日期或 null。");
    return ((String) value).trim();
  }

  private static OffsetDateTime timestamp(JSONObject input, JSONObject result, String key)
      throws JSONException {
    String raw = optionalString(input, key);
    if (raw == null) {
      result.put(key, JSONObject.NULL);
      return null;
    }
    try {
      OffsetDateTime parsed =
          OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
      result.put(key, parsed.toString());
      return parsed;
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("时间必须包含时区，例如 2026-10-20T15:00:00+08:00。");
    }
  }
}
