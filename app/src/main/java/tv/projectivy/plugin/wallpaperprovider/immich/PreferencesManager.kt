package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
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
    private const val KEY_ALBUM_NAME = "album_name"
    private const val KEY_IMAGE_COUNT = "image_count"
    private const val KEY_SECONDS = "seconds_per_image"
    private const val KEY_CACHE_LIMIT_MB = "cache_limit_mb"
    private const val KEY_TRUST_SSL = "trust_ssl"

    const val SOURCE_RECENT = "recent"
    const val SOURCE_FAVORITES = "favorites"
    const val SOURCE_RANDOM = "random"
    const val SOURCE_ALBUM = "album"

    const val DEFAULT_IMAGE_COUNT = 20
    const val DEFAULT_SECONDS = 300
    const val DEFAULT_CACHE_LIMIT_MB = 200
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 3600

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
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

    var albumId: String
        get() = prefs.getString(KEY_ALBUM_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALBUM_ID, value).apply()

    var albumName: String
        get() = prefs.getString(KEY_ALBUM_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALBUM_NAME, value).apply()

    var imageCount: Int
        get() = prefs.getInt(KEY_IMAGE_COUNT, DEFAULT_IMAGE_COUNT).coerceIn(1, 100)
        set(value) = prefs.edit().putInt(KEY_IMAGE_COUNT, value.coerceIn(1, 100)).apply()

    var secondsPerImage: Int
        get() = prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        set(value) = prefs.edit().putInt(KEY_SECONDS, value.coerceIn(MIN_SECONDS, MAX_SECONDS)).apply()

    var cacheLimitMb: Int
        get() = prefs.getInt(KEY_CACHE_LIMIT_MB, DEFAULT_CACHE_LIMIT_MB).coerceIn(10, 2048)
        set(value) = prefs.edit().putInt(KEY_CACHE_LIMIT_MB, value.coerceIn(10, 2048)).apply()

    var trustSsl: Boolean
        get() = prefs.getBoolean(KEY_TRUST_SSL, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_SSL, value).apply()

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
        json.put(KEY_ALBUM_ID, albumId)
        json.put(KEY_ALBUM_NAME, albumName)
        json.put(KEY_IMAGE_COUNT, imageCount)
        json.put(KEY_SECONDS, secondsPerImage)
        json.put(KEY_CACHE_LIMIT_MB, cacheLimitMb)
        json.put(KEY_TRUST_SSL, trustSsl)
        return json.toString()
    }

    fun import(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            if (json.has(KEY_SERVER_URL)) serverUrl = json.getString(KEY_SERVER_URL)
            if (json.has(KEY_API_KEY)) apiKey = json.getString(KEY_API_KEY)
            if (json.has(KEY_SOURCE)) source = json.getString(KEY_SOURCE)
            if (json.has(KEY_ALBUM_ID)) albumId = json.getString(KEY_ALBUM_ID)
            if (json.has(KEY_ALBUM_NAME)) albumName = json.getString(KEY_ALBUM_NAME)
            if (json.has(KEY_IMAGE_COUNT)) imageCount = json.getInt(KEY_IMAGE_COUNT)
            if (json.has(KEY_SECONDS)) secondsPerImage = json.getInt(KEY_SECONDS)
            if (json.has(KEY_CACHE_LIMIT_MB)) cacheLimitMb = json.getInt(KEY_CACHE_LIMIT_MB)
            if (json.has(KEY_TRUST_SSL)) trustSsl = json.getBoolean(KEY_TRUST_SSL)
            true
        } catch (e: Exception) {
            false
        }
    }
}
