package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves cached wallpaper images to Projectivy via `content://` URIs.
 *
 * Projectivy downloads [Wallpaper.uri] itself and cannot attach the Immich `x-api-key`
 * header, so the plugin downloads the images and exposes them here. The alternative —
 * handing Projectivy a direct Immich URL with the key in a query parameter — puts a
 * credential that reads the whole library into every consumer of this binder, into
 * Projectivy's own cache, and into the access log of any reverse proxy in front of
 * Immich. The key therefore never leaves this process.
 *
 * Unlike the usual pattern for this, the provider is **not exported**: it declares
 * `grantUriPermissions` and [WallpaperProviderService] calls `grantUriPermission` for
 * Projectivy's package on each URI it returns. An exported provider would let every app
 * on the device read the cache; a per-URI grant is scoped to the one consumer that asked.
 * `android:permission` is not usable here because Projectivy holds no permission of ours,
 * and androidx `FileProvider` is not usable because it forbids being exported at all —
 * but a programmatic grant needs no Intent, which is what makes this work.
 */
class WallpaperImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        WallpaperCache.mimeTypeForName(uri.lastPathSegment ?: "")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("Missing file name")
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = WallpaperCache(ctx).fileByName(name)
            ?: throw FileNotFoundException("Invalid path")
        if (!file.exists()) throw FileNotFoundException("Not cached: $name")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
