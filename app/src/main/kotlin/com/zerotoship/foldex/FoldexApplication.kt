package com.zerotoship.foldex

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.zerotoship.foldex.ui.viewer.AudioArtFetcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FoldexApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Android には "user.home" システムプロパティが無く、Apache MINA SSHD が
        // SshServer.setUpDefaultServer() 内の静的初期化でこれを参照して
        // IllegalArgumentException("No user home") を投げる。事前に書き込み可能な
        // ディレクトリを入れておく (~/.ssh/authorized_keys は存在しなければ無視される)。
        if (System.getProperty("user.home").isNullOrEmpty()) {
            System.setProperty("user.home", filesDir.absolutePath)
        }
    }

    // WorkManager を Hilt の WorkerFactory で初期化する (@HiltWorker な SyncWorker を使うため)。
    // 既定の WorkManagerInitializer は AndroidManifest で無効化済み。
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Coil 3: サムネ用のメモリ + ディスク 2 層キャッシュ。動画フレームのデコーダも登録する。
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AudioArtFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
