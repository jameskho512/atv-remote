package app.atvremote.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

class CompanionException(message: String) : IOException(message)

/** Long-term pairing keys. Serialized as hex fields joined by ':' (same layout as pyatv). */
data class Credentials(val ltpk: ByteArray, val ltsk: ByteArray, val atvId: ByteArray, val clientId: ByteArray) {
    fun serialize() = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.toHex() }

    companion object {
        fun parse(s: String): Credentials {
            val p = s.split(":").map { it.hexToBytes() }
            require(p.size == 4) { "malformed credentials" }
            return Credentials(p[0], p[1], p[2], p[3])
        }
    }
}

enum class HidCommand(val code: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7), VolumeUp(8), VolumeDown(9),
    Siri(10), Screensaver(11), Sleep(12), Wake(13), PlayPause(14), ChannelUp(15), ChannelDown(16),
    Guide(17), PageUp(18), PageDown(19),
}

/**
 * Client for Apple's Companion protocol (tvOS 15+), the protocol the iOS Remote uses.
 *
 * Lifecycle: [connect], then either pair ([startPairing] + [finishPairing]) or open an
 * encrypted session with [openSession]. Events pushed by the device arrive on [events].
 */
class CompanionClient(private val host: String, private val port: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private lateinit var output: OutputStream
    private var readerJob: Job? = null

    private val sendLock = Any()
    /** Every frame goes out on this thread, in order, so callers (often the main thread) never block on the network. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "companion-writer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    }
    private var outKey: ByteArray? = null
    private var inKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L

    private val xid = AtomicLong((0..0xFFFF).random().toLong())
    private val pending = ConcurrentHashMap<Any, CompletableDeferred<Map<String, Any?>>>()
    private val _events = MutableSharedFlow<Pair<String, Map<String, Any?>>>(extraBufferCapacity = 64)
    val events: SharedFlow<Pair<String, Map<String, Any?>>> = _events

    /** Completes when the connection drops, with the cause (null on a normal [close]). */
    val closed = CompletableDeferred<Throwable?>()

    private var localSid = 0L

    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        output = s.getOutputStream()
        val input = DataInputStream(s.getInputStream().buffered())
        readerJob = scope.launch { readLoop(input) }
    }

    fun close() {
        runCatching { socket?.close() }
        failAll(null)
        scope.cancel()
        writer.shutdown()
    }

    private fun failAll(cause: Throwable?) {
        closed.complete(cause)
        val err = cause ?: CompanionException("connection closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    // ---- framing ----

    private fun readLoop(input: DataInputStream) {
        try {
            val header = ByteArray(4)
            while (true) {
                input.readFully(header)
                val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                var payload = ByteArray(len).also { input.readFully(it) }
                val key = inKey
                if (key != null && len > 0) {
                    payload = ChaCha.open(key, counterNonce(inCounter++), payload, header)
                }
                handleFrame(header[0].toInt() and 0xFF, payload)
            }
        } catch (e: Exception) {
            failAll(e)
        }
    }

    private fun counterNonce(c: Long) = ByteArray(12).also { for (i in 0 until 8) it[i] = (c ushr (8 * i)).toByte() }

    private fun sendFrame(type: Int, data: ByteArray) {
        synchronized(sendLock) {
            val key = outKey
            val len = data.size + if (key != null && data.isNotEmpty()) 16 else 0
            val header = byteArrayOf(type.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
            val body = if (key != null && data.isNotEmpty()) ChaCha.seal(key, counterNonce(outCounter++), data, header) else data
            output.write(header + body)
            output.flush()
        }
    }

    /** Queues a frame on the writer thread; a write error closes the connection. */
    private fun post(type: Int, data: ByteArray) {
        try {
            writer.execute { try { sendFrame(type, data) } catch (e: Exception) { failAll(e) } }
        } catch (_: RejectedExecutionException) {
            // Already closed.
        }
    }

    private fun handleFrame(type: Int, payload: ByteArray) {
        if (type !in OPACK_FRAMES && type !in AUTH_FRAMES) return
        val msg = runCatching { Opack.unpack(payload) }.getOrNull() as? Map<*, *> ?: return
        val m = msg.asMap()
        if (type in AUTH_FRAMES) {
            pending.remove(AUTH_KEY)?.complete(m)
            return
        }
        when (m["_t"].asLong()) {
            1L -> _events.tryEmit((m["_i"] as? String ?: "") to m["_c"].asMap())
            3L -> m["_x"].asLong()?.let { pending.remove(it)?.complete(m) }
        }
    }

    private suspend fun exchangeAuth(type: Int, content: Map<String, Any?>, timeoutMs: Long = 15_000): Map<Int, ByteArray> {
        val d = CompletableDeferred<Map<String, Any?>>()
        pending[AUTH_KEY] = d
        post(type, Opack.pack(content + ("_x" to xid.getAndIncrement())))
        val resp = withTimeout(timeoutMs) { d.await() }
        val pd = resp["_pd"] as? ByteArray ?: throw CompanionException("no pairing data in response")
        val tlv = Tlv8.read(pd)
        HapPairing.checkError(tlv)
        return tlv
    }

    /** Sends a request and waits for its response; throws if the device reports an error. */
    suspend fun request(id: String, content: Map<String, Any?> = emptyMap(), timeoutMs: Long = 5000): Map<String, Any?> {
        val x = xid.getAndIncrement()
        val d = CompletableDeferred<Map<String, Any?>>()
        pending[x] = d
        post(E_OPACK, Opack.pack(mapOf("_i" to id, "_t" to 2, "_c" to content, "_x" to x)))
        val resp = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(x) }
        (resp["_em"] as? String)?.let { throw CompanionException("$id failed: $it") }
        return resp
    }

    fun event(id: String, content: Map<String, Any?>) {
        post(E_OPACK, Opack.pack(mapOf("_i" to id, "_t" to 1, "_c" to content, "_x" to xid.getAndIncrement())))
    }

    // ---- pairing (pair-setup, PIN shown on the TV) ----

    private var setupState: HapPairing.SetupState? = null

    private val setupExchange = HapPairing.Exchange { step, tlv ->
        exchangeAuth(if (step == 1) PS_START else PS_NEXT, mapOf("_pd" to tlv, "_pwTy" to 1))
    }

    /** Asks the Apple TV to show a PIN. */
    suspend fun startPairing() { setupState = HapPairing.setupStart(setupExchange) }

    suspend fun finishPairing(pin: String, displayName: String): Credentials =
        HapPairing.setupFinish(setupExchange, setupState ?: throw CompanionException("pairing not started"), pin, displayName)

    // ---- pair-verify and session ----

    private suspend fun verify(creds: Credentials) {
        val shared = HapPairing.verify({ step, tlv ->
            if (step == 1) exchangeAuth(PV_START, mapOf("_pd" to tlv, "_auTy" to 4)) else exchangeAuth(PV_NEXT, mapOf("_pd" to tlv))
        }, creds)
        synchronized(sendLock) {
            outKey = hkdf("", "ClientEncrypt-main", shared)
            inKey = hkdf("", "ServerEncrypt-main", shared)
        }
    }

    /** Pair-verify with saved credentials, then register as a remote control. */
    suspend fun openSession(creds: Credentials, deviceName: String) {
        verify(creds)
        request(
            "_systemInfo",
            mapOf(
                "_bf" to 0, "_cf" to 512, "_clFl" to 128,
                "_i" to creds.clientId.toHex().take(12),
                "_idsID" to String(creds.clientId),
                "_pubID" to creds.clientId.toHex().take(12).chunked(2).joinToString(":").uppercase(),
                "_sf" to 256, "_sv" to "170.18",
                "model" to "iPhone14,3", "name" to deviceName,
            ),
        )
        request("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
        localSid = (0..Int.MAX_VALUE).random().toLong()
        request("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to localSid))
        runCatching { request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
        event("_interest", mapOf("_regEvents" to listOf("_iMC", "SystemStatus", "TVSystemStatus")))
    }

    // ---- commands ----

    /**
     * Button down/up. Sent as a request, like the iOS remote, but nothing waits for the reply: the
     * press goes out immediately and back-to-back presses never queue behind a network round trip.
     */
    fun hid(command: HidCommand, down: Boolean) {
        val content = mapOf("_hBtS" to if (down) 1 else 2, "_hidC" to command.code)
        post(E_OPACK, Opack.pack(mapOf("_i" to "_hidC", "_t" to 2, "_c" to content, "_x" to xid.getAndIncrement())))
    }

    fun press(command: HidCommand) { hid(command, true); hid(command, false) }

    /** Skips the playing media by [seconds] (negative = back). Fails when nothing is playing. */
    suspend fun skip(seconds: Double) { request("_mcc", mapOf("_mcc" to 7, "_skpS" to seconds)) }

    /** Volume 0..1. Only available when the Apple TV itself controls volume (e.g. HomePod or AirPlay speakers). */
    suspend fun volume(): Double = (request("_mcc", mapOf("_mcc" to 5))["_c"].asMap()["_vol"] as? Number)?.toDouble()
        ?: throw CompanionException("volume unavailable")

    suspend fun setVolume(level: Double) { request("_mcc", mapOf("_mcc" to 6, "_vol" to level)) }

    suspend fun nextTrack() { request("_mcc", mapOf("_mcc" to 3)) }
    suspend fun previousTrack() { request("_mcc", mapOf("_mcc" to 4)) }

    suspend fun apps(): Map<String, String> =
        request("FetchLaunchableApplicationsEvent")["_c"].asMap().mapValues { it.value.toString() }

    suspend fun launchApp(bundleId: String) { request("_launchApp", mapOf("_bundleID" to bundleId)) }

    /** 1 = asleep, 2 = screensaver, 3 = awake, 4 = idle; null if the device does not say. */
    suspend fun attentionState(): Long? = runCatching { request("FetchAttentionState")["_c"].asMap()["state"].asLong() }.getOrNull()

    /** Starts a text-input session; returns the archived keyboard state, or null when no text field is focused. */
    suspend fun textInputStart(): ByteArray? = request("_tiStart")["_c"].asMap()["_tiD"] as? ByteArray

    suspend fun textInputStop() { runCatching { request("_tiStop") } }

    /**
     * Replaces the text of the focused field on the TV. With the [sessionUuid] of the current
     * text-input session it sends the edit directly; otherwise it restarts the session to get one.
     * Returns false when no text field is focused.
     */
    suspend fun setText(text: String, sessionUuid: ByteArray? = null): Boolean {
        val uuid = sessionUuid ?: run {
            textInputStop()
            val archive = textInputStart() ?: return false
            KeyedArchive.property(archive, "sessionUUID") as? ByteArray ?: return false
        }
        event("_tiC", mapOf("_tiV" to 1, "_tiD" to KeyedArchive.clearText(uuid)))
        if (text.isNotEmpty()) event("_tiC", mapOf("_tiV" to 1, "_tiD" to KeyedArchive.insertText(uuid, text)))
        return true
    }

    companion object {
        const val PS_START = 3
        const val PS_NEXT = 4
        const val PV_START = 5
        const val PV_NEXT = 6
        const val E_OPACK = 8
        private val AUTH_FRAMES = setOf(PS_START, PS_NEXT, PV_START, PV_NEXT)
        private val OPACK_FRAMES = setOf(7, 8, 9)
        private const val AUTH_KEY = "auth"
    }
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
fun String.hexToBytes() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
