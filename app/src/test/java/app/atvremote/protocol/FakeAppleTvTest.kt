package app.atvremote.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against pyatv's fake Companion Apple TV (PIN 1111). Skipped unless FAKE_ATV_PORT is set:
 *   python fake_atv.py 49153  &&  FAKE_ATV_PORT=49153 ./gradlew testDebugUnitTest
 */
class FakeAppleTvTest {
    private val port = System.getenv("FAKE_ATV_PORT")?.toIntOrNull()

    @Test fun pairThenControl() = runBlocking {
        assumeTrue(port != null)
        val pairing = CompanionClient("127.0.0.1", port!!)
        pairing.connect()
        pairing.startPairing()
        val creds = pairing.finishPairing("1111", "Test Phone")
        pairing.close()
        val restored = Credentials.parse(creds.serialize())

        val c = CompanionClient("127.0.0.1", port)
        c.connect()
        c.openSession(restored, "Test Phone")
        c.press(HidCommand.Up)
        c.press(HidCommand.Select)
        c.hid(HidCommand.Home, true); delay(50); c.hid(HidCommand.Home, false)
        val apps = c.apps()
        assertEquals("Netflix", apps["com.netflix.Netflix"])
        c.launchApp("com.netflix.Netflix")
        c.nextTrack()
        c.previousTrack()
        assertEquals(3L, c.attentionState())
        assertTrue(c.setText("hello tv"))
        c.press(HidCommand.Sleep)
        delay(300)
        c.close()
    }

    @Test fun wrongPinFails() = runBlocking {
        assumeTrue(port != null)
        val c = CompanionClient("127.0.0.1", port!!)
        c.connect()
        c.startPairing()
        val err = runCatching { c.finishPairing("9999", "Test Phone") }.exceptionOrNull()
        c.close()
        assertTrue("expected failure, got $err", err is CompanionException)
    }
}
