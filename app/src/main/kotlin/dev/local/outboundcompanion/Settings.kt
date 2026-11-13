package dev.local.outboundcompanion

import android.content.SharedPreferences

/** User settings, persisted in SharedPreferences. Defaults reproduce the behaviour before Settings existed. */
class Settings(private val prefs: SharedPreferences) {
    /** 0 = Small, 1 = Medium (default), 2 = Large. */
    var markerSize: Int
        get() = prefs.getInt("set_marker_size", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("set_marker_size", v).apply() }
    var showLabels: Boolean
        get() = prefs.getBoolean("set_labels", true)
        set(v) { prefs.edit().putBoolean("set_labels", v).apply() }
    var showVan: Boolean
        get() = prefs.getBoolean("set_van", true)
        set(v) { prefs.edit().putBoolean("set_van", v).apply() }
    var showTracking: Boolean
        get() = prefs.getBoolean("set_tracking", true)
        set(v) { prefs.edit().putBoolean("set_tracking", v).apply() }
    var buttonsLeft: Boolean
        get() = prefs.getBoolean("set_buttons_left", false)
        set(v) { prefs.edit().putBoolean("set_buttons_left", v).apply() }
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("set_keep_on", false)
        set(v) { prefs.edit().putBoolean("set_keep_on", v).apply() }
    var followOnStart: Boolean
        get() = prefs.getBoolean("set_follow_start", false)
        set(v) { prefs.edit().putBoolean("set_follow_start", v).apply() }

    /** Map icons. The old "Show all points of interest" value (set_all_pois) is used until the new one is saved. */
    var showPois: Boolean
        get() = if (prefs.contains("set_map_pois")) prefs.getBoolean("set_map_pois", true) else prefs.getBoolean("set_all_pois", true)
        set(v) { prefs.edit().putBoolean("set_map_pois", v).apply() }
    var showCampfires: Boolean
        get() = prefs.getBoolean("set_map_campfires", true)
        set(v) { prefs.edit().putBoolean("set_map_campfires", v).apply() }
    var showTowers: Boolean
        get() = prefs.getBoolean("set_map_towers", true)
        set(v) { prefs.edit().putBoolean("set_map_towers", v).apply() }
    var showCaches: Boolean
        get() = prefs.getBoolean("set_map_caches", false)
        set(v) { prefs.edit().putBoolean("set_map_caches", v).apply() }

    val markerScale: Float get() = SCALES[markerSize]

    companion object {
        private val SCALES = floatArrayOf(0.75f, 1f, 1.4f)
    }
}
