package dev.brahmkshatriya.echo.extension

import java.text.Normalizer
import java.util.Locale

/** Candidate metadata as returned by a provider. */
internal data class Candidate(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Long = 0L,
)

/**
 * Track matching. Deliberately conservative: an artist mismatch is a hard reject, and a
 * result is only accepted when the aggregate score clears [MIN_SCORE] or the title is an
 * exact match with sane duration. Weak matches are never returned as lyrics.
 */
internal object Match {

    const val MIN_SCORE = 7

    private val BRACKETS = Regex("""[\[(（【][^\])）】]*[]）)】]""")
    private val FEAT = Regex("""\b(feat\.?|ft\.?|featuring|with)\b.*$""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex("""\b(official|video|lyrics?|audio|visualizer|remaster(ed)?|mv)\b""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")
    private val PUNCT = Regex("""[\p{Punct}·•・、，。！？：；“”‘’（）【】《》]""")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        // Fold accents so "Ünïcode" and "unicode" match, then keep the composed form.
        text = Normalizer.normalize(
            removeMarks(Normalizer.normalize(text, Normalizer.Form.NFD)),
            Normalizer.Form.NFC,
        )
        text = text.replace('_', ' ')
        text = BRACKETS.replace(text, " ")
        text = FEAT.replace(text, " ")
        text = NOISE.replace(text, " ")
        text = PUNCT.replace(text, " ")
        return WHITESPACE.replace(text, " ").trim()
    }

    fun titleScore(query: String, candidate: String): Int {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 5
        if (c.contains(q) || q.contains(c)) return 3
        val similarity = jaccard(q, c)
        return when {
            similarity >= 0.6 -> 2
            similarity >= 0.35 -> 1
            else -> 0
        }
    }

    fun artistScore(query: String, candidate: String): Int {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c || c.contains(q) || q.contains(c)) return 3
        val parts = candidate.split(Regex("""\s*(?:,|/|&|×|和)\s*"""))
        for (part in parts) {
            val p = normalize(part)
            if (p.isNotEmpty() && (p == q || p.contains(q) || q.contains(p))) return 2
        }
        return -3
    }

    fun durationScore(querySec: Long, candidateSec: Long): Int {
        if (querySec <= 0 || candidateSec <= 0) return 0
        val delta = kotlin.math.abs(querySec - candidateSec)
        return when {
            delta <= 2 -> 2
            delta <= 5 -> 1
            delta <= 15 -> 0
            else -> -2
        }
    }

    fun albumScore(query: String?, candidate: String?): Int {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        return if (q == c || q.contains(c) || c.contains(q)) 1 else 0
    }

    fun score(query: Candidate, candidate: Candidate): Int =
        titleScore(query.title, candidate.title) +
            artistScore(query.artist, candidate.artist) +
            durationScore(query.durationSec, candidate.durationSec) +
            albumScore(query.album, candidate.album)

    fun accept(query: Candidate, candidate: Candidate): Boolean {
        val artist = artistScore(query.artist, candidate.artist)
        if (artist < 0) return false
        val duration = durationScore(query.durationSec, candidate.durationSec)
        if (duration == -2) return false
        val score = score(query, candidate)
        if (score >= MIN_SCORE) return true
        return titleScore(query.title, candidate.title) == 5 && duration >= 0
    }

    private fun removeMarks(text: String): String {
        val builder = StringBuilder(text.length)
        for (char in text) {
            if (Character.getType(char) != Character.NON_SPACING_MARK.toInt()) builder.append(char)
        }
        return builder.toString()
    }

    private fun jaccard(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size
        val union = left.union(right).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun tokens(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val current = StringBuilder()
        for (char in text) {
            if (char.isLetterOrDigit()) {
                current.append(char)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}

internal fun dev.brahmkshatriya.echo.common.models.Track.toCandidate(): Candidate = Candidate(
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.title.orEmpty(),
    durationSec = (duration ?: 0L) / 1000L,
)
