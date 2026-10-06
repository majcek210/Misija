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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
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
        private const val SLIDER_STEPS = 1000
        private const val NUDGE_M = 5.0
        private val RANGES_M = intArrayOf(200, 1000, 5000, 20000)

        @Volatile
        var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val providers = mutableListOf<String>()

    // Real position captured at start, and the current base (moved by "Pin").
    private var realLat = 0.0
    private var realLng = 0.0
    private var baseLat = 0.0
    private var baseLng = 0.0
    private var alt = 0.0
    private var offsetM = 0.0 // positive = north
    private var rangeIndex = 1

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var body: LinearLayout
    private lateinit var foldBtn: TextView
    private lateinit var offsetText: TextView
    private lateinit var coordText: TextView
    private lateinit var rangeBtn: TextView
    private lateinit var slider: SeekBar
    private var ignoreSlider = false

    private val tick = object : Runnable {
        override fun run() {
            pushLocation()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        rangeIndex = prefs.rangeIndex.coerceIn(RANGES_M.indices)
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
        baseLat = realLat
        baseLng = realLng
        offsetM = 0.0

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

    private fun currentLat() = baseLat + offsetM / METERS_PER_DEG_LAT
    private fun currentLng() = baseLng

    private fun pushLocation() {
        val lat = currentLat()
        val lng = currentLng()
        for (name in providers) {
            val loc = Location(name).apply {
                latitude = lat
                longitude = lng
                altitude = alt
                accuracy = 5f
                speed = 0f
                bearing = 0f
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

    private fun rangeM() = RANGES_M[rangeIndex].toDouble()

    private fun formatDist(m: Int): String =
        if (abs(m) >= 1000) "%.1f km".format(m / 1000.0) else "$m m"

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

        fun pill(label: String, onClick: () -> Unit) = action(label, onClick).apply {
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(255, 60, 64, 72))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
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

        // Body: vertical slider on the left, info + buttons on the right
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sliderLen = dp(220)
        val sliderThick = dp(48)
        slider = SeekBar(ctx).apply {
            max = SLIDER_STEPS
            progress = SLIDER_STEPS / 2
            rotation = 270f // right becomes up, so higher value = north
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (ignoreSlider || !fromUser) return
                    offsetM = ((p - SLIDER_STEPS / 2).toDouble() / (SLIDER_STEPS / 2)) * rangeM()
                    offsetM = offsetM.roundToInt().toDouble()
                    syncUi(updateSlider = false)
                    pushLocation()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val sliderBox = FrameLayout(ctx).apply {
            clipChildren = false
            addView(slider, FrameLayout.LayoutParams(sliderLen, sliderThick, Gravity.CENTER))
        }
        val sliderCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(TextView(ctx).apply { text = "N ▲"; setTextColor(textColor) })
            addView(sliderBox, LinearLayout.LayoutParams(sliderThick, sliderLen))
            addView(TextView(ctx).apply { text = "S ▼"; setTextColor(textColor) })
        }
        body.addView(sliderCol)

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        offsetText = TextView(ctx).apply {
            setTextColor(textColor)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        coordText = TextView(ctx).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        info.addView(offsetText)
        info.addView(coordText)
        info.addView(pill("▲ +${NUDGE_M.toInt()} m") { nudge(NUDGE_M) })
        info.addView(pill("▼ −${NUDGE_M.toInt()} m") { nudge(-NUDGE_M) })
        rangeBtn = pill("") { cycleRange() }
        info.addView(rangeBtn)
        info.addView(pill("📌 Pin here") { pinHere() })
        body.addView(info, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
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
            panelParams = params
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
        baseLat = realLat
        baseLng = realLng
        offsetM = 0.0
        syncUi()
        pushLocation()
    }

    private fun nudge(deltaM: Double) {
        offsetM += deltaM
        syncUi()
        pushLocation()
    }

    private fun cycleRange() {
        rangeIndex = (rangeIndex + 1) % RANGES_M.size
        prefs.rangeIndex = rangeIndex
        offsetM = offsetM.coerceIn(-rangeM(), rangeM())
        syncUi()
        pushLocation()
    }

    /** Make the current position the new slider centre, so you can keep going further. */
    private fun pinHere() {
        baseLat = currentLat()
        offsetM = 0.0
        syncUi()
        pushLocation()
    }

    private fun syncUi(updateSlider: Boolean = true) {
        if (!::offsetText.isInitialized) return
        val fromReal = ((currentLat() - realLat) * METERS_PER_DEG_LAT).roundToInt()
        val dir = when {
            fromReal > 0 -> "N"
            fromReal < 0 -> "S"
            else -> ""
        }
        offsetText.text = if (fromReal == 0) "At my location" else "${formatDist(abs(fromReal))} $dir"
        coordText.text = "%.6f, %.6f".format(currentLat(), currentLng())
        rangeBtn.text = "Range ±${formatDist(RANGES_M[rangeIndex])}"
        if (updateSlider) {
            val half = SLIDER_STEPS / 2
            val p = half + (offsetM / rangeM() * half).roundToInt()
            ignoreSlider = true
            slider.progress = p.coerceIn(0, SLIDER_STEPS)
            ignoreSlider = false
        }
    }
}
