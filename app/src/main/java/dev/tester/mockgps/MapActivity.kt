package dev.tester.mockgps

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.roundToInt

/** Map to pick a spot: tap it, then walk there at the panel's speed or jump straight there. */
class MapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var meMarker: Marker
    private lateinit var pickMarker: Marker
    private lateinit var info: TextView
    private lateinit var walkBtn: Button
    private lateinit var jumpBtn: Button
    private var picked: GeoPoint? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Keeps the blue dot on the mocked position. */
    private val refresh = object : Runnable {
        override fun run() {
            meMarker.position = GeoPoint(MockLocationService.posLat, MockLocationService.posLng)
            map.invalidate()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MockLocationService.running) {
            Toast.makeText(this, "Start mocking first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        val me = GeoPoint(MockLocationService.posLat, MockLocationService.posLng)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
            controller.setCenter(me)
        }

        // Taps on the markers fall through to the map (listener returns false).
        meMarker = Marker(map).apply {
            position = me
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setIcon(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 136, 229))
                setStroke(dp(3), Color.WHITE)
                setSize(dp(20), dp(20))
            })
            setOnMarkerClickListener { _, _ -> false }
        }
        pickMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> false }
        }

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                pick(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                pick(p)
                return true
            }
        }))
        map.overlays.add(meMarker)

        info = TextView(this).apply {
            text = "Tap the map to pick a spot."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        walkBtn = button("Walk there") { go(walk = true) }
        jumpBtn = button("Jump there") { go(walk = false) }
        val center = button("◎") { map.controller.animateTo(meMarker.position) }
        setPickEnabled(false)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(walkBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(jumpBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(center, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(230, 28, 30, 34))
            addView(info)
            addView(row)
        }

        setContentView(FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        map.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        if (!::map.isInitialized) return
        handler.removeCallbacks(refresh)
        map.onPause()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun setPickEnabled(on: Boolean) {
        walkBtn.isEnabled = on
        jumpBtn.isEnabled = on
    }

    private fun pick(p: GeoPoint) {
        picked = p
        pickMarker.position = p
        if (pickMarker !in map.overlays) map.overlays.add(pickMarker)
        map.invalidate()

        val d = FloatArray(1)
        Location.distanceBetween(
            MockLocationService.posLat, MockLocationService.posLng, p.latitude, p.longitude, d
        )
        val dist = if (d[0] >= 1000f) "%.2f km".format(d[0] / 1000f) else "${d[0].roundToInt()} m"
        info.text = "%.6f, %.6f  ·  %s away".format(p.latitude, p.longitude, dist)
        setPickEnabled(true)
    }

    private fun go(walk: Boolean) {
        val p = picked ?: return
        if (!MockLocationService.running) {
            Toast.makeText(this, "Mocking has stopped.", Toast.LENGTH_LONG).show()
            return
        }
        startService(
            Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_GOTO)
                .putExtra(MockLocationService.EXTRA_LAT, p.latitude)
                .putExtra(MockLocationService.EXTRA_LNG, p.longitude)
                .putExtra(MockLocationService.EXTRA_WALK, walk)
        )
        // Back to whatever app was open before the map.
        finish()
    }
}
