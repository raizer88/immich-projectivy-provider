package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves cached wallpaper JPEGs to Projectivy via `content://` URIs.
 *
 * Projectivy downloads the wallpaper [Wallpaper.uri] itself and cannot attach the
 * Immich `x-api-key` header, so the plugin downloads images and exposes them here.
 * The provider is exported because Projectivy reads the URI through ContentResolver
 * without an intent grant; only the cached image files are exposed (never the API key).
 */
class WallpaperImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

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
