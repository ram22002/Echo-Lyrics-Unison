package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {

    private val query = Candidate("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213)

    @Test
    fun acceptsExactCandidate() {
        assertTrue(Match.accept(query, query))
        assertEquals(11, Match.score(query, query))
    }

    @Test
    fun acceptsFeaturingAndSuffixVariants() {
        val candidate = Candidate("Never Gonna Give You Up (Remastered 2022)", "Rick Astley feat. Someone", "", 214)
        assertTrue(Match.accept(query, candidate))
    }

    @Test
    fun rejectsWrongArtistEvenWithMatchingTitle() {
        assertFalse(Match.accept(query, Candidate("Never Gonna Give You Up", "Some Cover Band", "", 213)))
    }

    @Test
    fun rejectsVeryDifferentTitle() {
        assertFalse(Match.accept(query, Candidate("Completely Different Song", "Rick Astley", "", 213)))
    }

    @Test
    fun rejectsDurationMismatch() {
        assertFalse(Match.accept(query, Candidate("Never Gonna Give You Up", "Rick Astley", "", 400)))
    }

    @Test
    fun normalizesUnicodeAndPunctuation() {
        assertEquals("never gonna give you up", Match.normalize("Never Gonna Give You Up"))
        assertEquals("群青", Match.normalize("群青"))
        assertTrue(Match.titleScore("群青", "群青 - Remastered") >= 3)
        assertTrue(Match.titleScore("Ünïcode", "unicode") >= 3)
    }

    @Test
    fun ignoresUnknownArtistScoresInsteadOfRejecting() {
        assertTrue(Match.accept(query, Candidate("Never Gonna Give You Up", "", "", 213)))
    }
}
