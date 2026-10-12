package cn.limpu.hita.planner

import android.content.Context
import java.util.UUID

/** Business consent is separate from Android's signature permission. */
class PlannerAccess(context: Context) {
    private val prefs = context.getSharedPreferences("planner_local", Context.MODE_PRIVATE)
    fun tables(): Set<String> = synchronized(lock) {
        if (!prefs.getBoolean("authorized", false)) emptySet()
        else prefs.getStringSet("tables", emptySet()).orEmpty().toSet()
    }
    fun grant(tables: Set<String>) = synchronized(lock) {
        require(tables.isNotEmpty() && tables.size <= 50)
        check(prefs.edit().putStringSet("tables", tables).putBoolean("authorized", true).commit())
    }
    fun revoke() = synchronized(lock) { check(prefs.edit().clear().commit()) }
    companion object {
        internal val lock = Any()
        val defaultTableId: String = UUID.nameUUIDFromBytes("chat-to-plan:local-timetable:v1".toByteArray(Charsets.UTF_8)).toString()
        const val permission = "cn.chatplanner.hita.permission.LOCAL_PLANNER"
        const val descriptor = "cn.chatplanner.hita.LocalPlanner.v1"
        const val queryTransaction = android.os.IBinder.FIRST_CALL_TRANSACTION
        const val applyTransaction = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
