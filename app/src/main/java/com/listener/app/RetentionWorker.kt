package com.listener.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.listener.app.data.UserPreferences
import com.listener.app.data.session.DatabaseProvider
import kotlinx.coroutines.flow.first

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val days = UserPreferences(applicationContext).values.first().retentionDays
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1_000L
        DatabaseProvider.get(applicationContext).sessions().deleteEndedBefore(cutoff)
        Result.success()
    }.getOrElse { Result.retry() }
}
