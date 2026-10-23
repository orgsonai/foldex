// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.VideoSkip
import com.zerotoship.foldex.core.model.filetype.Category
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foldex 自身の小窓で動画を再生する前景サービス (設定「ポップアップ再生の方式」が「Foldex の小窓」のとき)。
 *
 * Android 標準のピクチャー・イン・ピクチャーは大きさの上限と端へ寄る動きを Android が決めるため、
 * 「他のアプリの上に重ねて表示」の許可を使って自前の窓を出す。窓は好きな大きさ・位置にでき、
 * 最後の大きさと位置を覚えておく。再生を続けるため前景サービスにし、通知を出す (Android の決まり)。
 *
 * 小窓の Compose には Activity が無いので、このサービス自身が Lifecycle と SavedState の持ち主になる。
 */
class FloatingVideoService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    /** 小窓で再生する動画。全画面に戻すときに [ViewerActivity] を開き直すのにも使う。 */
    data class Source(
        /** 端末内の実体のパス。配信 ([streamingUri]) のときは空でよい。 */
        val localPath: String,
        val name: String,
        val streamingUri: String?,
    ) {
        val playUri: String get() = streamingUri ?: File(localPath).toURI().toString()
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var player: ExoPlayer? = null
    private var source: Source? = null
    private var skip = VideoSkip.DEFAULT
    private var frame: FloatingVideoFrame? = null
    private var params: WindowManager.LayoutParams? = null

    /** 映像の縦横比 (幅 / 高さ)。届くまでは 16:9 で窓を作る。 */
    private var aspect = DEFAULT_ASPECT

    private val isPlayingState = mutableStateOf(false)
    private val controlsVisibleState = mutableStateOf(true)
    private val interactionCount = mutableIntStateOf(0)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isPlayingState.value = isPlaying
            notifyState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            aspect = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(MIN_ASPECT, MAX_ASPECT)
            params?.let { p -> applyGeometry(p.width, p.x, p.y, save = false) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 最後まで再生したら、次の操作がすぐできるようにボタンを出す。
            if (playbackState == Player.STATE_ENDED) controlsVisibleState.value = true
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@FloatingVideoService,
                getString(R.string.video_playback_error, error.errorCodeName),
                Toast.LENGTH_LONG,
            ).show()
            stopNow()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 通知のボタンが古いまま残っていて、再生していないのに起こされたときは何もせず終える。
            ACTION_TOGGLE -> if (player == null) stopSelf() else togglePlay()
            ACTION_EXPAND -> if (player == null) stopSelf() else expand()
            ACTION_CLOSE -> stopNow()
            ACTION_START -> start(intent)
            else -> stopNow()
        }
        // 再生の途中で終了させられても、続きの位置が分からないので自動で再起動しない。
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 画面を回したら、はみ出さないように収め直す。
        params?.let { p -> applyGeometry(p.width, p.x, p.y, save = false) }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        removeWindow()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        running = false
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        val src = Source(
            localPath = intent.getStringExtra(EXTRA_PATH).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            streamingUri = intent.getStringExtra(EXTRA_STREAMING_URI),
        )
        // startForegroundService で起こされたら、やめる場合でも先に前景にしないとアプリが落ちる。
        try {
            startForegroundCompat(src.name)
        } catch (_: IllegalStateException) {
            // 前景サービスを始められない状態 (裏からの起動の制限など)。何も出さずに終わる。
            stopSelf()
            return
        }
        // 許可が取り消されていたら窓を出せない (出そうとすると例外になる)。
        if (!Settings.canDrawOverlays(this) || (src.localPath.isBlank() && src.streamingUri.isNullOrBlank())) {
            stopNow()
            return
        }
        running = true
        source = src
        skip = VideoSkip(
            amount = intent.getIntExtra(EXTRA_SKIP_AMOUNT, VideoSkip.DEFAULT.amount),
            percent = intent.getBooleanExtra(EXTRA_SKIP_PERCENT, VideoSkip.DEFAULT.percent),
        )
        player?.removeListener(playerListener)
        player?.release()
        val newPlayer = ExoPlayer.Builder(this)
            // 端末の「使用状況と診断情報」経由で再生の統計を渡さない (PRIVACY.md §5)。
            .setUsePlatformDiagnostics(false)
            // 他のアプリが音を鳴らし始めたら止まり、イヤホンを抜いたら止まる (小窓は他のアプリと並べて使うため)。
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
                setMediaItem(MediaItem.fromUri(src.playUri))
                val position = intent.getLongExtra(EXTRA_POSITION, 0L)
                if (position > 0) seekTo(position)
                prepare()
                playWhenReady = intent.getBooleanExtra(EXTRA_PLAYING, true)
            }
        player = newPlayer
        aspect = DEFAULT_ASPECT
        controlsVisibleState.value = !newPlayer.playWhenReady
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (frame == null) addWindow() else frame?.let { setContent(it, newPlayer) }
    }

    private fun addWindow() {
        val p = player ?: return
        val screen = screenSize()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val defaultWidth = (min(screen.widthPixels, screen.heightPixels) * DEFAULT_WIDTH_RATIO).roundToInt()
        val width = prefs.getInt(KEY_WIDTH, defaultWidth)
        val height = (width / aspect).roundToInt()
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 文字入力を奪わず、窓の外のタッチは後ろのアプリへ通す。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screen.widthPixels - width - dp(16f))
            y = prefs.getInt(KEY_Y, (screen.heightPixels * 0.4f).roundToInt())
        }
        val view = FloatingVideoFrame(this, frameCallbacks).apply {
            resizeHandleEnabled = { controlsVisibleState.value }
            setViewTreeLifecycleOwner(this@FloatingVideoService)
            setViewTreeSavedStateRegistryOwner(this@FloatingVideoService)
        }
        setContent(view, p)
        try {
            windowManager.addView(view, layoutParams)
        } catch (_: RuntimeException) {
            // 許可が無い・窓の種類が使えない端末など。サービスだけ残さないよう終える。
            stopNow()
            return
        }
        frame = view
        params = layoutParams
        applyGeometry(layoutParams.width, layoutParams.x, layoutParams.y, save = false)
    }

    private fun setContent(view: FloatingVideoFrame, p: Player) {
        view.removeAllViews()
        view.addView(
            ComposeView(this).apply {
                setContent {
                    FloatingVideoContent(
                        player = p,
                        isPlaying = isPlayingState.value,
                        controlsVisible = controlsVisibleState.value,
                        interactionCount = interactionCount.intValue,
                        skipLabel = skipLabel(),
                        onToggleControls = {
                            controlsVisibleState.value = !controlsVisibleState.value
                            interactionCount.intValue++
                        },
                        onHideControls = { controlsVisibleState.value = false },
                        onTogglePlay = { togglePlay(); interactionCount.intValue++ },
                        onRewind = { seekBy(-1); interactionCount.intValue++ },
                        onForward = { seekBy(1); interactionCount.intValue++ },
                        onClose = ::stopNow,
                        onExpand = ::expand,
                    )
                }
            },
        )
    }

    private fun removeWindow() {
        frame?.let { runCatching { windowManager.removeView(it) } }
        frame = null
        params = null
    }

    private val frameCallbacks = object : FloatingVideoFrame.Callbacks {
        override fun onMove(dx: Float, dy: Float) {
            val p = params ?: return
            applyGeometry(p.width, p.x + dx.roundToInt(), p.y + dy.roundToInt(), save = false)
        }

        override fun onScale(factor: Float) {
            val p = params ?: return
            // つまんだ中心が動かないよう、窓の中心を保ったまま大きさを変える。
            val newWidth = (p.width * factor).roundToInt()
            val newHeight = (newWidth / aspect).roundToInt()
            applyGeometry(newWidth, p.x + (p.width - newWidth) / 2, p.y + (p.height - newHeight) / 2, save = false)
        }

        override fun onResize(dx: Float, dy: Float) {
            val p = params ?: return
            // 縦横比は保つので、横と縦のうち大きく動いた方に合わせる。
            val delta = if (abs(dx) >= abs(dy * aspect)) dx else dy * aspect
            applyGeometry(p.width + delta.roundToInt(), p.x, p.y, save = false)
        }

        override fun onGestureEnd() {
            params?.let { p -> applyGeometry(p.width, p.x, p.y, save = true) }
        }
    }

    /**
     * 窓の幅と位置を画面に収めて反映する。高さは映像の縦横比から決める。
     * 上限は「画面の幅」か「画面の高さに収まる幅」の小さい方 (Android 標準の小窓より大きくできる)。
     */
    private fun applyGeometry(width: Int, x: Int, y: Int, save: Boolean) {
        val p = params ?: return
        val view = frame ?: return
        val screen = screenSize()
        val maxWidth = min(screen.widthPixels, (screen.heightPixels * aspect).toInt())
        val w = width.coerceIn(min(dp(MIN_WIDTH_DP), maxWidth), maxWidth)
        val h = (w / aspect).roundToInt()
        p.width = w
        p.height = h
        p.x = x.coerceIn(0, (screen.widthPixels - w).coerceAtLeast(0))
        p.y = y.coerceIn(0, (screen.heightPixels - h).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
        if (save) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_WIDTH, w)
                .putInt(KEY_X, p.x)
                .putInt(KEY_Y, p.y)
                .apply()
        }
    }

    // サービスには自分の画面が無いので、端末の画面全体の大きさ (今の向き) を使う。
    @Suppress("DEPRECATION")
    private fun screenSize(): DisplayMetrics =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
            }
        } else {
            DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun skipLabel(): String =
        if (skip.percent) "${skip.amount}%" else getString(R.string.popup_skip_seconds, skip.amount)

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            // 最後まで再生していたら頭から。
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    /** [direction] が負なら巻き戻し、正なら早送り。 */
    private fun seekBy(direction: Int) {
        val p = player ?: return
        val duration = p.duration
        val target = p.currentPosition + direction * skip.incrementMs(duration)
        p.seekTo(if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0))
    }

    /** 全画面のビューアで続きから開き直し、小窓を閉じる。 */
    private fun expand() {
        val src = source
        val p = player
        if (src != null && p != null) {
            val intent = ViewerActivity.intent(
                context = this,
                localPath = src.localPath,
                name = src.name,
                category = Category.VIDEO,
                streamingMediaUri = src.streamingUri,
                startPositionMs = p.currentPosition,
                startPlaying = p.isPlaying,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 「他のアプリの上に重ねて表示」の許可があるので、裏からでも画面を開ける。
            runCatching { startActivity(intent) }
        }
        stopNow()
    }

    private fun stopNow() {
        removeWindow()
        player?.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(title: String) {
        ensureChannel()
        val notification = buildNotification(title, playing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyState() {
        val src = source ?: return
        if (!running) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(src.name, isPlayingState.value))
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        fun action(action: String, requestCode: Int) = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, FloatingVideoService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.popup_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(action(ACTION_EXPAND, 1))
            .addAction(
                0,
                getString(if (playing) R.string.viewer_pip_pause else R.string.viewer_pip_play),
                action(ACTION_TOGGLE, 2),
            )
            .addAction(0, getString(R.string.popup_close), action(ACTION_CLOSE, 3))
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.popup_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.popup_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.zerotoship.foldex.viewer.popup.START"
        private const val ACTION_TOGGLE = "com.zerotoship.foldex.viewer.popup.TOGGLE"
        private const val ACTION_EXPAND = "com.zerotoship.foldex.viewer.popup.EXPAND"
        private const val ACTION_CLOSE = "com.zerotoship.foldex.viewer.popup.CLOSE"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_STREAMING_URI = "streaming_uri"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_SKIP_AMOUNT = "skip_amount"
        private const val EXTRA_SKIP_PERCENT = "skip_percent"
        private const val CHANNEL_ID = "foldex_video_popup"
        private const val NOTIFICATION_ID = 4345

        // 窓を作る瞬間に同期で読みたいので、DataStore ではなく SharedPreferences に置く。
        private const val PREFS = "floating_video_window"
        private const val KEY_WIDTH = "width"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        private const val DEFAULT_ASPECT = 16f / 9f
        private const val MIN_ASPECT = 1f / 3f
        private const val MAX_ASPECT = 3f
        private const val DEFAULT_WIDTH_RATIO = 0.6f
        private const val MIN_WIDTH_DP = 180f

        /** 小窓で再生中か。新しくビューアを開いたときに止めるかどうかの判断に使う。 */
        @Volatile
        var running = false
            private set

        /** 小窓で再生を始める。画面が前面にある間に呼ぶ。許可の確認は呼び出し側で済ませておく。 */
        fun start(context: Context, source: Source, positionMs: Long, playing: Boolean, skip: VideoSkip) {
            val intent = Intent(context, FloatingVideoService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PATH, source.localPath)
                .putExtra(EXTRA_NAME, source.name)
                .putExtra(EXTRA_STREAMING_URI, source.streamingUri)
                .putExtra(EXTRA_POSITION, positionMs)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_SKIP_AMOUNT, skip.amount)
                .putExtra(EXTRA_SKIP_PERCENT, skip.percent)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 小窓を閉じる (再生していなければ何もしない)。 */
        fun stop(context: Context) {
            if (running) context.stopService(Intent(context, FloatingVideoService::class.java))
        }
    }
}
