package com.listener.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.listener.app.context.ApiKeyStore
import com.listener.app.context.OpenRouterClient
import com.listener.app.data.UserPreferences
import com.listener.app.data.session.DatabaseProvider
import com.listener.app.data.session.SessionRepository
import com.listener.app.models.ModelRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ListenerApplication : Application() {
    val database by lazy { DatabaseProvider.get(this) }
    val sessions by lazy { SessionRepository(database.sessions()) }
    val preferences by lazy { UserPreferences(this) }
    val keyStore by lazy { ApiKeyStore(this) }
    val models by lazy { ModelRepository(this, database.sessions()) }
    val openRouter by lazy { OpenRouterClient() }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { database.sessions().finishInterrupted(System.currentTimeMillis()) }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "session-retention",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS).build(),
        )
    }
}
