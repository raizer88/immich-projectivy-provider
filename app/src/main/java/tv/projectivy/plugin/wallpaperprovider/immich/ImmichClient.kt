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
data class ImmichAsset(val id: String, val fileName: String)

/** Thrown for any Immich API failure; [message] is safe to show the user. */
class ImmichException(val httpCode: Int, override val message: String) : Exception(message)

data class TestResult(
    val success: Boolean,
    val message: String,
    val albums: List<ImmichAlbum> = emptyList()
)

/**
 * Minimal Immich v3 REST client (pure JVM, no native code).
 *
 * Endpoints verified against the Immich OpenAPI spec (v3.x):
 *   GET  /api/albums                     -> list albums (x-api-key)
 *   POST /api/search/metadata            -> search assets (filter + orderBy + size)
 *   POST /api/search/random              -> random assets (filter + size)
 *   GET  /api/assets/{id}/thumbnail?size=preview -> image bytes (JPEG)
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

    /** Strips a trailing "/" or "/api" so the base can be joined with "/api/...". */
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
            403 -> "API key lacks permission (403). It needs album.read, asset.read, asset.view."
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

    private fun buildSearchBody(source: String, albumId: String, count: Int): String {
        val filter = JSONObject()
        filter.put("type", JSONObject().put("eq", "IMAGE"))
        when (source) {
            PreferencesManager.SOURCE_ALBUM ->
                filter.put("albumIds", JSONObject().put("any", JSONArray().put(albumId)))
            PreferencesManager.SOURCE_FAVORITES ->
                filter.put("isFavorite", JSONObject().put("eq", true))
        }
        val body = JSONObject()
        body.put("filter", filter)
        body.put("size", count)
        if (source != PreferencesManager.SOURCE_RANDOM) {
            body.put("orderBy", JSONObject().put("field", "fileCreatedAt").put("direction", "desc"))
        }
        return body.toString()
    }

    fun fetchAssets(baseUrl: String, apiKey: String, trustSsl: Boolean, source: String, albumId: String, count: Int): List<ImmichAsset> {
        if (source == PreferencesManager.SOURCE_RANDOM) {
            val body = apiPost(baseUrl, apiKey, trustSsl, "/search/random", buildSearchBody(source, albumId, count))
            return parseAssetArray(JSONArray(body))
        }
        val body = apiPost(baseUrl, apiKey, trustSsl, "/search/metadata", buildSearchBody(source, albumId, count))
        val json = JSONObject(body)
        val assets = json.optJSONObject("assets")
        val items = assets?.optJSONArray("items") ?: JSONArray()
        return parseAssetArray(items)
    }

    private fun parseAssetArray(arr: JSONArray): List<ImmichAsset> {
        val result = ArrayList<ImmichAsset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val type = o.optString("type", "IMAGE")
            if (id.isNotBlank() && type == "IMAGE") {
                result.add(ImmichAsset(id, o.optString("originalFileName", "")))
            }
        }
        return result
    }

    /** Downloads a preview-sized image; returns the raw bytes or null on any failure. */
    fun fetchImage(baseUrl: String, apiKey: String, trustSsl: Boolean, assetId: String, size: String): ByteArray? {
        val request = Request.Builder()
            .url("$baseUrl/api/assets/$assetId/thumbnail?size=$size")
            .header("x-api-key", apiKey)
            .get()
            .build()
        return try {
            client(trustSsl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun testConnection(baseUrl: String, apiKey: String, trustSsl: Boolean): TestResult {
        return try {
            val albums = fetchAlbums(baseUrl, apiKey, trustSsl)
            TestResult(true, "Connected: ${albums.size} album(s)", albums)
        } catch (e: ImmichException) {
            TestResult(false, e.message ?: "Connection failed", emptyList())
        } catch (e: Exception) {
            TestResult(false, "Connection failed: ${e.message ?: e.javaClass.simpleName}", emptyList())
        }
    }
}
