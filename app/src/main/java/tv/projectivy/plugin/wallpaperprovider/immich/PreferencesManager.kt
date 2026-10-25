package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user configuration in the app's default SharedPreferences.
 *
 * Exposes [export]/[import] so Projectivy can back up and restore the plugin's
 * settings through the AIDL getPreferences()/setPreferences() calls. Note that
 * the exported JSON contains the Immich API key in plain text — the same way it
 * is stored on the device — so treat any Projectivy backup accordingly.
 */
object PreferencesManager {

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_SOURCE = "source"
    private const val KEY_ALBUM_ID = "album_id"
    private const val KEY_ALBUM_IDS = "album_ids"
    private const val KEY_ALBUM_NAME = "album_name"
    private const val KEY_IMAGE_COUNT = "image_count"
    private const val KEY_SECONDS = "seconds_per_image"
    private const val KEY_TRUST_SSL = "trust_ssl"
    private const val KEY_LANDSCAPE_ONLY = "landscape_only"
    private const val KEY_INCLUDE_ARCHIVED_HIDDEN = "include_archived_hidden"
    private const val KEY_QUALITY = "quality"

    const val SOURCE_RECENT = "recent"
    const val SOURCE_FAVORITES = "favorites"
    const val SOURCE_RANDOM = "random"
    const val SOURCE_ALBUM = "album"

    const val QUALITY_PREVIEW = "preview"
    const val QUALITY_FULL_JPEG_PNG = "full_jpeg_png"
    const val QUALITY_FULL_ALL = "full_all"

    const val DEFAULT_IMAGE_COUNT = 20
    const val DEFAULT_SECONDS = 300
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 3600

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        migrateLegacyAlbum()
    }

    /** One-time migration from the legacy single-album field to the multi-album field. */
    private fun migrateLegacyAlbum() {
        if (prefs.contains(KEY_ALBUM_IDS)) return
        val legacy = prefs.getString(KEY_ALBUM_ID, "").orEmpty()
        if (legacy.isNotBlank()) {
            prefs.edit().putString(KEY_ALBUM_IDS, JSONArray(listOf(legacy)).toString()).apply()
        }
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var source: String
        get() = prefs.getString(KEY_SOURCE, SOURCE_RECENT) ?: SOURCE_RECENT
        set(value) = prefs.edit().putString(KEY_SOURCE, value).apply()

    /** Legacy single-album id; kept in sync with [albumIds] for downgrade-compatible exports. */
    var albumId: String
        get() = prefs.getString(KEY_ALBUM_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALBUM_ID, value).apply()

    /**
     * Selected album ids. The legacy single-album value is migrated in [init] so this
     * getter is a pure read.
     */
    var albumIds: List<String>
        get() {
            val raw = prefs.getString(KEY_ALBUM_IDS, null)
            return if (raw != null) parseIds(raw) else emptyList()
        }
        set(value) {
            val ids = value.distinct()
            prefs.edit().putString(KEY_ALBUM_IDS, JSONArray(ids).toString()).apply()
            albumId = ids.firstOrNull() ?: ""
        }

    var albumName: String
        get() = prefs.getString(KEY_ALBUM_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALBUM_NAME, value).apply()

    var imageCount: Int
        get() = prefs.getInt(KEY_IMAGE_COUNT, DEFAULT_IMAGE_COUNT).coerceIn(1, 100)
        set(value) = prefs.edit().putInt(KEY_IMAGE_COUNT, value.coerceIn(1, 100)).apply()

    var secondsPerImage: Int
        get() = prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        set(value) = prefs.edit().putInt(KEY_SECONDS, value.coerceIn(MIN_SECONDS, MAX_SECONDS)).apply()

    var trustSsl: Boolean
        get() = prefs.getBoolean(KEY_TRUST_SSL, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_SSL, value).apply()

    /**
     * When true (default), only non-portrait photos are returned. Portrait wallpapers make
     * Projectivy mis-render its UI around the wallpaper on some TVs.
     */
    var landscapeOnly: Boolean
        get() = prefs.getBoolean(KEY_LANDSCAPE_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_LANDSCAPE_ONLY, value).apply()

    /**
     * When false (default), assets archived or hidden in Immich are excluded from the
     * rotation. When true, they are included — e.g. for an archive used as a dedicated
     * wallpaper album. The locked folder stays server-side excluded either way.
     */
    var includeArchivedHidden: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_ARCHIVED_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_ARCHIVED_HIDDEN, value).apply()

    /**
     * Wallpaper quality. One of [QUALITY_PREVIEW] (default, max compatibility),
     * [QUALITY_FULL_JPEG_PNG] (original only for JPEG/PNG) or [QUALITY_FULL_ALL]
     * (original for every format).
     */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, QUALITY_PREVIEW) ?: QUALITY_PREVIEW
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /** True once the minimum required configuration is present. */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** Masked API key for display, e.g. "••••abcd". */
    fun maskedApiKey(): String {
        val key = apiKey
        if (key.length <= 4) return if (key.isEmpty()) "" else "••••"
        return "••••" + key.takeLast(4)
    }

    fun export(): String {
        val json = JSONObject()
        json.put(KEY_SERVER_URL, serverUrl)
        json.put(KEY_API_KEY, apiKey)
        json.put(KEY_SOURCE, source)
        json.put(KEY_ALBUM_IDS, JSONArray(albumIds))
        json.put(KEY_ALBUM_ID, albumId)
        json.put(KEY_ALBUM_NAME, albumName)
        json.put(KEY_IMAGE_COUNT, imageCount)
        json.put(KEY_SECONDS, secondsPerImage)
        json.put(KEY_TRUST_SSL, trustSsl)
        json.put(KEY_LANDSCAPE_ONLY, landscapeOnly)
        json.put(KEY_INCLUDE_ARCHIVED_HIDDEN, includeArchivedHidden)
        json.put(KEY_QUALITY, quality)
        return json.toString()
    }

    /**
     * Imports settings from a JSON object produced by [export] (or by Projectivy's
     * backup). Parsing is defensive and atomic: values are coerced (numbers/booleans may
     * arrive as JSON primitives or as strings) and are only committed if the whole object
     * parses successfully, so a malformed value never leaves a half-applied config.
     */
    fun import(jsonString: String): Boolean {
        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Preferences import failed: invalid JSON", e)
            return false
        }

        val editor = prefs.edit()
        return try {
            if (json.has(KEY_SERVER_URL)) editor.putString(KEY_SERVER_URL, json.optString(KEY_SERVER_URL).trim())
            if (json.has(KEY_API_KEY)) editor.putString(KEY_API_KEY, json.optString(KEY_API_KEY).trim())
            if (json.has(KEY_SOURCE)) editor.putString(KEY_SOURCE, json.optString(KEY_SOURCE))
            if (json.has(KEY_ALBUM_IDS)) {
                val arr = json.optJSONArray(KEY_ALBUM_IDS)
                val ids = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.distinct().filter { it.isNotBlank() }
                editor.putString(KEY_ALBUM_IDS, JSONArray(ids).toString())
            } else if (json.has(KEY_ALBUM_ID) && json.optString(KEY_ALBUM_ID).isNotBlank()) {
                editor.putString(KEY_ALBUM_IDS, JSONArray(listOf(json.optString(KEY_ALBUM_ID))).toString())
            }
            if (json.has(KEY_ALBUM_ID)) editor.putString(KEY_ALBUM_ID, json.optString(KEY_ALBUM_ID))
            if (json.has(KEY_ALBUM_NAME)) editor.putString(KEY_ALBUM_NAME, json.optString(KEY_ALBUM_NAME))
            if (json.has(KEY_IMAGE_COUNT)) editor.putInt(KEY_IMAGE_COUNT, intValue(json, KEY_IMAGE_COUNT, DEFAULT_IMAGE_COUNT).coerceIn(1, 100))
            if (json.has(KEY_SECONDS)) editor.putInt(KEY_SECONDS, intValue(json, KEY_SECONDS, DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS))
            if (json.has(KEY_TRUST_SSL)) editor.putBoolean(KEY_TRUST_SSL, boolValue(json, KEY_TRUST_SSL))
            if (json.has(KEY_LANDSCAPE_ONLY)) editor.putBoolean(KEY_LANDSCAPE_ONLY, boolValue(json, KEY_LANDSCAPE_ONLY))
            if (json.has(KEY_INCLUDE_ARCHIVED_HIDDEN)) editor.putBoolean(KEY_INCLUDE_ARCHIVED_HIDDEN, boolValue(json, KEY_INCLUDE_ARCHIVED_HIDDEN))
            if (json.has(KEY_QUALITY)) editor.putString(KEY_QUALITY, json.optString(KEY_QUALITY))
            editor.apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Preferences import failed", e)
            false
        }
    }

    private fun intValue(json: JSONObject, key: String, default: Int): Int = when (val v = json.opt(key)) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: default
        else -> default
    }

    private fun boolValue(json: JSONObject, key: String): Boolean = when (val v = json.opt(key)) {
        is Boolean -> v
        is String -> v.trim().equals("true", ignoreCase = true)
        else -> false
    }

    private fun parseIds(raw: String): List<String> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private const val TAG = "ImmichPrefs"
}
