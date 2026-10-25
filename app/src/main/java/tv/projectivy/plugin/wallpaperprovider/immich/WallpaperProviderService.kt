package tv.projectivy.plugin.wallpaperprovider.immich

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
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
 * Immich image URLs require the `x-api-key` header, which Projectivy cannot send when it
 * downloads a wallpaper by URL. So this service downloads the images itself (preview or
 * full-resolution original, depending on the quality setting) and returns
 * `content://` URIs backed by [WallpaperImageProvider], granting read access per URI to
 * Projectivy alone. The Immich API key never leaves this process.
 */
class WallpaperProviderService : Service() {

    private lateinit var client: ImmichClient
    private lateinit var cache: WallpaperCache

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        client = ImmichClient()
        cache = WallpaperCache(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            assertCallerIsLauncher()
            return buildWallpapers()
        }

        override fun getPreferences(): String {
            assertCallerIsLauncher()
            return PreferencesManager.export()
        }

        override fun setPreferences(params: String) {
            assertCallerIsLauncher()
            PreferencesManager.import(params)
        }
    }

    /**
     * Rejects binder calls from anything but Projectivy.
     *
     * This service must be `exported` for Projectivy to bind it, and `android:permission`
     * is not an option because Projectivy holds no permission of ours — so without a check
     * here, every method is reachable by any app on the device. All three are sensitive,
     * not just the obvious one: [getPreferences] returns the API key verbatim,
     * [setPreferences] can repoint `server_url` and switch on `trust_ssl`, and
     * [getWallpapers] hands back readable image URIs. A UID comparison is the whole fix;
     * package visibility for the lookup is already declared via `<queries>` in the manifest.
     */
    private fun assertCallerIsLauncher() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return
        val allowedUid = try {
            packageManager.getApplicationInfo(PROJECTIVY_PACKAGE, 0).uid
        } catch (e: Exception) {
            Log.w(TAG, "$PROJECTIVY_PACKAGE not installed; rejecting uid $callingUid")
            -1
        }
        if (callingUid != allowedUid) {
            throw SecurityException("uid $callingUid is not $PROJECTIVY_PACKAGE")
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
        val requested = PreferencesManager.imageCount
        val landscapeOnly = PreferencesManager.landscapeOnly

        // Fetch extra when filtering so we can still fill the requested count.
        val fetchCount = if (landscapeOnly) minOf(requested * 3, 200) else requested

        val assets = try {
            client.fetchAssets(
                baseUrl, apiKey, trustSsl,
                PreferencesManager.source, PreferencesManager.albumIds, fetchCount
            )
        } catch (e: ImmichException) {
            Log.w(TAG, "fetchAssets failed (HTTP ${e.httpCode}): ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchAssets failed: ${e.message}", e)
            emptyList()
        }

        val quality = PreferencesManager.quality

        // Landscape/square only by default: portrait wallpapers can make Projectivy
        // mis-render the whole launcher UI on some TVs. HEIC/RAW originals are just as
        // risky, so QUALITY_FULL_JPEG_PNG keeps the JPEG preview for them.
        val usable = (if (landscapeOnly) assets.filter { it.isLandscape } else assets)
            .take(requested)

        // Projectivy is blocked in a binder transaction for the duration of this call, so
        // the downloads get a wall-clock budget: a slow or half-dead Immich must not hold
        // the launcher indefinitely. Anything already cached from an earlier pass is still
        // served, so exhausting the budget degrades the rotation rather than emptying it.
        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
        var fetched = 0
        for (asset in usable) {
            if (System.currentTimeMillis() > deadline) {
                Log.i(TAG, "Download budget spent after $fetched asset(s); serving the cache")
                break
            }
            val serveOriginal = quality == PreferencesManager.QUALITY_FULL_ALL ||
                (quality == PreferencesManager.QUALITY_FULL_JPEG_PNG && asset.canServeOriginal)
            val file = cache.ensureCached(
                asset.id,
                if (serveOriginal) WallpaperCache.ORIGINAL_SIZE else WallpaperCache.PREVIEW_SIZE
            ) {
                client.fetchImage(baseUrl, apiKey, trustSsl, asset.id, original = serveOriginal)
            }
            if (file != null) fetched++
        }
        cache.enforceSizeLimit(MAX_CACHE_BYTES)

        val titles = assets.associate { it.id to it.fileName }
        val wallpapers = cache.listCached().shuffled().map { cached ->
            val uri = cache.uriFor(cached.fileName)
            // Ad-hoc grants do not survive a reboot; re-issuing them on every call is what
            // keeps that self-healing, since Projectivy re-asks once the item cache expires.
            grantUriPermission(PROJECTIVY_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Wallpaper(
                uri = uri.toString(),
                type = WallpaperType.IMAGE,
                displayMode = WallpaperDisplayMode.CROP,
                title = titles[cached.assetId]?.takeIf { it.isNotBlank() }
            )
        }

        Log.i(TAG, "Returning ${wallpapers.size} wallpapers ($fetched newly downloaded)")
        return wallpapers
    }

    companion object {
        private const val TAG = "ImmichWallpaper"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
        private const val DOWNLOAD_BUDGET_MS = 20_000L
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
