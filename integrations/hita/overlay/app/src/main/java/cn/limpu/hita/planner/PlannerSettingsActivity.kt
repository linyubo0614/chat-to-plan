package cn.limpu.hita.planner

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.limpu.hita.data.AppDatabase

/** One-time, revocable consent for this phone; no URL, token, or network. */
class PlannerSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
        TextView(this).apply { text = "随手记事本地授权"; textSize = 23f; root.addView(this) }
        TextView(this).apply { text = "选择允许随手记事查询、添加、修改和删除日程的课表。授权只在本机生效，可随时撤销。"; textSize = 16f; setPadding(0, 20, 0, 20); root.addView(this) }
        val access = PlannerAccess(this); val existing = access.tables()
        val choices = linkedMapOf<String, CheckBox>()
        choices[PlannerAccess.defaultTableId] = CheckBox(this).apply {
            text = "随手记事独立课表"; isChecked = PlannerAccess.defaultTableId in existing || existing.isEmpty(); root.addView(this)
        }
        val result = TextView(this).apply { text = if (existing.isEmpty()) "尚未授权" else "已授权 ${existing.size} 个课表"; setPadding(0, 20, 0, 20) }
        val save = Button(this).apply { text = "保存授权" }; val revoke = Button(this).apply { text = "撤销全部授权" }
        root.addView(result); root.addView(save); root.addView(revoke)
        save.setOnClickListener {
            val selected = choices.filterValues { it.isChecked }.keys
            if (selected.isEmpty()) result.text = "请选择至少一个课表，或使用撤销授权。"
            else try { access.grant(selected); result.text = "已保存授权，返回随手记事即可使用。" }
            catch (_: Exception) { result.text = "授权未能保存，请重试。" }
        }
        revoke.setOnClickListener {
            try { access.revoke(); choices.values.forEach { it.isChecked = false }; result.text = "已撤销；随手记事无法再读取或修改 HITA 日程。" }
            catch (_: Exception) { result.text = "撤销未完成，请重试。" }
        }
        setContentView(scroll)
        Thread {
            try {
                val tables = AppDatabase.getDatabase(applicationContext).timetableDao().getTimetablesSync()
                runOnUiThread { if (!isDestroyed) tables.filter { it.id != PlannerAccess.defaultTableId }.forEach { table ->
                    val checkbox = CheckBox(this).apply { text = table.name ?: "未命名课表"; isChecked = table.id in existing }
                    choices[table.id] = checkbox; root.addView(checkbox, root.indexOfChild(result))
                } }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed) result.text = "未能读取现有课表，可先授权独立课表。" } }
        }.start()
    }
}
