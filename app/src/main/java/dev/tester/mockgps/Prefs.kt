package dev.tester.mockgps

import android.content.Context
import android.content.SharedPreferences

/** Small wrapper around the app's SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("mockgps", Context.MODE_PRIVATE)

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    var panelX: Int
        get() = sp.getInt("panel_x", 40)
        set(v) = sp.edit().putInt("panel_x", v).apply()

    var panelY: Int
        get() = sp.getInt("panel_y", 200)
        set(v) = sp.edit().putInt("panel_y", v).apply()

    var rangeIndex: Int
        get() = sp.getInt("range_index", 1)
        set(v) = sp.edit().putInt("range_index", v).apply()
}
