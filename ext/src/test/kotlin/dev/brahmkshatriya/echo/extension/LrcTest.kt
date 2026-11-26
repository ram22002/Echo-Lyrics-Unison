package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    @Test
    fun parsesStandardLrc() {
        val lines = Lrc.parse("[00:12.34]Hello\n[00:15.00]World")
        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].startMs)
        assertEquals(15_000L, lines[0].endMs)
        assertEquals("Hello", lines[0].text)
        assertEquals(20_000L, lines[1].endMs)
    }

    @Test
    fun appliesOffsetAndKeepsEveryTimestamp() {
        val lines = Lrc.parse("[offset:-500]\n[00:10.00][00:20.00]Chorus")
        assertEquals(2, lines.size)
        assertEquals(9_500L, lines[0].startMs)
        assertEquals(19_500L, lines[1].startMs)
    }

    @Test
    fun parsesEnhancedWordTiming() {
        val lines = Lrc.parse("[00:10.00]<00:10.00>Hello <00:10.50>world")
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals(10_000L, lines[0].words[0].startMs)
        assertEquals(10_500L, lines[0].words[1].startMs)
    }

    @Test
    fun ignoresMetadataAndEmptyLines() {
        val lines = Lrc.parse("[ti:Title]\n[ar:Artist]\n\n[00:01.00]Text")
        assertEquals(1, lines.size)
        assertEquals("Text", lines[0].text)
    }

    @Test
    fun plainTextIsNeverTimed() {
        assertFalse(Lrc.hasTimestamps("first line\nsecond line"))
        assertEquals(listOf("first line", "second line"), Lrc.parsePlain("first line\n\n second line "))
        assertTrue(Lrc.parse("no timing here").isEmpty())
    }

    @Test
    fun sortedByStartTime() {
        val lines = Lrc.parse("[00:20.00]Second\n[00:10.00]First")
        assertEquals("First", lines[0].text)
        assertEquals("Second", lines[1].text)
    }
}
