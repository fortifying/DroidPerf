package com.droidperf.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.droidperf.R
import com.droidperf.di.ServiceLocator
import com.droidperf.settings.OsdStyle
import com.droidperf.settings.OverlayConfig
import com.droidperf.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that owns the overlay window and the sampling loop.
 *
 * It is a foreground service (not a background one) because the overlay must keep
 * updating while a game is in front; the persistent notification is the required,
 * user-visible contract for that and is also how the user hides/locks the overlay.
 *
 * Sampling runs on a configurable interval from settings, and each metric is sampled at
 * its own cadence where it matters (see [MetricsRepository]); we never spin.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.droidperf.action.START"
        const val ACTION_STOP = "com.droidperf.action.STOP"
        const val ACTION_TOGGLE_LOCK = "com.droidperf.action.TOGGLE_LOCK"

        private const val CHANNEL_ID = "perf_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var samplingJob: Job? = null
    private var renderJob: Job? = null
    private var configJob: Job? = null

    private var locked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                locked = !locked
                overlayView?.setLocked(locked)
                updateNotification()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                ensureOverlay()
                startSampling()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlayView ?: return
        val lp = params ?: return
        val cfg = ServiceLocator.settings.current()
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (targetX, targetY) = getTargetCoords(cfg, isLandscape)
        lp.x = targetX
        lp.y = targetY
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {}
    }

    private fun getTargetCoords(cfg: OverlayConfig, isLandscape: Boolean): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val viewW = overlayView?.width?.takeIf { it > 0 } ?: (dm.density * 160).roundToInt()
        val viewH = overlayView?.height?.takeIf { it > 0 } ?: (dm.density * 100).roundToInt()

        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)

        val rawX: Int
        val rawY: Int

        if (isLandscape) {
            if (cfg.landscapeX >= 0 && cfg.landscapeY >= 0) {
                rawX = cfg.landscapeX
                rawY = cfg.landscapeY
            } else {
                // If landscape position hasn't been explicitly dragged yet,
                // adapt from portrait position: keep X within bounds, and if Y was in the bottom half of portrait,
                // scale Y down to stay on screen and avoid game touch areas
                rawX = cfg.x.coerceIn(0, maxX)
                rawY = if (cfg.y > maxY) {
                    (cfg.y * (screenH.toFloat() / (screenW.toFloat().coerceAtLeast(1f)))).roundToInt()
                } else {
                    cfg.y
                }
            }
        } else {
            rawX = cfg.x
            rawY = cfg.y
        }

        return Pair(rawX.coerceIn(0, maxX), rawY.coerceIn(0, maxY))
    }

    /** Create the small overlay window; reuse it if it already exists. */
    private fun ensureOverlay() {
        if (overlayView != null) return
        val cfg = ServiceLocator.settings.current()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (initX, initY) = getTargetCoords(cfg, isLandscape)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val view = OverlayView(this, windowManager, lp) { x, y ->
            val landscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ServiceLocator.settings.update {
                if (landscapeNow) {
                    it.copy(landscapeX = x, landscapeY = y)
                } else {
                    it.copy(x = x, y = y)
                }
            }
        }
        view.setStyle(cfg.textSizeSp, cfg.textColor, cfg.backgroundColor, cfg.textOpacity, cfg.bgOpacity)
        view.setLocked(locked)

        try {
            windowManager.addView(view, lp)
        } catch (_: Throwable) {
            // Most commonly: overlay permission revoked. Stop rather than crash.
            stopSelf()
            return
        }

        overlayView = view
        params = lp
    }

    /** Re-render the overlay whenever a new snapshot arrives. */
    private fun startSampling() {
        if (samplingJob?.isActive == true) return
        val settings = ServiceLocator.settings
        val metrics = ServiceLocator.metrics

        samplingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val cfg = settings.current()
                if (cfg.enabled) {
                    metrics.sample(includeNetwork = cfg.networkEnabled, includeLatency = cfg.latencyEnabled)
                }
                delay(cfg.sampleIntervalMs.coerceIn(250L, 5000L))
            }
        }

        // Render on every snapshot change.
        renderJob?.cancel()
        renderJob = scope.launch {
            metrics.snapshot.collectLatest { snap ->
                val cfg = settings.current()
                val view = overlayView ?: return@collectLatest
                view.setOsdStyle(cfg.osdStyle)
                when (cfg.osdStyle) {
                    OsdStyle.MODERN -> view.setModern(MetricsFormatter.modern(snap, cfg))
                    OsdStyle.RTSS -> view.setRtss(MetricsFormatter.rtss(snap, cfg))
                    OsdStyle.CLASSIC -> view.setLines(MetricsFormatter.render(snap, cfg))
                }
            }
        }

        // React to configuration changes (style, visibility, lock).
        configJob?.cancel()
        configJob = scope.launch {
            settings.config.collectLatest { cfg ->
                overlayView?.setStyle(cfg.textSizeSp, cfg.textColor, cfg.backgroundColor, cfg.textOpacity, cfg.bgOpacity)
                if (!cfg.enabled) overlayView?.setLines(emptyList())
                params?.let { lp ->
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val (targetX, targetY) = getTargetCoords(cfg, isLandscape)
                    if (lp.x != targetX || lp.y != targetY) {
                        lp.x = targetX
                        lp.y = targetY
                        overlayView?.let {
                            try { windowManager.updateViewLayout(it, lp) } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_LOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                if (locked) getString(R.string.notif_text_locked)
                else getString(R.string.notif_text_unlocked)
            )
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_lock), toggle)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
            // Notification permission may be missing on Android 13+; overlay still works.
        }
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        samplingJob = null
        renderJob?.cancel()
        renderJob = null
        configJob?.cancel()
        configJob = null
        scope.cancel()
        // SurfaceFlinger timestats collection is device-global; always turn it off.
        try { ServiceLocator.metrics.shutdown() } catch (_: Throwable) {}
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
                // Already detached.
            }
        }
        overlayView = null
        params = null
        super.onDestroy()
    }
}
