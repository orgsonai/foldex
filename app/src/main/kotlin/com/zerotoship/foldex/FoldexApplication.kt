package com.zerotoship.foldex

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import com.zerotoship.foldex.ui.viewer.AudioArtFetcher
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.PrintWriter
import java.security.Security
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
        registerBouncyCastle()
        installCrashHandler()
    }

    /**
     * Apache MINA SSHD は DH 鍵交換・各種暗号で BouncyCastle を期待する。Android に同梱の
     * "BC" プロバイダは機能限定版で、`SshServer.setUpDefaultServer()` の
     * `keyExchangeFactories` が空になり「KeyExchangeFactories not set」で起動できないことがある。
     * SSHD のクラスが触られる前にフル版 BC を登録する。
     */
    private fun registerBouncyCastle() {
        runCatching {
            // Android のデフォルト "BC" は機能限定。同名で上書きするには一度外す必要がある。
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
            Log.d(TAG, "BouncyCastle registered: ${BouncyCastleProvider().info}")
        }.onFailure { Log.w(TAG, "Failed to register BouncyCastle", it) }
    }

    /**
     * 未捕捉例外をファイルに保存してから既定ハンドラに引き継ぐ。
     *
     * Apache FtpServer / MINA SSHD はサーバ側スレッドで例外を握り潰しがちで、
     * 「立ち上がったのにクライアント接続で落ちる」「ログだけ消える」が再現性悪く解析しにくいので、
     * filesDir/crash/crash_*.txt にスタックトレースをローテーション保存して
     * `設定 > サーバ > ログ` 等から後追いできるようにする。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // adb pull で取り出しやすいよう externalFilesDir 配下に保存する (利用権限不要)。
        // 外部ストレージが無効なときは filesDir 配下にフォールバック。
        val base = getExternalFilesDir(null) ?: filesDir
        val crashDir = File(base, "crash").apply { mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // 直近 5 件だけ残す。
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(4)
                    ?.forEach { it.delete() }
                File(crashDir, "crash_${System.currentTimeMillis()}.txt").printWriter().use { w: PrintWriter ->
                    w.println("Thread: ${thread.name}")
                    w.println("Time: ${java.util.Date()}")
                    w.println("---")
                    throwable.printStackTrace(w)
                }
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            }
            // Apache MINA / Apache FtpServer のワーカで Error 系 (CoderMalfunctionError 等) が
            // 漏れるとプロセス全体が死ぬが、サーバ側セッションを失うだけで他機能は継続できる。
            // 既知問題: Android の ICU CharsetEncoder + MINA AbstractIoBuffer.putString で
            // CoderMalfunctionError(IllegalArgumentException newPosition>limit) が発生。
            // → スタックに org.apache.mina/ftpserver/sshd を含む未捕捉例外は握り潰し、プロセス継続。
            if (isFromServerStack(throwable)) {
                Log.w(TAG, "Suppressed uncaught throwable from MINA/FtpServer/SSHD; process kept alive.")
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** スタックトレースに MINA/FtpServer/SSHD のフレームを含むかで判定。 */
    private fun isFromServerStack(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            if (t.stackTrace.any { f ->
                    val n = f.className
                    n.startsWith("org.apache.mina") ||
                        n.startsWith("org.apache.ftpserver") ||
                        n.startsWith("org.apache.sshd")
                }) return true
            t = t.cause
        }
        return false
    }

    private companion object {
        const val TAG = "FoldexApp"
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
            // crossfade はグローバルでは入れない (フレーム単位アニメが一覧スクロール中のジャンクを増やすため)。
            // 必要な箇所 (ビューア等) の ImageRequest 側で個別に有効化する。
            .build()
}
