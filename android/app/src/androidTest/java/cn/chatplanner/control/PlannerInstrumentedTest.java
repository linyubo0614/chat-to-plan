package cn.chatplanner.control;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Activity + emulator networking + persistent HTTP backend. No fake UI. */
@android.annotation.TargetApi(35)
public class PlannerInstrumentedTest extends InstrumentationTestCase {
    private PlannerActivity activity;
    private static final String TOKEN="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private List<View> all(View root) {
        List<View> views=new ArrayList<>();views.add(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)views.addAll(all(((ViewGroup)root).getChildAt(i)));
        return views;
    }
    private View find(String label) {
        for(View v:all(activity.getWindow().getDecorView()))if(v instanceof TextView&&label.equals(((TextView)v).getText().toString()))return v;
        return null;
    }
    private void await(String label) throws Exception {
        long deadline=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<deadline) {
            final boolean[] found={false};getInstrumentation().runOnMainSync(()->found[0]=find(label)!=null);
            if(found[0])return;Thread.sleep(100);
        }fail("Missing UI: "+label);
    }
    private void click(String label) throws Exception {
        await(label);getInstrumentation().runOnMainSync(()->{
            View target=find(label);
            while(target!=null) {
                if(target.performClick())return;
                target=target.getParent() instanceof View?(View)target.getParent():null;
            }
            fail("No clickable card: "+label);
        });getInstrumentation().waitForIdleSync();
    }
    private JSONObject api(String path) throws Exception {return ApiClient.request("http://10.0.2.2:8000",TOKEN,"GET",path,null);}
    private void screenshot(String name) throws Exception {
        final Bitmap[] image={null};getInstrumentation().runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image[0]));
        });
        // UTP uninstalls the test app after execution. Shared test images survive
        // that cleanup and contain only synthetic CI records, never user data.
        android.content.ContentValues metadata=new android.content.ContentValues();
        metadata.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name+".png");
        metadata.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
        metadata.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ChatPlannerTest");
        android.net.Uri uri=activity.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,metadata);
        assertNotNull(uri);
        try(java.io.OutputStream out=activity.getContentResolver().openOutputStream(uri)){image[0].compress(Bitmap.CompressFormat.PNG,100,out);}image[0].recycle();
    }
    public void testWechatRecordsCalendarQuadrantsAndMemoEditing() throws Exception {
        CredentialStore credentials=new CredentialStore(getInstrumentation().getTargetContext());
        credentials.save("http://10.0.2.2:8000",TOKEN);
        activity=(PlannerActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),PlannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await("CI 微信待办");for(String label:new String[]{"优先处理","安排时间","快速处理","从容推进"})await(label);
            screenshot("calendar-quadrants");
            getInstrumentation().runOnMainSync(()->{
                for(View v:all(activity.getWindow().getDecorView()))if(v instanceof CheckBox&&"完成：CI 微信待办".contentEquals(v.getContentDescription())){v.performClick();return;}
                fail("Completion control missing");
            });
            boolean completed=false;
            for(int i=0;i<100&&!completed;i++){JSONArray records=api("/v1/planner/records?kind=todo").getJSONArray("records");for(int j=0;j<records.length();j++)if(records.getJSONObject(j).getString("title").equals("CI 微信待办"))completed=records.getJSONObject(j).getBoolean("completed");if(!completed)Thread.sleep(100);}
            assertTrue("Completion did not persist",completed);
            click("分类备忘");await("喜欢的茶");await("女朋友");screenshot("classified-memos");
            click("＋ 写备忘");
            getInstrumentation().waitForIdleSync();
            // The dialog window is the currently focused root.
            getInstrumentation().runOnMainSync(()->{
                View root=activity.getWindow().getDecorView().getRootView();
                // Use WindowInspector to obtain the actual dialog, not the underlying Activity.
                for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    List<EditText> inputs=new ArrayList<>();for(View v:all(window))if(v instanceof EditText)inputs.add((EditText)v);
                    if(inputs.size()==3) {
                        inputs.get(0).setText("英语积累");inputs.get(1).setText("practice makes progress");inputs.get(2).setText("英语");
                        for(View v:all(window))if(v instanceof TextView&&"保存".contentEquals(((TextView)v).getText())){v.performClick();return;}
                    }
                }fail("Memo editor missing");
            });
            await("英语积累");JSONArray notes=api("/v1/planner/records?kind=memo&category=英语").getJSONArray("records");
            assertEquals(1,notes.length());assertEquals("practice makes progress",notes.getJSONObject(0).getString("body"));
            click("英语积累");getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(()->{
                for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    List<EditText> inputs=new ArrayList<>();for(View v:all(window))if(v instanceof EditText)inputs.add((EditText)v);
                    if(inputs.size()==3){inputs.get(1).setText("edited from Android");for(View v:all(window))if(v instanceof TextView&&"保存".contentEquals(((TextView)v).getText())){v.performClick();return;}}
                }fail("Edit dialog missing");
            });
            await("edited from Android");assertEquals("edited from Android",api("/v1/planner/records?kind=memo&category=英语").getJSONArray("records").getJSONObject(0).getString("body"));
            // Reopening the Activity must read persisted data rather than a UI placeholder.
            getInstrumentation().runOnMainSync(()->activity.finish());getInstrumentation().waitForIdleSync();
            activity=(PlannerActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),PlannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click("分类备忘");await("英语积累");await("edited from Android");
        }finally {getInstrumentation().runOnMainSync(()->activity.finish());}
    }
}
