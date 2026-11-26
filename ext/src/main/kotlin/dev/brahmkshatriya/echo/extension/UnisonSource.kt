package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Unison (unison.boidu.dev). Primarily keyed by YouTube video id; the search endpoint returned
 * an empty data array for every well-known track during the audit, so this provider is
 * UNSUPPORTED for ordinary Echo tracks. It still implements both documented shapes.
 */
internal class UnisonSource : LyricsSource {

    private val throttle = Throttle(600)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        val videoId = track.extras["videoId"] ?: track.id.takeIf { it.matches(VIDEO_ID) }
        if (videoId == null && (query.title.isBlank() || query.artist.isBlank())) return emptyList()
        throttle.await()
        val url = if (videoId != null) {
            BASE + "?v=" + Http.encode(videoId) + "&song=" + Http.encode(track.title) +
                "&artist=" + Http.encode(query.artist)
        } else {
            BASE + "/search?song=" + Http.encode(track.title) + "&artist=" + Http.encode(query.artist)
        }
        val body = runCatching { Http.get(url) }.getOrNull() ?: return emptyList()
        val root = runCatching { Http.json(body).obj() }.getOrNull() ?: return emptyList()
        val data = root.arr("data").objects()
        if (data.isEmpty()) return emptyList()

        return data.mapNotNull { entry ->
            val id = entry.str("videoId") ?: videoId ?: return@mapNotNull null
            val title = entry.str("song") ?: track.title
            val artist = entry.str("artist").orEmpty()
            val duration = (entry.long("duration") ?: 0L) / 1000
            val candidate = Candidate(title, artist, "", duration)
            if (!Match.accept(query, candidate) && videoId == null) return@mapNotNull null
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
        val url = BASE + "?v=" + Http.encode(videoId)
        val body = try {
            Http.get(url)
        } catch (error: HttpException) {
            if (error.code == 404) throw LyricsException("Unison has no lyrics for this video")
            throw error
        }
        val data = Http.json(body).obj()?.obj("data")
            ?: throw LyricsException("Unison returned an empty response")
        val format = data.str("format")?.lowercase().orEmpty()
        val content = data.str("lyrics") ?: throw LyricsException("Unison variant has no lyrics")
        val lines = when (format) {
            "ttml" -> Ttml.parse(content)
            "lrc" -> Lrc.parse(content)
            else -> emptyList()
        }
        if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
        val plainLines = Lrc.parsePlain(content)
        if (plainLines.isNotEmpty()) return lyrics.copy(lyrics = plain(plainLines.joinToString("\n")))
        throw LyricsException("Unison lyric format was not usable")
    }

    companion object {
        private const val BASE = "https://unison.boidu.dev/lyrics"
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun settings(): List<Setting> = emptyList()
    }
}
