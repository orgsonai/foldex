package com.zerotoship.foldex

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FoldexApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager を Hilt の WorkerFactory で初期化する (@HiltWorker な SyncWorker を使うため)。
    // 既定の WorkManagerInitializer は AndroidManifest で無効化済み。
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
