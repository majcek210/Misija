package dev.tester.mockgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var fetching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Mock GPS"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Start takes your real position as the starting point. " +
                "The floating panel's joystick then walks the mocked position " +
                "in any direction from there."
            setPadding(0, dp(8), 0, dp(16))
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(status)

        root.addView(button("1. Grant location + notification permission") { requestRuntimePermissions() })
        root.addView(button("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Developer options (Select mock location app)") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                setStatus("Developer options are not enabled. Go to Settings > About phone and tap Build number 7 times.")
            }
        })
        root.addView(button("Start from my location") { start() })
        root.addView(button("Stop") {
            stopService(Intent(this, MockLocationService::class.java))
            handler.postDelayed({ refreshStatus() }, 300)
        })

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun setStatus(msg: String) {
        status.text = msg
    }

    // ---- permission checks ----

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun isMockApp(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun refreshStatus() {
        if (fetching) return
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val lines = mutableListOf(
            "${mark(hasLocation())} Location permission",
            "${mark(hasOverlay())} Display over other apps",
            "${mark(isMockApp())} Selected as mock location app",
        )
        if (Build.VERSION.SDK_INT >= 33) {
            lines += "${if (hasNotifications()) "✅" else "⚠️"} Notifications (optional, shows the running notice)"
        }
        lines += ""
        lines += if (MockLocationService.running) "▶ Mocking is running." else "⏹ Not running."
        prefs.lastError?.let { lines += "\nLast problem: $it" }
        setStatus(lines.joinToString("\n"))
    }

    // ---- start flow ----

    private fun start() {
        val missing = mutableListOf<String>()
        if (!hasLocation()) missing += "location permission (button 1)"
        if (!hasOverlay()) missing += "display over other apps (button 2)"
        if (!isMockApp()) missing += "select Mock GPS as mock location app (button 3)"
        if (missing.isNotEmpty()) {
            setStatus("Can't start yet. Missing:\n• " + missing.joinToString("\n• "))
            return
        }
        prefs.lastError = null
        fetching = true
        if (MockLocationService.running) {
            // While mocking, the providers return fake fixes, so release them first.
            stopService(Intent(this, MockLocationService::class.java))
            setStatus("Releasing the mock, then reading your real location…")
            handler.postDelayed({ fetchRealLocation() }, 1500)
        } else {
            fetchRealLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchRealLocation() {
        setStatus("Getting your real location… (go near a window if this takes long)")
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (candidates.isEmpty()) {
            fetching = false
            setStatus("Location is turned off. Turn on Location in quick settings and try again.")
            return
        }

        var done = false
        val listeners = mutableListOf<LocationListener>()
        fun finish(loc: Location?) {
            if (done) return
            done = true
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            fetching = false
            if (loc == null) {
                setStatus("Couldn't get your location. Make sure Location is on and try again.")
            } else {
                launchService(loc)
            }
        }

        // Ask every enabled provider; the first fix wins.
        for (p in candidates) {
            // Full object (not a lambda): on API < 30 these methods are abstract.
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = finish(location)
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            listeners += l
            try {
                lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper())
            } catch (e: Exception) {
                // provider unavailable, ignore
            }
        }

        // Fall back to the freshest cached fix after 25 s.
        handler.postDelayed({
            val best = candidates
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            finish(best)
        }, 25_000)
    }

    private fun launchService(loc: Location) {
        val i = Intent(this, MockLocationService::class.java)
            .putExtra(MockLocationService.EXTRA_LAT, loc.latitude)
            .putExtra(MockLocationService.EXTRA_LNG, loc.longitude)
            .putExtra(MockLocationService.EXTRA_ALT, if (loc.hasAltitude()) loc.altitude else 0.0)
        ContextCompat.startForegroundService(this, i)
        setStatus(
            "Started at %.6f, %.6f.\nUse the joystick on the floating panel to move."
                .format(loc.latitude, loc.longitude)
        )
        handler.postDelayed({ refreshStatus() }, 1500)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
