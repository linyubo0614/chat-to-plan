package cn.limpu.hita.planner

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PlannerWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result = try {
        PlannerSync(applicationContext).runOnce()
        Result.success()
    } catch (e: PlannerUnauthorized) {
        Result.failure()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
