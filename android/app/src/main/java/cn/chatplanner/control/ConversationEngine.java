package cn.chatplanner.control;

import android.content.Context;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable conversation state machine. Call from a background thread. */
final class ConversationEngine {
  interface Interpreter {
    JSONObject interpret(JSONObject context) throws Exception;
  }

  private static final Object LOCK = new Object();
  private static final String PENDING = "conversation.pending.v1", DRAFT = "conversation.draft.v1";
  private final Context context;
  private final LocalStore store;
  private final Interpreter interpreter;

  ConversationEngine(Context context) {
    this(context, new DeepSeekClient(new AppSettings(context)));
  }

  ConversationEngine(Context context, Interpreter interpreter) {
    this.context = context.getApplicationContext();
    store = LocalStore.get(context);
    this.interpreter = interpreter;
  }

  boolean hasPending() {
    return !store.getValue(PENDING).isEmpty();
  }

  String pendingStage() {
    JSONObject value = pending();
    return value == null ? "" : value.optString("stage");
  }

  boolean needsConfirmation() {
    return pendingStage().equals("confirmation");
  }

  JSONObject send(String text) {
    synchronized (LOCK) {
      String clean = text == null ? "" : text.trim();
      if (clean.isEmpty() || clean.length() > 8000)
        return recorded(
            withFlag(response("请输入 1 至 8000 字的信息。", null, new JSONArray()), "accepted", false));
      JSONObject previous = pending();
      if (previous != null) {
        if (previous.optString("stage").equals("confirmation")) {
          if (Arrays.asList("确认", "确认删除", "是", "yes")
              .contains(clean.toLowerCase(java.util.Locale.ROOT))) {
            store.appendMessage("user", clean);
            return confirmPending(true);
          }
          if (Arrays.asList("取消", "不删除", "否", "no")
              .contains(clean.toLowerCase(java.util.Locale.ROOT))) {
            store.appendMessage("user", clean);
            return confirmPending(false);
          }
          return recorded(
              response("请先确认或取消上一次删除，再继续添加信息。", "是否执行已列出的删除操作？", new JSONArray()),
              "needs_confirmation",
              true);
        }
        return recorded(
            response(
                previous.optString("stage").equals("unknown")
                    ? "上一次 HITA 操作尚未核对。请先打开 HITA 核对结果，再清除该待处理操作。"
                    : "上一次请求尚未完成。请先重试，或取消待处理请求后再发送。",
                null,
                new JSONArray()),
            "retryable",
            !previous.optString("stage").equals("unknown"));
      }
      JSONObject request =
          LocalStore.json(
              "request_id",
              UUID.randomUUID().toString(),
              "stage",
              "request",
              "text",
              clean,
              "reference_time",
              java.time.ZonedDateTime.now().toString(),
              "timezone",
              java.time.ZoneId.systemDefault().getId());
      store.appendMessage("user", clean);
      store.putValue(PENDING, request.toString());
      return run(request);
    }
  }

  JSONObject retryPending() {
    synchronized (LOCK) {
      JSONObject request = pending();
      if (request == null) return response("没有待重试的请求。", null, new JSONArray());
      if (request.optString("stage").equals("confirmation"))
        return withFlag(
            response("删除操作需要你的确认。", "是否执行已列出的删除操作？", new JSONArray()), "needs_confirmation", true);
      if (request.optString("stage").equals("unknown"))
        return response("HITA 可能已执行上次操作。请先在 HITA 中核对，避免重复修改；此处不会自动重放。", null, new JSONArray());
      return run(request);
    }
  }

  JSONObject confirmPending(boolean confirmed) {
    synchronized (LOCK) {
      JSONObject request = pending();
      if (request == null) return response("没有待确认的操作。", null, new JSONArray());
      if (!confirmed) {
        store.putValue(PENDING, "");
        return recorded(
            response(
                request.optString("stage").equals("unknown")
                    ? "已清除待核对状态。请以 HITA 中的实际记录为准。"
                    : "已取消尚未执行的操作；此前已成功保存的记录会保留。",
                null,
                new JSONArray()));
      }
      if (!request.optString("stage").equals("confirmation"))
        return response("该请求无需删除确认，请使用重试。", null, new JSONArray());
      LocalStore.put(request, "stage", "execute");
      store.putValue(PENDING, request.toString());
      return run(request);
    }
  }

  /** Explicitly discard a clarification draft, useful for a new conversation. */
  void clearDraft() {
    synchronized (LOCK) {
      store.putValue(DRAFT, "");
    }
  }

  boolean hasDraft() {
    return !store.getValue(DRAFT).isEmpty();
  }

  private JSONObject run(JSONObject request) {
    try {
      JSONObject plan = request.optJSONObject("plan");
      if (plan == null) {
        JSONObject payload = request.optJSONObject("payload");
        if (payload == null) {
          payload = buildContext(request);
          LocalStore.put(request, "payload", payload);
          store.putValue(PENDING, request.toString());
        }
        for (int round = 0; round < 4; round++) {
          plan = validatePlan(interpreter.interpret(payload), payload);
          JSONArray queries = onlyQueries(plan.optJSONArray("actions"));
          if (queries.length() == 0) break;
          if (round == 3) throw new IllegalArgumentException("查询次数过多，请更具体地说明要查找的事项。");
          JSONArray outputs = payload.optJSONArray("query_results");
          if (outputs == null) outputs = new JSONArray();
          for (int i = 0; i < queries.length(); i++) {
            JSONObject query = queries.getJSONObject(i);
            JSONObject found = query(query);
            if (!found.optString("status").equals("ok")) {
              store.putValue(PENDING, "");
              return recorded(response(hitaError(found), null, new JSONArray()));
            }
            outputs.put(
                LocalStore.json(
                    "target",
                    query.optString("target"),
                    "filter",
                    query.optJSONObject("filter"),
                    "result",
                    found));
          }
          LocalStore.put(payload, "query_results", outputs);
          LocalStore.put(request, "payload", payload);
          store.putValue(PENDING, request.toString());
        }
        if (plan == null) throw new IllegalArgumentException("没有收到可用的处理结果。");
        String question = nullableText(plan, "question", 2000);
        if (question != null) {
          JSONObject draft = plan.optJSONObject("draft");
          // Preserve both the original request and all known intent if the model omitted a draft.
          if (draft == null)
            draft =
                LocalStore.json(
                    "original_text",
                    request.optString("text"),
                    "proposed_actions",
                    plan.optJSONArray("actions"));
          JSONObject previousDraft = payload.optJSONObject("draft");
          store.putValue(
              DRAFT,
              LocalStore.json(
                      "question",
                      question,
                      "intent",
                      draft,
                      "original_text",
                      previousDraft == null
                          ? request.optString("text")
                          : previousDraft.optString("original_text", request.optString("text")),
                      "reference_time",
                      previousDraft == null
                          ? payload.optString("reference_time")
                          : previousDraft.optString(
                              "reference_time", payload.optString("reference_time")),
                      "timezone",
                      previousDraft == null
                          ? payload.optString("timezone")
                          : previousDraft.optString("timezone", payload.optString("timezone")))
                  .toString());
          store.putValue(PENDING, "");
          return recorded(response("还需要补充一点信息，当前事项尚未保存。", question, new JSONArray()));
        }
        LocalStore.put(request, "plan", plan);
        boolean deletion = hasDelete(plan.optJSONArray("actions"));
        LocalStore.put(request, "stage", deletion ? "confirmation" : "execute");
        store.putValue(PENDING, request.toString());
        if (deletion)
          return recorded(
              withFlag(
                  response(deletePreview(plan), "确认执行这些删除操作吗？", new JSONArray()),
                  "needs_confirmation",
                  true));
      }
      if (request.optString("stage").equals("confirmation"))
        return withFlag(
            response(deletePreview(plan), "确认执行这些删除操作吗？", new JSONArray()),
            "needs_confirmation",
            true);
      return execute(request, plan);
    } catch (Exception failure) {
      if (failure instanceof IllegalArgumentException && request.optJSONObject("plan") == null) {
        // Retry interpretation against current records, keeping the original date reference.
        request.remove("payload");
        store.putValue(PENDING, request.toString());
      }
      JSONObject result =
          withFlag(response(errorMessage(failure), null, new JSONArray()), "retryable", true);
      return recorded(result);
    }
  }

  private JSONObject execute(JSONObject request, JSONObject plan) throws Exception {
    JSONArray actions = plan.getJSONArray("actions"), results = new JSONArray();
    for (int i = 0; i < actions.length(); i++) {
      JSONObject action = actions.getJSONObject(i);
      String operationId = request.getString("request_id") + ":" + i;
      JSONObject result = store.operationResult(operationId);
      if (result == null) {
        if (action.getString("target").equals("local")) {
          try {
            result = store.applyAction(action, operationId);
          } catch (IllegalArgumentException conflict) {
            results.put(
                LocalStore.json(
                    "status",
                    "conflict",
                    "target",
                    "local",
                    "operation",
                    action.optString("operation"),
                    "record_id",
                    action.optString("record_id"),
                    "message",
                    conflict.getMessage()));
            store.putValue(PENDING, "");
            ReminderScheduler.refresh(context);
            String alreadySaved = summary(results);
            return recorded(
                response(
                    (alreadySaved.isEmpty() ? "" : alreadySaved + "\n\n")
                        + conflict.getMessage()
                        + "\n剩余操作未执行，请重新发送需要修改的事项。",
                    null,
                    results));
          }
        } else {
          JSONObject external = new JSONObject(action.toString());
          LocalStore.put(external, "operation_id", operationId);
          if (action.getString("operation").equals("create"))
            LocalStore.put(
                external,
                "record_id",
                UUID.nameUUIDFromBytes(
                        operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", ""));
          try (HitaClient client = new HitaClient(context)) {
            result = client.apply(external);
          }
          LocalStore.put(result, "target", "hita");
          LocalStore.put(result, "operation", action.getString("operation"));
          if (result.optString("status").equals("unknown")) {
            results.put(result);
            LocalStore.put(request, "stage", "unknown");
            LocalStore.put(request, "unknown_action", action);
            store.putValue(PENDING, request.toString());
            return recorded(
                response("HITA 未能确认操作结果。它可能已经执行，请打开 HITA 核对；为避免重复修改，暂时停止后续操作。", null, results));
          }
          if (!result.optString("status").equals("ok")) {
            // A conflict is not retried with a stale fingerprint or revision.
            results.put(result);
            store.putValue(PENDING, "");
            return recorded(response(summary(results) + hitaError(result), null, results));
          }
          if (result.optJSONObject("record") == null && action.optJSONObject("record") != null)
            LocalStore.put(result, "record", action.optJSONObject("record"));
          store.rememberOperation(operationId, result);
        }
      }
      if (action.has("reason")) LocalStore.put(result, "reason", action.optString("reason"));
      results.put(result);
    }
    ReminderScheduler.refresh(context);
    store.putValue(PENDING, "");
    // A side conversation must not silently discard the unfinished item.
    if (actions.length() > 0 && plan.optBoolean("draft_resolved", false)) store.putValue(DRAFT, "");
    String reply =
        actions.length() == 0 ? plan.optString("reply", "你可以告诉我想记录或调整的事情。") : summary(results);
    if (reply.trim().isEmpty()) reply = "你可以告诉我想记录或调整的事情。";
    return recorded(response(reply, null, results));
  }

  private JSONObject buildContext(JSONObject request) {
    JSONArray all = store.messages(), history = new JSONArray();
    for (int i = Math.max(0, all.length() - 12); i < all.length(); i++) {
      JSONObject message = all.optJSONObject(i);
      if (message != null)
        history.put(
            LocalStore.json(
                "role",
                message.optString("role"),
                "content",
                message.optString("content"),
                "created_at",
                message.optString("created_at"),
                "result",
                message.optJSONObject("result")));
    }
    JSONArray records = store.searchRecords("", 50);
    boolean hitaAvailable;
    try (HitaClient client = new HitaClient(context)) {
      hitaAvailable = client.isAvailable();
    }
    JSONObject draft =
        store.getValue(DRAFT).isEmpty() ? null : LocalStore.parse(store.getValue(DRAFT));
    return LocalStore.json(
        "message",
        request.optString("text"),
        "reference_time",
        request.optString("reference_time"),
        "timezone",
        request.optString("timezone"),
        "history",
        history,
        "draft",
        draft,
        "records",
        records,
        "hita_available",
        hitaAvailable,
        "query_results",
        new JSONArray());
  }

  private JSONObject query(JSONObject action) {
    JSONObject filter = action.optJSONObject("filter");
    if (filter == null) filter = new JSONObject();
    if (action.optString("target").equals("hita")) {
      try (HitaClient client = new HitaClient(context)) {
        return client.query(filter);
      }
    }
    JSONArray found;
    if (filter.has("record_id")) {
      found = new JSONArray();
      JSONObject record = store.getRecord(filter.optString("record_id"));
      if (record != null) found.put(record);
    } else
      found = store.searchRecords(filter.optString("keyword", filter.optString("title", "")), 50);
    JSONArray filtered = new JSONArray();
    String from = filter.optString("task_date_from"), to = filter.optString("task_date_to");
    for (int i = 0; i < found.length(); i++) {
      JSONObject record = found.optJSONObject(i);
      String date = record.optString("task_date", "");
      if (record.isNull("task_date")) date = "";
      if (!from.isEmpty() && (date.isEmpty() || date.compareTo(from) < 0)) continue;
      if (!to.isEmpty() && (date.isEmpty() || date.compareTo(to) > 0)) continue;
      filtered.put(record);
    }
    return LocalStore.json("status", "ok", "records", filtered);
  }

  /** Validate the entire batch before the first mutation. */
  JSONObject validatePlan(JSONObject input, JSONObject payload) throws JSONException {
    if (input == null) throw new IllegalArgumentException("DeepSeek 未返回处理方案。");
    requireKeys(input, "reply", "question", "draft", "draft_resolved", "actions");
    nullableText(input, "reply", 4000);
    nullableText(input, "question", 2000);
    if (input.has("draft_resolved") && !(input.opt("draft_resolved") instanceof Boolean))
      throw new IllegalArgumentException("追问完成状态格式不正确。");
    if (input.has("draft") && !input.isNull("draft") && !(input.opt("draft") instanceof JSONObject))
      throw new IllegalArgumentException("追问草稿格式不正确。");
    JSONArray original = input.optJSONArray("actions");
    if (original == null || original.length() > 20)
      throw new IllegalArgumentException("处理方案中的操作数量不正确。");
    if (nullableText(input, "question", 2000) != null) {
      JSONObject draft = input.optJSONObject("draft");
      if (draft == null) draft = new JSONObject();
      else draft = new JSONObject(draft.toString());
      if (original.length() > 0) draft.put("proposed_actions", new JSONArray(original.toString()));
      return new JSONObject(input.toString()).put("draft", draft).put("actions", new JSONArray());
    }
    JSONArray normalized = new JSONArray();
    boolean hasQuery = false, hasMutation = false;
    Set<String> mutatedIds = new HashSet<>();
    for (int i = 0; i < original.length(); i++) {
      JSONObject action = original.getJSONObject(i);
      requireKeys(
          action,
          "operation",
          "target",
          "record",
          "record_id",
          "expected_revision",
          "fingerprint",
          "expected_fingerprint",
          "reason",
          "filter",
          "timetable_id");
      String operation = action.optString("operation"),
          target = action.optString("target", "local");
      if (!Arrays.asList("create", "update", "delete", "complete", "query").contains(operation)
          || !Arrays.asList("local", "hita").contains(target))
        throw new IllegalArgumentException("DeepSeek 提出了不支持的操作，未执行。");
      nullableText(action, "reason", 1000);
      JSONObject next = new JSONObject(action.toString());
      next.put("target", target);
      if (operation.equals("query")) {
        hasQuery = true;
        JSONObject filter = action.optJSONObject("filter");
        if (filter != null)
          requireKeys(
              filter,
              "record_id",
              "keyword",
              "title",
              "starts_at",
              "ends_at",
              "task_date_from",
              "task_date_to",
              "timetable_id");
        normalized.put(next);
        continue;
      }
      hasMutation = true;
      if (!operation.equals("create")) {
        String id = action.optString("record_id");
        if (id.isEmpty() || id.length() > 128 || !mutatedIds.add(target + ":" + id))
          throw new IllegalArgumentException("修改目标为空或同一批次重复修改，请明确目标。");
        JSONObject known = findKnown(payload, target, id);
        if (known == null) throw new IllegalArgumentException("需要先查询并确定要修改的记录，未执行此次操作。");
        if (target.equals("local")) {
          Object revision = action.opt("expected_revision");
          if (!(revision instanceof Number)
              || ((Number) revision).doubleValue() != ((Number) revision).intValue()
              || action.optInt("expected_revision", -1) != known.optInt("revision", -2))
            throw new IllegalArgumentException("目标记录的版本不正确，请重新查询。");
          JSONObject current = store.getRecord(id);
          if (current == null || current.optInt("revision") != known.optInt("revision"))
            throw new IllegalArgumentException("记录已改变，请重新发送修改要求。");
          if (operation.equals("complete") && current.optString("kind").equals("memo"))
            throw new IllegalArgumentException("备忘没有完成状态；可以将它改为任务后标记完成。");
          if (!operation.equals("delete")) {
            JSONObject merged = new JSONObject(current.toString()),
                patch = action.optJSONObject("record");
            if (patch != null) {
              Iterator<String> keys = patch.keys();
              while (keys.hasNext()) {
                String key = keys.next();
                merged.put(key, patch.get(key));
              }
            }
            if (operation.equals("complete")) {
              merged.put("completed", true);
              next.put("operation", "update");
            } else if (patch == null) throw new IllegalArgumentException("修改内容不能为空。");
            next.put("record", RecordValidator.normalize(merged));
          }
        } else {
          String fingerprint =
              action.optString("fingerprint", action.optString("expected_fingerprint"));
          if (fingerprint.isEmpty() || !fingerprint.equals(known.optString("fingerprint")))
            throw new IllegalArgumentException("HITA 记录缺少有效指纹，请先查询。");
          next.put("fingerprint", fingerprint);
          if (operation.equals("complete")) throw new IllegalArgumentException("HITA 日程不支持标记完成。");
          if (operation.equals("update"))
            next.put("record", validateHitaRecord(action.optJSONObject("record"), known));
        }
      } else if (target.equals("local"))
        next.put("record", RecordValidator.normalize(action.optJSONObject("record")));
      else next.put("record", validateHitaRecord(action.optJSONObject("record"), null));
      normalized.put(next);
    }
    if (hasQuery && hasMutation) throw new IllegalArgumentException("需要先完成查询，再生成修改方案；此次操作未执行。");
    return new JSONObject(input.toString()).put("actions", normalized);
  }

  private JSONObject validateHitaRecord(JSONObject raw, JSONObject existing) throws JSONException {
    if (raw == null) throw new IllegalArgumentException("HITA 日程内容不能为空。");
    requireKeys(
        raw, "kind", "record_type", "title", "starts_at", "ends_at", "location", "timetable_id");
    if (raw.has("record_type") && !raw.optString("record_type").equals("schedule"))
      throw new IllegalArgumentException("HITA 仅支持单次课程或日程；任务、DDL和备忘请保存到本 App。");
    JSONObject record = existing == null ? new JSONObject() : new JSONObject(existing.toString());
    Iterator<String> fields = raw.keys();
    while (fields.hasNext()) {
      String key = fields.next();
      record.put(key, raw.get(key));
    }
    String kind = record.optString("kind", "schedule");
    if (!Arrays.asList("schedule", "course", "todo").contains(kind))
      throw new IllegalArgumentException("HITA 仅支持课程或日程。");
    JSONObject validation = new JSONObject();
    for (String field : Arrays.asList("title", "starts_at", "ends_at", "location"))
      if (record.has(field)) validation.put(field, record.get(field));
    validation.put("kind", "todo").put("record_type", "schedule");
    JSONObject normalized = RecordValidator.normalize(validation);
    JSONObject result =
        LocalStore.json(
            "kind",
            kind.equals("todo") ? "schedule" : kind,
            "title",
            normalized.get("title"),
            "starts_at",
            normalized.get("starts_at"),
            "ends_at",
            normalized.get("ends_at"),
            "location",
            normalized.get("location"));
    if (record.has("timetable_id")) result.put("timetable_id", record.get("timetable_id"));
    return result;
  }

  private static JSONObject findKnown(JSONObject payload, String target, String id) {
    if (payload == null) return null;
    JSONArray queries = payload.optJSONArray("query_results");
    if (queries != null)
      for (int i = queries.length() - 1; i >= 0; i--) {
        JSONObject query = queries.optJSONObject(i);
        if (query != null && query.optString("target").equals(target)) {
          JSONObject result = query.optJSONObject("result");
          JSONObject found = findIn(result == null ? null : result.optJSONArray("records"), id);
          if (found != null) return found;
        }
      }
    if (target.equals("local")) {
      JSONObject found = findIn(payload.optJSONArray("records"), id);
      if (found != null) return found;
    }
    return null;
  }

  private static JSONObject findIn(JSONArray records, String id) {
    if (records != null)
      for (int i = 0; i < records.length(); i++) {
        JSONObject record = records.optJSONObject(i);
        if (record != null
            && (id.equals(record.optString("id")) || id.equals(record.optString("record_id"))))
          return record;
      }
    return null;
  }

  private static JSONArray onlyQueries(JSONArray actions) {
    JSONArray queries = new JSONArray();
    if (actions != null) {
      for (int i = 0; i < actions.length(); i++) {
        JSONObject action = actions.optJSONObject(i);
        if (action != null && action.optString("operation").equals("query")) {
          queries.put(action);
        }
      }
    }
    return queries;
  }

  private static boolean hasDelete(JSONArray actions) {
    if (actions != null) {
      for (int i = 0; i < actions.length(); i++) {
        if (actions.optJSONObject(i).optString("operation").equals("delete")) {
          return true;
        }
      }
    }
    return false;
  }

  private String deletePreview(JSONObject plan) {
    StringBuilder text = new StringBuilder("即将执行以下删除，尚未修改记录：");
    JSONArray actions = plan.optJSONArray("actions");
    for (int i = 0; actions != null && i < actions.length(); i++) {
      JSONObject action = actions.optJSONObject(i);
      if (!action.optString("operation").equals("delete")) continue;
      JSONObject known =
          findKnown(
              pending().optJSONObject("payload"),
              action.optString("target"),
              action.optString("record_id"));
      text.append("\n• ")
          .append(known == null ? action.optString("record_id") : known.optString("title"));
    }
    if (actions != null && actions.length() > 1) {
      text.append("\n确认后也会执行本次方案中的其他操作。");
    }
    return text.toString();
  }

  private static String summary(JSONArray results) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < results.length(); i++) {
      JSONObject result = results.optJSONObject(i);
      if (!result.optString("status").equals("ok")) continue;
      if (text.length() > 0) text.append("\n\n");
      JSONObject record = result.optJSONObject("record");
      String operation = result.optString("operation");
      text.append(operation.equals("delete") ? "已删除" : operation.equals("update") ? "已更新" : "已记录");
      text.append(result.optString("target").equals("hita") ? "到 HITA：" : "：")
          .append(record == null ? "事项" : record.optString("title"));
      if (record != null && !operation.equals("delete")) {
        text.append("\n分类：")
            .append(
                result.optString("target").equals("hita")
                    ? "HITA 日程"
                    : RecordValidator.typeLabel(record));
        if (record.optString("kind").equals("todo")
            && !record.optString("record_type").equals("schedule"))
          text.append(" · ").append(RecordValidator.quadrant(record));
        if (!record.optString("category").isEmpty())
          text.append(" · ").append(record.optString("category"));
        if (!record.isNull("due_at") && !record.optString("due_at").isEmpty())
          text.append("\n时间：").append(record.optString("due_at"));
        else if (!record.isNull("starts_at") && !record.optString("starts_at").isEmpty())
          text.append("\n时间：")
              .append(record.optString("starts_at"))
              .append(" — ")
              .append(record.optString("ends_at"));
        else if (!record.isNull("task_date") && !record.optString("task_date").isEmpty())
          text.append("\n日期：").append(record.optString("task_date"));
        if (!result.optString("reason").isEmpty())
          text.append("\n判断：").append(result.optString("reason"));
      }
    }
    return text.toString();
  }

  private static String hitaError(JSONObject result) {
    String status = result.optString("status");
    if (status.equals("authorization_required") || status.equals("unauthorized"))
      return "HITA 尚未授权，请在设置中连接 HITA 并选择允许编辑的课表。";
    if (status.equals("unavailable") || status.equals("not_installed"))
      return "未安装支持此接口的 HITA 兼容版。普通日程可保存在本 App；显式 HITA 指令不会改存到其他位置。";
    if (status.equals("conflict") || status.equals("not_found")) return "HITA 目标已变化或不存在，请重新查询后再修改。";
    String message = result.optString("message");
    return message.isEmpty() ? "HITA 未执行此次操作（" + status + "）。请检查连接和授权后重试。" : message;
  }

  private static void requireKeys(JSONObject object, String... names) {
    Set<String> allowed = new HashSet<>(Arrays.asList(names));
    Iterator<String> keys = object.keys();
    while (keys.hasNext())
      if (!allowed.contains(keys.next()))
        throw new IllegalArgumentException("DeepSeek 返回了不支持的字段，未执行此次操作。");
  }

  private static String nullableText(JSONObject object, String key, int max) {
    if (!object.has(key) || object.isNull(key)) return null;
    Object value = object.opt(key);
    if (!(value instanceof String) || ((String) value).length() > max)
      throw new IllegalArgumentException("DeepSeek 回复字段格式不正确。");
    String result = ((String) value).trim();
    return result.isEmpty() ? null : result;
  }

  private JSONObject pending() {
    String text = store.getValue(PENDING);
    return text.isEmpty() ? null : LocalStore.parse(text);
  }

  private JSONObject recorded(JSONObject result) {
    store.appendMessage("assistant", display(result), result);
    return result;
  }

  private JSONObject recorded(JSONObject result, String key, boolean value) {
    return recorded(withFlag(result, key, value));
  }

  private static JSONObject withFlag(JSONObject result, String key, boolean value) {
    LocalStore.put(result, key, value);
    return result;
  }

  private static JSONObject response(String reply, String question, JSONArray results) {
    return LocalStore.json("reply", reply, "question", question, "results", results);
  }

  static String display(JSONObject response) {
    String reply = response.optString("reply"),
        question = response.isNull("question") ? "" : response.optString("question");
    return question.isEmpty() ? reply : (reply.isEmpty() ? question : reply + "\n\n" + question);
  }

  private static String errorMessage(Exception error) {
    if (error instanceof UnknownHostException) return "无法连接 DeepSeek，请检查网络。输入已保留，可以重试。";
    if (error instanceof SocketTimeoutException) return "DeepSeek 回复超时。输入已保留，可以重试。";
    if (error instanceof IOException
        || error instanceof IllegalArgumentException
        || error instanceof IllegalStateException) {
      String message = error.getMessage();
      if (message != null && !message.trim().isEmpty()) return message + "\n待处理请求已保留。";
    }
    return "此次处理未完成。输入已保留，可以重试；已经成功执行的操作不会重复。";
  }
}
