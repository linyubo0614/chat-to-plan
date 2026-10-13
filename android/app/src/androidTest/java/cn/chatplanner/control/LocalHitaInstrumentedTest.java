package cn.chatplanner.control;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.UUID;
import org.json.JSONObject;

/** Actual two-APK Binder, user authorization UI, and persistent HITA Room integration. */
public final class LocalHitaInstrumentedTest extends InstrumentationTestCase {
  public void testSignedBindingAuthorizationAndCrud() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    HitaClient client = new HitaClient(context);
    Bundle arguments = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
    boolean requireHita = "true".equals(arguments.getString("requireHita", "false"));
    if (!client.isAvailable()) {
      client.close();
      if (requireHita) fail("HITA integration job requires the compatible APK to be installed.");
      Log.i("LocalHitaTest", "SKIP: optional HITA APK is absent.");
      return;
    }
    try {
      context.startActivity(client.authorizationIntent());
      clickText("撤销全部授权");
      waitForText("已撤销；随手记事无法再读取或修改 HITA 日程。");
      assertEquals("authorization_required", client.query(new JSONObject()).getString("status"));
      // Revocation clears the choices on this screen; explicitly give consent again.
      checkTimetable("随手记事独立课表");
      clickText("保存授权");
      waitForText("已保存授权，返回随手记事即可使用。");
      JSONObject initial = client.query(new JSONObject());
      assertEquals("ok", initial.getString("status"));
      assertTrue(initial.getJSONArray("timetables").length() >= 1);
      String id = "instrumented-" + UUID.randomUUID();
      JSONObject action =
          new JSONObject()
              .put("operation", "create")
              .put("record_id", id)
              .put(
                  "record",
                  new JSONObject()
                      .put("kind", "schedule")
                      .put("title", "本地接口测试日程")
                      .put("location", "A101")
                      .put("starts_at", "2026-10-14T15:00:00+08:00")
                      .put("ends_at", "2026-10-14T16:00:00+08:00"));
      JSONObject created = client.apply(action);
      assertEquals("ok", created.getString("status"));
      assertTrue(created.getBoolean("written"));
      assertTrue(client.apply(action).getBoolean("duplicate"));
      JSONObject record =
          client
              .query(new JSONObject().put("record_id", id))
              .getJSONArray("records")
              .getJSONObject(0);
      assertEquals("本地接口测试日程", record.getString("title"));
      JSONObject update =
          new JSONObject()
              .put("operation", "update")
              .put("record_id", id)
              .put("fingerprint", record.getString("fingerprint"))
              .put("record", new JSONObject().put("title", "已修改测试日程"));
      assertEquals("ok", client.apply(update).getString("status"));
      assertEquals("conflict", client.apply(update).getString("status"));
      JSONObject latest =
          client
              .query(new JSONObject().put("record_id", id))
              .getJSONArray("records")
              .getJSONObject(0);
      assertEquals("已修改测试日程", latest.getString("title"));
      assertEquals(
          "ok",
          client
              .apply(
                  new JSONObject()
                      .put("operation", "delete")
                      .put("record_id", id)
                      .put("fingerprint", latest.getString("fingerprint")))
              .getString("status"));
      assertEquals(
          0, client.query(new JSONObject().put("record_id", id)).getJSONArray("records").length());
      clickText("撤销全部授权");
      waitForText("已撤销；随手记事无法再读取或修改 HITA 日程。");
      assertEquals("authorization_required", client.query(new JSONObject()).getString("status"));
      Log.i(
          "LocalHitaTest",
          "PASS: signature binding, consent, Room CRUD, conflict, and revocation.");
    } finally {
      client.close();
    }
  }

  private void clickText(String label) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10000;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo node = findText(label);
      if (node != null)
        try {
          if (node.isEnabled()
              && node.isClickable()
              && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        } finally {
          node.recycle();
        }
      Thread.sleep(100);
    }
    fail("Authorization screen did not expose an enabled, clickable button: " + label);
  }

  private void checkTimetable(String label) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10000;
    boolean clicked = false;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo node = findText(label);
      if (node != null)
        try {
          if (node.isCheckable() && node.isEnabled()) {
            if (node.isChecked()) return;
            if (!clicked && node.isClickable())
              clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
          }
        } finally {
          node.recycle();
        }
      Thread.sleep(100);
    }
    fail("Authorization screen did not select the timetable: " + label);
  }

  private void waitForText(String label) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10000;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo node = findText(label);
      if (node != null) {
        node.recycle();
        return;
      }
      Thread.sleep(100);
    }
    fail("Authorization screen did not confirm the operation: " + label);
  }

  private AccessibilityNodeInfo findText(String label) {
    AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
    if (root == null) return null;
    AccessibilityNodeInfo match = null;
    try {
      for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
        if (match == null
            && node.getPackageName() != null
            && HitaClient.PACKAGE.contentEquals(node.getPackageName())
            && node.getText() != null
            && label.contentEquals(node.getText())
            && node.isVisibleToUser()) match = node;
        else node.recycle();
      }
    } finally {
      root.recycle();
    }
    return match;
  }
}
