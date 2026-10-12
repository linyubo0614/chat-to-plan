package cn.limpu.hita.planner

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject

/** Same-device IPC only. No server, socket, account, or background polling. */
class PlannerLocalService : Service() {
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(PlannerAccess.descriptor); return true }
            if (code !in setOf(PlannerAccess.queryTransaction, PlannerAccess.applyTransaction))
                return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != android.os.Process.myUid()) {
                enforceCallingPermission(PlannerAccess.permission, "Only the signed planner may edit HITA")
                if (packageManager.getPackagesForUid(Binder.getCallingUid())?.contains("cn.chatplanner.control") != true)
                    throw SecurityException("Unexpected planner package")
            }
            data.enforceInterface(PlannerAccess.descriptor)
            val raw = data.readString().orEmpty()
            val result = if (raw.length > 65536) JSONObject().put("status", "invalid").put("message", "请求内容过长。")
            else try {
                val repository = PlannerLocalRepository(applicationContext)
                if (code == PlannerAccess.queryTransaction) repository.query(JSONObject(raw)) else repository.apply(JSONObject(raw))
            } catch (_: Exception) { JSONObject().put("status", if (code == PlannerAccess.applyTransaction) "unknown" else "error").put("message", "HITA 未能返回结果，请重新查询确认。") }
            reply?.writeNoException(); reply?.writeString(result.toString()); return true
        }
    }
    override fun onBind(intent: Intent?): IBinder = endpoint
}
