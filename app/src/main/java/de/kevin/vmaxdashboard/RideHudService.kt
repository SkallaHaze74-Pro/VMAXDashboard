package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** User-started, draggable live HUD backed by the app's one physical BLE session. */
class RideHudService : Service() {
    companion object {
        private const val ACTION_SHOW = "de.kevin.vmaxdashboard.action.SHOW_RIDE_HUD"
        private const val ACTION_HIDE = "de.kevin.vmaxdashboard.action.HIDE_RIDE_HUD"
        private const val NOTIFICATION_CHANNEL_ID = "ride_hud"
        private const val NOTIFICATION_ID = 7_214
        private const val POSITION_PREFS = "ride_hud_position"
        private const val POSITION_X = "x"
        private const val POSITION_Y = "y"
        private const val HUD_REFRESH_MS = 250L
        private const val HUD_CAPTURE_REFRESH_MS = 2_000L

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context)) {
                RideHudRuntime.report(
                    active = false,
                    message = "Android-Berechtigung für das Mini-HUD fehlt"
                )
                return false
            }
            RideHudRuntime.report(false, "Mini-HUD wird gestartet …")
            return runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, RideHudService::class.java)
                        .setAction(ACTION_SHOW)
                )
            }.onFailure { error ->
                RideHudRuntime.report(
                    false,
                    "Mini-HUD konnte nicht starten: ${error.message ?: error.javaClass.simpleName}"
                )
            }.isSuccess
        }

        fun hide(context: Context) {
            if (!RideHudRuntime.state.value.serviceRunning) {
                RideHudRuntime.report(
                    active = false,
                    message = "Mini-HUD ist aus",
                    serviceRunning = false
                )
                return
            }
            runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, RideHudService::class.java)
                        .setAction(ACTION_HIDE)
                )
            }.onFailure { error ->
                RideHudRuntime.report(
                    active = RideHudRuntime.state.value.active,
                    message = "Mini-HUD konnte nicht ausgeblendet werden: " +
                        (error.message ?: error.javaClass.simpleName),
                    serviceRunning = true
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var rootView: FrameLayout? = null
    private var speedValue: TextView? = null
    private var batteryValue: TextView? = null
    private var batteryDetail: TextView? = null
    private var metricsContent: View? = null
    private var liveStatus: TextView? = null
    private var runtimeLease: ScooterRuntimeLease? = null
    private var manager: BleScooterManager? = null
    private var started = false
    private var overlayShown = false
    private var stopping = false
    private var stopMessage = "Mini-HUD beendet"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            if (started) hideOverlay("Mini-HUD ausgeblendet • App und Fahrt laufen weiter")
            else stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            if (canDrawOverlays(this)) {
                showOverlay("Mini-HUD wieder eingeblendet")
            } else {
                hideOverlay("Mini-HUD bleibt ausgeblendet • Android-Berechtigung fehlt")
            }
            return START_NOT_STICKY
        }
        if (!canDrawOverlays(this)) {
            requestStop("Android-Berechtigung für das Mini-HUD fehlt")
            return START_NOT_STICKY
        }

        val foregroundError = runCatching {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(overlayVisible = true),
                foregroundType
            )
        }.exceptionOrNull()
        if (foregroundError != null) {
            requestStop(
                "Mini-HUD-Dienst konnte nicht starten: " +
                    (foregroundError.message ?: foregroundError.javaClass.simpleName)
            )
            return START_NOT_STICKY
        }

        val runtime = (application as VMAXSyncApplication).scooterRuntime
        runtimeLease = runtime.acquire()
        manager = runtime.bleManager
        windowManager = getSystemService(WindowManager::class.java)
        val overlayError = runCatching { addOverlay() }.exceptionOrNull()
        if (overlayError != null) {
            requestStop(
                "Mini-HUD konnte nicht eingeblendet werden: " +
                    (overlayError.message ?: overlayError.javaClass.simpleName)
            )
            return START_NOT_STICKY
        }

        started = true
        overlayShown = true
        showOverlay("Mini-HUD aktiv • zum Verschieben am Griff ziehen")
        startTelemetryRendering(runtime.bleManager)
        startBackgroundCapture(runtime.bleManager)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayShown) rootView?.post { clampCurrentPosition() }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        rootView?.let { view -> runCatching { windowManager.removeViewImmediate(view) } }
        rootView = null
        runtimeLease?.close()
        runtimeLease = null
        manager = null
        started = false
        overlayShown = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        RideHudRuntime.report(active = false, message = stopMessage, serviceRunning = false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VMAX Mini-HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält Geschwindigkeit und Akku über anderen Apps sichtbar"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(overlayVisible: Boolean): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val overlayIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RideHudService::class.java).setAction(
                if (overlayVisible) ACTION_HIDE else ACTION_SHOW
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud_notification)
            .setContentTitle(
                if (overlayVisible) "VMAX Mini-HUD aktiv" else "VMAX Fahrt läuft im Hintergrund"
            )
            .setContentText(
                if (overlayVisible) {
                    "Live-km/h und Akku über anderen Apps"
                } else {
                    "HUD ausgeblendet • Verbindung und Sicherung bleiben aktiv"
                }
            )
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (overlayVisible) "HUD ausblenden" else "HUD einblenden",
                overlayIntent
            )
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val prefs = getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(POSITION_X, dp(16))
            y = prefs.getInt(POSITION_Y, dp(96))
        }

        val root = FrameLayout(this).apply {
            minimumWidth = dp(220)
            setPadding(dp(12), dp(8), dp(10), dp(10))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(242, 13, 21, 17))
                setStroke(dp(1), HUD_GREEN)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        val dragHandle = hudText("⋮⋮  ZIEHEN", 11f, HUD_MUTED).apply {
            contentDescription = "Mini-HUD verschieben"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { }
        }
        val status = hudText("VERBUNDEN", 10f, HUD_GREEN).apply {
            gravity = Gravity.CENTER
        }
        val close = hudText("×", 22f, HUD_WHITE).apply {
            contentDescription = "Mini-HUD schließen"
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener {
                hideOverlay("Mini-HUD ausgeblendet • App und Fahrt laufen weiter")
            }
        }
        topRow.addView(
            dragHandle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(status)
        topRow.addView(close)
        content.addView(topRow)

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speedColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hudText("—", 34f, HUD_WHITE).also { speedValue = it })
            addView(hudText("km/h", 11f, HUD_MUTED))
        }
        val batteryColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, 0, 0)
            addView(hudText("—", 24f, HUD_WHITE).also { batteryValue = it })
            addView(hudText("warte auf Daten", 10f, HUD_MUTED).also { batteryDetail = it })
        }
        metricsRow.addView(
            speedColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        metricsRow.addView(
            batteryColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(metricsRow)
        metricsContent = metricsRow

        rootView = root
        liveStatus = status
        dragHandle.setOnTouchListener(createDragListener(root))
        windowManager.addView(root, layoutParams)
        root.post { clampCurrentPosition() }
    }

    private fun hudText(text: String, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragListener(root: View): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var start = RideHudPosition(0, 0)
        var moved = false
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    start = RideHudPosition(layoutParams.x, layoutParams.y)
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).roundToInt()
                    val deltaY = (event.rawY - downRawY).roundToInt()
                    moved = moved || kotlin.math.abs(deltaX) > dp(3) || kotlin.math.abs(deltaY) > dp(3)
                    val display = displaySize()
                    val next = moveRideHudPosition(
                        start = start,
                        deltaX = deltaX,
                        deltaY = deltaY,
                        displayWidth = display.first,
                        displayHeight = display.second,
                        hudWidth = root.width,
                        hudHeight = root.height
                    )
                    updateOverlayPosition(next)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistPosition()
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun startTelemetryRendering(bleManager: BleScooterManager) {
        serviceScope.launch {
            var previous: RideHudReading? = null
            while (isActive) {
                if (overlayShown && !canDrawOverlays(this@RideHudService)) {
                    hideOverlay(
                        "Mini-HUD ausgeblendet • Overlay-Berechtigung entzogen; Fahrt läuft weiter"
                    )
                }
                val reading = rideHudReading(
                    bleManager.state.value,
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    nowWallClockMs = System.currentTimeMillis()
                )
                if (reading != previous) render(reading)
                previous = reading
                delay(HUD_REFRESH_MS)
            }
        }
    }

    private fun startBackgroundCapture(bleManager: BleScooterManager) {
        serviceScope.launch {
            while (isActive) {
                val current = bleManager.state.value
                when (
                    rideHudCaptureAction(
                        connectionDesired = current.connectionDesired,
                        connected = current.connected,
                        scanning = current.scanning,
                        scanStartedAtElapsedMs = current.scanStartedAtElapsedRealtimeMs,
                        recordingActive = current.recordingActive,
                        recordingDesired = current.recordingDesired,
                        permissionsGranted = bleManager.hasRequiredPermissions(),
                        nowElapsedMs = SystemClock.elapsedRealtime()
                    )
                ) {
                    RideHudCaptureAction.STOP_HUD -> requestStop(
                        if (bleManager.hasRequiredPermissions()) {
                            "Mini-HUD nach manueller Trennung beendet"
                        } else {
                            "Mini-HUD beendet • Bluetooth-Berechtigung fehlt"
                        }
                    )
                    RideHudCaptureAction.START_SCAN -> bleManager.startScan()
                    RideHudCaptureAction.RESTART_SCAN -> {
                        bleManager.stopScan()
                        bleManager.startScan()
                    }
                    RideHudCaptureAction.START_MEASUREMENT -> bleManager.startMeasurement()
                    RideHudCaptureAction.NONE -> Unit
                }
                delay(HUD_CAPTURE_REFRESH_MS)
            }
        }
    }

    private fun render(reading: RideHudReading) {
        val root = rootView ?: return
        root.visibility = if (overlayShown) View.VISIBLE else View.GONE
        val metricsVisibility = if (reading.visible) View.VISIBLE else View.GONE
        if (metricsContent?.visibility != metricsVisibility) {
            metricsContent?.visibility = metricsVisibility
            root.post { clampCurrentPosition() }
        }
        speedValue?.apply {
            text = reading.speedText
            setTextColor(if (reading.speedLive) HUD_GREEN else HUD_WHITE)
        }
        batteryValue?.apply {
            text = reading.batteryText
            setTextColor(
                when (reading.batterySource) {
                    RideHudBatterySource.STABLE -> HUD_GREEN
                    RideHudBatterySource.STABLE_WITH_RAW -> HUD_WHITE
                    RideHudBatterySource.RAW -> HUD_AMBER
                    RideHudBatterySource.STALE -> HUD_AMBER
                    RideHudBatterySource.LAST_KNOWN,
                    RideHudBatterySource.UNAVAILABLE -> HUD_MUTED
                }
            )
        }
        batteryDetail?.text = reading.batteryDetail
        liveStatus?.apply {
            text = reading.statusText
            setTextColor(if (reading.speedLive) HUD_GREEN else HUD_AMBER)
        }
    }

    private fun showOverlay(message: String) {
        if (!canDrawOverlays(this)) {
            hideOverlay("Mini-HUD bleibt ausgeblendet • Android-Berechtigung fehlt")
            return
        }
        overlayShown = true
        rootView?.visibility = View.VISIBLE
        RideHudRuntime.report(active = true, message = message, serviceRunning = true)
        updateNotification()
        rootView?.post { clampCurrentPosition() }
    }

    private fun hideOverlay(message: String) {
        overlayShown = false
        rootView?.visibility = View.GONE
        val hidden = rideHudHiddenRuntimeState(message)
        RideHudRuntime.report(
            active = hidden.active,
            message = hidden.message,
            serviceRunning = hidden.serviceRunning
        )
        updateNotification()
    }

    private fun updateNotification() {
        if (!started) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(overlayVisible = overlayShown)
        )
    }

    private fun clampCurrentPosition() {
        val root = rootView ?: return
        val display = displaySize()
        updateOverlayPosition(
            moveRideHudPosition(
                start = RideHudPosition(layoutParams.x, layoutParams.y),
                deltaX = 0,
                deltaY = 0,
                displayWidth = display.first,
                displayHeight = display.second,
                hudWidth = root.width,
                hudHeight = root.height
            )
        )
        persistPosition()
    }

    private fun updateOverlayPosition(position: RideHudPosition) {
        val root = rootView ?: return
        layoutParams.x = position.x
        layoutParams.y = position.y
        val failure = runCatching { windowManager.updateViewLayout(root, layoutParams) }.exceptionOrNull()
        if (failure != null) requestStop("Mini-HUD-Fenster wurde beendet")
    }

    private fun persistPosition() {
        getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(POSITION_X, layoutParams.x)
            .putInt(POSITION_Y, layoutParams.y)
            .apply()
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun requestStop(message: String) {
        if (stopping) return
        stopping = true
        stopMessage = message
        RideHudRuntime.report(false, message)
        stopSelf()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}

private val HUD_GREEN = Color.rgb(105, 231, 124)
private val HUD_WHITE = Color.rgb(232, 240, 234)
private val HUD_MUTED = Color.rgb(180, 195, 182)
private val HUD_AMBER = Color.rgb(255, 193, 7)
