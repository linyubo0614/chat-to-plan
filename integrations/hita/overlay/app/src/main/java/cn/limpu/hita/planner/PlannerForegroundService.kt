package cn.limpu.hita.planner

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.limpu.hita.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** User-enabled ongoing connection. It never starts an Activity or takes a wake lock. */
class PlannerForegroundService: Service() {
    private val active=AtomicBoolean(false)
    private val io=Executors.newSingleThreadExecutor()
    private var lastText=0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if (!PlannerConfig(this).enabled) { stopSelf(); return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.planner_channel),NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null,null); enableVibration(false); setShowBadge(false)
        })
        val initial=notification(R.string.planner_connecting)
        if (Build.VERSION.SDK_INT>=34) startForeground(ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID,initial)
        if(active.compareAndSet(false,true)) io.execute {
            val sync=PlannerSync(this)
            var delay=1000L
            while(active.get() && PlannerConfig(this).enabled) {
                try {
                    sync.runOnce(25)
                    update(R.string.planner_connected); delay=1000L
                } catch(e: Exception) {
                    if(!active.get()) break
                    update(R.string.planner_reconnecting); delay=minOf(maxOf(delay*2,3000L),30000L)
                }
                try { Thread.sleep(delay) } catch(e: InterruptedException) { break }
            }
            stopSelf()
        }
        return START_STICKY
    }
    private fun notification(text: Int): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,PlannerSettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.planner_channel)).setContentText(getString(text))
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
    }
    private fun update(text: Int) {
        if(lastText!=text && active.get()) { getSystemService(NotificationManager::class.java).notify(ID,notification(text)); lastText=text }
    }
    override fun onDestroy() { active.set(false); io.shutdownNow(); super.onDestroy() }
    companion object {
        private const val CHANNEL="chat-planner-connection"
        private const val ID=7421
        fun start(context: Context) { context.startForegroundService(Intent(context,PlannerForegroundService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context,PlannerForegroundService::class.java)) }
    }
}

class PlannerBootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED && PlannerConfig(context).enabled) {
            try { PlannerForegroundService.start(context) } catch(e: Exception) { /* OEM auto-start may require user settings. */ }
        }
    }
}
