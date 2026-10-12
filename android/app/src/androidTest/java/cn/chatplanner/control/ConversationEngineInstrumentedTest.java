package cn.chatplanner.control;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android SQLite and conversation lifecycle, with deterministic provider replies. */
public class ConversationEngineInstrumentedTest extends InstrumentationTestCase {
    private Context context;
    private LocalStore store;
    @Override protected void setUp() throws Exception {
        super.setUp(); context = getInstrumentation().getTargetContext(); store = LocalStore.get(context);
        store.putValue("conversation.pending.v1", ""); store.putValue("conversation.draft.v1", "");
    }
    @Override protected void tearDown() throws Exception {
        store.putValue("conversation.pending.v1", ""); store.putValue("conversation.draft.v1", "");
        super.tearDown();
    }
    private JSONObject record(String title) { return LocalStore.json("kind", "todo", "record_type", "task", "title", title, "category", "高数", "important", false, "urgent", false); }
    private JSONObject plan(JSONObject... actions) {
        JSONArray array = new JSONArray(); for (JSONObject action : actions) array.put(action);
        return LocalStore.json("reply", "理解了。", "question", null, "draft", null, "actions", array);
    }
    private JSONObject create(JSONObject record) { return LocalStore.json("operation", "create", "target", "local", "record", record, "reason", "有明确截止日期，但未说明重要影响。"); }
    private String unique(String title) { return title + "-" + UUID.randomUUID().toString().substring(0, 8); }

    public void testClassificationPreservesDdlAndIndependentQuadrant() throws Exception {
        String title = unique("交高数作业");
        JSONObject item = record(title).put("record_type", "ddl").put("task_date", "2030-10-18").put("urgent", true);
        ConversationEngine engine = new ConversationEngine(context, input -> plan(create(item)));
        JSONObject result = engine.send("周五交高数作业");
        JSONObject saved = result.getJSONArray("results").getJSONObject(0).getJSONObject("record");
        assertEquals("ddl", saved.getString("record_type")); assertTrue(saved.getBoolean("urgent")); assertFalse(saved.getBoolean("important"));
        assertTrue(result.getString("reply").contains("DDL")); assertTrue(result.getString("reply").contains("紧急不重要"));
        assertNotNull(store.getRecord(saved.getString("id"))); assertFalse(engine.hasPending());
        JSONArray messages = store.messages(); assertEquals("assistant", messages.getJSONObject(messages.length() - 1).getString("role"));
    }

    public void testFollowUpFillsOriginalDraftAndCreatesOnlyOnce() throws Exception {
        String title = unique("学院会议"); int before = store.records(null).length(); final int[] calls = {0};
        ConversationEngine engine = new ConversationEngine(context, input -> {
            if (++calls[0] == 1) return LocalStore.json("reply", "", "question", "几点开始、几点结束？", "draft", LocalStore.json("title", title, "intent", "明天开会"), "actions", new JSONArray());
            assertNotNull(input.optJSONObject("draft")); assertTrue(input.getJSONObject("draft").toString().contains(title));
            assertEquals("三点到四点", input.getString("message"));
            return plan(create(record(title).put("record_type", "schedule").put("starts_at", "2030-10-20T15:00:00+08:00").put("ends_at", "2030-10-20T16:00:00+08:00"))).put("draft_resolved", true);
        });
        JSONObject question = engine.send("明天开会"); assertTrue(question.getString("question").contains("开始"));
        assertEquals(before, store.records(null).length()); assertTrue(engine.hasDraft()); assertFalse(engine.hasPending());
        JSONObject answer = engine.send("三点到四点"); assertEquals(1, answer.getJSONArray("results").length());
        assertEquals(before + 1, store.records(null).length()); assertFalse(engine.hasDraft());
        assertEquals(0, engine.retryPending().getJSONArray("results").length()); assertEquals(before + 1, store.records(null).length());
    }

    public void testNetworkFailurePersistsInputAndRetryDoesNotDuplicateUserOrRecord() throws Exception {
        String title = unique("重试事项"); final int[] calls = {0}; int before = store.records(null).length();
        ConversationEngine engine = new ConversationEngine(context, input -> { if (++calls[0] == 1) throw new IOException("测试网络失败"); return plan(create(record(title))); });
        int messagesBefore = store.messages().length();
        JSONObject failed = engine.send("记录" + title); assertTrue(failed.getBoolean("retryable")); assertTrue(engine.hasPending());
        assertEquals(before, store.records(null).length());
        JSONObject retried = engine.retryPending(); assertEquals(1, retried.getJSONArray("results").length()); assertFalse(engine.hasPending());
        int users = 0; JSONArray messages = store.messages();
        for (int i = messagesBefore; i < messages.length(); i++) if (messages.getJSONObject(i).getString("role").equals("user")) users++;
        assertEquals(1, users); assertEquals(before + 1, store.records(null).length());
        engine.retryPending(); assertEquals(before + 1, store.records(null).length());
    }

    public void testRetryKeepsOriginalRelativeDateReferenceAndHistoryTimes() throws Exception {
        final String[] reference = {null}; final int[] calls = {0};
        ConversationEngine engine = new ConversationEngine(context, input -> {
            assertFalse(input.getString("reference_time").isEmpty()); assertFalse(input.getString("timezone").isEmpty());
            JSONArray history = input.getJSONArray("history"); assertFalse(history.getJSONObject(history.length() - 1).getString("created_at").isEmpty());
            if (++calls[0] == 1) { reference[0] = input.getString("reference_time"); throw new IOException("测试断网"); }
            assertEquals(reference[0], input.getString("reference_time")); return plan();
        });
        engine.send("明天下午三点"); assertTrue(engine.hasPending());
        assertEquals("request", engine.pendingStage()); engine.retryPending(); assertFalse(engine.hasPending());
    }

    public void testQuestionMovesIncompleteActionsIntoDraftWithoutWriting() throws Exception {
        int before = store.records(null).length();
        JSONObject incomplete = create(record(unique("无日期考试")).put("record_type", "exam"));
        ConversationEngine engine = new ConversationEngine(context, input -> LocalStore.json("reply", "", "question", "考试是哪一天？", "draft", null, "actions", new JSONArray().put(incomplete)));
        JSONObject result = engine.send("记下考试"); assertEquals("考试是哪一天？", result.getString("question"));
        assertFalse(result.optBoolean("retryable")); assertEquals(before, store.records(null).length()); assertTrue(engine.hasDraft()); assertFalse(engine.hasPending());
        assertTrue(store.getValue("conversation.draft.v1").contains("proposed_actions"));
    }

    public void testSideConversationDoesNotDiscardUnfinishedDraft() throws Exception {
        final int[] calls = {0};
        ConversationEngine engine = new ConversationEngine(context, input -> ++calls[0] == 1
            ? LocalStore.json("reply", "", "question", "几点结束？", "draft", LocalStore.json("intent", "会议"), "actions", new JSONArray())
            : plan());
        engine.send("明天开会"); assertTrue(engine.hasDraft()); engine.send("谢谢"); assertTrue(engine.hasDraft());
        engine.clearDraft(); assertFalse(engine.hasDraft());
    }

    public void testIndependentNewMemoDoesNotDiscardUnfinishedDraft() throws Exception {
        final int[] calls = {0}; String memoTitle = unique("图书馆密码");
        ConversationEngine engine = new ConversationEngine(context, input -> ++calls[0] == 1
            ? LocalStore.json("reply", "", "question", "几点结束？", "draft", LocalStore.json("intent", "会议"), "actions", new JSONArray())
            : plan(create(LocalStore.json("kind", "memo", "title", memoTitle, "body", "123"))).put("draft_resolved", false));
        engine.send("明天开会"); assertTrue(engine.hasDraft());
        JSONObject result = engine.send("另外记一下图书馆密码123"); assertEquals(1, result.getJSONArray("results").length()); assertTrue(engine.hasDraft());
        assertTrue(store.getValue("conversation.draft.v1").contains("会议"));
    }

    public void testUpdateChangesSameIdAndCompletionUsesRevision() throws Exception {
        JSONObject original = store.createRecord(record(unique("修改事项")).put("body", "保留这个备注"), UUID.randomUUID().toString());
        String id = original.getString("id");
        ConversationEngine update = new ConversationEngine(context, input -> plan(LocalStore.json("operation", "update", "target", "local", "record_id", id,
            "expected_revision", original.getInt("revision"), "record", LocalStore.json("title", "修改后的名称"))));
        JSONObject result = update.send("改下名称"); JSONObject changed = result.getJSONArray("results").getJSONObject(0).getJSONObject("record");
        assertEquals(id, changed.getString("id")); assertEquals(1, changed.getInt("revision")); assertEquals("保留这个备注", changed.getString("body"));
        ConversationEngine completion = new ConversationEngine(context, input -> plan(LocalStore.json("operation", "complete", "target", "local", "record_id", id, "expected_revision", 1)));
        completion.send("完成它"); assertTrue(store.getRecord(id).getBoolean("completed")); assertEquals(2, store.getRecord(id).getInt("revision"));
    }

    public void testDeleteRequiresConfirmationAndCancelKeepsRecord() throws Exception {
        JSONObject original = store.createRecord(record(unique("待删除事项")), UUID.randomUUID().toString()); String id = original.getString("id");
        ConversationEngine engine = new ConversationEngine(context, input -> plan(LocalStore.json("operation", "delete", "target", "local", "record_id", id, "expected_revision", 0)));
        JSONObject request = engine.send("删掉它"); assertTrue(request.getBoolean("needs_confirmation")); assertNotNull(store.getRecord(id));
        engine.confirmPending(false); assertNotNull(store.getRecord(id));
        engine.send("还是删掉它"); JSONObject deleted = engine.confirmPending(true);
        assertEquals("delete", deleted.getJSONArray("results").getJSONObject(0).getString("operation")); assertNull(store.getRecord(id)); assertTrue(store.containsRecordId(id));
        engine.retryPending(); assertNull(store.getRecord(id));
    }

    public void testInvalidSecondActionPreventsWholeBatchFromWriting() throws Exception {
        int before = store.records(null).length();
        JSONObject valid = create(record(unique("有效事项"))), invalid = create(record(unique("不完整DDL")).put("record_type", "ddl"));
        ConversationEngine engine = new ConversationEngine(context, input -> plan(valid, invalid));
        JSONObject result = engine.send("记录两项"); assertTrue(result.getBoolean("retryable")); assertEquals(before, store.records(null).length()); assertTrue(engine.hasPending());
    }

    public void testQueryRoundUsesRealIdsAndCanFindOlderRecords() throws Exception {
        String title = unique("查找旧事项"); JSONObject original = store.createRecord(record(title), UUID.randomUUID().toString()); String id = original.getString("id");
        final int[] rounds = {0};
        ConversationEngine engine = new ConversationEngine(context, input -> {
            if (++rounds[0] == 1) return plan(LocalStore.json("operation", "query", "target", "local", "filter", LocalStore.json("keyword", title)));
            JSONObject query = input.getJSONArray("query_results").getJSONObject(0).getJSONObject("result"); assertEquals(id, query.getJSONArray("records").getJSONObject(0).getString("id"));
            return plan(LocalStore.json("operation", "update", "target", "local", "record_id", id, "expected_revision", 0, "record", LocalStore.json("important", true)));
        });
        JSONObject result = engine.send("把" + title + "标为重要"); assertEquals(1, result.getJSONArray("results").length()); assertTrue(store.getRecord(id).getBoolean("important"));
    }

    public void testHallucinatedRecordIdDoesNotCreateReplacement() throws Exception {
        int before = store.records(null).length();
        ConversationEngine engine = new ConversationEngine(context, input -> plan(LocalStore.json("operation", "update", "target", "local", "record_id", "does-not-exist", "expected_revision", 0, "record", record("替代记录"))));
        JSONObject result = engine.send("修改不存在的任务"); assertTrue(result.getBoolean("retryable")); assertEquals(before, store.records(null).length());
    }
}
