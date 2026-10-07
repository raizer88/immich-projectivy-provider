package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * On-disk cache of Immich "preview"-size JPEGs.
 *
 * Files are stored in the app's internal cache dir and served to Projectivy via
 * [WallpaperImageProvider] as `content://` URIs. Enforces a configurable total
 * size limit by evicting oldest-first (the Fire TV has little free storage).
 */
class WallpaperCache(private val context: Context) {

    companion object {
        const val AUTHORITY = "tv.projectivy.plugin.wallpaperprovider.immich.provider"
        const val PREVIEW_SIZE = "preview"
    }

    private val dir: File
        get() = File(context.cacheDir, "wallpapers")

    /** Filesystem path for a given asset id. */
    fun fileFor(assetId: String): File = File(dir, "$assetId.jpg")

    /** Sanitized file lookup by raw URI path segment. */
    fun fileByName(name: String): File? {
        if (name.contains('/') || name.contains("..") || !name.endsWith(".jpg")) return null
        return File(dir, name)
    }

    fun uriFor(assetId: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath("$assetId.jpg")
        .build()

    /** Returns the cached file, downloading via [download] only when missing. */
    @Synchronized
    fun ensureCached(assetId: String, download: () -> ByteArray?): File? {
        val file = fileFor(assetId)
        if (file.exists() && file.length() > 0) return file
        val bytes = download() ?: return null
        if (bytes.isEmpty()) return null
        dir.mkdirs()
        file.writeBytes(bytes)
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

    /** Asset ids (filenames without ".jpg") currently cached, newest first. */
    @Synchronized
    fun listCachedAssetIds(): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(".jpg") }
    }
}
