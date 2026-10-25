package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.Context
import android.net.Uri
import java.io.File

/** A wallpaper currently present in the cache. */
data class CachedImage(val assetId: String, val fileName: String)

/**
 * On-disk cache of Immich images.
 *
 * Files are stored in the app's internal cache dir and served to Projectivy via
 * [WallpaperImageProvider] as `content://` URIs. A file name is
 * `<assetId>.<variant>.<ext>`, where variant is `preview` or `original`: switching the
 * quality setting therefore never serves bytes cached under the previous setting, and
 * the extension (and MIME type reported by the provider) follows what Immich actually
 * returned. Enforces a total size limit by evicting oldest-first (the Fire TV has little
 * free storage).
 */
class WallpaperCache(private val context: Context) {

    companion object {
        const val AUTHORITY = "tv.projectivy.plugin.wallpaperprovider.immich.provider"
        const val PREVIEW_SIZE = "preview"
        const val ORIGINAL_SIZE = "original"

        private val MIME_TO_EXT = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/avif" to "avif"
        )
        private val EXT_TO_MIME = MIME_TO_EXT.entries.associate { (mime, ext) -> ext to mime }
        private val KNOWN_EXTENSIONS = MIME_TO_EXT.values.toSet()

        /** File extension to store an image of [mimeType] under. Unknown types fall back to jpg. */
        fun extensionForMime(mimeType: String): String =
            MIME_TO_EXT[mimeType.substringBefore(';').trim().lowercase()] ?: "jpg"

        /** MIME type to report for a cached file name, derived from its extension. */
        fun mimeTypeForName(name: String): String =
            EXT_TO_MIME[name.substringAfterLast('.', "").lowercase()] ?: "image/jpeg"
    }

    private val dir: File
        get() = File(context.cacheDir, "wallpapers")

    /** Sanitized file lookup by raw URI path segment. */
    fun fileByName(name: String): File? {
        if (name.contains('/') || name.contains("..")) return null
        if (name.substringAfterLast('.', "") !in KNOWN_EXTENSIONS) return null
        return File(dir, name)
    }

    fun uriFor(fileName: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath(fileName)
        .build()

    /**
     * Returns the cached file for [assetId] at [variant], downloading via [download] only
     * when missing or cached under a different variant. Only one variant per asset is kept,
     * so a quality change replaces the previous bytes instead of serving both.
     */
    @Synchronized
    fun ensureCached(assetId: String, variant: String, download: () -> ImmichImage?): File? {
        val prefix = "$assetId."
        val existing = dir.listFiles()?.firstOrNull {
            it.isFile && it.length() > 0 && it.name.startsWith(prefix)
        }
        if (existing != null && existing.name.startsWith("$assetId.$variant.")) return existing
        val image = download() ?: return existing
        if (image.bytes.isEmpty()) return existing
        dir.mkdirs()
        val file = File(dir, "$assetId.$variant.${extensionForMime(image.mimeType)}")
        file.writeBytes(image.bytes)
        dir.listFiles()
            ?.filter { it != file && it.isFile && it.name.startsWith(prefix) }
            ?.forEach { it.delete() }
        return file
    }

    /** Deletes oldest files until the total size is within [maxBytes]. */
    @Synchronized
    fun enforceSizeLimit(maxBytes: Long) {
        val files = dir.listFiles() ?: return
        var total = files.fold(0L) { acc, f -> acc + f.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** Cached wallpapers, newest first, one entry per asset. */
    @Synchronized
    fun listCached(): List<CachedImage> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                val parts = file.name.split('.')
                if (parts.size != 3 || parts[0].isBlank() || parts[2] !in KNOWN_EXTENSIONS) {
                    return@mapNotNull null
                }
                CachedImage(assetId = parts[0], fileName = file.name)
            }
            .distinctBy { it.assetId }
    }
}
