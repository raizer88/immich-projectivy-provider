package tv.projectivy.plugin.wallpaperprovider.immich

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/**
 * Projectivy wallpaper-provider plugin. Projectivy binds to this service and calls
 * [getWallpapers] to obtain the list of wallpapers to rotate.
 *
 * Because Immich image URLs require the `x-api-key` header (which Projectivy cannot
 * send), this service downloads "preview"-size images itself and returns
 * `content://` URIs backed by [WallpaperImageProvider].
 */
class WallpaperProviderService : Service() {

    private lateinit var client: ImmichClient

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        client = ImmichClient()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> = buildWallpapers()

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }

    private fun buildWallpapers(): List<Wallpaper> {
        if (!PreferencesManager.isConfigured) {
            Log.i(TAG, "Not configured; returning no wallpapers")
            return emptyList()
        }

        val baseUrl = client.normalizeBaseUrl(PreferencesManager.serverUrl)
        val apiKey = PreferencesManager.apiKey
        val trustSsl = PreferencesManager.trustSsl
        val cache = WallpaperCache(this)

        // Best-effort fresh asset list (never let a network failure block the call).
        val fresh = try {
            client.fetchAssets(
                baseUrl, apiKey, trustSsl,
                PreferencesManager.source, PreferencesManager.albumId, PreferencesManager.imageCount
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchAssets failed: ${e.message}")
            emptyList()
        }

        val titles = fresh.associate { it.id to it.fileName }

        // Candidate ids: fresh assets (shuffled for variety) or, as a fallback when the
        // list fetch failed, whatever is already on disk so the wallpaper keeps working.
        val ids: List<String> = if (fresh.isNotEmpty()) {
            fresh.shuffled().map { it.id }
        } else {
            cache.listCachedAssetIds()
        }

        val wallpapers = ArrayList<Wallpaper>(ids.size)
        for (id in ids) {
            try {
                val file = cache.ensureCached(id) {
                    client.fetchImage(baseUrl, apiKey, trustSsl, id, WallpaperCache.PREVIEW_SIZE)
                }
                if (file != null) {
                    val title = titles[id]?.takeIf { it.isNotBlank() }
                    wallpapers.add(
                        Wallpaper(
                            uri = cache.uriFor(id).toString(),
                            type = WallpaperType.IMAGE,
                            displayMode = WallpaperDisplayMode.CROP,
                            title = title
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Caching asset $id failed: ${e.message}")
            }
        }

        cache.enforceSizeLimit(PreferencesManager.cacheLimitMb * 1024L * 1024L)

        Log.i(TAG, "Returning ${wallpapers.size} wallpapers")
        return wallpapers
    }

    companion object {
        private const val TAG = "ImmichWallpaper"
    }
}
