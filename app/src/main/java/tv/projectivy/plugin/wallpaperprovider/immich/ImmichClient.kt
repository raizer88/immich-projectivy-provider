package tv.projectivy.plugin.wallpaperprovider.immich

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ImmichAlbum(val id: String, val name: String)
data class ImmichAsset(
    val id: String,
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = ""
) {
    /**
     * True when the asset is not portrait. Unknown dimensions are treated as usable.
     * Portrait wallpapers are skipped by default because on some TVs Projectivy mis-renders
     * the launcher UI around a non-landscape wallpaper.
     */
    val isLandscape: Boolean get() = width <= 0 || height <= 0 || width >= height

    /**
     * True when the original can be served at full resolution and still be decoded by a TV.
     * HEIC, RAW and other formats fall back to Immich's JPEG preview instead.
     */
    val canServeOriginal: Boolean get() = mimeType in ORIGINAL_SAFE_MIME

    companion object {
        private val ORIGINAL_SAFE_MIME = setOf("image/jpeg", "image/png")
    }
}

/** Thrown for any Immich API failure; [message] is safe to show the user. */
class ImmichException(val httpCode: Int, override val message: String) : Exception(message)

data class TestResult(
    val success: Boolean,
    val message: String,
    val albums: List<ImmichAlbum> = emptyList()
)

/** Downloaded image bytes together with the MIME type Immich reported for them. */
data class ImmichImage(val bytes: ByteArray, val mimeType: String)

/**
 * Minimal Immich v3 REST client (pure JVM, no native code).
 *
 * Endpoints verified against the Immich OpenAPI spec (v3.x):
 *   GET  /api/albums                     -> list albums (x-api-key)
 *   POST /api/search/metadata            -> search assets (filter + orderBy + size)
 *   POST /api/search/random              -> random assets (filter + size)
 *   GET  /api/assets/{id}/original       -> original image bytes (x-api-key)
 *   GET  /api/assets/{id}/thumbnail?size=preview -> image bytes (x-api-key)
 *
 * Every request is authenticated with the `x-api-key` header.
 */
class ImmichClient {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        @Volatile private var normalClient: OkHttpClient? = null
        @Volatile private var insecureClient: OkHttpClient? = null

        private fun buildClient(trustAllCerts: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
            if (trustAllCerts) {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                builder.hostnameVerifier { _, _ -> true }
            }
            return builder.build()
        }

        fun client(trustAllCerts: Boolean): OkHttpClient {
            val cached = if (trustAllCerts) insecureClient else normalClient
            if (cached != null) return cached
            val created = buildClient(trustAllCerts)
            if (trustAllCerts) insecureClient = created else normalClient = created
            return created
        }
    }

    /**
     * Normalizes the user-entered base URL so it can be joined with "/api/...".
     * Strips a trailing "/" or "/api" (with any trailing slashes), but preserves any
     * path prefix (e.g. a reverse proxy at "https://host/immich"), which is then followed
     * by "/api/...".
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/api", ignoreCase = true)) {
            url = url.dropLast(4).trimEnd('/')
        }
        return url
    }

    private fun execute(baseUrl: String, apiKey: String, trustSsl: Boolean, request: Request): String {
        val call = client(trustSsl).newCall(request)
        try {
            call.execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw ImmichException(response.code, errorMessage(response.code, body))
                }
                return body
            }
        } catch (e: ImmichException) {
            throw e
        } catch (e: IOException) {
            throw ImmichException(0, "Cannot reach server: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        var detail = ""
        try {
            val json = JSONObject(body)
            val msg = json.optString("message", "")
            if (msg.isNotBlank()) detail = msg
        } catch (_: Exception) {
            // body is not JSON, ignore
        }
        val base = when (code) {
            401 -> "Invalid API key (401)."
            403 -> "API key lacks permission (403). It needs album.read, asset.read, asset.view, asset.download."
            404 -> "Not found (404). Check the server URL."
            else -> "HTTP $code."
        }
        return if (detail.isBlank()) base else "$base $detail"
    }

    private fun apiGet(baseUrl: String, apiKey: String, trustSsl: Boolean, path: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api$path")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(baseUrl, apiKey, trustSsl, request)
    }

    private fun apiPost(baseUrl: String, apiKey: String, trustSsl: Boolean, path: String, json: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api$path")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
        return execute(baseUrl, apiKey, trustSsl, request)
    }

    fun fetchAlbums(baseUrl: String, apiKey: String, trustSsl: Boolean): List<ImmichAlbum> {
        val body = apiGet(baseUrl, apiKey, trustSsl, "/albums")
        val arr = JSONArray(body)
        val result = ArrayList<ImmichAlbum>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", "")
            val name = o.optString("albumName", "")
            if (id.isNotBlank()) result.add(ImmichAlbum(id, name.ifBlank { "Album $id" }))
        }
        return result
    }

    private fun buildSearchBody(source: String, albumIds: List<String>, count: Int): String {
        val filter = JSONObject()
        filter.put("type", JSONObject().put("eq", "IMAGE"))
        when (source) {
            PreferencesManager.SOURCE_ALBUM ->
                filter.put("albumIds", JSONObject().put("any", JSONArray(albumIds)))
            PreferencesManager.SOURCE_FAVORITES ->
                filter.put("isFavorite", JSONObject().put("eq", true))
        }
        val body = JSONObject()
        body.put("filter", filter)
        body.put("size", count)
        // Recent means "newest N" by definition; every other source is a random sample
        // drawn by the server, so each refresh returns a different set of photos. The
        // ordered search would keep pulling the newest files of whichever album has them.
        if (source == PreferencesManager.SOURCE_RECENT) {
            body.put("orderBy", JSONObject().put("field", "fileCreatedAt").put("direction", "desc"))
        }
        return body.toString()
    }

    fun fetchAssets(baseUrl: String, apiKey: String, trustSsl: Boolean, source: String, albumIds: List<String>, count: Int): List<ImmichAsset> {
        return if (source == PreferencesManager.SOURCE_RECENT) {
            val body = apiPost(baseUrl, apiKey, trustSsl, "/search/metadata", buildSearchBody(source, albumIds, count))
            val json = JSONObject(body)
            val assets = json.optJSONObject("assets")
            val items = assets?.optJSONArray("items") ?: JSONArray()
            parseAssetArray(items)
        } else {
            val body = apiPost(baseUrl, apiKey, trustSsl, "/search/random", buildSearchBody(source, albumIds, count))
            parseAssetArray(JSONArray(body))
        }
    }

    private fun parseAssetArray(arr: JSONArray): List<ImmichAsset> {
        val result = ArrayList<ImmichAsset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val type = o.optString("type", "IMAGE")
            // Anything the user took off the timeline should not surface on a living-room
            // TV. Immich enforces the locked folder server-side for API-key sessions, but
            // archived and hidden assets are returned unless excluded here. Filtered on the
            // response rather than in the search body on purpose: an unknown filter key is
            // a 400 that would empty the rotation, whereas an absent response field just
            // defaults to visible. Opt-in via the "Include archived & hidden" setting.
            val visibility = o.optString("visibility", "timeline")
            val archived = o.optBoolean("isArchived", false)
            val excluded = archived || !visibility.equals("timeline", ignoreCase = true)
            if (id.isNotBlank() && type == "IMAGE" && (PreferencesManager.includeArchivedHidden || !excluded)) {
                result.add(
                    ImmichAsset(
                        id = id,
                        fileName = o.optString("originalFileName", ""),
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        mimeType = o.optString("originalMimeType", "")
                    )
                )
            }
        }
        return result
    }

    /**
     * Downloads an asset; returns the bytes plus the server-reported MIME type, or null on
     * any failure. [original] selects `/original` (full-resolution modes); otherwise the
     * preview-sized JPEG thumbnail is fetched. HEIC/RAW originals are only requested when
     * the caller has decided they are acceptable.
     *
     * The key travels in the `x-api-key` header. The plugin deliberately does not build
     * `...thumbnail?apiKey=<key>` URLs for Projectivy to fetch: Immich accepts that form,
     * but it puts a credential for the entire library into Projectivy's cache and into the
     * access log of any reverse proxy fronting Immich.
     */
    fun fetchImage(
        baseUrl: String,
        apiKey: String,
        trustSsl: Boolean,
        assetId: String,
        original: Boolean
    ): ImmichImage? {
        val path = if (original) {
            "/api/assets/$assetId/original"
        } else {
            "/api/assets/$assetId/thumbnail?size=preview"
        }
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("x-api-key", apiKey)
            .get()
            .build()
        return try {
            client(trustSsl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val mimeType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                ImmichImage(bytes, mimeType)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun testConnection(baseUrl: String, apiKey: String, trustSsl: Boolean): TestResult {
        return try {
            val albums = fetchAlbums(baseUrl, apiKey, trustSsl)
            var message = "Connected: ${albums.size} album(s)"
            if (PreferencesManager.quality != PreferencesManager.QUALITY_PREVIEW) {
                downloadPermissionWarning(baseUrl, apiKey, trustSsl)?.let { message += " — $it" }
            }
            TestResult(true, message, albums)
        } catch (e: ImmichException) {
            TestResult(false, e.message ?: "Connection failed", emptyList())
        } catch (e: Exception) {
            TestResult(false, "Connection failed: ${e.message ?: e.javaClass.simpleName}", emptyList())
        }
    }

    /**
     * Full-resolution wallpaper URLs hit /original, which Immich grants only with the
     * asset download permission — unlike /thumbnail, which needs mere asset view. Without
     * it Projectivy fails to download every wallpaper silently, so warn in Test connection.
     */
    private fun downloadPermissionWarning(baseUrl: String, apiKey: String, trustSsl: Boolean): String? = try {
        val asset = fetchAssets(baseUrl, apiKey, trustSsl, PreferencesManager.SOURCE_RANDOM, emptyList(), 1).firstOrNull()
            ?: return null
        val request = Request.Builder()
            .url("$baseUrl/api/assets/${asset.id}/original")
            .header("x-api-key", apiKey)
            .get()
            .build()
        client(trustSsl).newCall(request).execute().use { response ->
            if (response.isSuccessful) null
            else "cannot download full-resolution originals (HTTP ${response.code}); the API key needs the asset download permission"
        }
    } catch (e: Exception) {
        null
    }
}
