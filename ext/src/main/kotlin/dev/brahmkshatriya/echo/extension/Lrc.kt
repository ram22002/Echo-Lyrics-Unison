package dev.brahmkshatriya.echo.extension

/**
 * One timed lyric line. [endMs] is always greater than [startMs]; for line-synced sources
 * it is the next line's start, for the last line start + 5 s capped by the track duration.
 * Plain text never goes through this type.
 */
internal data class Line(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
)

internal data class Word(val startMs: Long, val endMs: Long, val text: String)

internal object Lrc {

    private val META = Regex("""^\s*\[(ti|ar|al|by|offset|length|re|ve|au|kana|language):([^]]*)]""", RegexOption.IGNORE_CASE)
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val INLINE = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun hasTimestamps(text: String): Boolean = text.lineSequence().any { STAMP.containsMatchIn(it) }

    /**
     * Parses standard and enhanced LRC. Multiple timestamps on one line duplicate the line,
     * [offset:] shifts every line, enhanced inline word times are kept as word timings.
     */
    fun parse(text: String, fallbackEndMs: Long = 0L): List<Line> {
        val out = mutableListOf<Line>()
        var offset = 0L

        for (raw in text.lines()) {
            val meta = META.find(raw)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }
            val stamps = STAMP.findAll(raw).mapNotNull { stamp(it) }.toList()
            if (stamps.isEmpty()) continue
            val content = STAMP.replace(raw, "").trim()
            if (content.isEmpty()) continue

            val inline = INLINE.findAll(content).toList()
            val plain = INLINE.replace(content, "").trim()
            if (plain.isEmpty() && inline.isEmpty()) continue

            for (lineStart in stamps) {
                val words = wordsOf(content, inline, lineStart)
                out += Line(lineStart + offset, lineStart + offset, plain, words)
            }
        }
        out.sortBy { it.startMs }
        return withEnds(out, fallbackEndMs)
    }

    /** Splits plain lyrics into non blank trimmed lines. No timing is invented. */
    fun parsePlain(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Enhanced LRC marks the start of the text that follows each inline timestamp, so a word
     * runs from its own timestamp to the next one.
     */
    private fun wordsOf(content: String, inline: List<MatchResult>, lineStart: Long): List<Word> {
        if (inline.isEmpty()) return emptyList()
        val words = mutableListOf<Word>()
        inline.forEachIndexed { index, match ->
            val start = timeOf(match) ?: return@forEachIndexed
            val from = match.range.last + 1
            val to = inline.getOrNull(index + 1)?.range?.first ?: content.length
            if (to <= from) return@forEachIndexed
            words += Word(start, start, content.substring(from, to))
        }
        return words.mapIndexed { index, word ->
            word.copy(endMs = maxOf(word.startMs + 1, words.getOrNull(index + 1)?.startMs ?: word.endMs))
        }
    }

    private fun stamp(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        return minutes * 60_000 + seconds * 1_000 + fractionMs(fraction)
    }

    private fun timeOf(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        return minutes * 60_000 + seconds * 1_000 + fractionMs(match.groupValues[3])
    }

    fun fractionMs(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        return when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
    }

    /** Fills end times from the next line start; the tail gets 5 s, capped by the track. */
    fun withEnds(lines: List<Line>, fallbackEndMs: Long): List<Line> {
        if (lines.isEmpty()) return lines
        return lines.mapIndexed { index, line ->
            val next = lines.getOrNull(index + 1)
            val end = when {
                next != null -> maxOf(next.startMs, line.startMs + 1)
                fallbackEndMs > line.startMs -> fallbackEndMs
                else -> line.startMs + 5_000
            }
            line.copy(endMs = maxOf(end, line.startMs + 1))
        }
    }
}
