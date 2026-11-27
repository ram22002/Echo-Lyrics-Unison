package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveApiTest {

    @Test
    fun loadsLyricsBySearch() = runBlocking {
        assumeTrue(System.getProperty("echo.live") == "1")
        val track = Track(
            id = "live-probe",
            title = "Yellow",
            artists = listOf(Artist("1", "Coldplay")),
            duration = 266000L,
        )
        val source = UnisonSource()
        val candidates = source.search(track, null)
        assertTrue("no candidates returned (X-Key-ID rejected?)", candidates.isNotEmpty())
        val loaded = source.load(candidates.first(), null)
        val lyric = loaded.lyrics
        assertNotNull("no lyrics loaded", lyric)
        val size = when (lyric) {
            is Lyrics.Simple -> lyric.text.lines().size
            is Lyrics.Timed -> lyric.list.size
            is Lyrics.WordByWord -> lyric.list.size
            else -> 0
        }
        assertTrue("empty lyrics", size > 0)
        println("live: unison ${lyric?.javaClass?.simpleName} lines=$size")
    }
}
