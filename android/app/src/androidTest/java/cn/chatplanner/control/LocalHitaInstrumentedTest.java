package cn.chatplanner.control;

import android.content.Context;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.util.UUID;

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
            assertEquals("authorization_required", client.query(new JSONObject()).getString("status"));
            // Reopening after revocation selects the default independent timetable.
            getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            context.startActivity(client.authorizationIntent());
            clickText("保存授权");
            JSONObject initial = client.query(new JSONObject());
            assertEquals("ok", initial.getString("status"));
            assertTrue(initial.getJSONArray("timetables").length() >= 1);
            String id = "instrumented-" + UUID.randomUUID();
            JSONObject action = new JSONObject().put("operation", "create").put("record_id", id).put("record", new JSONObject()
                .put("kind", "schedule").put("title", "本地接口测试日程").put("location", "A101")
                .put("starts_at", "2026-10-14T15:00:00+08:00").put("ends_at", "2026-10-14T16:00:00+08:00"));
            JSONObject created = client.apply(action);
            assertEquals("ok", created.getString("status")); assertTrue(created.getBoolean("written"));
            assertTrue(client.apply(action).getBoolean("duplicate"));
            JSONObject record = client.query(new JSONObject().put("record_id", id)).getJSONArray("records").getJSONObject(0);
            assertEquals("本地接口测试日程", record.getString("title"));
            JSONObject update = new JSONObject().put("operation", "update").put("record_id", id)
                .put("fingerprint", record.getString("fingerprint")).put("record", new JSONObject().put("title", "已修改测试日程"));
            assertEquals("ok", client.apply(update).getString("status"));
            assertEquals("conflict", client.apply(update).getString("status"));
            JSONObject latest = client.query(new JSONObject().put("record_id", id)).getJSONArray("records").getJSONObject(0);
            assertEquals("已修改测试日程", latest.getString("title"));
            assertEquals("ok", client.apply(new JSONObject().put("operation", "delete").put("record_id", id)
                .put("fingerprint", latest.getString("fingerprint"))).getString("status"));
            assertEquals(0, client.query(new JSONObject().put("record_id", id)).getJSONArray("records").length());
            context.startActivity(client.authorizationIntent()); clickText("撤销全部授权");
            assertEquals("authorization_required", client.query(new JSONObject()).getString("status"));
            Log.i("LocalHitaTest", "PASS: signature binding, consent, Room CRUD, conflict, and revocation.");
        } finally { client.close(); }
    }

    private void clickText(String label) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                    if (label.contentEquals(node.getText()) && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Thread.sleep(200); return;
                    }
                }
            }
            Thread.sleep(100);
        }
        fail("Authorization screen did not expose button: " + label);
    }
}
