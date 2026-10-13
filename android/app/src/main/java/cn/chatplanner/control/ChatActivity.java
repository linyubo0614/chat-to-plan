package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** The main entry: an uncluttered conversation with editable results. */
public final class ChatActivity extends Activity {
  private LocalStore store;
  private ChatRuntime runtime;
  private LinearLayout messages, progress;
  private ScrollView scroll;
  private EditText input;
  private Button send;
  private String signature = "";
  private final Runnable update = () -> renderMessages(false);

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    SimpleTheme.window(this);
    store = LocalStore.get(this);
    runtime = ChatRuntime.get(this);
    LinearLayout root = SimpleTheme.column(this);
    root.setBackgroundColor(SimpleTheme.PAPER);
    setContentView(root);
    SimpleTheme.insets(this, root, 16);
    LinearLayout top = SimpleTheme.row(this);
    SimpleTheme.weight(top, SimpleTheme.text(this, "随手记事", 20, SimpleTheme.INK, true));
    top.addView(
        SimpleTheme.button(this, "设置", () -> startActivity(new Intent(this, MainActivity.class))));
    root.addView(top);
    SimpleTheme.gap(this, root, 8);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    messages = SimpleTheme.column(this);
    messages.setPadding(0, SimpleTheme.dp(this, 8), 0, SimpleTheme.dp(this, 16));
    scroll.addView(messages);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    progress = SimpleTheme.row(this);
    ProgressBar spinner = new ProgressBar(this);
    progress.addView(
        spinner, new LinearLayout.LayoutParams(SimpleTheme.dp(this, 20), SimpleTheme.dp(this, 20)));
    progress.addView(SimpleTheme.text(this, "  正在整理…", 13, SimpleTheme.MUTED, false));
    progress.setPadding(0, 0, 0, SimpleTheme.dp(this, 10));
    root.addView(progress);
    LinearLayout composer = SimpleTheme.column(this);
    composer.setPadding(
        SimpleTheme.dp(this, 14),
        SimpleTheme.dp(this, 10),
        SimpleTheme.dp(this, 10),
        SimpleTheme.dp(this, 8));
    composer.setBackground(SimpleTheme.shape(this, SimpleTheme.SOFT, 22));
    input = new EditText(this);
    input.setId(R.id.chat_input);
    input.setHint("告诉我需要记住或安排的事情…");
    input.setTextSize(15);
    input.setTextColor(SimpleTheme.INK);
    input.setHintTextColor(SimpleTheme.MUTED);
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setMinLines(1);
    input.setMaxLines(5);
    input.setGravity(Gravity.TOP);
    input.setPadding(0, SimpleTheme.dp(this, 4), 0, SimpleTheme.dp(this, 8));
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setMaxLines(5);
    composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout actions = SimpleTheme.row(this);
    SimpleTheme.weight(actions, SimpleTheme.text(this, "DeepSeek", 12, SimpleTheme.MUTED, false));
    Button add =
        SimpleTheme.button(
            this,
            "＋",
            () ->
                new AlertDialog.Builder(this)
                    .setItems(
                        new String[] {"手动添加任务", "手动写备忘"},
                        (d, w) ->
                            startActivity(
                                new Intent(this, PlannerActivity.class)
                                    .putExtra("page", w == 1 ? "memos" : "tasks")
                                    .putExtra("create", true)))
                    .show());
    add.setContentDescription("手动记录");
    actions.addView(
        add, new LinearLayout.LayoutParams(SimpleTheme.dp(this, 44), SimpleTheme.dp(this, 44)));
    send = SimpleTheme.button(this, "↑", this::sendMessage);
    send.setId(R.id.chat_send);
    send.setContentDescription("发送消息");
    send.setTextSize(24);
    send.setTextColor(Color.WHITE);
    send.setBackground(SimpleTheme.shape(this, SimpleTheme.BLUE, 24));
    LinearLayout.LayoutParams sp =
        new LinearLayout.LayoutParams(SimpleTheme.dp(this, 44), SimpleTheme.dp(this, 44));
    sp.setMargins(SimpleTheme.dp(this, 8), 0, 0, 0);
    actions.addView(send, sp);
    composer.addView(actions);
    root.addView(composer);
    SimpleTheme.navigation(this, root, "对话");
    input.setText(store.getValue("composer_draft"));
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            store.putValue("composer_draft", s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
    if (state == null) receiveShare(getIntent());
    renderMessages(true);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    receiveShare(intent);
  }

  private void receiveShare(Intent intent) {
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      String text = intent.getStringExtra(Intent.EXTRA_TEXT);
      if (text != null && !text.trim().isEmpty()) input.setText(text);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    runtime.observe(update);
    renderMessages(true);
  }

  @Override
  protected void onStop() {
    runtime.remove(update);
    super.onStop();
  }

  private void sendMessage() {
    String text = input.getText().toString().trim();
    if (text.isEmpty() || runtime.busy()) return;
    if (text.length() > 8000) {
      input.setError("单条消息最多 8000 字，请拆分后发送。");
      return;
    }
    String pending = pendingStage();
    if (!pending.isEmpty() && !pending.equals("confirmation")) {
      input.setError("请先重试或取消上一项待处理操作，输入内容会保留。");
      return;
    }
    if (pending.equals("confirmation")
        && !java.util.Arrays.asList("确认", "确认删除", "是", "yes", "取消", "不删除", "否", "no")
            .contains(text.toLowerCase(java.util.Locale.ROOT))) {
      input.setError("请先确认或取消上一次删除，输入内容会保留。");
      return;
    }
    boolean configured;
    try {
      configured = !new AppSettings(this).apiKey().isEmpty();
    } catch (Exception ex) {
      configured = false;
    }
    if (!configured && !pending.equals("confirmation")) {
      new AlertDialog.Builder(this)
          .setTitle("连接 DeepSeek")
          .setMessage("请在设置中填写或重新保存自己的 API Key，之后会保存在这台手机。输入内容已保留。")
          .setNegativeButton("稍后", null)
          .setPositiveButton("去设置", (d, w) -> startActivity(new Intent(this, MainActivity.class)))
          .show();
      return;
    }
    if (runtime.submit(text, 0)) {
      input.setText("");
      ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
  }

  private void renderMessages(boolean force) {
    JSONArray history = store.messages();
    String next = history.toString() + runtime.busy();
    progress.setVisibility(runtime.busy() ? View.VISIBLE : View.GONE);
    send.setEnabled(!runtime.busy());
    if (!force && next.equals(signature)) return;
    signature = next;
    messages.removeAllViews();
    if (history.length() == 0) {
      LinearLayout empty = SimpleTheme.column(this);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(
          SimpleTheme.dp(this, 12),
          SimpleTheme.dp(this, 58),
          SimpleTheme.dp(this, 12),
          SimpleTheme.dp(this, 32));
      TextView mark = SimpleTheme.text(this, "✦", 38, SimpleTheme.BLUE, false);
      mark.setGravity(Gravity.CENTER);
      empty.addView(mark);
      SimpleTheme.gap(this, empty, 18);
      TextView title = SimpleTheme.text(this, "今天有什么需要记住？", 23, SimpleTheme.INK, true);
      title.setGravity(Gravity.CENTER);
      empty.addView(title);
      SimpleTheme.gap(this, empty, 10);
      TextView intro = SimpleTheme.text(this, "随手说一句，把事情安排好。", 14, SimpleTheme.MUTED, false);
      intro.setGravity(Gravity.CENTER);
      empty.addView(intro);
      SimpleTheme.gap(this, empty, 24);
      for (String example : new String[] {"周五晚上十点前交高数作业", "记一下：图书馆周末八点开门", "明天下午三点到四点开会"}) {
        empty.addView(
            SimpleTheme.button(
                this,
                example,
                () -> {
                  input.setText(example);
                  input.setSelection(input.length());
                  input.requestFocus();
                }),
            new LinearLayout.LayoutParams(-1, -2));
        SimpleTheme.gap(this, empty, 8);
      }
      messages.addView(empty);
    }
    for (int i = 0; i < history.length(); i++) {
      JSONObject m = history.optJSONObject(i);
      if (m == null) continue;
      boolean user = "user".equals(m.optString("role"));
      LinearLayout line = SimpleTheme.row(this);
      line.setGravity(user ? Gravity.END : Gravity.START);
      LinearLayout block = SimpleTheme.column(this);
      if (user) {
        block.setBackground(SimpleTheme.shape(this, SimpleTheme.SOFT, 18));
        block.setPadding(
            SimpleTheme.dp(this, 14),
            SimpleTheme.dp(this, 10),
            SimpleTheme.dp(this, 14),
            SimpleTheme.dp(this, 10));
      } else {
        block.addView(SimpleTheme.text(this, "✦  随手记事", 12, SimpleTheme.BLUE, true));
        SimpleTheme.gap(this, block, 8);
      }
      TextView content = SimpleTheme.text(this, m.optString("content"), 16, SimpleTheme.INK, false);
      content.setTextIsSelectable(true);
      block.addView(content);
      JSONObject result = m.optJSONObject("result");
      if (result != null) renderResult(block, result, i == history.length() - 1);
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(user ? -2 : -1, -2);
      if (user) bp.setMarginStart(SimpleTheme.dp(this, 42));
      line.addView(block, bp);
      messages.addView(line);
      SimpleTheme.gap(this, messages, user ? 20 : 24);
    }
    renderPending();
    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
  }

  private void renderResult(LinearLayout block, JSONObject result, boolean last) {
    JSONArray rows = result.optJSONArray("results");
    if (rows != null)
      for (int j = 0; j < rows.length(); j++) {
        JSONObject r = rows.optJSONObject(j);
        if (r == null) continue;
        JSONObject record = r.optJSONObject("record");
        if (record == null) continue;
        SimpleTheme.gap(this, block, 10);
        LinearLayout card = SimpleTheme.card(this);
        card.addView(SimpleTheme.text(this, record.optString("title"), 15, SimpleTheme.INK, true));
        SimpleTheme.gap(this, card, 5);
        card.addView(
            SimpleTheme.text(this, PlannerActivity.describe(record), 12, SimpleTheme.MUTED, false));
        String target = r.optString("target", "local"),
            id = record.optString("id", r.optString("record_id"));
        if ((target.equals("local") || target.equals("planner"))
            && !id.isEmpty()
            && store.getRecord(id) != null) {
          SimpleTheme.gap(this, card, 8);
          card.addView(
              SimpleTheme.button(
                  this,
                  "查看 / 编辑",
                  () ->
                      startActivity(
                          new Intent(this, PlannerActivity.class)
                              .putExtra("edit_id", id)
                              .putExtra(
                                  "page",
                                  record.optString("kind").equals("memo") ? "memos" : "tasks"))));
          if (!record.isNull("reminder_minutes") && !record.optBoolean("completed")) {
            SimpleTheme.gap(this, card, 6);
            card.addView(
                SimpleTheme.text(
                    this,
                    "提醒："
                        + (record.optInt("reminder_minutes") == 0
                            ? "准点"
                            : "提前 " + record.optInt("reminder_minutes") + " 分钟"),
                    12,
                    SimpleTheme.MUTED,
                    false));
            if (!ReminderScheduler.notificationsAllowed(this)) {
              card.addView(SimpleTheme.button(this, "允许日程提醒", this::allowNotifications));
            }
          }
        }
        block.addView(card);
      }
  }

  private String pendingStage() {
    try {
      String value = store.getValue("conversation.pending.v1");
      return value.isEmpty() ? "" : new JSONObject(value).optString("stage");
    } catch (Exception e) {
      return "request";
    }
  }

  private void allowNotifications() {
    if (android.os.Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && (store.getValue("notification_permission_requested").isEmpty()
            || shouldShowRequestPermissionRationale(
                android.Manifest.permission.POST_NOTIFICATIONS))) {
      store.putValue("notification_permission_requested", "1");
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 71);
    } else {
      startActivity(
          new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
              .putExtra("android.provider.extra.APP_PACKAGE", getPackageName()));
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 71) {
      ReminderScheduler.refresh(this);
      renderMessages(true);
    }
  }

  private void renderPending() {
    if (runtime.busy()) return;
    String stage = pendingStage();
    LinearLayout controls = SimpleTheme.row(this);
    if (stage.equals("confirmation")) {
      controls.addView(SimpleTheme.button(this, "确认操作", () -> runtime.submit("", 2)));
      controls.addView(SimpleTheme.button(this, "取消", () -> runtime.submit("", 3)));
    } else if (stage.equals("unknown")) {
      controls.addView(
          SimpleTheme.button(
              this,
              "核对 HITA 后继续",
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("请先核对 HITA")
                      .setMessage("上一次操作可能已执行。请打开 HITA 检查实际结果；清除待核对状态不会撤销已经发生的修改。")
                      .setNegativeButton("稍后", null)
                      .setPositiveButton("已核对，继续", (d, w) -> runtime.submit("", 3))
                      .show()));
    } else if (!stage.isEmpty()) {
      controls.addView(SimpleTheme.button(this, "重试这次操作", () -> runtime.submit("", 1)));
      controls.addView(SimpleTheme.button(this, "取消待处理", () -> runtime.submit("", 3)));
    } else if (!store.getValue("conversation.draft.v1").isEmpty()) {
      controls.addView(SimpleTheme.button(this, "结束当前追问", () -> runtime.submit("", 4)));
    }
    if (controls.getChildCount() > 0) {
      messages.addView(controls);
      SimpleTheme.gap(this, messages, 12);
    }
  }
}
