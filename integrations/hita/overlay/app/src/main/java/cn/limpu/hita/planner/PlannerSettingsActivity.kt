package cn.limpu.hita.planner

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import cn.limpu.hita.R

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
    }
}
