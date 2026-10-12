package cn.chatplanner.control;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;

public class PairingInstrumentedTest extends InstrumentationTestCase {
    private boolean click(View v,String text) {
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()) && v.isClickable()){v.performClick();return true;}
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(click(((ViewGroup)v).getChildAt(i),text))return true;
        return false;
    }
    public void testPairingLinkPersistsCredentialAndRejectsMalformedLinks() throws Exception {
        final String base="http://10.0.2.2:8000";
        new CredentialStore(getInstrumentation().getTargetContext()).save(base,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        JSONObject ticket=ApiClient.request(base,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","POST","/v1/pairings",new JSONObject().put("label","instrumentation"));
        String link="chatplanner://pair?server="+Uri.encode(base)+"#"+ticket.getString("code");
        assertEquals(base,PairingActivity.parse(link)[0]);
        for(String bad:new String[]{"https://example.com/", "chatplanner://pair?server=https://x#short", "chatplanner://pair?server=http://evil.example#"+ticket.getString("code")}) {
            try {PairingActivity.parse(bad);fail("Invalid pairing URL accepted");}catch(IllegalArgumentException expected){}
        }
        Activity activity=getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),PairingActivity.class)
            .setData(Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(()->{
                for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(click(root,"连接"))return;
                fail("Pair confirmation missing");
            });
            CredentialStore store=new CredentialStore(getInstrumentation().getTargetContext());
            String token="";
            for(int i=0;i<100;i++) {
                token=store.token();
                if(base.equals(store.server()) && token.length()>=24 && !token.equals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))break;
                Thread.sleep(100);
            }
            assertFalse(token.isEmpty());assertFalse(token.equals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            assertEquals("0.7.0",ApiClient.request(base,token,"GET","/v1/status",null).getString("version"));
            assertEquals(token,new CredentialStore(getInstrumentation().getTargetContext()).token());
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
}
