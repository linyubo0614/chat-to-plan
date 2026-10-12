package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.provider.CalendarContract;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** A daily workspace. All saves use the same store as the WeChat tools. */
public final class PlannerActivity extends Activity {
    private static final int INK=0xff243c3b, MUTED=0xff71817d, GREEN=0xff29796b, PAPER=0xfff5f6f2;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private CredentialStore credentials;
    private LinearLayout root, content;
    private ScrollView scroll;
    private TextView notice;
    private LocalDate selected=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private YearMonth month=YearMonth.from(selected);
    private boolean memos=false, alive=false, loading=false, editing=false, calendarExpanded=true, calendarPage=false, datesPage=false;
    private String category="", query="", signature="", activeBase="";
    private JSONArray records=new JSONArray(), days=new JSONArray(), dateMarkers=new JSONArray(), categories=new JSONArray();
    private int limit=50;
    private boolean more=false;
    private final Runnable poll=new Runnable() { public void run() {
        if (!alive) return;
        if (!editing) load(false);
        ui.postDelayed(this,3000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        credentials=new CredentialStore(this);
        activeBase=credentials.server();
        if (state!=null) {
            selected=LocalDate.parse(state.getString("day",selected.toString()));
            month=YearMonth.parse(state.getString("month",month.toString()));
            datesPage=state.getBoolean("datesPage"); calendarPage=state.getBoolean("calendarPage"); memos=state.getBoolean("memos");calendarExpanded=state.getBoolean("calendarExpanded"); category=state.getString("category",""); query=state.getString("query","");
        }
        render();
        HomeShortcut.offerOnce(this);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("day",selected.toString()); state.putString("month",month.toString());
        state.putBoolean("memos",memos); state.putString("category",category); state.putString("query",query);
        state.putBoolean("calendarExpanded",calendarExpanded);state.putBoolean("calendarPage",calendarPage);state.putBoolean("datesPage",datesPage);
    }
    @Override protected void onResume() { super.onResume(); alive=true; load(true); ui.postDelayed(poll,3000); }
    @Override protected void onPause() { alive=false; ui.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private void gap(LinearLayout parent,int size) { View v=new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(size))); }
    private void weight(LinearLayout parent,View v) { parent.addView(v,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1)); }
    private Button button(String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setTextSize(14); b.setTextColor(GREEN); b.setAllCaps(false);
        b.setPadding(dp(8),dp(6),dp(8),dp(6)); b.setElevation(0); b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44)); b.setBackground(shape(0xffe8efea,14));
        b.setOnClickListener(v->action.run()); return b;
    }
    private LinearLayout card(int color) {
        LinearLayout v=column(); v.setPadding(dp(16),dp(16),dp(16),dp(16)); v.setBackground(shape(color,18)); return v;
    }
    private TextView monthArrow(String label,Runnable action) {
        TextView arrow=text(label,24,GREEN,false);arrow.setGravity(Gravity.CENTER);arrow.setBackground(shape(0xffe8efea,12));
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(44),dp(44)));arrow.setOnClickListener(v->action.run());
        arrow.setContentDescription(label.equals("‹")?"上一月":"下一月");return arrow;
    }
    private void render() {
        final int scrollY=scroll==null?0:scroll.getScrollY();
        root=column(); root.setBackgroundColor(PAPER);
        root.setPadding(dp(16),dp(8),dp(16),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)-> {
            v.setPadding(dp(16),insets.getSystemWindowInsetTop()+dp(8),dp(16),insets.getSystemWindowInsetBottom()+dp(8)); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout heading=row(); weight(heading,text("对话计划",26,INK,true));
        heading.addView(button("设置",()->startActivity(new Intent(this,MainActivity.class)))); root.addView(heading);
        gap(root,12);
        LinearLayout tabs=row();
        String[] labels={"每日任务","日历","分类备忘","重要日期"};
        for(int i=0;i<labels.length;i++) {
            final int page=i; boolean active=datesPage?i==3:memos?i==2:calendarPage?i==1:i==0;
            Button tab=button(labels[i],()->switchPage(page));tab.setTextSize(12);tab.setPadding(dp(2),dp(6),dp(2),dp(6));
            tab.setMinWidth(0);tab.setMinimumWidth(0);
            tab.setBackground(shape(active?GREEN:0xffeceee8,14));tab.setTextColor(active?Color.WHITE:MUTED);
            weight(tabs,tab);if(i<3)tabs.addView(new View(this),new LinearLayout.LayoutParams(dp(6),1));
        } root.addView(tabs);
        gap(root,10); notice=text("正在连接…",12,MUTED,false); root.addView(notice); gap(root,10);
        scroll=new ScrollView(this); scroll.setFillViewport(true); content=column(); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (datesPage) renderDates(); else if (memos) renderMemos(); else if(calendarPage) renderCalendar(); else renderTasks();
        gap(root,10); LinearLayout bottom=row();
        weight(bottom,button(datesPage?"＋ 记录日期":memos?"＋ 写备忘":"＋ 添加任务",()->newRecord(-1)));
        bottom.addView(new View(this),new LinearLayout.LayoutParams(dp(10),1));
        bottom.addView(button("微信 / HITA",()->startActivity(new Intent(this,InboxActivity.class)))); root.addView(bottom);
        ScrollView renderedScroll=scroll;renderedScroll.post(()->renderedScroll.scrollTo(0,scrollY));
    }
    private void switchPage(int page) {
        if(scroll!=null)scroll.scrollTo(0,0);
        datesPage=page==3;memos=page==2;calendarPage=page==1;limit=50;records=new JSONArray();signature="";render();load(true);
    }
    private void renderCalendar() {
        LinearLayout calendar=card(Color.WHITE); LinearLayout title=row();
        title.addView(monthArrow("‹",()->moveMonth(-1))); TextView heading=text(month.getYear()+" 年 "+month.getMonthValue()+" 月 "+"",17,INK,true);
        heading.setGravity(Gravity.CENTER);heading.setPadding(0,dp(10),0,dp(10));
        weight(title,heading);title.addView(monthArrow("›",()->moveMonth(1)));calendar.addView(title);gap(calendar,8);
        LinearLayout weekdays=row(); for (String label:new String[]{"一","二","三","四","五","六","日"}) {
            TextView cell=text(label,12,MUTED,false); cell.setGravity(Gravity.CENTER); weight(weekdays,cell);
        } calendar.addView(weekdays); gap(calendar,4);
        int offset=month.atDay(1).getDayOfWeek().getValue()-1, n=month.lengthOfMonth();
        int firstWeek=0;
        int afterWeek=(offset+n+6)/7;
        for (int week=firstWeek;week<afterWeek;week++) {
            LinearLayout line=row();
            for (int weekday=0;weekday<7;weekday++) {
                int value=week*7+weekday-offset+1;
                TextView cell=text("",14,INK,false); cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(1),dp(1),dp(1),dp(1)); line.addView(cell,p);
                if (value>0&&value<=n) {
                    LocalDate date=month.atDay(value); int remaining=0;
                    for (int i=0;i<days.length();i++) { JSONObject d=days.optJSONObject(i); if (date.toString().equals(d.optString("day"))) remaining=d.optInt("total")-d.optInt("completed"); }
                    boolean marked=false;for(int j=0;j<dateMarkers.length();j++)if(date.toString().equals(dateMarkers.optJSONObject(j).optString("day")))marked=true;
                    cell.setText(value+(marked?"\n◆":remaining>0?"\n· "+remaining:""));
                    cell.setContentDescription(date+(marked?" 有重要日期":"")+(remaining>0?"，"+remaining+"项未完成":"")); cell.setTextSize(13);
                    if (date.equals(selected)) { cell.setBackground(shape(GREEN,13)); cell.setTextColor(Color.WHITE); }
                    else if (date.equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))) cell.setBackground(shape(0xffe8efea,13));
                    cell.setOnClickListener(v->{ selected=date;calendarPage=false; limit=50; records=new JSONArray(); signature=""; render(); load(true); });
                }
            } calendar.addView(line);
        } content.addView(calendar); gap(content,18);
        content.addView(text("◆ 重要日期　· 当天任务。点击日期查看。",14,MUTED,false));
    }
    private void renderTasks() {
        LinearLayout dateTitle=row(); weight(dateTitle,text(selected.getMonthValue()+" 月 "+selected.getDayOfMonth()+" 日",22,INK,true));
        dateTitle.addView(button("选日期",()->{switchPage(1);}));
        dateTitle.addView(button("今天",()->{selected=LocalDate.now(ZoneId.of("Asia/Shanghai"));month=YearMonth.from(selected);records=new JSONArray();signature="";render();load(true);}));
        content.addView(dateTitle); gap(content,12);
        int pending=0, special=0;for(int i=0;i<records.length();i++){JSONObject r=records.optJSONObject(i);if(!r.optBoolean("completed"))pending++;if(!r.optString("record_type","task").equals("task"))special++;}
        content.addView(text(pending+" 项待完成"+(special>0?"  ·  "+special+" 个重要日期":""),13,MUTED,false));gap(content,14);
        String[] names={"优先处理","安排时间","快速处理","从容推进"};
        String[] subtitles={"重要 · 紧急","重要 · 不紧急","不重要 · 紧急","不重要 · 不紧急"};
        int[] colors={0xfff5eae5,0xfff3efdf,0xffe8eef1,0xffe8efe5};
        for (int line=0;line<2;line++) {
            LinearLayout pair=row(); pair.setGravity(Gravity.TOP);
            for (int col=0;col<2;col++) {
                int quadrant=line*2+col; LinearLayout box=card(colors[quadrant]);box.setPadding(dp(12),dp(14),dp(12),dp(12));
                box.setMinimumHeight(dp(160)); box.addView(text(names[quadrant],16,INK,true)); gap(box,4); box.addView(text(subtitles[quadrant],11,MUTED,false)); gap(box,10);
                int count=0;
                for (int pass=0;pass<2;pass++) for (int i=0;i<records.length();i++) {
                    JSONObject task=records.optJSONObject(i); int q=(task.optBoolean("important")?0:2)+(task.optBoolean("urgent")?0:1);
                    if (q!=quadrant||task.optBoolean("completed")!=(pass==1)) continue;
                    count++; LinearLayout entry=row();
                    CheckBox checked=new CheckBox(this);checked.setMinWidth(dp(32));checked.setMinimumWidth(dp(32)); checked.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN)); checked.setChecked(task.optBoolean("completed"));
                    checked.setContentDescription("完成："+task.optString("title")); checked.setOnCheckedChangeListener((b,on)->complete(task,on)); entry.addView(checked);
                    TextView name=text(task.optString("record_type","task").equals("task")?task.optString("title"):typeLabel(task)+" · "+task.optString("title"),14,task.optBoolean("completed")?MUTED:INK,false);
                    if (task.optBoolean("completed")) name.setPaintFlags(name.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    name.setPadding(0,dp(8),0,dp(8)); name.setOnClickListener(v->edit(task,quadrant)); weight(entry,name); box.addView(entry);
                }
                if (count==0) box.addView(text("留一点空间给自己",12,MUTED,false));
                gap(box,8); box.addView(button("＋",()->newRecord(quadrant)));
                weight(pair,box); if (col==0) pair.addView(new View(this),new LinearLayout.LayoutParams(dp(10),1));
            } content.addView(pair); gap(content,10);
        }
        showMore();
    }
    private String typeLabel(JSONObject record) {
        switch(record.optString("record_type","task")){case "ddl":return "DDL";case "exam":return "考试";case "important_date":return "重要日期";default:return "任务";}
    }
    private LocalDate recordDate(JSONObject record) {
        return LocalDate.parse(record.optString("task_date"));
    }
    private String countdown(JSONObject record) {
        if(record.optBoolean("completed"))return "已完成";
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));long daysLeft=ChronoUnit.DAYS.between(today,recordDate(record));
        String due=record.optString("due_at","");
        if(!due.isEmpty()&&!due.equals("null")) {
            long minutes=ChronoUnit.MINUTES.between(java.time.Instant.now(),OffsetDateTime.parse(due).toInstant());
            if(OffsetDateTime.parse(due).toInstant().isBefore(java.time.Instant.now()))return record.optString("record_type").equals("ddl")?"已逾期":"已过时间";
            if(daysLeft==0)return minutes<60?"剩余 "+Math.max(1,minutes)+" 分钟":"剩余 "+(minutes/60)+" 小时";
        }
        if(daysLeft<0)return record.optString("record_type").equals("ddl")?"逾期 "+(-daysLeft)+" 天":"已过 "+(-daysLeft)+" 天";
        return daysLeft==0?"今天":daysLeft==1?"明天":"还有 "+daysLeft+" 天";
    }
    private void renderDates() {
        content.addView(text("把重要的日子，记在这里",20,INK,true));gap(content,6);
        content.addView(text("截止日期、考试和其他值得记住的日子",13,MUTED,false));gap(content,18);
        if(records.length()==0){LinearLayout empty=card(Color.WHITE);empty.addView(text("还没有重要日期",17,INK,true));gap(empty,8);empty.addView(text("点下方记录，也可以在微信或 QQ 告诉助手。",13,MUTED,false));content.addView(empty);}
        for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);LinearLayout item=card(Color.WHITE);LinearLayout top=row();
            weight(top,text(typeLabel(record)+" · "+record.optString("category"),12,GREEN,true));
            String count=countdown(record);boolean overdue=count.startsWith("逾期")||count.equals("已逾期");
            TextView badge=text(count,12,overdue?0xffaa5548:GREEN,true);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(shape(overdue?0xfff9ede7:0xffedf3ee,10));top.addView(badge);item.addView(top);gap(item,12);
            item.addView(text(record.optString("title"),19,record.optBoolean("completed")?MUTED:INK,true));gap(item,8);
            String date=recordDate(record).toString();String due=record.optString("due_at","");if(!due.isEmpty()&&!due.equals("null"))date+="  "+OffsetDateTime.parse(due).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalTime().toString();
            item.addView(text(date,13,MUTED,false));String body=record.optString("body");if(!body.isEmpty()){gap(item,8);TextView preview=text(body,13,MUTED,false);preview.setMaxLines(2);item.addView(preview);}
            gap(item,12);LinearLayout actions=row();weight(actions,button("编辑",()->edit(record,-1)));actions.addView(new View(this),new LinearLayout.LayoutParams(dp(8),1));weight(actions,button("加入日历提醒",()->calendarReminder(record)));item.addView(actions);
            content.addView(item);gap(content,12);
        }showMore();
    }
    private void calendarReminder(JSONObject record) {
        try {
            String due=record.optString("due_at","");boolean timed=!due.isEmpty()&&!due.equals("null");
            long start=timed?OffsetDateTime.parse(due).toInstant().toEpochMilli():recordDate(record).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            Intent intent=new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE,typeLabel(record)+" · "+record.optString("title"))
                .putExtra(CalendarContract.Events.DESCRIPTION,record.optString("body"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,start+(timed?3600000:86400000))
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,!timed);
            startActivity(intent);Toast.makeText(this,"请在日历中选择提醒时间并保存",Toast.LENGTH_LONG).show();
        }catch(android.content.ActivityNotFoundException ex){Toast.makeText(this,"请先安装支持添加日程的日历应用",Toast.LENGTH_LONG).show();}
    }
    private void moveMonth(int step) { month=month.plusMonths(step); selected=month.atDay(Math.min(selected.getDayOfMonth(),month.lengthOfMonth()));records=new JSONArray();signature="";render();load(true); }
    private void renderMemos() {
        LinearLayout filters=row();
        weight(filters,button(category.isEmpty()?"全部分类 ▾":category+" ▾",()->{
            String[] choices=new String[categories.length()+1]; choices[0]="全部分类";
            for (int i=0;i<categories.length();i++) choices[i+1]=categories.optJSONObject(i).optString("category");
            new AlertDialog.Builder(this).setTitle("选择分类").setItems(choices,(d,w)->{category=w==0?"":choices[w];limit=50;signature="";render();load(true);}).show();
        })); filters.addView(new View(this),new LinearLayout.LayoutParams(dp(8),1));
        filters.addView(button(query.isEmpty()?"搜索":"搜索中 · 清除",()->{
            if (!query.isEmpty()) {query="";signature="";render();load(true);return;}
            EditText input=new EditText(this);input.setHint("标题或内容");
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
            new AlertDialog.Builder(this).setTitle("搜索备忘").setView(input).setNegativeButton("取消",null).setPositiveButton("搜索",(d,w)->{query=input.getText().toString().trim();limit=50;signature="";render();load(true);}).show();
        })); content.addView(filters); gap(content,14);
        if (records.length()==0) { LinearLayout empty=card(Color.WHITE); empty.addView(text("这里等着你的第一条备忘",17,INK,true)); gap(empty,8);empty.addView(text("在微信说“帮我记下…”，也可以点击下方写备忘。",13,MUTED,false));content.addView(empty); }
        for (int i=0;i<records.length();i++) {
            JSONObject memo=records.optJSONObject(i);LinearLayout note=card(Color.WHITE);
            note.addView(text(memo.optString("category"),11,GREEN,true));gap(note,8);note.addView(text(memo.optString("title"),18,INK,true));
            String body=memo.optString("body");if (!body.isEmpty()){gap(note,8);TextView preview=text(body,14,MUTED,false);preview.setMaxLines(4);note.addView(preview);}
            note.setOnClickListener(v->edit(memo,-1));content.addView(note);gap(content,10);
        } showMore();
    }
    private void showMore() { if (more) content.addView(button("加载更多",()->{limit+=50;signature="";load(true);})); }
    private String scope() {return credentials.server()+":planner-create";}
    private void newRecord(int quadrant) {
        if(credentials.server().isEmpty()) {
            Toast.makeText(this,"请先连接电脑服务，再开始记录",Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this,MainActivity.class));return;
        }
        try {
            String pending=credentials.pendingMessage(scope());
            if (!pending.isEmpty()) {
                JSONObject payload=new JSONObject(pending);
                new AlertDialog.Builder(this).setTitle("上次保存尚未确认").setMessage("先核对“"+payload.optString("title")+"”，重试会复用原编号，避免重复。")
                    .setNegativeButton("稍后",null).setPositiveButton("核对 / 重试",(d,w)->write(null,payload)).show();return;
            }
            edit(null,quadrant);
        } catch (Exception ex) { error(ex); }
    }
    private EditText field(LinearLayout form,String label,String value,boolean multiline) {
        gap(form,8);form.addView(text(label,12,MUTED,false));EditText v=new EditText(this);v.setText(value);v.setTextSize(16);
        v.setSingleLine(!multiline);if(multiline){v.setMinLines(4);v.setGravity(Gravity.TOP);}form.addView(v);return v;
    }
    private void edit(JSONObject existing,int quadrant) {
        editing=true;String kind=existing==null?(memos?"memo":"todo"):existing.optString("kind");
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        EditText title=field(form,"标题",existing==null?"":existing.optString("title"),false);
        title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        final LocalDate[] date={existing!=null&&!existing.isNull("task_date")?LocalDate.parse(existing.optString("task_date")):selected};
        Spinner type=new Spinner(this);String[] types={"普通任务","DDL / 截止日期","考试","重要日期"};String[] typeValues={"task","ddl","exam","important_date"};
        type.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,types));
        int initial=datesPage?1:0;if(existing!=null){for(int i=0;i<typeValues.length;i++)if(typeValues[i].equals(existing.optString("record_type","task")))initial=i;}type.setSelection(initial);
        final LocalTime[] time={null};if(existing!=null&&!existing.isNull("due_at"))time[0]=OffsetDateTime.parse(existing.optString("due_at")).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalTime();
        CheckBox important=new CheckBox(this),urgent=new CheckBox(this),completed=new CheckBox(this);
        important.setText("重要");urgent.setText("紧急");completed.setText("已完成");
        if (kind.equals("todo")) {
            gap(form,12);form.addView(text("记录类型",12,MUTED,false));form.addView(type);gap(form,8);Button day=button("日期："+date[0],()->{});
            day.setOnClickListener(v->new DatePickerDialog(this,(p,y,m,d)->{date[0]=LocalDate.of(y,m+1,d);day.setText("日期："+date[0]);},date[0].getYear(),date[0].getMonthValue()-1,date[0].getDayOfMonth()).show());form.addView(day);
            gap(form,8);Button clock=button(time[0]==null?"全天 · 设置具体时间":"时间："+time[0],()->{});
            clock.setOnClickListener(v->new TimePickerDialog(this,(picker,h,m)->{time[0]=LocalTime.of(h,m);clock.setText("时间："+time[0]);},time[0]==null?9:time[0].getHour(),time[0]==null?0:time[0].getMinute(),true).show());form.addView(clock);
            form.addView(button("清除时间 · 改为全天",()->{time[0]=null;clock.setText("全天 · 设置具体时间");}));
            important.setChecked(existing==null?(datesPage||quadrant==0||quadrant==1):existing.optBoolean("important"));
            urgent.setChecked(existing==null?(quadrant==0||quadrant==2):existing.optBoolean("urgent"));
            completed.setChecked(existing!=null&&existing.optBoolean("completed"));
            LinearLayout options=row();weight(options,important);weight(options,urgent);form.addView(options);form.addView(completed);
        }
        EditText body=field(form,"内容",existing==null?"":existing.optString("body"),true);
        body.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8000)});
        EditText group=field(form,"分类 · 可以直接填写新分类",existing==null?"其他":existing.optString("category"),false);
        group.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        group.setHint("女朋友 / 高数 / 英语");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?(kind.equals("memo")?"新备忘":datesPage?"记录重要日期":"新任务"):"编辑")
            .setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).setNeutralButton(existing==null?null:"删除",null).create();
        dialog.setOnDismissListener(d->editing=false);dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(title.getText().toString().trim().isEmpty()||group.getText().toString().trim().isEmpty()){title.setError("请填写标题和分类");return;}
                try {
                    JSONObject payload=new JSONObject().put("kind",kind).put("title",title.getText().toString().trim()).put("body",body.getText().toString())
                        .put("category",group.getText().toString().trim()).put("important",important.isChecked()).put("urgent",urgent.isChecked())
                        .put("completed",completed.isChecked()).put("task_date",kind.equals("todo")?date[0].toString():JSONObject.NULL)
                        .put("record_type",kind.equals("todo")?typeValues[type.getSelectedItemPosition()]:"task")
                        .put("due_at",kind.equals("todo")&&time[0]!=null?date[0].atTime(time[0]).atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString():JSONObject.NULL);
                    if (existing==null) {payload.put("record_id",UUID.randomUUID().toString().replace("-",""));credentials.savePending(scope(),payload.toString());}
                    else payload.put("revision",existing.getInt("revision"));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.dismiss();write(existing,payload);
                } catch(Exception ex){error(ex);}
            });
            if(existing!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("删除这条记录？")
                .setMessage("仅移除所选任务或备忘。").setNegativeButton("取消",null).setPositiveButton("删除",(a,w)->{dialog.dismiss();remove(existing);}).show());
        });dialog.show();
    }
    private JSONObject patch(JSONObject record) throws Exception {
        JSONObject p=new JSONObject();for(String key:new String[]{"kind","title","body","category","important","urgent","completed","task_date","record_type","due_at","revision"})p.put(key,record.opt(key));return p;
    }
    private void complete(JSONObject record,boolean value) {try {write(record,patch(record).put("completed",value));}catch(Exception ex){error(ex);} }
    private void write(JSONObject existing,JSONObject payload) {
        String base=credentials.server();String draftScope=scope();notice.setText("正在保存…");
        io.execute(()->{try {
            JSONObject response=ApiClient.request(base,credentials.token(),existing==null?"POST":"PUT","/v1/planner/records"+(existing==null?"":"/"+existing.optString("id")),payload);
            if(existing==null)credentials.clearPending(draftScope);
            ui.post(()->{if(!isFinishing()){Toast.makeText(this,response.optBoolean("deleted")?"原记录已删除，未重新创建":"已保存",Toast.LENGTH_SHORT).show();signature="";load(true);}});
        }catch(Exception ex){ui.post(()->{error(ex);if(existing!=null)load(true);});}});
    }
    private void remove(JSONObject record) {
        String base=credentials.server();io.execute(()->{try {
            ApiClient.request(base,credentials.token(),"DELETE","/v1/planner/records/"+record.optString("id")+"?revision="+record.optInt("revision"),null);
            ui.post(()->{signature="";load(true);});
        }catch(Exception ex){ui.post(()->error(ex));}});
    }
    private String encode(String value) {try {return URLEncoder.encode(value,"UTF-8");} catch(java.io.UnsupportedEncodingException ex){throw new IllegalStateException(ex);}}
    private void load(boolean force) {
        if (loading||isFinishing())return;
        String base=credentials.server();
        if(!base.equals(activeBase)){activeBase=base;records=new JSONArray();days=new JSONArray();categories=new JSONArray();category="";query="";signature="";render();}
        if(base.isEmpty()){notice.setText("先在设置中连接电脑服务，再开始记录。");return;}
        loading=true;boolean dateTab=datesPage;boolean tab=memos;String group=category,search=query;LocalDate day=selected;YearMonth shown=month;int requested=limit;
        String path="/v1/planner/records?kind="+(tab?"memo":"todo")+"&limit="+Math.min(requested,200)+
            (dateTab?"&dates_only=true":tab?(group.isEmpty()?"":"&category="+encode(group))+"&q="+encode(search):"&day="+day);
        io.execute(()->{try {
            String token=credentials.token();JSONObject data=ApiClient.request(base,token,"GET",path,null);
            JSONArray accumulated=data.getJSONArray("records");
            while(!data.isNull("next_cursor")&&accumulated.length()<requested) {
                JSONObject page=ApiClient.request(base,token,"GET",path+"&before="+data.getInt("next_cursor"),null);
                JSONArray batch=page.getJSONArray("records");for(int i=0;i<batch.length();i++)accumulated.put(batch.get(i));
                data.put("next_cursor",page.opt("next_cursor"));
            }
            data.put("records",accumulated);
            JSONObject extra=ApiClient.request(base,token,"GET",tab?"/v1/planner/categories?kind=memo":"/v1/planner/calendar?month="+shown,null);
            JSONObject markers=tab||dateTab?new JSONObject():ApiClient.request(base,token,"GET","/v1/planner/date-markers?month="+shown,null);
            String next=data.toString()+extra+markers+(dateTab?java.time.Instant.now().getEpochSecond()/60:0);ui.post(()->{
                loading=false;if(!alive||dateTab!=datesPage||tab!=memos||!group.equals(category)||!search.equals(query)||!day.equals(selected)||!shown.equals(month)||!base.equals(credentials.server()))return;
                records=data.optJSONArray("records");more=!data.isNull("next_cursor");
                if(tab)categories=extra.optJSONArray("categories");else days=extra.optJSONArray("days");dateMarkers=markers.optJSONArray("days");if(dateMarkers==null)dateMarkers=new JSONArray();
                if(!editing&&(force||!next.equals(signature))){signature=next;render();}
                notice.setText("电脑服务已连接");
            });
        }catch(Exception ex){ui.post(()->{loading=false;if(alive)error(ex);});}});
    }
    private void error(Exception ex) {
        if(isFinishing())return;String message="连接失败，检查电脑服务及网络。新建保存未确认时，请点添加按钮核对重试。";
        if(ex instanceof ApiClient.ApiException){int code=((ApiClient.ApiException)ex).status;
            if(code==409)message="记录已经变化，请刷新后再编辑，避免覆盖其他修改。";
            else if(code==401||code==403)message="连接口令无效，请在设置中重新连接。";
            else if(code==422)message="内容格式不正确，请核对标题、分类和日期。";
            else if(code==404)message="记录已删除，或电脑端需要更新。";
        }notice.setText(message);
    }
}
