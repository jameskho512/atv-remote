package app.atvremote.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Now-playing over the AirPlay remote-control tunnel, against fake_airplay.py (PIN 1111).
 * Skipped unless FAKE_AIRPLAY_PORT is set.
 */
class FakeAirPlayTest {
    private val port = System.getenv("FAKE_AIRPLAY_PORT")?.toIntOrNull()

    @Test fun pairThenFollowNowPlaying() = runBlocking {
        assumeTrue(port != null)
        val pairing = AirPlayClient("127.0.0.1", port!!)
        pairing.connect()
        pairing.startPairing()
        val creds = pairing.finishPairing("1111", "Test Phone")
        pairing.close()

        val c = AirPlayClient("127.0.0.1", port)
        c.connect()
        c.verify(creds)
        val tracker = NowPlayingTracker()
        val seen = ArrayList<NowPlaying?>()
        val artwork = HashMap<String?, ByteArray>()
        val requests = HashSet<String>()
        val collector = async(start = CoroutineStart.UNDISPATCHED) {
            c.messages.collect { m ->
                tracker.artwork(m)?.let { (id, art) -> artwork[id] = art }
                if (m.string(2) in requests) return@collect // queue-request answers only carry artwork
                tracker.handle(m)
                val now = tracker.current()
                if (now != seen.lastOrNull()) {
                    seen += now
                    if (now?.itemId != null && now.itemId !in artwork) {
                        val id = UUID.randomUUID().toString()
                        requests += id
                        c.send(NowPlayingTracker.artworkRequest(id))
                    }
                }
            }
        }
        c.startRemoteControl("Test Phone", String(creds.clientId))
        withTimeout(10_000) { while (seen.none { it?.title == "Second Song" } || "item-2" !in artwork) delay(50) }
        c.send(AirPlayClient.hidEvent(0x0C, 0xE2, true))
        c.send(AirPlayClient.hidEvent(0x0C, 0xE2, false))
        delay(2500) // let a feedback heartbeat go through
        collector.cancel()
        c.close()

        val first = seen.first { it?.title == "First Song" }!!
        assertTrue(first.playing)
        assertEquals("Fake Artist — Fake Album", first.subtitle)
        assertEquals("Music", first.appName)
        assertEquals(240.0, first.duration!!, 0.0)
        assertEquals(30.0, first.positionNow(first.positionAtMs)!!, 0.01)
        assertTrue(seen.any { it?.title == "First Song" && !it.playing })
        val second = seen.last()!!
        assertEquals("Second Song", second.title)
        assertTrue(second.playing)
        assertTrue(String(artwork["item-1"]!!).endsWith("item-1"))
        assertTrue(String(artwork["item-2"]!!).endsWith("item-2"))
        assertFalse(c.closed.isCompleted && c.closed.await() != null)
    }
}
