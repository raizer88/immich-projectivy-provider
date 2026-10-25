package tv.projectivy.plugin.wallpaperprovider.immich

import java.io.File

/**
 * Shared "download + cache" logic used by both the wallpaper service
 * ([WallpaperProviderService]) and the settings "Preload wallpapers" action.
 */
object WallpaperLoader {

    /**
     * Downloads [assets] into [cache] until [deadlineMs] (wall-clock), returning the
     * (asset, file) pairs that are actually served. A previously-cached file is reused
     * without re-downloading. [onProgress] is invoked after each asset as (done, total).
     */
    fun download(
        client: ImmichClient,
        cache: WallpaperCache,
        baseUrl: String,
        apiKey: String,
        trustSsl: Boolean,
        assets: List<ImmichAsset>,
        quality: String,
        deadlineMs: Long,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<Pair<ImmichAsset, File>> {
        val served = ArrayList<Pair<ImmichAsset, File>>(assets.size)
        val total = assets.size
        for ((index, asset) in assets.withIndex()) {
            if (System.currentTimeMillis() > deadlineMs) break
            val serveOriginal = quality == PreferencesManager.QUALITY_FULL_ALL ||
                (quality == PreferencesManager.QUALITY_FULL_JPEG_PNG && asset.canServeOriginal)
            val variant = if (serveOriginal) WallpaperCache.ORIGINAL_SIZE else WallpaperCache.PREVIEW_SIZE
            val file = cache.ensureCached(asset.id, variant) {
                client.fetchImage(baseUrl, apiKey, trustSsl, asset.id, original = serveOriginal)
            }
            if (file != null) served.add(asset to file)
            onProgress?.invoke(index + 1, total)
        }
        return served
    }
}
