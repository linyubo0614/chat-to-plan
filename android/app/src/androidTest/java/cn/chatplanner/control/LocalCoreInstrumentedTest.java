package cn.chatplanner.control;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class LocalCoreInstrumentedTest extends InstrumentationTestCase {
    private JSONObject record(String title) { return LocalStore.json("kind", "todo", "title", title, "record_type", "task"); }
    private String unique() { return UUID.randomUUID().toString(); }
    private void rejected(JSONObject record) {
        try { RecordValidator.normalize(record); fail("Invalid record accepted"); } catch (IllegalArgumentException expected) {}
    }
    public void testDatesAndTypesAreValidatedBeforeStorage() throws Exception {
        rejected(record("无日期DDL").put("record_type", "ddl"));
        rejected(record("无时区").put("due_at", "2030-10-18T12:00:00"));
        rejected(record("不一致日期").put("task_date", "2030-10-19").put("due_at", "2030-10-18T12:00:00Z"));
        rejected(record("无结束时间").put("record_type", "schedule").put("starts_at", "2030-10-18T12:00:00Z"));
        rejected(record("逆序日程").put("record_type", "schedule").put("starts_at", "2030-10-18T12:00:00Z").put("ends_at", "2030-10-18T11:00:00Z"));
        rejected(record("提醒越界").put("task_date", "2030-10-18").put("reminder_minutes", 10081));
        rejected(record("布尔格式").put("important", "true"));
        rejected(record("无效日期").put("task_date", "2030-02-30"));
        rejected(record("备忘期限").put("kind", "memo").put("due_at", "2030-10-18T12:00:00Z"));
        JSONObject exam = RecordValidator.normalize(record("考试").put("record_type", "exam").put("task_date", "2030-10-18").put("important", true).put("urgent", false));
        assertEquals("考试", RecordValidator.typeLabel(exam)); assertEquals("重要不紧急", RecordValidator.quadrant(exam));
        JSONObject memo = RecordValidator.normalize(record("备忘").put("kind", "memo").put("important", true)); assertFalse(memo.getBoolean("important"));
    }
    public void testCreateUpdateDeleteReceiptsAreIdempotent() throws Exception {
        LocalStore store = LocalStore.get(getInstrumentation().getTargetContext()); String operation = unique();
        JSONObject first = store.createRecord(record("幂等" + operation), operation), second = store.createRecord(record("重复"), operation);
        assertEquals(first.getString("id"), second.getString("id")); assertEquals(first.getString("title"), second.getString("title"));
        String id = first.getString("id"), updateId = unique();
        JSONObject update = LocalStore.json("operation", "update", "record_id", id, "expected_revision", 0, "record", record("已修改"));
        store.applyAction(update, updateId); store.applyAction(update, updateId); assertEquals(1, store.getRecord(id).getInt("revision"));
        try { store.updateRecord(id, 0, record("过时修改")); fail("Stale revision accepted"); } catch (IllegalArgumentException expected) {}
        String deleteId = unique(); JSONObject deletion = LocalStore.json("operation", "delete", "record_id", id, "expected_revision", 1);
        store.applyAction(deletion, deleteId); store.applyAction(deletion, deleteId); assertNull(store.getRecord(id)); assertTrue(store.containsRecordId(id));
    }
    public void testImportIsAtomicAndCannotResurrectDeletedIds() throws Exception {
        LocalStore store = LocalStore.get(getInstrumentation().getTargetContext());
        JSONObject old = store.createRecord(record("已删除备份"), unique()); store.deleteRecord(old.getString("id"), 0);
        String freshId = unique().replace("-", ""); JSONObject fresh = record("新导入").put("id", freshId);
        int before = store.records(null).length();
        JSONArray invalid = new JSONArray().put(fresh).put(record("坏记录").put("id", unique()).put("record_type", "exam"));
        try { store.importRecords(invalid); fail("Partial import accepted"); } catch (IllegalArgumentException expected) {}
        assertEquals(before, store.records(null).length()); assertFalse(store.containsRecordId(freshId));
        JSONObject result = store.importRecords(new JSONArray().put(fresh).put(old)); assertEquals(1, result.getInt("imported")); assertEquals(1, result.getInt("skipped"));
        assertNull(store.getRecord(old.getString("id"))); assertNotNull(store.getRecord(freshId));
    }
    public void testApiKeyEncryptedAndPersistentWithoutIncludingItInRecords() throws Exception {
        Context context = getInstrumentation().getTargetContext(); AppSettings settings = new AppSettings(context); String previous = settings.apiKey();
        String fake = "sk-test-" + unique();
        try {
            settings.setApiKey(fake); assertEquals(fake, new AppSettings(context).apiKey());
            String ciphertext = context.getSharedPreferences("deepseek_settings", Context.MODE_PRIVATE).getString("api_key", "");
            assertFalse(ciphertext.isEmpty()); assertFalse(ciphertext.equals(fake));
            assertFalse(LocalStore.get(context).records(null).toString().contains(fake));
            assertFalse(LocalStore.get(context).messages().toString().contains(fake));
            settings.setApiKey(""); assertEquals("", new AppSettings(context).apiKey());
        } finally { settings.setApiKey(previous); }
    }
}
