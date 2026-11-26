package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings

/** The provider half of an extension: candidate discovery plus loading one candidate. */
internal interface LyricsSource {
    suspend fun search(track: Track, settings: Settings?): List<Lyrics>
    suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics
}

/** Builds the timed/word-by-word payload Echo renders. */
internal fun timed(lines: List<Line>, wordLevel: Boolean): Lyrics.Lyric {
    if (wordLevel) {
        val words = lines.map { line ->
            val source = if (line.words.size > 1) line.words else listOf(Word(line.startMs, line.endMs, line.text))
            source.map { Lyrics.Item(it.text, it.startMs, it.endMs) }
        }
        if (words.any { it.size > 1 }) return Lyrics.WordByWord(words, fillTimeGaps = true)
    }
    return Lyrics.Timed(lines.map { Lyrics.Item(it.text, it.startMs, it.endMs) }, fillTimeGaps = true)
}

internal fun plain(text: String): Lyrics.Lyric = Lyrics.Simple(text)

/** Stable, collision free candidate id used by Echo's on-disk cache. */
internal fun candidateId(vararg parts: Any?): String =
    parts.joinToString("|") { it?.toString().orEmpty() }.hashCode().toString()

internal fun dev.brahmkshatriya.echo.common.models.Track.query(): Candidate = toCandidate()
