package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** One-time model settings, optional HITA access and user-controlled backups. */
public final class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private AppSettings settings;
  private EditText key, model;
  private TextView status, keyStatus;
  private Button save, test;
  private File exportFile;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    SimpleTheme.window(this);
    settings = new AppSettings(this);
    LinearLayout root = SimpleTheme.column(this);
    root.setBackgroundColor(SimpleTheme.PAPER);
    setContentView(root);
    SimpleTheme.insets(this, root, 16);
    LinearLayout heading = SimpleTheme.row(this);
    heading.addView(SimpleTheme.button(this, "‹", this::finish));
    SimpleTheme.weight(heading, SimpleTheme.text(this, "设置", 22, SimpleTheme.INK, true));
    root.addView(heading);
    SimpleTheme.gap(this, root, 14);
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = SimpleTheme.column(this);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout ai = card(body, "DeepSeek");
    ai.addView(SimpleTheme.text(this, "填写一次，之后直接在首页对话。", 14, SimpleTheme.MUTED, false));
    SimpleTheme.gap(this, ai, 10);
    keyStatus = SimpleTheme.text(this, "", 12, SimpleTheme.BLUE, false);
    ai.addView(keyStatus);
    refreshKeyState();
    key = new EditText(this);
    key.setHint("API Key（已保存时留空即可）");
    key.setTextSize(14);
    key.setSingleLine(true);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    ai.addView(key);
    model = new EditText(this);
    model.setSingleLine(true);
    model.setTextSize(14);
    model.setHint("模型名称");
    model.setText(settings.model());
    ai.addView(model);
    SimpleTheme.gap(this, ai, 10);
    LinearLayout buttons = SimpleTheme.row(this);
    save = SimpleTheme.button(this, "保存设置", this::save);
    SimpleTheme.weight(buttons, save);
    test = SimpleTheme.button(this, "检查连接", this::checkConnection);
    SimpleTheme.weight(buttons, test);
    ai.addView(buttons);
    SimpleTheme.gap(this, ai, 8);
    ai.addView(
        SimpleTheme.button(
            this, "获取 API Key", () -> open("https://platform.deepseek.com/api_keys")));
    ai.addView(
        SimpleTheme.text(
            this, "AI 对话需要联网并按官方 API 用量计费。记录保存在手机，手动管理无需联网。", 12, SimpleTheme.MUTED, false));
    SimpleTheme.gap(this, ai, 8);
    ai.addView(
        SimpleTheme.button(
            this,
            "移除已保存的 Key",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("移除 API Key？")
                    .setMessage("记录会保留，可继续手动管理。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "移除",
                        (d, w) -> {
                          settings.setApiKey("");
                          refreshKeyState();
                        })
                    .show()));
    LinearLayout hita = card(body, "HITA · 可选扩展");
    hita.addView(
        SimpleTheme.text(
            this, "在这台手机安装兼容版，并授权需要编辑的课表，即可通过对话管理 HITA 日程。", 14, SimpleTheme.MUTED, false));
    SimpleTheme.gap(this, hita, 12);
    hita.addView(
        SimpleTheme.button(
            this,
            "授权 / 管理 HITA 访问",
            () -> {
              try {
                startActivity(new HitaClient(this).authorizationIntent());
              } catch (Exception ex) {
                new AlertDialog.Builder(this)
                    .setTitle("安装 HITA 兼容版")
                    .setMessage("原版 HITA 没有本项目的日程接口。需要此扩展时，请从项目 Releases 下载兼容版；不安装也可完整使用本地功能。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton(
                        "前往下载",
                        (d, w) -> open("https://github.com/linyubo0614/chat-to-plan/releases"))
                    .show();
              }
            }));
    LinearLayout data = card(body, "数据与提醒");
    data.addView(
        SimpleTheme.text(
            this, "备份包含任务与备忘，不包含 API Key。导入会补充缺失记录，保留手机上已有的编辑。", 14, SimpleTheme.MUTED, false));
    SimpleTheme.gap(this, data, 12);
    data.addView(SimpleTheme.button(this, "导出记录备份", this::export));
    SimpleTheme.gap(this, data, 6);
    data.addView(
        SimpleTheme.button(
            this,
            "导入备份 / 旧电脑版数据库",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 102);
            }));
    SimpleTheme.gap(this, data, 6);
    data.addView(
        SimpleTheme.button(
            this,
            "允许日程提醒",
            () -> {
              if (android.os.Build.VERSION.SDK_INT >= 33
                  && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                      != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(
                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 71);
              else {
                startActivity(
                    new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                        .putExtra("android.provider.extra.APP_PACKAGE", getPackageName()));
              }
            }));
    data.addView(
        SimpleTheme.text(
            this, "提醒由手机执行。全天事项按 09:00 计算；系统省电可能使通知延迟。", 12, SimpleTheme.MUTED, false));
    LinearLayout about = card(body, "随手记事");
    about.addView(SimpleTheme.text(this, "0.8.0 · 聊天入口，本地计划", 14, SimpleTheme.MUTED, false));
    SimpleTheme.gap(this, about, 8);
    about.addView(
        SimpleTheme.button(
            this, "使用说明 / 项目仓库", () -> open("https://github.com/linyubo0614/chat-to-plan")));
    status = SimpleTheme.text(this, "", 13, SimpleTheme.MUTED, false);
    body.addView(status);
    SimpleTheme.gap(this, body, 16);
  }

  private LinearLayout card(LinearLayout body, String title) {
    LinearLayout c = SimpleTheme.card(this);
    c.addView(SimpleTheme.text(this, title, 18, SimpleTheme.INK, true));
    SimpleTheme.gap(this, c, 10);
    body.addView(c);
    SimpleTheme.gap(this, body, 14);
    return c;
  }

  private void refreshKeyState() {
    try {
      keyStatus.setText(settings.apiKey().isEmpty() ? "尚未配置 API Key" : "API Key 已保存在手机");
    } catch (Exception ex) {
      keyStatus.setText("原 Key 无法读取，请重新填写。");
    }
  }

  private boolean save() {
    try {
      String value = key.getText().toString().trim();
      if (!value.isEmpty()) settings.setApiKey(value);
      settings.setModel(model.getText().toString().trim());
      key.setText("");
      refreshKeyState();
      status.setText("设置已保存");
      return true;
    } catch (Exception ex) {
      status.setText(ex.getMessage());
      return false;
    }
  }

  private void checkConnection() {
    if (!save()) return;
    save.setEnabled(false);
    test.setEnabled(false);
    status.setText("正在检查 DeepSeek 连接…");
    worker.execute(
        () -> {
          String message;
          try {
            new DeepSeekClient(settings).checkConnection();
            message = "DeepSeek 连接正常，可以回到首页对话。";
          } catch (Exception ex) {
            message = ex.getMessage() == null ? "连接失败，请检查网络后重试。" : ex.getMessage();
          }
          final String result = message;
          main.post(
              () -> {
                if (!isFinishing()) {
                  status.setText(result);
                  save.setEnabled(true);
                  test.setEnabled(true);
                }
              });
        });
  }

  private void export() {
    status.setText("正在准备备份…");
    worker.execute(
        () -> {
          try {
            String text = BackupManager.exportJson(this);
            File file = File.createTempFile("record-export-", ".json", getCacheDir());
            try (OutputStream out = new FileOutputStream(file)) {
              out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            main.post(
                () -> {
                  if (isFinishing() || isDestroyed()) {
                    file.delete();
                    return;
                  }
                  if (exportFile != null) exportFile.delete();
                  exportFile = file;
                  Intent i =
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .setType("application/json")
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .putExtra(
                              Intent.EXTRA_TITLE, "随手记事-" + java.time.LocalDate.now() + ".json");
                  startActivityForResult(i, 101);
                });
          } catch (Exception ex) {
            main.post(
                () -> {
                  if (!isFinishing()) status.setText(ex.getMessage());
                });
          }
        });
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    if (exportFile != null) b.putString("export_file", exportFile.getName());
  }

  @Override
  protected void onRestoreInstanceState(Bundle b) {
    super.onRestoreInstanceState(b);
    String name = b.getString("export_file");
    if (name != null && name.matches("record-export-[A-Za-z0-9-]+\\.json"))
      exportFile = new File(getCacheDir(), name);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 101 && request != 102) return;
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (request == 101 && exportFile != null) {
        exportFile.delete();
        exportFile = null;
      }
      return;
    }
    Uri uri = data.getData();
    status.setText(request == 101 ? "正在导出…" : "正在导入…");
    final File source = exportFile;
    worker.execute(
        () -> {
          String message;
          try {
            if (request == 101) {
              if (source == null || !source.isFile())
                throw new IllegalStateException("导出内容已失效，请重新导出。");
              try (OutputStream out = getContentResolver().openOutputStream(uri);
                  FileInputStream in = new FileInputStream(source)) {
                if (out == null) throw new IllegalStateException("无法打开目标文件。");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
              } finally {
                source.delete();
              }
              message = "记录备份已导出";
            } else {
              JSONObject summary = BackupManager.importUri(this, uri);
              message =
                  "导入完成：新增 "
                      + summary.optInt("imported")
                      + " 条，跳过 "
                      + summary.optInt("skipped")
                      + " 条。";
            }
          } catch (Exception ex) {
            message = ex.getMessage() == null ? "文件操作未完成。" : ex.getMessage();
          }
          final String completed = message;
          main.post(
              () -> {
                if (request == 101) exportFile = null;
                if (!isFinishing()) status.setText(completed);
              });
        });
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] p, int[] results) {
    super.onRequestPermissionsResult(request, p, results);
    if (request == 71) {
      ReminderScheduler.refresh(this);
      status.setText(
          results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
              ? "已允许提醒"
              : "提醒权限尚未开启，可稍后在系统设置中允许。");
    }
  }

  private void open(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception ex) {
      Toast.makeText(this, "未找到可打开链接的浏览器。", Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onDestroy() {
    worker.shutdown();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
