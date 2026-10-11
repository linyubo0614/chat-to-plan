package cn.limpu.hita.planner

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.work.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class PlannerConfig(context: Context) {
    private val prefs = EncryptedSharedPreferences.create("planner", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val enabled get() = prefs.getBoolean("enabled", false)
    val server get() = prefs.getString("server", "").orEmpty()
    val token get() = prefs.getString("token", "").orEmpty()
    val clientId: String get() = synchronized(PlannerConfig::class.java) {
        prefs.getString("client", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        check(prefs.edit().putString("client", id).commit())
        id
    }
    fun save(server: String, token: String, enabled: Boolean) {
        val normalized = validateServer(server)
        require(token.length >= 24)
        check(prefs.edit().putString("server", normalized).putString("token", token).putBoolean("enabled", enabled).commit())
    }
    fun disable() { check(prefs.edit().putBoolean("enabled", false).commit()) }
    companion object {
        fun validateServer(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
            return value.trim().trimEnd('/')
        }
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!PlannerConfig(context).enabled) { work.cancelUniqueWork("chat-planner-periodic"); return }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            work.enqueueUniquePeriodicWork("chat-planner-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PlannerWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("chat-planner-once", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PlannerWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
