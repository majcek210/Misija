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
import kotlin.math.roundToInt
import kotlin.math.sin

class MockLocationService : Service() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ALT = "alt"
        const val ACTION_STOP = "dev.tester.mockgps.STOP"
        const val ACTION_GOTO = "dev.tester.mockgps.GOTO"
        const val EXTRA_WALK = "walk"

        private const val CHANNEL_ID = "mock"
        private const val NOTIF_ID = 1
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val MOVE_TICK_MS = 100L
        private const val JOY_DEAD_ZONE = 0.08

        // Joystick top speeds in m/s, with labels.
        private val SPEEDS = doubleArrayOf(0.5, 1.4, 3.0, 6.0, 15.0, 35.0)
        private val SPEED_NAMES = arrayOf("Creep", "Walk", "Jog", "Bike", "Car", "Highway")

        @Volatile
        var running = false
            private set

        // Last mocked position, read by the map screen.
        @Volatile
        var posLat = 0.0
            private set
        @Volatile
        var posLng = 0.0
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

    // Joystick deflection after the response curve, -1..1 (x+ = east, y+ = north).
    private var joyX = 0.0
    private var joyY = 0.0
    // Point picked on the map that we're walking to, as (lat, lng).
    private var walkTarget: DoubleArray? = null
    // Movement during the last tick, reported with each fix.
    private var speedMs = 0f
    private var bearingDeg = 0f

    private var panel: LinearLayout? = null
    private lateinit var body: LinearLayout
    private lateinit var foldBtn: TextView
    private lateinit var distText: TextView
    private lateinit var coordText: TextView
    private lateinit var speedBtn: TextView

    /**
     * Moves the position (joystick or map target) and sends a fix every tick.
     * Fixes go out every 100 ms even when standing still: with gaps between them,
     * the real location can slip in and the position jumps back for a moment.
     */
    private val tick = object : Runnable {
        override fun run() {
            speedMs = 0f
            val step = SPEEDS[speedIndex] * MOVE_TICK_MS / 1000.0
            val target = walkTarget
            if (joyX != 0.0 || joyY != 0.0) {
                walkTarget = null
                move(joyY * step, joyX * step)
            } else if (target != null) {
                walkToward(target, step)
            }
            pushLocation()
            if (running) handler.postDelayed(this, MOVE_TICK_MS)
        }
    }

    private fun move(northM: Double, eastM: Double) {
        curLat += northM / METERS_PER_DEG_LAT
        curLng += eastM / (METERS_PER_DEG_LAT * cos(Math.toRadians(curLat)))
        speedMs = (hypot(northM, eastM) * 1000.0 / MOVE_TICK_MS).toFloat()
        bearingDeg = ((Math.toDegrees(atan2(eastM, northM)) + 360) % 360).toFloat()
        syncUi()
    }

    private fun walkToward(target: DoubleArray, step: Double) {
        val res = FloatArray(2)
        Location.distanceBetween(curLat, curLng, target[0], target[1], res)
        if (res[0] <= step) {
            curLat = target[0]
            curLng = target[1]
            walkTarget = null
            syncUi()
        } else {
            val b = Math.toRadians(res[1].toDouble())
            move(cos(b) * step, sin(b) * step)
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
        if (intent?.action == ACTION_GOTO) {
            if (running) goTo(intent) else stopSelf()
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
        walkTarget = null

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
        posLat = curLat
        posLng = curLng
        for (name in providers) {
            val loc = Location(name).apply {
                latitude = curLat
                longitude = curLng
                altitude = alt
                accuracy = 3f
                speed = speedMs
                bearing = bearingDeg
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
        header.addView(action("🗺") { openMap() })
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

        val joystick = JoystickView(ctx) { x, y -> setJoystick(x.toDouble(), y.toDouble()) }
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

    /**
     * Small dead zone, then a squared curve: small pushes move very slowly so
     * you can line up on a spot, full deflection still gives the top speed.
     */
    private fun setJoystick(x: Double, y: Double) {
        val mag = hypot(x, y)
        if (mag < JOY_DEAD_ZONE) {
            joyX = 0.0
            joyY = 0.0
            return
        }
        val t = ((mag - JOY_DEAD_ZONE) / (1 - JOY_DEAD_ZONE)).coerceAtMost(1.0)
        joyX = x / mag * t * t
        joyY = y / mag * t * t
    }

    private fun goTo(intent: Intent) {
        val lat = intent.getDoubleExtra(EXTRA_LAT, curLat)
        val lng = intent.getDoubleExtra(EXTRA_LNG, curLng)
        if (intent.getBooleanExtra(EXTRA_WALK, true)) {
            walkTarget = doubleArrayOf(lat, lng)
        } else {
            walkTarget = null
            curLat = lat
            curLng = lng
            pushLocation()
        }
        syncUi()
    }

    private fun openMap() {
        try {
            startActivity(Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the map: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetToMyLocation() {
        walkTarget = null
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
        val target = walkTarget
        distText.text = if (target != null) {
            val left = FloatArray(1)
            Location.distanceBetween(curLat, curLng, target[0], target[1], left)
            "Walking, ${formatDist(left[0])} to go"
        } else if (d[0] < 0.5f) {
            "At my location"
        } else {
            "${formatDist(d[0])} from start"
        }
        coordText.text = "%.6f, %.6f".format(curLat, curLng)
        speedBtn.text = "Speed: ${SPEED_NAMES[speedIndex]} (%.0f km/h)".format(SPEEDS[speedIndex] * 3.6)
    }
}
