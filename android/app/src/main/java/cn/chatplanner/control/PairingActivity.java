package cn.chatplanner.control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** In-page scanner: permission/error handling never finishes the pairing screen. */
public final class PairingActivity extends Activity {
    private static final int CAMERA_PERMISSION=71;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button scan, paste;
    private DecoratedBarcodeView camera;
    private boolean busy, scanning;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);SimpleTheme.window(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(SimpleTheme.dp(this,24),SimpleTheme.dp(this,24),SimpleTheme.dp(this,24),SimpleTheme.dp(this,24));root.setBackgroundColor(SimpleTheme.PAPER);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(SimpleTheme.dp(this,24)+i.getSystemWindowInsetLeft(),SimpleTheme.dp(this,24)+i.getSystemWindowInsetTop(),SimpleTheme.dp(this,24)+i.getSystemWindowInsetRight(),SimpleTheme.dp(this,24)+i.getSystemWindowInsetBottom());return i;});
        status=new TextView(this);status.setTextColor(SimpleTheme.INK);status.setTextSize(17);status.setPadding(0,0,0,SimpleTheme.dp(this,20));status.setText("扫描电脑配对二维码，或粘贴配对链接。连接成功后会保存，以后自动连接。");root.addView(status);
        camera=new DecoratedBarcodeView(this);camera.setVisibility(View.GONE);camera.setStatusText("将电脑二维码放入取景框");
        camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        root.addView(camera,new LinearLayout.LayoutParams(-1,SimpleTheme.dp(this,280)));
        camera.getBarcodeView().addStateListener(new CameraPreview.StateListener(){
            public void previewSized(){}
            public void previewStarted(){if(scanning&&!busy)status.setText("相机已打开，请扫描电脑配对二维码。");}
            public void previewStopped(){}
            public void cameraClosed(){}
            public void cameraError(Exception error){cameraFailed();}
        });
        scan=new Button(this);scan.setText("扫描配对二维码");root.addView(scan);scan.setOnClickListener(v->{if(scanning)stopScan();else startScan();});
        paste=new Button(this);paste.setText("粘贴配对链接");root.addView(paste);
        paste.setOnClickListener(v->{
            stopScan();EditText input=new EditText(this);input.setSingleLine(true);input.setHint("chatplanner://pair…");
            new AlertDialog.Builder(this).setTitle("配对链接").setView(input).setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->accept(input.getText().toString())).show();
        });
        Button permissions=new Button(this);permissions.setText("打开相机权限设置");root.addView(permissions);
        permissions.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        SimpleTheme.buttons(this,root);scroll.addView(root);setContentView(scroll);
        if(state!=null&&state.getBoolean("scanning")&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startScan();
        if(state==null&&getIntent().getData()!=null){String value=getIntent().getDataString();getIntent().setData(null);accept(value);}
    }
    private void startScan(){
        if(busy)return;
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)){status.setText("设备未提供可用相机，请使用粘贴配对链接。");return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            status.setText("请允许相机权限后扫码；拒绝权限也可以粘贴链接连接。");requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);return;
        }
        scanning=true;scan.setText("停止扫描");camera.setVisibility(View.VISIBLE);status.setText("正在打开相机…");
        try{camera.decodeSingle(result->{stopScan();accept(result.getText());});camera.resume();}catch(RuntimeException error){cameraFailed();}
    }
    private void stopScan(){scanning=false;if(camera!=null){camera.pause();camera.setVisibility(View.GONE);}if(scan!=null)scan.setText("扫描配对二维码");}
    void cameraFailed(){stopScan();status.setText("相机无法启动。请检查相机权限、系统相机开关或其他应用是否占用相机，也可以粘贴配对链接。");}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==CAMERA_PERMISSION){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startScan();else status.setText("未获得相机权限。可点击“打开相机权限设置”允许相机，或直接粘贴配对链接。");}
    }
    @Override protected void onResume(){super.onResume();if(scanning&&camera!=null){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){try{camera.resume();}catch(RuntimeException e){cameraFailed();}}else cameraFailed();}}
    @Override protected void onPause(){if(camera!=null)camera.pause();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("scanning",scanning);}
    @Override public void onBackPressed(){if(scanning)stopScan();else super.onBackPressed();}

    static String[] parse(String value){
        Uri uri=Uri.parse(value.trim());
        if(!"chatplanner".equals(uri.getScheme())||!"pair".equals(uri.getHost()))throw new IllegalArgumentException();
        String server=uri.getQueryParameter("server"),code=uri.getFragment();
        if(server==null||code==null||!code.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();
        return new String[]{ApiClient.validateBase(server),code};
    }
    private void accept(String value){
        if(busy)return;
        try{String[] pair=parse(value);new AlertDialog.Builder(this).setTitle("连接这台电脑").setMessage(pair[0]+"\n连接成功后自动保存。HITA 是可选扩展。")
            .setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->claim(pair[0],pair[1])).show();
        }catch(Exception error){status.setText("这不是有效的对话计划配对链接，请在电脑重新生成并完整复制。");}
    }
    private void claim(String base,String code){
        stopScan();busy=true;scan.setEnabled(false);paste.setEnabled(false);status.setText("正在检查手机到电脑中转的连接…");
        io.execute(()->{
            boolean claiming=false;
            try{
                JSONObject health=ApiClient.request(base,"","GET","/healthz",null,45000);
                if(!"ok".equals(health.optString("status")))throw new ApiClient.InvalidResponseException();
                claiming=true;runOnUiThread(()->{if(!isDestroyed())status.setText("中转可达，正在领取配对凭据…");});
                JSONObject reply=ApiClient.request(base,"","POST","/v1/pairings/claim",new JSONObject().put("code",code).put("label","对话计划手机"),45000);
                String token=reply.optString("token","");if(!token.matches("[A-Za-z0-9_-]{24,256}"))throw new ApiClient.InvalidResponseException();
                try{new CredentialStore(this).save(base,token);}catch(Exception local){throw new SaveException();}
                runOnUiThread(()->{if(!isDestroyed()){startActivity(new Intent(this,MainActivity.class));finish();}});return;
            }catch(Exception error){
                String message=connectionError(error,claiming)+"\n检查地址："+base+"/healthz";
                runOnUiThread(()->{if(!isDestroyed()){busy=false;scan.setEnabled(true);paste.setEnabled(true);status.setText(message);}});
            }
        });
    }
    private static final class SaveException extends Exception{}
    static String connectionError(Exception error,boolean claiming){
        if(error instanceof SaveException)return "电脑已返回配对凭据，但手机保存失败。请重新生成二维码后重试；持续失败时检查手机系统存储与密钥库。";
        if(error instanceof ApiClient.ApiException){
            int code=((ApiClient.ApiException)error).status;
            if(code==410)return "二维码已过期或已领取，请在电脑生成新的配对码。";
            if(code==503)return "中转可达，但电脑未连接或正在忙。请在电脑启动服务，确认显示“固定中转已连接”后再试。";
            if(code==502||code==504)return "中转可达，但电脑后端没有正常响应（HTTP "+code+"）。请查看电脑服务状态并重试。";
            if(code==401||code==403)return "连接被服务拒绝（HTTP "+code+"）。请确认使用程序生成的中转地址，检查 Cloudflare 访问规则。";
            if(code>=300&&code<400)return "地址发生跳转。请使用电脑程序生成的固定 HTTPS 地址，不要使用控制台或预览地址。";
            if(code==404)return "服务地址不正确或电脑版本不兼容（HTTP 404）。请更新电脑端并重新生成链接。";
            return "配对服务返回 HTTP "+code+"，请检查电脑和中转状态。";
        }
        if(error instanceof ApiClient.InvalidResponseException)return "该地址没有返回本项目的有效数据。请确认不是 Hello World Worker、控制台或其他服务地址。";
        if(error instanceof java.net.UnknownHostException)return "手机无法解析中转域名。正常上网不代表 workers.dev 可达，请用手机浏览器检查下方地址，并检查 DNS 或网络。";
        if(error instanceof javax.net.ssl.SSLException)return "手机到中转的 HTTPS 连接失败。请检查手机时间、网络代理及证书设置，再用浏览器验证下方地址。";
        if(error instanceof java.net.SocketTimeoutException)return claiming?"领取配对凭据超时，结果未确认。请检查中转连接并在电脑重新生成配对码后重试。":"手机连接中转超时。请用手机浏览器检查下方地址；电脑和手机都需要能访问这个域名。";
        if(error instanceof java.io.IOException)return "手机未能连接这个中转地址。请用浏览器检查下方地址，切换 Wi-Fi／移动网络验证是否可达。";
        return "配对未完成，请核对程序生成的完整链接和电脑服务状态；已领取但未保存时需重新生成配对码。";
    }
    @Override protected void onDestroy(){if(camera!=null)camera.pause();io.shutdown();super.onDestroy();}
}
