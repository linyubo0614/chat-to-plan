package cn.limpu.hita.planner

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import cn.limpu.hita.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PlannerSettingsActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val scroll=ScrollView(this); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }; scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { v,insets ->
            v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets
        }
        fun label(id: Int)=TextView(this).apply { setText(id); setTextSize(16f); root.addView(this) }
        label(R.string.planner_title); label(R.string.planner_help)
        val config=PlannerConfig(this)
        val server=EditText(this).apply { setHint(R.string.planner_server); setText(config.server); setSingleLine(); root.addView(this) }
        val token=EditText(this).apply { setHint(R.string.planner_token); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; root.addView(this) }
        val enabled=Switch(this).apply { setText(R.string.planner_enabled); isChecked=config.enabled; root.addView(this) }
        val result=label(R.string.planner_unconfirmed)
        Button(this).apply {
            setText(R.string.planner_save); root.addView(this)
            setOnClickListener {
                try {
                    if (!enabled.isChecked) config.disable()
                    else {
                        val base=PlannerConfig.validateServer(server.text.toString())
                        val secret=token.text.toString().trim().ifBlank { if(base==config.server) config.token else "" }
                        config.save(base,secret,true)
                    }
                    token.setText(""); PlannerConfig.schedule(this@PlannerSettingsActivity)
                    if(config.enabled) {
                        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7421)
                        PlannerForegroundService.start(this@PlannerSettingsActivity)
                    } else PlannerForegroundService.stop(this@PlannerSettingsActivity)
                    result.setText(R.string.planner_saved)
                } catch(e: Exception) { result.setText(R.string.planner_invalid) }
            }
        }
        setContentView(scroll)
        val pairServer=intent.getStringExtra("planner_pair_server")
        val pairCode=intent.getStringExtra("planner_pair_code")
        intent.removeExtra("planner_pair_server");intent.removeExtra("planner_pair_code")
        if(pairServer!=null && pairCode!=null && state==null) {
            try {
                val base=PlannerConfig.validateServer(pairServer)
                require(pairCode.matches(Regex("[A-Za-z0-9_-]{43}")))
                AlertDialog.Builder(this).setTitle("连接对话计划电脑")
                    .setMessage("$base\n确认后保存连接并开启后台同步，以后无需填写地址和口令。")
                    .setNegativeButton("取消",null).setPositiveButton("连接") { _,_ ->
                        result.text="正在配对…"
                        Thread {
                            try {
                                val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
                                    .followRedirects(false).followSslRedirects(false).build()
                                val body=JSONObject().put("code",pairCode).put("label","HITA 手机连接").toString()
                                val request=Request.Builder().url("$base/v1/pairings/claim")
                                    .post(body.toRequestBody("application/json".toMediaType())).build()
                                val secret=client.newCall(request).execute().use { response ->
                                    check(response.isSuccessful)
                                    val raw=response.body?.string() ?: error("Empty response")
                                    check(raw.length<8192)
                                    JSONObject(raw).getString("token")
                                }
                                config.save(base,secret,true)
                                PlannerConfig.schedule(applicationContext)
                                runOnUiThread {
                                    if(!isDestroyed) {
                                        server.setText(base);token.setText("");enabled.isChecked=true
                                        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7421)
                                        PlannerForegroundService.start(this)
                                        result.text="配对成功，已保存连接并开启后台同步。"
                                    }
                                }
                            } catch(e: Exception) {
                                runOnUiThread { if(!isDestroyed) result.text="配对未完成。请回到对话计划，重新点击“一键配置 HITA 连接”。" }
                            }
                        }.start()
                    }.show()
            } catch(e: Exception) {result.text="配对链接无效，请从对话计划重新配置。"}
        }
    }
}
