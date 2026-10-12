package cn.chatplanner.control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual native screens and editor saves. Provider responses are fixture data; no API call or Key. */
public final class LocalPlannerUiInstrumentedTest extends InstrumentationTestCase {
    private Context context;
    private LocalStore store;
    private final List<Activity> activities = new ArrayList<>();
    private final List<String> recordIds = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        store = LocalStore.get(context);
        assertFalse("Tests must not race an active provider request", ChatRuntime.get(context).busy());
        store.clearMessages();
        clearConversationState();
        if (Build.VERSION.SDK_INT >= 33)
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override protected void tearDown() throws Exception {
        try {
            getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : activities) {
                    if (activity instanceof PlannerActivity) {
                        AlertDialog editor = ((PlannerActivity) activity).editor;
                        if (editor != null && editor.isShowing()) editor.dismiss();
                    }
                    if (!activity.isDestroyed()) activity.finish();
                }
            });
            getInstrumentation().waitForIdleSync();
            for (String id : recordIds) {
                JSONObject record = store.getRecord(id);
                if (record != null) store.deleteRecord(id, record.optInt("revision"));
            }
            store.clearMessages();
            clearConversationState();
            ReminderScheduler.refresh(context);
        } finally { super.tearDown(); }
    }

    private void clearConversationState() {
        store.putValue("conversation.pending.v1", "");
        store.putValue("conversation.draft.v1", "");
        store.putValue("composer_draft", "");
    }

    private JSONObject seed(JSONObject record) {
        JSONObject saved = store.createRecord(record, "ui-fixture-" + UUID.randomUUID());
        recordIds.add(saved.optString("id"));
        return saved;
    }

    private JSONObject task(String title) {
        return LocalStore.json("kind", "todo", "record_type", "task", "title", title,
                "category", "学习", "important", true, "urgent", false);
    }

    private <T extends Activity> T launch(Class<T> type) {
        return launch(type, new Intent(context, type));
    }

    private <T extends Activity> T launch(Class<T> type, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = getInstrumentation().startActivitySync(intent);
        assertTrue(type.getName(), type.isInstance(activity));
        activities.add(activity);
        getInstrumentation().waitForIdleSync();
        return type.cast(activity);
    }

    private View decor(Activity activity) { return activity.getWindow().getDecorView(); }

    private Button button(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            Button found = button(((ViewGroup) root).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private EditText editorField(View root, String hint) {
        if (root instanceof EditText && hint.contentEquals(((EditText) root).getHint())) return (EditText) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            EditText found = editorField(((ViewGroup) root).getChildAt(i), hint);
            if (found != null) return found;
        }
        return null;
    }

    private boolean hasText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (hasText(((ViewGroup) root).getChildAt(i), expected)) return true;
        return false;
    }

    private void click(Button button) {
        assertNotNull("Expected native button", button);
        getInstrumentation().runOnMainSync(() -> assertTrue(button.performClick()));
        getInstrumentation().waitForIdleSync();
    }

    private void screenshot(Activity activity, String name) throws Exception {
        getInstrumentation().waitForIdleSync();
        assertEquals("Screenshots must remain allowed", 0,
                activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        // Let the surface commit the already-idle view hierarchy before its actual system capture.
        SystemClock.sleep(180);
        Bitmap capture = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation could not capture the screen", capture);
        try {
            assertTrue(capture.getWidth() > 100); assertTrue(capture.getHeight() > 100);
            File directory = context.getExternalFilesDir("previews");
            assertNotNull(directory); assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(capture.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { capture.recycle(); }
    }

    public void testLauncherIsConversationAndScreenCanBeCaptured() throws Exception {
        Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launcher); assertNotNull(launcher.getComponent());
        assertEquals(ChatActivity.class.getName(), launcher.getComponent().getClassName());
        ChatActivity chat = launch(ChatActivity.class);
        assertTrue(hasText(decor(chat), "今天有什么需要记住？"));
        assertNotNull(chat.findViewById(R.id.chat_input));
        screenshot(chat, "chat-empty");
    }

    public void testRejectedLongAndPendingInputsStayInComposer() throws Exception {
        ChatActivity chat = launch(ChatActivity.class);
        EditText composer = chat.findViewById(R.id.chat_input);
        Button send = chat.findViewById(R.id.chat_send);
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 8001; i++) longInput.append('字');
        String original = longInput.toString();
        getInstrumentation().runOnMainSync(() -> composer.setText(original));
        click(send);
        assertEquals(original, composer.getText().toString());
        assertEquals(original, store.getValue("composer_draft"));
        assertEquals(0, store.messages().length());
        assertFalse(ChatRuntime.get(context).busy());

        for (String stage : new String[]{"request", "unknown", "confirmation"}) {
            store.putValue("conversation.pending.v1", LocalStore.json("request_id", "ui-pending", "stage", stage).toString());
            String newInput = "新的记录内容应继续保留：" + stage;
            getInstrumentation().runOnMainSync(() -> composer.setText(newInput));
            click(send);
            assertEquals(newInput, composer.getText().toString());
            assertEquals(newInput, store.getValue("composer_draft"));
            assertFalse(ChatRuntime.get(context).busy());
            assertEquals(0, store.messages().length());
        }
    }

    public void testLocalResultHasWorkingEditorEntry() throws Exception {
        JSONObject saved = seed(task("整理高数笔记"));
        JSONObject result = LocalStore.json("reply", "已记录：整理高数笔记\n分类：任务 · 重要不紧急", "question", null,
                "results", new JSONArray().put(LocalStore.json("status", "ok", "target", "local", "operation", "create", "record", saved)));
        store.appendMessage("user", "明天抽空整理高数笔记。");
        store.appendMessage("assistant", ConversationEngine.display(result), result);
        ChatActivity chat = launch(ChatActivity.class);
        Button edit = button(decor(chat), "查看 / 编辑");
        assertNotNull("Local engine receipts must expose their editor", edit);
        screenshot(chat, "chat-result");
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(PlannerActivity.class.getName(), null, false);
        try {
            click(edit);
            Activity opened = monitor.waitForActivityWithTimeout(5000);
            assertTrue("Editor navigation did not open the plan page", opened instanceof PlannerActivity);
            activities.add(opened);
            getInstrumentation().waitForIdleSync();
            AlertDialog dialog = ((PlannerActivity) opened).editor;
            assertNotNull(dialog); assertTrue(dialog.isShowing());
            EditText title = editorField(dialog.getWindow().getDecorView(), "标题");
            assertNotNull(title); assertEquals(saved.getString("title"), title.getText().toString());
        } finally { getInstrumentation().removeMonitor(monitor); }
    }

    public void testSharedTextEditsSurviveActivityRecreation() throws Exception {
        Intent shared = new Intent(context, ChatActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "从其他应用分享的原文");
        ChatActivity chat = launch(ChatActivity.class, shared);
        EditText composer = chat.findViewById(R.id.chat_input);
        assertEquals("从其他应用分享的原文", composer.getText().toString());
        String edited = "已经补充日期和地点的分享内容";
        getInstrumentation().runOnMainSync(() -> composer.setText(edited));
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(ChatActivity.class.getName(), null, false);
        try {
            getInstrumentation().runOnMainSync(chat::recreate);
            Activity replacement = monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Activity recreation did not complete", replacement);
            assertTrue(replacement instanceof ChatActivity); assertNotSame(chat, replacement);
            activities.add(replacement);
            getInstrumentation().waitForIdleSync();
            EditText restored = replacement.findViewById(R.id.chat_input);
            assertEquals(edited, restored.getText().toString());
            assertEquals(edited, store.getValue("composer_draft"));
        } finally { getInstrumentation().removeMonitor(monitor); }
    }

    private JSONObject editTitleAndSave(JSONObject saved, String title) throws Exception {
        PlannerActivity page = launch(PlannerActivity.class, new Intent(context, PlannerActivity.class)
                .putExtra("page", "tasks").putExtra("edit_id", saved.getString("id")));
        AlertDialog dialog = page.editor;
        assertNotNull("Record intent should open its actual editor", dialog); assertTrue(dialog.isShowing());
        EditText field = editorField(dialog.getWindow().getDecorView(), "标题");
        assertNotNull(field);
        getInstrumentation().runOnMainSync(() -> field.setText(title));
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertFalse("Valid edit must save and close the dialog", dialog.isShowing());
        JSONObject changed = store.getRecord(saved.getString("id"));
        assertNotNull(changed); assertEquals(title, changed.getString("title"));
        assertEquals(saved.getInt("revision") + 1, changed.getInt("revision"));
        return changed;
    }

    public void testTitleEditPreservesUnscheduledDateAndCustomReminder() throws Exception {
        JSONObject unscheduled = seed(task("准备读书清单"));
        assertTrue(unscheduled.isNull("task_date"));
        JSONObject changed = editTitleAndSave(unscheduled, "整理读书清单");
        assertTrue("Editing a title must not invent today's date", changed.isNull("task_date"));
        assertTrue(changed.isNull("due_at")); assertTrue(changed.isNull("reminder_minutes"));

        LocalDate day = LocalDate.now().plusDays(3);
        String deadline = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        JSONObject reminder = seed(task("交课程报告").put("record_type", "ddl").put("task_date", day.toString())
                .put("due_at", deadline).put("reminder_minutes", 120));
        JSONObject updated = editTitleAndSave(reminder, "交课程报告终稿");
        assertEquals(120, updated.getInt("reminder_minutes"));
        assertEquals(reminder.getString("task_date"), updated.getString("task_date"));
        assertEquals(OffsetDateTime.parse(deadline).toInstant(), OffsetDateTime.parse(updated.getString("due_at")).toInstant());
    }

    public void testTitleEditPreservesScheduleEndingNextDay() throws Exception {
        LocalDate day = LocalDate.now().plusDays(4);
        String start = day.atTime(LocalTime.of(23, 30)).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String end = day.plusDays(1).atTime(LocalTime.of(0, 30)).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        JSONObject overnight = seed(task("晚间讨论").put("record_type", "schedule").put("starts_at", start)
                .put("ends_at", end).put("location", "线上"));
        JSONObject updated = editTitleAndSave(overnight, "晚间课程讨论");
        assertEquals(OffsetDateTime.parse(start).toInstant(), OffsetDateTime.parse(updated.getString("starts_at")).toInstant());
        assertEquals(OffsetDateTime.parse(end).toInstant(), OffsetDateTime.parse(updated.getString("ends_at")).toInstant());
        assertEquals(day.toString(), updated.getString("task_date"));
        assertEquals("线上", updated.getString("location"));
    }

    public void testUnifiedPlanMemoAndSettingsPreviews() throws Exception {
        seed(task("完成高数作业").put("task_date", LocalDate.now().toString()).put("important", true).put("urgent", true));
        seed(task("整理课程笔记").put("task_date", LocalDate.now().toString()));
        seed(LocalStore.json("kind", "memo", "record_type", "task", "title", "图书馆开放时间",
                "body", "周末 08:00 开门，记得带校园卡。", "category", "校园生活"));
        PlannerActivity plans = launch(PlannerActivity.class);
        click(button(decor(plans), "今天"));
        assertTrue(hasText(decor(plans), "完成高数作业"));
        screenshot(plans, "plans");
        PlannerActivity memos = launch(PlannerActivity.class, new Intent(context, PlannerActivity.class).putExtra("page", "memos"));
        assertTrue(hasText(decor(memos), "图书馆开放时间"));
        screenshot(memos, "memos");
        MainActivity settings = launch(MainActivity.class);
        assertTrue(hasText(decor(settings), "DeepSeek"));
        screenshot(settings, "settings");
    }
}
