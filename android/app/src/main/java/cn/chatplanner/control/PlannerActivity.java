package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline plans and memos, sharing the conversation's on-device database. */
public final class PlannerActivity extends Activity {
  private LocalStore store;
  private LinearLayout content;
  private String page = "tasks", query = "", category = "";
  private LocalDate selected;
  private YearMonth month = YearMonth.now();
  private boolean first = true;
  AlertDialog editor;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    SimpleTheme.window(this);
    store = LocalStore.get(this);
    page =
        getIntent().getStringExtra("page") == null ? "tasks" : getIntent().getStringExtra("page");
    String notificationId = getIntent().getStringExtra("record_id");
    if (notificationId != null) getIntent().putExtra("edit_id", notificationId);
    if (saved != null) {
      page = saved.getString("page", page);
      query = saved.getString("query", "");
      category = saved.getString("category", "");
      String day = saved.getString("selected");
      if (day != null) selected = LocalDate.parse(day);
      month = YearMonth.parse(saved.getString("month", month.toString()));
    }
    render();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("page", page);
    b.putString("query", query);
    b.putString("category", category);
    b.putString("selected", selected == null ? null : selected.toString());
    b.putString("month", month.toString());
  }

  @Override
  protected void onResume() {
    super.onResume();
    render();
    if (first) {
      first = false;
      String id = getIntent().getStringExtra("edit_id");
      if (id != null) {
        JSONObject r = store.getRecord(id);
        if (r != null) edit(r, -1);
      } else if (getIntent().getBooleanExtra("create", false)) edit(null, -1);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    page = intent.getStringExtra("page") == null ? "tasks" : intent.getStringExtra("page");
    render();
    String id = intent.getStringExtra("edit_id");
    if (id == null) id = intent.getStringExtra("record_id");
    if (id != null) {
      JSONObject r = store.getRecord(id);
      if (r != null) edit(r, -1);
    }
  }

  private int dp(int n) {
    return SimpleTheme.dp(this, n);
  }

  private Button button(String text, Runnable r) {
    return SimpleTheme.button(this, text, r);
  }

  private TextView text(String s, int size, int color, boolean bold) {
    return SimpleTheme.text(this, s, size, color, bold);
  }

  private void gap(LinearLayout p, int n) {
    SimpleTheme.gap(this, p, n);
  }

  private void render() {
    LinearLayout root = SimpleTheme.column(this);
    root.setBackgroundColor(SimpleTheme.PAPER);
    setContentView(root);
    SimpleTheme.insets(this, root, 16);
    LinearLayout heading = SimpleTheme.row(this);
    SimpleTheme.weight(
        heading, text(page.equals("memos") ? "备忘" : "计划", 24, SimpleTheme.INK, true));
    heading.addView(button("＋", () -> edit(null, -1)));
    heading.addView(button("设置", () -> startActivity(new Intent(this, MainActivity.class))));
    root.addView(heading);
    gap(root, 14);
    if (!page.equals("memos")) {
      LinearLayout tabs = SimpleTheme.row(this);
      String[] names = {"四象限", "DDL / 日期", "日历"}, modes = {"tasks", "dates", "calendar"};
      for (int i = 0; i < names.length; i++) {
        final String mode = modes[i];
        Button b =
            button(
                names[i],
                () -> {
                  page = mode;
                  render();
                });
        if (page.equals(mode)) {
          b.setBackground(SimpleTheme.shape(this, SimpleTheme.BLUE, 14));
          b.setTextColor(Color.WHITE);
        }
        SimpleTheme.weight(tabs, b);
        if (i < 2) tabs.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      }
      root.addView(tabs);
      gap(root, 12);
    }
    if (page.equals("memos")) {
      EditText search = new EditText(this);
      search.setHint("搜索备忘");
      search.setSingleLine(true);
      search.setTextSize(14);
      search.setBackground(SimpleTheme.shape(this, SimpleTheme.SOFT, 14));
      search.setPadding(dp(12), dp(8), dp(12), dp(8));
      search.setText(query);
      root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
      search.addTextChangedListener(
          new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            public void afterTextChanged(Editable e) {}

            public void onTextChanged(CharSequence s, int st, int b, int c) {
              query = s.toString();
              renderContent();
            }
          });
      gap(root, 10);
    }
    ScrollView scroll = new ScrollView(this);
    content = SimpleTheme.column(this);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    renderContent();
    SimpleTheme.navigation(this, root, page.equals("memos") ? "备忘" : "计划");
  }

  private List<JSONObject> list() {
    JSONArray records = store.records(page.equals("memos") ? "memo" : "todo");
    List<JSONObject> result = new ArrayList<>();
    for (int i = 0; i < records.length(); i++) {
      JSONObject r = records.optJSONObject(i);
      if (r != null) result.add(r);
    }
    return result;
  }

  private void renderContent() {
    if (content == null) return;
    content.removeAllViews();
    if (page.equals("memos")) renderMemos();
    else if (page.equals("calendar")) renderCalendar();
    else if (page.equals("dates")) renderDates();
    else renderTasks();
  }

  private void renderTasks() {
    LinearLayout filter = SimpleTheme.row(this);
    SimpleTheme.weight(
        filter,
        text(
            selected == null
                ? "所有任务"
                : selected.getMonthValue() + " 月 " + selected.getDayOfMonth() + " 日",
            18,
            SimpleTheme.INK,
            true));
    filter.addView(
        button(
            selected == null ? "今天" : "全部",
            () -> {
              selected = selected == null ? LocalDate.now() : null;
              renderContent();
            }));
    filter.addView(
        button(
            "选日期",
            () -> {
              page = "calendar";
              render();
            }));
    content.addView(filter);
    gap(content, 14);
    List<JSONObject> tasks = list();
    if (selected != null)
      tasks.removeIf(r -> !selected.toString().equals(r.optString("task_date")));
    int remaining = 0;
    for (JSONObject r : tasks) if (!r.optBoolean("completed")) remaining++;
    content.addView(text(remaining + " 项待完成", 13, SimpleTheme.MUTED, false));
    gap(content, 14);
    String[] names = {"优先处理", "安排时间", "快速处理", "从容推进"},
        sub = {"重要 · 紧急", "重要 · 不紧急", "不重要 · 紧急", "不重要 · 不紧急"};
    int[] colors = {0xfffff2f0, 0xfff0f3ff, 0xfffff8ec, 0xfff0f6f3};
    for (int i = 0; i < 4; i++) {
      final int quadrant = i;
      LinearLayout box = SimpleTheme.card(this);
      box.setBackground(SimpleTheme.shape(this, colors[i], 18));
      LinearLayout h = SimpleTheme.row(this);
      SimpleTheme.weight(h, text(names[i], 17, SimpleTheme.INK, true));
      h.addView(button("＋", () -> edit(null, quadrant)));
      box.addView(h);
      box.addView(text(sub[i], 12, SimpleTheme.MUTED, false));
      gap(box, 8);
      int count = 0;
      tasks.sort(Comparator.comparing(r -> r.optBoolean("completed")));
      for (JSONObject r : tasks) {
        int q = (r.optBoolean("important") ? 0 : 2) + (r.optBoolean("urgent") ? 0 : 1);
        if (q != i) continue;
        count++;
        LinearLayout line = SimpleTheme.row(this);
        CheckBox check = new CheckBox(this);
        check.setChecked(r.optBoolean("completed"));
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(SimpleTheme.BLUE));
        check.setContentDescription("完成：" + r.optString("title"));
        check.setOnCheckedChangeListener((v, on) -> complete(r, on));
        line.addView(check);
        LinearLayout entry = SimpleTheme.column(this);
        TextView title =
            text(
                r.optString("title"),
                15,
                r.optBoolean("completed") ? SimpleTheme.MUTED : SimpleTheme.INK,
                false);
        if (r.optBoolean("completed"))
          title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        entry.addView(title);
        entry.addView(text(describe(r), 11, SimpleTheme.MUTED, false));
        entry.setPadding(0, dp(8), 0, dp(8));
        entry.setOnClickListener(v -> edit(r, quadrant));
        SimpleTheme.weight(line, entry);
        box.addView(line);
      }
      if (count == 0) box.addView(text("暂无事项", 13, SimpleTheme.MUTED, false));
      content.addView(box);
      gap(content, 12);
    }
  }

  private void renderDates() {
    List<JSONObject> records = list();
    records.removeIf(
        r ->
            r.optString("record_type", "task").equals("task")
                || r.optString("record_type").equals("schedule"));
    records.sort(Comparator.comparing(r -> r.optString("task_date", "9999")));
    content.addView(text("重要的日子，提前安排。", 14, SimpleTheme.MUTED, false));
    gap(content, 14);
    if (records.isEmpty()) empty("还没有重要日期", "记下一个 DDL、考试或纪念日。");
    for (JSONObject r : records) {
      LinearLayout c = recordCard(r);
      String day = r.optString("task_date");
      if (!day.isEmpty())
        try {
          long delta = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(day));
          c.addView(
              text(
                  r.optBoolean("completed")
                      ? "已完成"
                      : delta == 0
                          ? "就是今天"
                          : delta > 0 ? "还有 " + delta + " 天" : "已过 " + (-delta) + " 天",
                  15,
                  delta < 0 && !r.optBoolean("completed") ? 0xffb85b58 : SimpleTheme.BLUE,
                  true));
        } catch (Exception ignored) {
        }
      content.addView(c);
      gap(content, 12);
    }
  }

  private void renderMemos() {
    List<JSONObject> records = list();
    LinkedHashSet<String> groups = new LinkedHashSet<>();
    for (JSONObject r : records) groups.add(r.optString("category", "其他"));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout cats = SimpleTheme.row(this);
    cats.addView(
        button(
            "全部",
            () -> {
              category = "";
              renderContent();
            }));
    for (String group : groups) {
      Button b =
          button(
              group,
              () -> {
                category = group;
                renderContent();
              });
      if (group.equals(category)) {
        b.setTextColor(Color.WHITE);
        b.setBackground(SimpleTheme.shape(this, SimpleTheme.BLUE, 14));
      }
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
      p.leftMargin = dp(6);
      cats.addView(b, p);
    }
    hs.addView(cats);
    content.addView(hs);
    gap(content, 14);
    records.removeIf(
        r ->
            (!category.isEmpty() && !category.equals(r.optString("category")))
                || !(r.optString("title") + " " + r.optString("body"))
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(query.toLowerCase(java.util.Locale.ROOT)));
    if (records.isEmpty()) empty("留住随手想到的事", "聊天记录会整理到这里，也可以手动添加。");
    for (JSONObject r : records) {
      content.addView(recordCard(r));
      gap(content, 12);
    }
  }

  private LinearLayout recordCard(JSONObject r) {
    LinearLayout c = SimpleTheme.card(this);
    c.addView(text(r.optString("title"), 18, SimpleTheme.INK, true));
    gap(c, 6);
    c.addView(text(describe(r), 12, SimpleTheme.MUTED, false));
    String body = r.optString("body");
    if (!body.isEmpty()) {
      gap(c, 10);
      TextView note = text(body, 15, SimpleTheme.INK, false);
      note.setMaxLines(5);
      c.addView(note);
    }
    gap(c, 10);
    c.addView(button("编辑", () -> edit(r, -1)));
    return c;
  }

  private void empty(String title, String help) {
    LinearLayout c = SimpleTheme.card(this);
    c.setPadding(dp(20), dp(32), dp(20), dp(32));
    c.addView(text(title, 20, SimpleTheme.INK, true));
    gap(c, 10);
    c.addView(text(help, 14, SimpleTheme.MUTED, false));
    gap(c, 18);
    c.addView(button("＋ 手动记录", () -> edit(null, -1)));
    content.addView(c);
  }

  private void renderCalendar() {
    LinearLayout card = SimpleTheme.card(this), h = SimpleTheme.row(this);
    h.addView(
        button(
            "‹",
            () -> {
              month = month.minusMonths(1);
              renderContent();
            }));
    TextView label =
        text(month.getYear() + " 年 " + month.getMonthValue() + " 月", 18, SimpleTheme.INK, true);
    label.setGravity(Gravity.CENTER);
    SimpleTheme.weight(h, label);
    h.addView(
        button(
            "›",
            () -> {
              month = month.plusMonths(1);
              renderContent();
            }));
    card.addView(h);
    gap(card, 14);
    LinearLayout weekdays = SimpleTheme.row(this);
    for (String s : new String[] {"一", "二", "三", "四", "五", "六", "日"}) {
      TextView t = text(s, 12, SimpleTheme.MUTED, false);
      t.setGravity(Gravity.CENTER);
      SimpleTheme.weight(weekdays, t);
    }
    card.addView(weekdays);
    gap(card, 8);
    List<JSONObject> all = list();
    int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
    for (int week = 0; week < (offset + month.lengthOfMonth() + 6) / 7; week++) {
      LinearLayout line = SimpleTheme.row(this);
      for (int j = 0; j < 7; j++) {
        int day = week * 7 + j - offset + 1;
        TextView cell = text("", 14, SimpleTheme.INK, false);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
        p.setMargins(dp(1), dp(2), dp(1), dp(2));
        line.addView(cell, p);
        if (day < 1 || day > month.lengthOfMonth()) continue;
        LocalDate date = month.atDay(day);
        boolean exists = false, important = false;
        for (JSONObject r : all)
          if (date.toString().equals(r.optString("task_date")) && !r.optBoolean("completed")) {
            exists = true;
            if (!r.optString("record_type", "task").equals("task")) important = true;
          }
        cell.setText(day + (important ? "\n◆" : exists ? "\n·" : ""));
        cell.setContentDescription(date + " 查看事项");
        if (date.equals(LocalDate.now())) {
          cell.setBackground(SimpleTheme.shape(this, SimpleTheme.BLUE, 12));
          cell.setTextColor(Color.WHITE);
        }
        cell.setOnClickListener(
            v -> {
              selected = date;
              page = "tasks";
              render();
            });
      }
      card.addView(line);
    }
    content.addView(card);
    gap(content, 14);
    content.addView(text("◆ 重要日期　· 有待完成事项", 12, SimpleTheme.MUTED, false));
  }

  static String typeLabel(JSONObject r) {
    if ("memo".equals(r.optString("kind"))) return "备忘";
    switch (r.optString("record_type", "task")) {
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

  static String describe(JSONObject r) {
    String type = typeLabel(r), group = r.optString("category", "其他");
    if (type.equals("备忘")) return type + " · " + group;
    String day = r.optString("task_date");
    String time = r.optString("due_at");
    if (time.isEmpty() || time.equals("null")) time = r.optString("starts_at");
    String formatted = "";
    if (!time.isEmpty() && !time.equals("null"))
      try {
        formatted =
            OffsetDateTime.parse(time)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
      } catch (Exception ignored) {
      }
    if (formatted.isEmpty() && !day.equals("null")) formatted = day;
    String priority =
        (r.optBoolean("important") ? "重要" : "不重要")
            + " · "
            + (r.optBoolean("urgent") ? "紧急" : "不紧急");
    return type
        + " · "
        + group
        + (formatted.isEmpty() ? "" : " · " + formatted)
        + (type.equals("日程") ? "" : "\n" + priority);
  }

  private EditText field(LinearLayout parent, String hint, String value, boolean multi) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(15);
    e.setSingleLine(!multi);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private void edit(JSONObject existing, int quadrant) {
    boolean memo =
        existing == null ? page.equals("memos") : existing.optString("kind").equals("memo");
    LinearLayout form = SimpleTheme.column(this);
    form.setPadding(dp(16), dp(6), dp(16), dp(10));
    ScrollView sv = new ScrollView(this);
    sv.addView(form);
    EditText title = field(form, "标题", existing == null ? "" : existing.optString("title"), false),
        body = field(form, "补充内容", existing == null ? "" : existing.optString("body"), true),
        group =
            field(
                form, "分类", existing == null ? "其他" : existing.optString("category", "其他"), false);
    String[] types = {"普通任务", "DDL / 截止日期", "考试", "重要日期", "日程"},
        values = {"task", "ddl", "exam", "important_date", "schedule"};
    Spinner type = new Spinner(this);
    type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types));
    int initial = page.equals("dates") ? 1 : 0;
    if (existing != null)
      for (int i = 0; i < values.length; i++)
        if (values[i].equals(existing.optString("record_type"))) initial = i;
    type.setSelection(initial);
    final boolean[] dated = {existing == null || !existing.isNull("task_date")};
    final LocalDate[]
        date =
            {
              existing != null && !existing.isNull("task_date")
                  ? LocalDate.parse(existing.optString("task_date"))
                  : selected == null ? LocalDate.now() : selected
            },
        endDate = {date[0]};
    final LocalTime[] time = {null}, end = {null};
    if (existing != null) {
      String t = existing.optString("due_at");
      if (t.isEmpty() || t.equals("null")) t = existing.optString("starts_at");
      if (!t.isEmpty() && !t.equals("null"))
        time[0] = OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime();
      String en = existing.optString("ends_at");
      if (!en.isEmpty() && !en.equals("null")) {
        java.time.ZonedDateTime parsed =
            OffsetDateTime.parse(en).atZoneSameInstant(ZoneId.systemDefault());
        end[0] = parsed.toLocalTime();
        endDate[0] = parsed.toLocalDate();
      }
    }
    Button day = button(dated[0] ? "日期：" + date[0] : "日期：未安排", () -> {}),
        endDay = button("结束日期：" + endDate[0], () -> {});
    day.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setItems(
                    new String[] {"设置日期", "不设日期（普通任务）"},
                    (d, w) -> {
                      if (w == 1) {
                        dated[0] = false;
                        time[0] = null;
                        day.setText("日期：未安排");
                      } else
                        new DatePickerDialog(
                                this,
                                (picker, y, m, n) -> {
                                  LocalDate old = date[0];
                                  date[0] = LocalDate.of(y, m + 1, n);
                                  if (endDate[0].equals(old)) {
                                    endDate[0] = date[0];
                                    endDay.setText("结束日期：" + endDate[0]);
                                  }
                                  dated[0] = true;
                                  day.setText("日期：" + date[0]);
                                },
                                date[0].getYear(),
                                date[0].getMonthValue() - 1,
                                date[0].getDayOfMonth())
                            .show();
                    })
                .show());
    endDay.setOnClickListener(
        v ->
            new DatePickerDialog(
                    this,
                    (d, y, m, n) -> {
                      endDate[0] = LocalDate.of(y, m + 1, n);
                      endDay.setText("结束日期：" + endDate[0]);
                    },
                    endDate[0].getYear(),
                    endDate[0].getMonthValue() - 1,
                    endDate[0].getDayOfMonth())
                .show());
    Button clock = button(time[0] == null ? "时间：全天" : "时间：" + time[0], () -> {});
    clock.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setItems(
                    new String[] {"设置时间", "全天"},
                    (d, w) -> {
                      if (w == 1) {
                        time[0] = null;
                        clock.setText("时间：全天");
                      } else
                        new TimePickerDialog(
                                this,
                                (x, hh, mm) -> {
                                  time[0] = LocalTime.of(hh, mm);
                                  clock.setText("时间：" + time[0]);
                                },
                                time[0] == null ? 9 : time[0].getHour(),
                                time[0] == null ? 0 : time[0].getMinute(),
                                true)
                            .show();
                    })
                .show());
    Button endClock = button(end[0] == null ? "日程结束时间：未设置" : "结束时间：" + end[0], () -> {});
    endClock.setOnClickListener(
        v ->
            new TimePickerDialog(
                    this,
                    (x, hh, mm) -> {
                      end[0] = LocalTime.of(hh, mm);
                      endClock.setText("结束时间：" + end[0]);
                    },
                    end[0] == null ? 10 : end[0].getHour(),
                    end[0] == null ? 0 : end[0].getMinute(),
                    true)
                .show());
    EditText location =
        field(form, "地点（选填）", existing == null ? "" : existing.optString("location"), false);
    CheckBox important = new CheckBox(this),
        urgent = new CheckBox(this),
        completed = new CheckBox(this);
    important.setText("重要");
    urgent.setText("紧急");
    completed.setText("已完成");
    important.setChecked(
        existing == null ? (quadrant == 0 || quadrant == 1) : existing.optBoolean("important"));
    urgent.setChecked(
        existing == null ? (quadrant == 0 || quadrant == 2) : existing.optBoolean("urgent"));
    completed.setChecked(existing != null && existing.optBoolean("completed"));
    Spinner reminder = new Spinner(this);
    List<String> reminderNames =
        new ArrayList<>(
            java.util.Arrays.asList("不提醒", "准点提醒", "提前 10 分钟", "提前 30 分钟", "提前 1 小时", "提前 1 天"));
    List<Integer> minutes = new ArrayList<>(java.util.Arrays.asList(null, 0, 10, 30, 60, 1440));
    int reminderIndex = 0;
    if (existing != null && !existing.isNull("reminder_minutes")) {
      int value = existing.optInt("reminder_minutes");
      reminderIndex = minutes.indexOf(value);
      if (reminderIndex < 0) {
        reminderIndex = minutes.size();
        minutes.add(value);
        reminderNames.add("提前 " + value + " 分钟（原设置）");
      }
    }
    reminder.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, reminderNames));
    reminder.setSelection(reminderIndex);
    if (!memo) {
      form.addView(type);
      gap(form, 8);
      form.addView(day);
      gap(form, 6);
      form.addView(clock);
      gap(form, 6);
      form.addView(endDay);
      gap(form, 6);
      form.addView(endClock);
      gap(form, 6);
      LinearLayout opts = SimpleTheme.row(this);
      SimpleTheme.weight(opts, important);
      SimpleTheme.weight(opts, urgent);
      form.addView(opts);
      form.addView(completed);
      form.addView(reminder);
      form.addView(text("全天事项按当天 09:00 提醒。系统省电可能使提醒延迟。", 12, SimpleTheme.MUTED, false));
      type.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}

            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              boolean schedule = values[pos].equals("schedule");
              endDay.setVisibility(schedule ? View.VISIBLE : View.GONE);
              endClock.setVisibility(schedule ? View.VISIBLE : View.GONE);
              location.setVisibility(schedule ? View.VISIBLE : View.GONE);
            }
          });
    } else location.setVisibility(View.GONE);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? (memo ? "写备忘" : "添加事项") : "编辑记录")
            .setView(sv)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .setNeutralButton(existing == null ? "" : "删除", null)
            .create();
    editor = dialog;
    dialog.setOnShowListener(
        x -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    try {
                      String name = title.getText().toString().trim();
                      if (name.isEmpty()) {
                        title.setError("请填写标题");
                        return;
                      }
                      String recordType = memo ? "task" : values[type.getSelectedItemPosition()];
                      JSONObject r =
                          new JSONObject()
                              .put("kind", memo ? "memo" : "todo")
                              .put("title", name)
                              .put("body", body.getText().toString())
                              .put(
                                  "category",
                                  group.getText().toString().trim().isEmpty()
                                      ? "其他"
                                      : group.getText().toString().trim())
                              .put("important", !memo && important.isChecked())
                              .put("urgent", !memo && urgent.isChecked())
                              .put("completed", !memo && completed.isChecked())
                              .put("record_type", recordType)
                              .put("location", memo ? "" : location.getText().toString().trim());
                      if (!memo) {
                        if (!dated[0] && !recordType.equals("task"))
                          throw new IllegalArgumentException("此事项需要明确日期。");
                        r.put("task_date", dated[0] ? date[0].toString() : JSONObject.NULL);
                        r.put(
                            "reminder_minutes",
                            minutes.get(reminder.getSelectedItemPosition()) == null
                                ? JSONObject.NULL
                                : minutes.get(reminder.getSelectedItemPosition()));
                        if (recordType.equals("schedule")) {
                          if (time[0] == null || end[0] == null)
                            throw new IllegalArgumentException("日程需要开始和结束时间。");
                          r.put(
                              "starts_at",
                              date[0]
                                  .atTime(time[0])
                                  .atZone(ZoneId.systemDefault())
                                  .toOffsetDateTime()
                                  .toString());
                          r.put(
                              "ends_at",
                              endDate[0]
                                  .atTime(end[0])
                                  .atZone(ZoneId.systemDefault())
                                  .toOffsetDateTime()
                                  .toString());
                        } else if (dated[0] && time[0] != null)
                          r.put(
                              "due_at",
                              date[0]
                                  .atTime(time[0])
                                  .atZone(ZoneId.systemDefault())
                                  .toOffsetDateTime()
                                  .toString());
                      }
                      if (existing == null) store.createRecord(r, "manual-" + UUID.randomUUID());
                      else
                        store.updateRecord(
                            existing.optString("id"), existing.optInt("revision"), r);
                      ReminderScheduler.refresh(this);
                      if (!memo && minutes.get(reminder.getSelectedItemPosition()) != null)
                        requestNotifications();
                      dialog.dismiss();
                      renderContent();
                    } catch (Exception ex) {
                      Toast.makeText(
                              this,
                              ex.getMessage() == null ? "保存失败，请核对内容。" : ex.getMessage(),
                              Toast.LENGTH_LONG)
                          .show();
                    }
                  });
          if (existing != null)
            dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(
                    v ->
                        new AlertDialog.Builder(this)
                            .setTitle("删除这条记录？")
                            .setMessage(existing.optString("title"))
                            .setNegativeButton("取消", null)
                            .setPositiveButton(
                                "删除",
                                (d, w) -> {
                                  try {
                                    store.deleteRecord(
                                        existing.optString("id"), existing.optInt("revision"));
                                    ReminderScheduler.refresh(this);
                                    dialog.dismiss();
                                    renderContent();
                                  } catch (Exception ex) {
                                    Toast.makeText(this, "记录已变化，请重新打开后操作。", Toast.LENGTH_LONG)
                                        .show();
                                  }
                                })
                            .show());
          else dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        });
    dialog.show();
  }

  private void requestNotifications() {
    if (android.os.Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 71);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] p, int[] results) {
    super.onRequestPermissionsResult(request, p, results);
    if (request == 71
        && results.length > 0
        && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
      ReminderScheduler.refresh(this);
  }

  private void complete(JSONObject existing, boolean completed) {
    try {
      JSONObject r = new JSONObject(existing.toString()).put("completed", completed);
      store.updateRecord(existing.optString("id"), existing.optInt("revision"), r);
      ReminderScheduler.refresh(this);
      renderContent();
    } catch (Exception ex) {
      Toast.makeText(this, "记录已变化，请重新打开后操作。", Toast.LENGTH_LONG).show();
      renderContent();
    }
  }
}
