package dev.tester.mockgps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class MockLocationService : Service() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ALT = "alt"
        const val ACTION_STOP = "dev.tester.mockgps.STOP"

        private const val CHANNEL_ID = "mock"
        private const val NOTIF_ID = 1
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val MOVE_TICK_MS = 100L

        // Joystick top speeds in m/s, with labels.
        private val SPEEDS = doubleArrayOf(1.4, 3.0, 6.0, 15.0)
        private val SPEED_NAMES = arrayOf("Walk", "Jog", "Bike", "Car")

        @Volatile
        var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val providers = mutableListOf<String>()

    // Real position captured at start, and the current mocked position.
    private var realLat = 0.0
    private var realLng = 0.0
    private var curLat = 0.0
    private var curLng = 0.0
    private var alt = 0.0
    private var speedIndex = 0

    // Joystick deflection, -1..1 (x+ = east, y+ = north).
    private var joyX = 0f
    private var joyY = 0f
    private var lastPushMs = 0L

    private var panel: LinearLayout? = null
    private lateinit var body: LinearLayout
    private lateinit var foldBtn: TextView
    private lateinit var distText: TextView
    private lateinit var coordText: TextView
    private lateinit var speedBtn: TextView

    /** Moves the position while the joystick is held and keeps sending fixes. */
    private val tick = object : Runnable {
        override fun run() {
            val moving = joyX != 0f || joyY != 0f
            if (moving) {
                val step = SPEEDS[speedIndex] * MOVE_TICK_MS / 1000.0
                val northM = joyY * step
                val eastM = joyX * step
                curLat += northM / METERS_PER_DEG_LAT
                curLng += eastM / (METERS_PER_DEG_LAT * cos(Math.toRadians(curLat)))
                syncUi()
            }
            val now = SystemClock.elapsedRealtime()
            // 1 fix per second when still, 2 per second while moving.
            if (now - lastPushMs >= if (moving) 500 else 1000) pushLocation()
            if (running) handler.postDelayed(this, MOVE_TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        speedIndex = prefs.speedIndex.coerceIn(SPEEDS.indices)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || !intent.hasExtra(EXTRA_LAT)) {
            fail("No starting location. Open the app and press Start.")
            return START_NOT_STICKY
        }

        realLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        realLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        alt = intent.getDoubleExtra(EXTRA_ALT, 0.0)
        curLat = realLat
        curLng = realLng

        if (providers.isEmpty()) {
            val err = setupProviders()
            if (err != null) {
                fail(err)
                return START_NOT_STICKY
            }
        }

        running = true
        if (panel == null) showPanel()
        syncUi()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        removePanel()
        removeProviders()
        super.onDestroy()
    }

    // ---------------- foreground notification ----------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mock GPS active")
            .setContentText("Use the floating panel to move. Tap to open.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun fail(msg: String) {
        prefs.lastError = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    // ---------------- test providers ----------------

    /** Returns null on success, or a user-facing error message. */
    private fun setupProviders(): String? {
        val names = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        for (name in names) {
            try {
                runCatching { lm.removeTestProvider(name) }
                addTestProvider(name)
                lm.setTestProviderEnabled(name, true)
                providers += name
            } catch (e: SecurityException) {
                removeProviders()
                return "This app is not selected as the mock location app. " +
                    "Developer options > Select mock location app > Mock GPS."
            } catch (e: Exception) {
                // The fused provider can't be mocked on some devices; GPS/network are what matter.
                if (name != LocationManager.FUSED_PROVIDER) {
                    removeProviders()
                    return "Couldn't create the $name test provider: ${e.message}"
                }
            }
        }
        return null
    }

    private fun addTestProvider(name: String) {
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(
                name,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        }
    }

    private fun removeProviders() {
        for (name in providers) {
            runCatching { lm.setTestProviderEnabled(name, false) }
            runCatching { lm.removeTestProvider(name) }
        }
        providers.clear()
    }

    private fun pushLocation() {
        lastPushMs = SystemClock.elapsedRealtime()
        val mag = min(1.0, hypot(joyX.toDouble(), joyY.toDouble()))
        val speedMs = (SPEEDS[speedIndex] * mag).toFloat()
        val heading = ((Math.toDegrees(atan2(joyX.toDouble(), joyY.toDouble())) + 360) % 360).toFloat()
        for (name in providers) {
            val loc = Location(name).apply {
                latitude = curLat
                longitude = curLng
                altitude = alt
                accuracy = 5f
                speed = speedMs
                if (speedMs > 0f) bearing = heading
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = 5f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 30f
            }
            try {
                lm.setTestProviderLocation(name, loc)
            } catch (e: SecurityException) {
                fail("Mock location access was removed. Select Mock GPS again in Developer options.")
                return
            } catch (e: IllegalArgumentException) {
                // provider vanished; ignore this tick
            }
        }
    }

    // ---------------- floating panel ----------------

    private fun formatDist(m: Float): String =
        if (m >= 1000f) "%.2f km".format(m / 1000f) else "${m.roundToInt()} m"

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val ctx = this
        val textColor = Color.WHITE

        fun action(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(textColor)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 28, 30, 34))
            }
        }

        // Header: title (drag handle) + Fold / Reset / X
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "✥ Mock GPS"
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            setPadding(dp(6), dp(8), dp(12), dp(8))
        }
        foldBtn = action("▾") { toggleFold() }
        header.addView(title)
        header.addView(foldBtn)
        header.addView(action("↺") { resetToMyLocation() })
        header.addView(action("✕") { stopSelf() })
        root.addView(header)

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        distText = TextView(ctx).apply {
            setTextColor(textColor)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        coordText = TextView(ctx).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        body.addView(distText)
        body.addView(coordText)

        val joystick = JoystickView(ctx) { x, y ->
            joyX = x
            joyY = y
            if (x == 0f && y == 0f) pushLocation() // send the stop right away
        }
        body.addView(joystick, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        speedBtn = action("") { cycleSpeed() }.apply {
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(255, 60, 64, 72))
            }
        }
        body.addView(speedBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        root.addView(body)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.panelX
            y = prefs.panelY
        }

        // Drag the panel by its title.
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.panelX = params.x
                    prefs.panelY = params.y
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, params)
            panel = root
        } catch (e: Exception) {
            fail("Can't show the floating panel. Allow \"Display over other apps\" for Mock GPS.")
        }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun toggleFold() {
        val folded = body.visibility == View.VISIBLE
        body.visibility = if (folded) View.GONE else View.VISIBLE
        foldBtn.text = if (folded) "▸" else "▾"
    }

    private fun resetToMyLocation() {
        curLat = realLat
        curLng = realLng
        syncUi()
        pushLocation()
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        prefs.speedIndex = speedIndex
        syncUi()
    }

    private fun syncUi() {
        if (!::distText.isInitialized) return
        val d = FloatArray(1)
        Location.distanceBetween(realLat, realLng, curLat, curLng, d)
        distText.text = if (d[0] < 0.5f) "At my location" else "${formatDist(d[0])} from start"
        coordText.text = "%.6f, %.6f".format(curLat, curLng)
        speedBtn.text = "Speed: ${SPEED_NAMES[speedIndex]} (%.0f km/h)".format(SPEEDS[speedIndex] * 3.6)
    }
}
