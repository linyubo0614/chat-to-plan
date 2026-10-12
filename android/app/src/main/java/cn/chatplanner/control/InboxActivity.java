package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.DateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent server inbox, including messages received through WeChat. */
public class InboxActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CredentialStore credentials;
    private String base, token;
    private JSONObject pending;
    private LinearLayout root, entries;
    private TextView status;
    private Button refresh, send, more;
    private Long cursor;
    private boolean busy, detail;
    private ZoneId timezone = ZoneId.of("Asia/Shanghai");
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);SimpleTheme.window(this);
        credentials = new CredentialStore(this);
        ScrollView scroll = new ScrollView(this); root = column(); root.setPadding(SimpleTheme.dp(this,20),SimpleTheme.dp(this,20),SimpleTheme.dp(this,20),SimpleTheme.dp(this,20)); scroll.setBackgroundColor(SimpleTheme.PAPER); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        text(root, getString(R.string.inbox_title), 26);
        text(root, getString(R.string.inbox_help), 14);
        status = text(root, "", 14);
        refresh = button(root, getString(R.string.refresh), () -> load(false));
        send = button(root, getString(R.string.send_message), this::compose);
        entries = column(); root.addView(entries);
        more = button(root, getString(R.string.load_more), () -> load(true));
        setContentView(scroll); setBusy(true);
        io.execute(() -> {
            try {
                base = ApiClient.validateBase(credentials.server()); token = credentials.token();
                JSONObject settings = ApiClient.request(base,token,"GET","/v1/settings",null);
                timezone = ZoneId.of(settings.optString("timezone","Asia/Shanghai"));
                String draft = credentials.pendingMessage(base); pending = draft.isEmpty() ? null : new JSONObject(draft);
                post(() -> { send.setText(pending == null ? R.string.send_message : R.string.resume_send); load(false); });
            } catch (Exception ex) { failed(ex); }
        });
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(SimpleTheme.INK); view.setPadding(0, 10, 0, 10); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String value, Runnable action) {
        Button view = new Button(this); view.setText(value); SimpleTheme.button(this,view);
        view.setOnClickListener(v -> { if (!busy) action.run(); }); parent.addView(view); return view;
    }
    private EditText field(LinearLayout parent, String caption, String value, boolean multiline) {
        text(parent, caption, 14); EditText view = new EditText(this); view.setText(value);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        view.setSingleLine(!multiline); view.setMaxLines(multiline ? 6 : 1); parent.addView(view); return view;
    }
    private EditText timeField(LinearLayout parent, String caption, String value) {
        EditText view = field(parent,caption,"",false); view.setFocusable(false); view.setTag(value);
        ZonedDateTime initial = value.isEmpty() ? ZonedDateTime.now(timezone) : OffsetDateTime.parse(value).atZoneSameInstant(timezone);
        if (!value.isEmpty()) view.setText(initial.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        view.setHint(R.string.choose_time);
        view.setOnClickListener(v -> {
            String stored = (String) view.getTag();
            ZonedDateTime now = stored.isEmpty() ? ZonedDateTime.now(timezone) : OffsetDateTime.parse(stored).atZoneSameInstant(timezone);
            new DatePickerDialog(this,(d,year,month,day) -> new TimePickerDialog(this,(t,hour,minute) -> {
                ZonedDateTime chosen = java.time.LocalDateTime.of(year,month+1,day,hour,minute).atZone(timezone);
                view.setTag(chosen.toOffsetDateTime().toString()); view.setText(chosen.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            },now.getHour(),now.getMinute(),true).show(),now.getYear(),now.getMonthValue()-1,now.getDayOfMonth()).show();
        });
        Button clearTime = new Button(this); clearTime.setText(R.string.clear_time); parent.addView(clearTime);
        clearTime.setOnClickListener(v -> { view.setTag(""); view.setText(""); }); return view;
    }
    private void post(Runnable task) { ui.post(() -> { if (!destroyed) task.run(); }); }
    private void setBusy(boolean value) {
        busy = value; refresh.setEnabled(!value); send.setEnabled(!value); more.setEnabled(!value && !detail && cursor != null);
    }
    private void failed(Exception ex) {
        int message = R.string.failed;
        if (ex instanceof ApiClient.ApiException) {
            int code = ((ApiClient.ApiException) ex).status;
            if (code == 409) message = R.string.record_conflict;
            if (code == 422) message = R.string.invalid_record;
            if (code == 404) message = R.string.upgrade_backend;
        }
        final int display = message; post(() -> { status.setText(display); setBusy(false); });
    }
    private String state(String value) {
        switch (value) {
            case "parsed": return getString(R.string.state_parsed);
            case "failed": return getString(R.string.state_failed);
            case "processing": return getString(R.string.state_processing);
            case "blocked": return getString(R.string.state_blocked);
            case "needs_clarification": return getString(R.string.state_clarify);
            case "cancelled": return getString(R.string.state_cancelled);
            case "delivering": return getString(R.string.state_delivering);
            case "written": return getString(R.string.state_written);
            default: return getString(R.string.state_pending);
        }
    }
    private void load(boolean append) {
        setBusy(true); status.setText(R.string.loading);
        final String path = "/v1/messages?limit=20" + (append && cursor != null ? "&before=" + cursor : "");
        io.execute(() -> {
            try {
                JSONObject page = ApiClient.request(base, token, "GET", path, null);
                post(() -> {
                    if (!append) entries.removeAllViews(); detail = false;
                    cursor = page.isNull("next_cursor") ? null : page.optLong("next_cursor");
                    JSONArray messages = page.optJSONArray("messages");
                    if (messages != null) for (int i = 0; i < messages.length(); i++) {
                        JSONObject row = messages.optJSONObject(i); if (row == null) continue;
                        String id = row.optString("id");
                        String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date((long)(row.optDouble("created") * 1000)));
                        button(entries, date + " · " + state(row.optString("status")) + "\n" + row.optString("text"), () -> open(id));
                    }
                    status.setText(entries.getChildCount() == 0 ? R.string.inbox_empty : R.string.inbox_loaded); setBusy(false);
                });
            } catch (Exception ex) { failed(ex); }
        });
    }
    private void open(String id) {
        setBusy(true); io.execute(() -> {
            try { JSONObject row = ApiClient.request(base, token, "GET", "/v1/messages/" + id, null); post(() -> show(row)); }
            catch (Exception ex) { failed(ex); }
        });
    }
    private void show(JSONObject row) {
        detail = true; entries.removeAllViews(); status.setText(state(row.optString("status")));
        text(entries, row.optString("text"), 18);
        if (!row.isNull("question")) text(entries, row.optString("question"), 16);
        if ("failed".equals(row.optString("status"))) {
            text(entries, getString("deepseek_not_configured".equals(row.optString("error")) ? R.string.key_missing : R.string.analysis_failed), 14);
            button(entries, getString(R.string.retry_analysis), () -> retry(row.optString("id")));
        } else if (!"parsed".equals(row.optString("status"))) {
            button(entries, getString(R.string.refresh_result), () -> open(row.optString("id")));
        }
        JSONArray tasks = row.optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            JSONObject item = task.optJSONObject("payload"); if (item == null) continue;
            text(entries, item.optString("title") + " · " + item.optString("category"), 20);
            text(entries, item.optString("body") + "\n" + getString("hita".equals(task.optString("target")) ? R.string.hita : R.string.planner) + " · " + state(task.optString("status")), 14);
            for (String name : new String[]{"starts_at", "ends_at", "due_at", "location", "recurrence_description"})
                if (!item.isNull(name) && !item.optString(name).isEmpty()) text(entries, item.optString(name), 14);
            text(entries, task.optString("reason"), 14);
            if ("delivering".equals(task.optString("status")))
                button(entries,getString(R.string.retry_delivery),() -> retryDelivery(task.optString("id"),row.optString("id")));
            if ("blocked".equals(task.optString("status")) || "needs_clarification".equals(task.optString("status"))) {
                button(entries, getString(R.string.edit_record), () -> edit(task));
                button(entries, getString(R.string.cancel_record), () -> new AlertDialog.Builder(this)
                    .setTitle(R.string.cancel_record).setMessage(R.string.cancel_record_help)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.cancel_record, (d,w) -> change(task, null, true)).show());
            }
        }
        setBusy(false);
    }
    private void retry(String id) {
        setBusy(true); io.execute(() -> {
            try { ApiClient.request(base, token, "POST", "/v1/messages/" + id + "/retry", null); poll(id, 0); }
            catch (Exception ex) { failed(ex); }
        });
    }
    private void retryDelivery(String taskId,String messageId) {
        setBusy(true); io.execute(() -> {
            try {
                ApiClient.request(base,token,"POST","/v1/delivery/"+taskId+"/retry",null);
                JSONObject row=ApiClient.request(base,token,"GET","/v1/messages/"+messageId,null);
                post(() -> show(row));
            } catch(Exception ex) { failed(ex); }
        });
    }
    private void compose() {
        EditText message = new EditText(this); message.setMinLines(3); message.setMaxLines(8); message.setHint(R.string.test_help);
        if (pending != null) { message.setText(pending.optString("text")); message.setEnabled(false); }
        new AlertDialog.Builder(this).setTitle(pending == null ? R.string.send_message : R.string.resume_send)
            .setView(message).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.send, (d,w) -> {
                String value = message.getText().toString().trim(); if (value.isEmpty()) return;
                if (value.codePointCount(0,value.length()) > 8000) { status.setText(R.string.message_too_long); return; }
                setBusy(true); io.execute(() -> {
                    try {
                        if (pending == null) pending = new JSONObject().put("message_id",UUID.randomUUID().toString()).put("text",value);
                        credentials.savePending(base, pending.toString());
                        JSONObject accepted = ApiClient.request(base, token, "POST", "/v1/messages", pending);
                        credentials.clearPending(base); pending = null;
                        post(() -> send.setText(R.string.send_message)); poll(accepted.getString("id"),0);
                    } catch (Exception ex) {
                        post(() -> send.setText(R.string.resume_send)); failed(ex);
                    }
                });
            }).show();
    }
    private void poll(String id, int attempt) {
        if (destroyed) return;
        try {
            JSONObject row = ApiClient.request(base, token, "GET", "/v1/messages/" + id, null);
            post(() -> show(row));
            if (!"parsed".equals(row.optString("status")) && !"failed".equals(row.optString("status")) && attempt < 30) {
                post(() -> setBusy(true));
                ui.postDelayed(() -> { if (!destroyed) io.execute(() -> poll(id, attempt+1)); },2000);
            }
        } catch (Exception ex) { failed(ex); }
    }
    private void edit(JSONObject task) {
        JSONObject current = task.optJSONObject("payload"); if (current == null) return;
        ScrollView scroll = new ScrollView(this); LinearLayout form = column(); form.setPadding(24, 0, 24, 0); scroll.addView(form);
        text(form, getString(R.string.edit_help),14);
        EditText title = field(form,getString(R.string.record_title),current.optString("title"),false);
        EditText body = field(form,getString(R.string.record_body),current.optString("body"),true);
        EditText category = field(form,getString(R.string.record_category),current.optString("category"),false);
        EditText start = timeField(form,getString(R.string.record_start),current.isNull("starts_at") ? "" : current.optString("starts_at"));
        EditText end = timeField(form,getString(R.string.record_end),current.isNull("ends_at") ? "" : current.optString("ends_at"));
        EditText due = timeField(form,getString(R.string.record_due),current.isNull("due_at") ? "" : current.optString("due_at"));
        EditText location = field(form,getString(R.string.record_location),current.optString("location"),false);
        EditText clarification = field(form,getString(R.string.record_clarification),current.isNull("clarification") ? "" : current.optString("clarification"),true);
        new AlertDialog.Builder(this).setTitle(R.string.edit_record).setView(scroll).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.save_record,(d,w) -> {
                try {
                    JSONObject item = new JSONObject(current.toString()).put("title",title.getText().toString().trim()).put("body",body.getText().toString())
                        .put("category",category.getText().toString().trim()).put("location",location.getText().toString().trim());
                    String[] names = {"starts_at","ends_at","due_at","clarification"};
                    EditText[] fields = {start,end,due,clarification};
                    for (int i=0;i<names.length;i++) { String value=i<3 ? (String)fields[i].getTag() : fields[i].getText().toString().trim(); item.put(names[i],value.isEmpty() ? JSONObject.NULL : value); }
                    change(task,item,false);
                } catch (Exception ex) { failed(ex); }
            }).show();
    }
    private void change(JSONObject task, JSONObject item, boolean cancel) {
        setBusy(true); io.execute(() -> {
            try {
                int revision = task.getInt("revision");
                JSONObject patch = cancel ? null : new JSONObject().put("revision",revision).put("item",item);
                String path = "/v1/tasks/" + task.getString("id") + (cancel ? "?revision=" + revision : "");
                JSONObject row = ApiClient.request(base,token,cancel ? "DELETE" : "PUT",path,patch); post(() -> show(row));
            } catch (Exception ex) { failed(ex); }
        });
    }
    @Override protected void onDestroy() { destroyed=true; ui.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
}
