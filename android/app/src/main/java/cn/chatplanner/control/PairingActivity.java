package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** QR contains only a short-lived ticket, never the existing device credential. */
public final class PairingActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button scan, paste;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32,40,32,32);
        root.setOnApplyWindowInsetsListener((v,i)->{ v.setPadding(32+i.getSystemWindowInsetLeft(),40+i.getSystemWindowInsetTop(),32+i.getSystemWindowInsetRight(),32+i.getSystemWindowInsetBottom());return i; });
        status = new TextView(this); status.setTextSize(18); status.setText("扫描电脑“手机配对”二维码。成功后会保存连接，以后打开自动连接。"); root.addView(status);
        scan = new Button(this); scan.setText("扫描配对二维码"); root.addView(scan);
        scan.setOnClickListener(v -> new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("扫描对话计划电脑端的配对二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan());
        paste = new Button(this); paste.setText("粘贴配对链接"); root.addView(paste);
        paste.setOnClickListener(v -> {
            EditText input = new EditText(this); input.setSingleLine(true); input.setHint("chatplanner://pair…");
            new AlertDialog.Builder(this).setTitle("配对链接").setView(input).setNegativeButton("取消",null)
                .setPositiveButton("连接",(d,w)->accept(input.getText().toString())).show();
        });
        setContentView(root);
        if (state == null && getIntent().getData() != null) { String value=getIntent().getDataString(); getIntent().setData(null); accept(value); }
    }

    static String[] parse(String value) {
        Uri uri = Uri.parse(value.trim());
        if (!"chatplanner".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) throw new IllegalArgumentException();
        String server=uri.getQueryParameter("server"), code=uri.getFragment();
        if (server==null || code==null || !code.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
        return new String[]{ApiClient.validateBase(server),code};
    }

    private void accept(String value) {
        if (busy) return;
        try {
            String[] pair=parse(value);
            new AlertDialog.Builder(this).setTitle("连接这台电脑")
                .setMessage(pair[0]+"\n连接成功后自动保存，可一键配置 HITA。")
                .setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->claim(pair[0],pair[1])).show();
        } catch(Exception e) { status.setText("这不是有效的对话计划配对码，请在电脑端重新生成。"); }
    }

    private void claim(String base, String code) {
        busy=true;scan.setEnabled(false);paste.setEnabled(false);status.setText("正在配对…");
        io.execute(()->{
            String error;
            try {
                JSONObject reply=ApiClient.request(base,"","POST","/v1/pairings/claim",new JSONObject().put("code",code).put("label","对话计划手机"));
                String token=reply.getString("token");
                if (token.length()<24) throw new IllegalStateException();
                new CredentialStore(this).save(base,token);
                runOnUiThread(()->{if(!isDestroyed()) {startActivity(new Intent(this,MainActivity.class));finish();}});
                return;
            } catch(ApiClient.ApiException e) {
                error=e.status==410 ? "二维码已过期或已使用，请在电脑端生成新的二维码。" : "电脑或中转暂时不可用，请启动电脑服务后重试。";
            } catch(Exception e) { error="配对未完成，请检查网络。若提示配对码已使用，请在电脑重新生成。"; }
            final String message=error;
            runOnUiThread(()->{if(!isDestroyed()){busy=false;scan.setEnabled(true);paste.setEnabled(true);status.setText(message);}});
        });
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        IntentResult result=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);
        if(result!=null) {if(result.getContents()!=null)accept(result.getContents());}
        else super.onActivityResult(requestCode,resultCode,data);
    }
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
