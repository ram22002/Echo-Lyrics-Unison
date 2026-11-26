package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlTest {

    private val document = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
          <body><div>
            <p begin="00:00:10.000" end="00:00:12.000">
              <span begin="00:00:10.000" end="00:00:10.500">Hello</span>
              <span begin="00:00:10.500" end="00:00:12.000"> world</span>
            </p>
            <p begin="12.5s" end="15s">Second line</p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun parsesLinesAndSyllables() {
        val lines = Ttml.parse(document)
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].startMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals(12_500L, lines[1].startMs)
        assertEquals(15_000L, lines[1].endMs)
    }

    @Test
    fun parsesEveryTimeUnit() {
        assertEquals(1_500L, Ttml.parseTime("1.5s"))
        assertEquals(1_500L, Ttml.parseTime("1500ms"))
        assertEquals(65_000L, Ttml.parseTime("00:01:05.000"))
        assertEquals(65_000L, Ttml.parseTime("1:05.0"))
        assertEquals(null, Ttml.parseTime("indefinite"))
    }

    @Test
    fun malformedDocumentYieldsNothing() {
        assertTrue(Ttml.parse("<tt><body>").isEmpty())
        assertTrue(Ttml.parse("").isEmpty())
    }
}
