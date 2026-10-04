package app.atvremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.atvremote.protocol.AirPlayClient
import app.atvremote.protocol.CompanionException
import app.atvremote.protocol.Credentials
import app.atvremote.protocol.NowPlaying
import app.atvremote.protocol.NowPlayingTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class NowPlayingLink { Off, Connecting, Live, NeedsPairing }

/**
 * Keeps an AirPlay remote-control session open to follow what the Apple TV is playing.
 * It first tries the Companion pairing; if the TV wants a separate AirPlay pairing,
 * [link] becomes [NowPlayingLink.NeedsPairing] and [startPairing] asks for a second PIN.
 */
class NowPlayingSession(private val scope: CoroutineScope, private val phoneName: String) {
    val link = MutableStateFlow(NowPlayingLink.Off)
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** Artwork for the current item: item id to image. */
    val artwork = MutableStateFlow<Pair<String?, Bitmap>?>(null)
    val pairing = MutableStateFlow<Pairing?>(null)

    private var job: Job? = null
    private var live: AirPlayClient? = null
    private var pairingClient: AirPlayClient? = null

    /** Connects (and reconnects with backoff) until [stop]. */
    fun start(host: String, companion: Credentials, airplay: Credentials?) {
        stop()
        job = scope.launch {
            var backoff = 3_000L
            while (isActive) {
                val creds = airplay ?: companion
                link.value = NowPlayingLink.Connecting
                val ap = AirPlayClient(host)
                try {
                    ap.connect()
                    try {
                        ap.verify(creds)
                    } catch (e: Exception) {
                        // Connected but the TV refused this pairing: it needs its own AirPlay pairing.
                        if (airplay == null || e is CompanionException) { link.value = NowPlayingLink.NeedsPairing; return@launch }
                        throw e
                    }
                    val follow = launch(start = CoroutineStart.UNDISPATCHED) { follow(ap) }
                    ap.startRemoteControl(phoneName, String(creds.clientId))
                    link.value = NowPlayingLink.Live
                    live = ap
                    backoff = 3_000L
                    ap.closed.await()
                    follow.cancel()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Logged because a silent retry loop once hid a real bug here.
                    android.util.Log.w("NowPlayingSession", "AirPlay link failed; retrying", e)
                } finally {
                    if (live === ap) live = null
                    ap.close()
                    nowPlaying.value = null
                }
                link.value = NowPlayingLink.Connecting
                delay(backoff) // e.g. the TV went to sleep; try again later
                backoff = minOf(backoff * 2, 60_000L)
            }
        }
    }

    /** Shows placeholder playback for README screenshots; see [DemoMode]. */
    fun showDemo(np: NowPlaying, art: Bitmap) {
        link.value = NowPlayingLink.Live
        nowPlaying.value = np
        artwork.value = np.itemId to art
    }

    fun stop() {
        job?.cancel()
        job = null
        link.value = NowPlayingLink.Off
        nowPlaying.value = null
        artwork.value = null
    }

    /** Seeks the playing item to [seconds]; false when the AirPlay link is down. */
    fun seek(seconds: Double): Boolean {
        val ap = live ?: return false
        if (runCatching { ap.send(AirPlayClient.command(AirPlayClient.CMD_SEEK) { double(9, seconds) }) }.isFailure) return false
        // Show the new position straight away; the TV's next state update confirms it.
        nowPlaying.value?.let { nowPlaying.value = it.copy(position = seconds, positionAtMs = System.currentTimeMillis()) }
        return true
    }

    /** Presses and releases an HID key over the AirPlay link; false when the link is down. */
    fun hidKey(usagePage: Int, usage: Int): Boolean {
        val ap = live ?: return false
        return runCatching {
            ap.send(AirPlayClient.hidEvent(usagePage, usage, true))
            ap.send(AirPlayClient.hidEvent(usagePage, usage, false))
        }.isSuccess
    }

    private suspend fun follow(ap: AirPlayClient) {
        val tracker = NowPlayingTracker()
        val cache = LinkedHashMap<String?, Bitmap>()
        val requests = HashSet<String>()
        var requestedFor: String? = null
        ap.messages.collect { m ->
            tracker.artwork(m)?.let { (id, bytes) ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                    val key = id ?: requestedFor
                    cache[key] = bmp
                    while (cache.size > 8) cache.remove(cache.keys.first())
                    if (key == nowPlaying.value?.itemId) artwork.value = key to bmp
                }
            }
            if (m.string(2) in requests) return@collect // answers to artwork requests carry no state
            tracker.handle(m)
            val now = tracker.current()
            nowPlaying.value = now
            val item = now?.itemId
            if (now == null) { artwork.value = null; return@collect }
            val cached = cache[item]
            artwork.value = cached?.let { item to it }
            if (cached == null && now.artworkAvailable && item != requestedFor) {
                requestedFor = item
                val id = UUID.randomUUID().toString().uppercase()
                requests += id
                runCatching { ap.send(NowPlayingTracker.artworkRequest(id)) }
            }
        }
    }

    // ---- AirPlay pairing (only when the Companion pairing is not accepted) ----

    fun startPairing(host: String) {
        cancelPairing()
        pairing.value = Pairing.Starting
        scope.launch {
            try {
                val c = AirPlayClient(host)
                pairingClient = c
                c.connect()
                c.startPairing()
                pairing.value = Pairing.AwaitingPin
            } catch (e: Exception) {
                pairing.value = Pairing.Failed(e.message ?: "Could not reach the Apple TV")
            }
        }
    }

    fun submitPin(pin: String, onPaired: (Credentials) -> Unit) {
        val c = pairingClient ?: return
        pairing.value = Pairing.Verifying
        scope.launch {
            try {
                val creds = c.finishPairing(pin, phoneName)
                c.close()
                pairingClient = null
                pairing.value = null
                onPaired(creds)
            } catch (e: Exception) {
                c.close()
                pairingClient = null
                pairing.value = Pairing.Failed(e.message ?: "Pairing failed")
            }
        }
    }

    fun cancelPairing() {
        pairingClient?.close()
        pairingClient = null
        pairing.value = null
    }
}
