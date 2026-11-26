package dev.brahmkshatriya.echo.extension

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Apple Music style TTML: one p element per line, optional span syllables,
 * itunes:timing="Word", begin/end in "12.3s", "1234ms", "00:00:12.345" or SMPTE.
 * Namespace aware and tolerant: a malformed document yields no lines instead of an exception.
 */
internal object Ttml {

    fun parse(xml: String, fallbackEndMs: Long = 0L): List<Line> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (_: Exception) {
            return emptyList()
        }

        val paragraphs = document.getElementsByTagNameNS("*", "p")
        val lines = mutableListOf<Line>()
        for (index in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(index) as? Element ?: continue
            val start = time(paragraph, "begin") ?: continue
            var end = time(paragraph, "end") ?: time(paragraph, "dur")?.let { start + it } ?: -1L
            val text = textOf(paragraph).trim()
            if (text.isEmpty()) continue
            val words = wordSpans(paragraph)
            if (end < 0) end = words.lastOrNull()?.endMs ?: start
            lines += Line(start, maxOf(end, start + 1), text, words)
        }
        lines.sortBy { it.startMs }
        // TTML carries real end times; only fill the ones the document omitted.
        return lines.mapIndexed { index, line ->
            if (line.endMs <= line.startMs) {
                val next = lines.getOrNull(index + 1)?.startMs
                line.copy(endMs = maxOf(line.startMs + 1, next ?: fallbackEndMs.takeIf { it > line.startMs } ?: (line.startMs + 5_000)))
            } else {
                line
            }
        }
    }

    private fun wordSpans(paragraph: Element): List<Word> {
        val spans = paragraph.getElementsByTagNameNS("*", "span")
        val words = mutableListOf<Word>()
        for (index in 0 until spans.length) {
            val span = spans.item(index) as? Element ?: continue
            val start = time(span, "begin") ?: continue
            val end = time(span, "end") ?: start
            val text = textOf(span)
            if (text.isEmpty()) continue
            words += Word(start, maxOf(end, start + 1), text)
        }
        return words.sortedBy { it.startMs }
    }

    private fun time(element: Element, attribute: String): Long? {
        val raw = sequenceOf(
            element.getAttribute(attribute),
            element.getAttributeNS("http://www.w3.org/ns/ttml", attribute),
            element.getAttribute("tts:$attribute"),
        ).firstOrNull { it.isNotBlank() } ?: return null
        return parseTime(raw)
    }

    fun parseTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty() || value == "indefinite") return null
        if (value.endsWith("ms")) return value.dropLast(2).toDoubleOrNull()?.toLong()
        if (value.endsWith("s")) return value.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        if (value.endsWith("m")) return value.dropLast(1).toDoubleOrNull()?.let { (it * 60_000).toLong() }
        if (value.endsWith("h")) return value.dropLast(1).toDoubleOrNull()?.let { (it * 3_600_000).toLong() }
        val parts = value.split(":")
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val seconds = parts[1].toDoubleOrNull() ?: return null
                minutes * 60_000 + (seconds * 1000).toLong()
            }
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val seconds = parts[2].toDoubleOrNull() ?: return null
                hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
            }
            4 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val seconds = parts[2].toLongOrNull() ?: return null
                val frames = parts[3].toDoubleOrNull() ?: return null
                hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + (frames * 1000 / 30).toLong()
            }
            else -> null
        }
    }

    private fun textOf(node: Node): String {
        val builder = StringBuilder()
        fun walk(current: Node) {
            when (current.nodeType) {
                Node.TEXT_NODE -> builder.append(current.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val name = current.localName ?: current.nodeName
                    if (name == "br") {
                        builder.append('\n')
                        return
                    }
                    var index = 0
                    while (index < current.childNodes.length) {
                        walk(current.childNodes.item(index))
                        index++
                    }
                }
            }
        }
        walk(node)
        // Collapse every run of whitespace (including source newlines) into one space.
        val collapsed = StringBuilder(builder.length)
        var pendingSpace = false
        for (char in builder) {
            if (char.isWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && collapsed.isNotEmpty()) collapsed.append(' ')
                pendingSpace = false
                collapsed.append(char)
            }
        }
        return collapsed.toString()
    }
}
