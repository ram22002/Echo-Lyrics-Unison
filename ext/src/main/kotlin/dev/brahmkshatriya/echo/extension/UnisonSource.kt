package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Unison (unison.boidu.dev). Lyrics live under `/lyrics/variants/<video id>`; the older
 * `/lyrics?v=` form now answers 404 and is not used. The API only responds when the request
 * carries an `X-Key-ID`: the SHA-256 of a freshly generated P-256 JWK, which the web client
 * sends to identify itself. Without the header the paths answer 404 or the SPA's HTML.
 */
internal class UnisonSource : LyricsSource {

    private val throttle = Throttle(600)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        val videoId = track.extras["videoId"] ?: track.id.takeIf { it.matches(VIDEO_ID) }
        if (videoId != null) {
            throttle.await()
            return variants(videoId).filter { it.bool("hidden") != true }.mapNotNull { variant ->
                val id = variant.str("videoId") ?: videoId
                val title = variant.str("song") ?: track.title
                val artist = variant.str("artist").orEmpty()
                Lyrics(
                    id = "unison:$id",
                    title = title,
                    subtitle = if (artist.isBlank()) "Unison" else "$artist • Unison",
                    lyrics = null,
                    extras = mapOf("videoId" to id),
                )
            }.distinctBy { it.id }
        }

        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        throttle.await()
        val url = BASE + "/search?song=" + Http.encode(track.title) +
            "&artist=" + Http.encode(query.artist) + "&limit=10"
        val body = runCatching { Http.get(url, HEADERS) }.getOrNull() ?: return emptyList()
        val data = runCatching { Http.json(body).obj()?.arr("data").objects() }.getOrNull()
            ?: return emptyList()

        return data.mapNotNull { entry ->
            val id = entry.str("videoId") ?: return@mapNotNull null
            val title = entry.str("song") ?: track.title
            val artist = entry.str("artist").orEmpty()
            val duration = (entry.long("duration") ?: 0L) / 1000
            if (!Match.accept(query, Candidate(title, artist, "", duration))) return@mapNotNull null
            Lyrics(
                id = "unison:$id",
                title = title,
                subtitle = artist + " • Unison",
                lyrics = null,
                extras = mapOf("videoId" to id),
            )
        }.take(6)
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val videoId = lyrics.extras["videoId"] ?: throw LyricsException("Unison candidate is missing its video id")
        throttle.await()
        val entries = variants(videoId)
        if (entries.isEmpty()) throw LyricsException("Unison has no lyrics for this video")

        for (entry in entries) {
            if (entry.bool("hidden") == true) continue
            val content = entry.str("lyrics") ?: continue
            val format = entry.str("format")?.lowercase().orEmpty()
            val lines = when (format) {
                "ttml" -> Ttml.parse(content)
                "lrc" -> Lrc.parse(content)
                else -> emptyList()
            }
            if (lines.isNotEmpty()) {
                return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
            }
            val plainLines = Lrc.parsePlain(content)
            if (plainLines.isNotEmpty()) return lyrics.copy(lyrics = plain(plainLines.joinToString("\n")))
        }
        throw LyricsException("Unison lyric format was not usable")
    }

    private suspend fun variants(videoId: String): List<JsonObject> {
        val url = BASE + "/variants/" + Http.encode(videoId)
        val body = runCatching { Http.get(url, HEADERS) }
            .getOrElse { error ->
                if (error is HttpException && error.code == 404) return emptyList()
                return emptyList()
            }
        return runCatching { Http.json(body).obj()?.arr("data").objects() }.getOrNull() ?: emptyList()
    }

    companion object {
        private const val BASE = "https://unison.boidu.dev/lyrics"
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** Computed once; the API only accepts requests that carry it. */
        private val KEY_ID: String by lazy { computeKeyId() }
        private val HEADERS = mapOf("X-Key-ID" to KEY_ID, "Accept" to "application/json")

        /** SHA-256 hex of the client's P-256 public key in its canonical JWK form. */
        private fun computeKeyId(): String = runCatching {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val publicKey = generator.generateKeyPair().public as ECPublicKey
            val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" +
                base64Url(publicKey.w.affineX) + "\",\"y\":\"" + base64Url(publicKey.w.affineY) + "\"}"
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { byte -> "%02x".format(byte) }
        }.getOrDefault("0".repeat(64))

        /** Base64url of the 32 byte big endian coordinate, unpadded. */
        private fun base64Url(value: BigInteger): String {
            val bytes = value.toByteArray()
            val coordinate = ByteArray(32)
            val start = if (bytes.size > 32 && bytes[0] == 0.toByte()) 1 else 0
            val length = minOf(32, bytes.size - start)
            System.arraycopy(bytes, start, coordinate, 32 - length, length)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder(43)
            var index = 0
            while (index < coordinate.size) {
                val b0 = coordinate[index].toInt() and 0xFF
                val b1 = if (index + 1 < coordinate.size) coordinate[index + 1].toInt() and 0xFF else 0
                val b2 = if (index + 2 < coordinate.size) coordinate[index + 2].toInt() and 0xFF else 0
                out.append(alphabet[b0 shr 2])
                out.append(alphabet[((b0 and 0x3) shl 4) or (b1 shr 4)])
                if (index + 1 < coordinate.size) out.append(alphabet[((b1 and 0xF) shl 2) or (b2 shr 6)])
                if (index + 2 < coordinate.size) out.append(alphabet[b2 and 0x3F])
                index += 3
            }
            return out.toString()
        }

        fun settings(): List<Setting> = emptyList()
    }
}
