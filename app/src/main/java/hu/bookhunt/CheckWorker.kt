package hu.bookhunt

import android.content.Context
import androidx.work.*

class CheckWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        runCheck(applicationContext).forEach { notifyHit(applicationContext, it) }
        return Result.success()
    }
}
