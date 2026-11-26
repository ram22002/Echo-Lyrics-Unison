package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thrown when a request fails in a way the user should see (non-2xx, malformed body).
 * Echo turns it into a row level error and keeps the previously cached lyrics.
 */
internal class HttpException(
    val code: Int,
    val url: String,
    val body: String,
) : IOException("HTTP $code for $url")

/** The provider has no lyrics for this candidate anymore. */
internal class LyricsException(message: String) : Exception(message)

/**
 * Small HTTP facade. One client per extension, bounded timeouts, explicit cancellation
 * through OkHttp's await(), and a shared User-Agent so upstreams can identify the client.
 */
internal object Http {

    const val NAME = "Unison"
    const val VERSION = "1.0.0"
    const val UA = "$NAME/$VERSION (Echo lyrics extension; +https://github.com/ram22002/Echo-Lyrics-Unison)"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(url, headers, null, "GET")

    suspend fun postForm(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        execute(url, headers, body.toRequestBody(FORM), "POST")

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        execute(url, headers, body.toRequestBody(JSON), "POST")

    private suspend fun execute(
        url: String,
        headers: Map<String, String>,
        body: RequestBody?,
        method: String,
    ): String {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        for ((key, value) in headers) builder.header(key, value)
        if (body != null) builder.method(method, body)
        val response = client.newCall(builder.build()).await()
        response.use { result ->
            val text = result.body?.string().orEmpty()
            if (!result.isSuccessful) throw HttpException(result.code, url, text)
            return text
        }
    }

    fun json(text: String): JsonElement = json.parseToJsonElement(text)

    /** Percent-encodes a query value.  is valid form encoding but several upstreams
     * treat it literally, so spaces become %20 instead. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    private val JSON = "application/json; charset=utf-8".toMediaType()
}

/** Minimum interval between two requests to the same upstream. */
internal class Throttle(private val minIntervalMs: Long) {
    @Volatile
    private var lastRequest = 0L

    suspend fun await() {
        val now = System.currentTimeMillis()
        val wait = lastRequest + minIntervalMs - now
        if (wait > 0) delay(wait)
        lastRequest = System.currentTimeMillis()
    }
}

// ---- tolerant JSON accessors -------------------------------------------------------------

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).obj()

internal fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

internal fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

internal fun JsonObject?.dbl(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject?.firstArrayItem(key: String): JsonObject? = this?.arr(key)?.firstOrNull().obj()

internal fun JsonArray?.objects(): List<JsonObject> =
    this?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** Decodes the four HTML entities that actually occur in lyric payloads. */
internal fun decodeEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
