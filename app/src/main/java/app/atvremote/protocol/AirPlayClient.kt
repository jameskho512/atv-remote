package app.atvremote.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * HAP-encrypted byte stream used by AirPlay: 1024-byte frames, each sent as a 2-byte
 * little-endian length (also the AAD), the ChaCha20-Poly1305 ciphertext and tag. Nonces are
 * a per-direction counter, 4 zero bytes then 8 bytes little-endian.
 */
private class HapStream(private val raw: InputStream, private val rawOut: OutputStream) {
    private var outKey: ByteArray? = null
    private var inKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L
    private var buf = ByteArray(0)
    private var pos = 0

    fun enable(outputKey: ByteArray, inputKey: ByteArray) { outKey = outputKey; inKey = inputKey }

    private fun nonce(c: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (c ushr (8 * i)).toByte() }

    @Synchronized
    fun write(data: ByteArray) {
        val key = outKey
        if (key == null) { rawOut.write(data); rawOut.flush(); return }
        val out = ByteArrayOutputStream()
        var p = 0
        while (p < data.size) {
            val n = minOf(1024, data.size - p)
            val len = byteArrayOf(n.toByte(), (n shr 8).toByte())
            out.write(len)
            out.write(ChaCha.seal(key, nonce(outCounter++), data.copyOfRange(p, p + n), len))
            p += n
        }
        rawOut.write(out.toByteArray()); rawOut.flush()
    }

    fun read(): Int {
        if (pos >= buf.size) {
            val key = inKey
            if (key == null) return raw.read()
            val len = ByteArray(2).also { readFully(it) }
            val n = (len[0].toInt() and 0xFF) or ((len[1].toInt() and 0xFF) shl 8)
            val sealed = ByteArray(n + 16).also { readFully(it) }
            buf = ChaCha.open(key, nonce(inCounter++), sealed, len)
            pos = 0
            if (buf.isEmpty()) return read()
        }
        return buf[pos++].toInt() and 0xFF
    }

    fun readFully(n: Int): ByteArray = ByteArray(n).also { for (i in 0 until n) it[i] = readByte().toByte() }
    private fun readByte(): Int = read().also { if (it < 0) throw java.io.EOFException() }

    private fun readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = raw.read(b, off, b.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }

    /** One HTTP/RTSP message (start line, headers, Content-Length body). */
    fun readMessage(): HttpMessage {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = readByte()
            if (c == '\n'.code) {
                val s = line.toString().trimEnd('\r')
                line.clear()
                if (s.isEmpty()) { if (lines.isEmpty()) continue else break }
                lines += s
            } else line.append(c.toChar())
        }
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = readFully(headers["content-length"]?.toIntOrNull() ?: 0)
        return HttpMessage(lines.first(), headers, body)
    }
}

class HttpMessage(val startLine: String, val headers: Map<String, String>, val body: ByteArray) {
    /** Status code for a response ("RTSP/1.0 200 OK"), or -1 for a request. */
    val code: Int get() = startLine.split(' ').getOrNull(1)?.toIntOrNull()?.takeIf { startLine.startsWith("HTTP") || startLine.startsWith("RTSP") } ?: -1
}

/**
 * AirPlay 2 remote-control session: the channel the iOS Remote uses to receive "now playing"
 * state as MRP protobuf messages. Port 7000, pair-verify over HTTP, then encrypted RTSP SETUP of
 * an event channel and a data-stream channel that carries the protobufs.
 */
class AirPlayClient(private val host: String, private val port: Int = 7000) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ArrayList<Socket>()
    private lateinit var control: HapStream
    private lateinit var localIp: String
    private val controlLock = Mutex()
    private var data: HapStream? = null
    /** Writes to the remote-control channel happen here, in order, never on the caller's (often the main) thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-writer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    }
    private var shared: ByteArray? = null

    private var cseq = 0
    private val sessionId = (0..Int.MAX_VALUE).random().toLong()
    private val dacpId = "%016X".format(random.nextLong())
    private val activeRemote = (0..Int.MAX_VALUE).random().toLong()
    private val dataSeqno = 0x100000000L + (random.nextLong() and 0xFFFFFFFL)

    private val _messages = MutableSharedFlow<ProtoMsg>(extraBufferCapacity = 256)
    /** Every MRP ProtocolMessage the Apple TV sends. */
    val messages: SharedFlow<ProtoMsg> = _messages
    val closed = CompletableDeferred<Throwable?>()

    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = open(port)
        s.soTimeout = 8000 // control replies; the channel sockets block indefinitely
        localIp = s.localAddress.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        control = HapStream(s.getInputStream().buffered(), s.getOutputStream())
    }

    private fun open(p: Int): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, p), 5000)
        soTimeout = 0
        synchronized(sockets) { sockets += this }
    }

    fun close() {
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        closed.complete(null)
        scope.cancel()
        writer.shutdown()
    }

    private fun fail(e: Throwable) {
        if (closed.complete(e)) close()
    }

    // ---- control connection ----

    private suspend fun request(
        method: String, path: String, protocol: String = "RTSP/1.0",
        headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0),
    ): HttpMessage = controlLock.withLock {
        withContext(Dispatchers.IO) {
            val sb = StringBuilder("$method $path $protocol\r\n")
            val all = LinkedHashMap<String, String>()
            if (protocol.startsWith("RTSP")) {
                all["CSeq"] = (cseq++).toString()
                all["DACP-ID"] = dacpId
                all["Active-Remote"] = activeRemote.toString()
                all["Client-Instance"] = dacpId
                all["User-Agent"] = "AirPlay/550.10"
            }
            all.putAll(headers)
            if (body.isNotEmpty()) all["Content-Length"] = body.size.toString()
            all.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("\r\n")
            control.write(sb.toString().toByteArray() + body)
            control.readMessage()
        }
    }

    private val hapHeaders = mapOf(
        "User-Agent" to "AirPlay/320.20", "Connection" to "keep-alive",
        "X-Apple-HKP" to "3", "Content-Type" to "application/octet-stream",
    )

    private fun exchange(path: String) = HapPairing.Exchange { _, tlv ->
        val resp = request("POST", path, "HTTP/1.1", hapHeaders, tlv)
        if (resp.code != 200) throw CompanionException("AirPlay $path failed (HTTP ${resp.code})")
        Tlv8.read(resp.body).also { HapPairing.checkError(it) }
    }

    private var setupState: HapPairing.SetupState? = null

    /** Shows an AirPlay pairing PIN on the TV. */
    suspend fun startPairing() {
        request("POST", "/pair-pin-start", "HTTP/1.1", hapHeaders)
        setupState = HapPairing.setupStart(exchange("/pair-setup"))
    }

    suspend fun finishPairing(pin: String, displayName: String): Credentials =
        HapPairing.setupFinish(exchange("/pair-setup"), setupState ?: throw CompanionException("pairing not started"), pin, displayName)

    /** Pair-verify; afterwards the control connection is encrypted. Throws if the TV rejects [creds]. */
    suspend fun verify(creds: Credentials) {
        val s = HapPairing.verify(exchange("/pair-verify"), creds)
        shared = s
        control.enable(hkdf("Control-Salt", "Control-Write-Encryption-Key", s), hkdf("Control-Salt", "Control-Read-Encryption-Key", s))
    }

    // ---- remote-control channels ----

    /** Opens the event and data channels and performs the MRP handshake. */
    suspend fun startRemoteControl(deviceName: String, clientId: String) {
        val s = shared ?: throw CompanionException("not verified")
        val plist = mapOf("Content-Type" to "application/x-apple-binary-plist")
        val deviceId = UUID.nameUUIDFromBytes(clientId.toByteArray()).toString().replace("-", "").take(12)
            .chunked(2).joinToString(":").uppercase()
        val ev = request(
            "SETUP", uri(), headers = plist,
            body = BinaryPlist.write(
                mapOf(
                    "isRemoteControlOnly" to true, "osName" to "iPhone OS", "sourceVersion" to "550.10",
                    "timingProtocol" to "None", "model" to "iPhone10,6", "deviceID" to deviceId,
                    "osVersion" to "14.7.1", "osBuildVersion" to "18G82", "macAddress" to deviceId,
                    "sessionUUID" to UUID.randomUUID().toString().uppercase(), "name" to deviceName,
                ),
            ),
        )
        val eventPort = ((BinaryPlist.read(ev.body) as? Map<*, *>)?.get("eventPort") as? Number)?.toInt()
            ?: throw CompanionException("AirPlay SETUP failed (HTTP ${ev.code})")
        // Event channel: keys reversed because the receiver is the one sending requests on it.
        val event = withContext(Dispatchers.IO) { open(eventPort) }.let { HapStream(it.getInputStream().buffered(), it.getOutputStream()) }
        event.enable(hkdf("Events-Salt", "Events-Read-Encryption-Key", s), hkdf("Events-Salt", "Events-Write-Encryption-Key", s))
        scope.launch { eventLoop(event) }

        request("RECORD", uri())

        val seed = random.nextLong() and Long.MAX_VALUE
        val ds = request(
            "SETUP", uri(), headers = plist,
            body = BinaryPlist.write(
                mapOf(
                    "streams" to listOf(
                        mapOf(
                            "controlType" to 2, "channelID" to UUID.randomUUID().toString().uppercase(), "seed" to seed,
                            "clientUUID" to UUID.randomUUID().toString().uppercase(), "type" to 130,
                            "wantsDedicatedSocket" to true, "clientTypeUUID" to "1910A70F-DBC0-4242-AF95-115DB30604E1",
                        ),
                    ),
                ),
            ),
        )
        val dataPort = (((BinaryPlist.read(ds.body) as? Map<*, *>)?.get("streams") as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.get("dataPort").let { (it as? Number)?.toInt() } ?: throw CompanionException("AirPlay data SETUP failed (HTTP ${ds.code})")
        val salt = "DataStream-Salt$seed"
        val dataStream = withContext(Dispatchers.IO) { open(dataPort) }.let { HapStream(it.getInputStream().buffered(), it.getOutputStream()) }
        dataStream.enable(hkdf(salt, "DataStream-Output-Encryption-Key", s), hkdf(salt, "DataStream-Input-Encryption-Key", s))
        data = dataStream
        scope.launch { dataLoop(dataStream) }

        // Keep the session alive like iOS does.
        scope.launch {
            while (isActive) {
                delay(2000)
                try { request("POST", "/feedback") } catch (e: Exception) { fail(e); break }
            }
        }

        // MRP handshake: DEVICE_INFO must come first and is answered with the TV's own.
        val info = scope.async(start = CoroutineStart.UNDISPATCHED) { messages.first { it.int(1) == MRP_DEVICE_INFO } }
        send(mrp(MRP_DEVICE_INFO, identifier = UUID.randomUUID().toString().uppercase()) {
            msg(20) {
                string(1, clientId); string(2, deviceName); string(3, "iPhone"); string(4, "18G82")
                string(5, "com.apple.TVRemote"); string(6, "344.28"); int(7, 1); int(8, 108)
                bool(9, true); bool(10, true); string(12, "com.apple.TVMusic"); bool(13, true)
                bool(14, true); bool(15, true); int(17, 2); int(21, 1); int(22, 1)
            }
        })
        withTimeout(5000) { info.await() }
        send(mrp(MRP_SET_CONNECTION_STATE, identifier = newId()) { msg(42) { int(1, 2) } })
        send(mrp(MRP_CLIENT_UPDATES_CONFIG, identifier = newId()) { msg(21) { bool(1, true); bool(2, true); bool(3, true); bool(4, true); bool(5, true) } })
        send(mrp(MRP_GET_KEYBOARD_SESSION, identifier = newId()) {})
        // Updates only report changes, so ask for what's playing right now (otherwise a video already
        // running when the app connects never shows up).
        send(mrp(MRP_GET_STATE, identifier = newId()) {})
        scope.launch {
            while (isActive) { delay(30_000); runCatching { send(mrp(MRP_GENERIC) {}) } }
        }
    }

    private fun uri() = "rtsp://$localIp/$sessionId"

    /** Answers every request the receiver sends on the event channel with 200 OK. */
    private fun eventLoop(s: HapStream) {
        try {
            while (true) {
                val req = s.readMessage()
                val proto = req.startLine.substringAfterLast(' ')
                val cseq = req.headers["cseq"]?.let { "CSeq: $it\r\n" } ?: ""
                val server = req.headers["server"]?.let { "Server: $it\r\n" } ?: ""
                s.write("$proto 200 OK\r\nContent-Length: 0\r\nAudio-Latency: 0\r\n$server$cseq\r\n".toByteArray())
            }
        } catch (e: Exception) { fail(e) }
    }

    /** Data-stream frames: 32-byte header (size, 12-byte type, 4-byte command, seqno, padding) + plist payload. */
    private fun dataLoop(s: HapStream) {
        try {
            while (true) {
                val header = s.readFully(32)
                val size = be(header, 0, 4).toInt()
                val payload = s.readFully(size - 32)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (type == "sync") s.write(frame("rply", ByteArray(4), be(header, 20, 8), ByteArray(0)))
                if (payload.isEmpty()) continue
                val params = (BinaryPlist.read(payload) as? Map<*, *>)?.get("params") as? Map<*, *>
                val blob = params?.get("data") as? ByteArray ?: continue
                var p = 0
                while (p < blob.size) {
                    // Messages are varint length-prefixed, except a lone message that starts with its type tag.
                    val (len, start) = if (blob[p].toInt() == 0x08) (blob.size - p).toLong() to p else Proto.readVarint(blob, p)
                    val end = minOf(blob.size, start + len.toInt())
                    _messages.tryEmit(Proto.decode(blob.copyOfRange(start, end)))
                    p = end
                }
            }
        } catch (e: Exception) { fail(e) }
    }

    fun send(message: ByteArray) {
        val s = data ?: throw CompanionException("remote control channel not open")
        val blob = ByteArrayOutputStream().also { Proto.writeVarint(it, message.size.toLong()); it.write(message) }.toByteArray()
        val bytes = frame("sync", "comm".toByteArray(), dataSeqno, BinaryPlist.write(mapOf("params" to mapOf("data" to blob))))
        writer.execute { try { s.write(bytes) } catch (e: Exception) { fail(e) } }
    }

    private fun frame(type: String, command: ByteArray, seqno: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun be(v: Long, n: Int) { for (i in n - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        be(32L + payload.size, 4)
        out.write(type.toByteArray() + ByteArray(8))
        out.write(command)
        be(seqno, 8)
        be(0, 4)
        out.write(payload)
        return out.toByteArray()
    }

    private fun be(d: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (d[off + i].toLong() and 0xFF)
        return v
    }

    companion object {
        const val MRP_SEND_COMMAND = 1
        const val MRP_GET_STATE = 3
        const val MRP_SET_STATE = 4
        const val MRP_DEVICE_INFO = 15
        const val MRP_CLIENT_UPDATES_CONFIG = 16
        const val MRP_GET_KEYBOARD_SESSION = 24
        const val MRP_PLAYBACK_QUEUE_REQUEST = 32
        const val MRP_SET_CONNECTION_STATE = 38
        const val MRP_GENERIC = 42
        const val MRP_SET_NOW_PLAYING_CLIENT = 46
        const val MRP_SET_NOW_PLAYING_PLAYER = 47
        const val MRP_REMOVE_CLIENT = 53
        const val MRP_REMOVE_PLAYER = 54
        const val MRP_UPDATE_CLIENT = 55
        const val MRP_UPDATE_CONTENT_ITEM = 56
        const val MRP_UPDATE_CONTENT_ITEM_ARTWORK = 57

        const val CMD_SEEK = 45

        /** SEND_COMMAND for the active player; [options] fills CommandOptions (e.g. field 9, playbackPosition). */
        fun command(cmd: Int, options: ProtoBuilder.() -> Unit = {}): ByteArray =
            mrp(MRP_SEND_COMMAND, UUID.randomUUID().toString().uppercase()) { msg(6) { int(1, cmd); msg(2, options) } }

        const val MRP_SEND_HID_EVENT = 8
        private const val HID_PREFIX = "438922cf080200000000000000000000010000000000000002000000200000000300000001000000000000"
        private const val HID_SUFFIX = "0000000000000001000000"

        /** An HID key event (IOHIDEvent keyboard record, layout as used by pyatv). */
        fun hidEvent(usagePage: Int, usage: Int, down: Boolean): ByteArray = mrp(MRP_SEND_HID_EVENT) {
            msg(13) {
                bytes(
                    1,
                    HID_PREFIX.hexToBytes() +
                        byteArrayOf((usagePage shr 8).toByte(), usagePage.toByte(), (usage shr 8).toByte(), usage.toByte(), 0, if (down) 1 else 0) +
                        HID_SUFFIX.hexToBytes(),
                )
            }
        }

        private fun newId() = UUID.randomUUID().toString().uppercase()

        /** A ProtocolMessage of [type]; [body] fills in the type-specific extension field. */
        fun mrp(type: Int, identifier: String? = null, body: ProtoBuilder.() -> Unit): ByteArray = proto {
            int(1, type)
            if (identifier != null) string(2, identifier)
            int(4, 0)
            body()
            string(85, UUID.randomUUID().toString().uppercase())
        }
    }
}
