package app.atvremote.protocol

import app.atvremote.protocol.AirPlayClient.Companion.MRP_REMOVE_CLIENT
import app.atvremote.protocol.AirPlayClient.Companion.MRP_REMOVE_PLAYER
import app.atvremote.protocol.AirPlayClient.Companion.MRP_SET_NOW_PLAYING_CLIENT
import app.atvremote.protocol.AirPlayClient.Companion.MRP_SET_NOW_PLAYING_PLAYER
import app.atvremote.protocol.AirPlayClient.Companion.MRP_SET_STATE
import app.atvremote.protocol.AirPlayClient.Companion.MRP_UPDATE_CLIENT
import app.atvremote.protocol.AirPlayClient.Companion.MRP_UPDATE_CONTENT_ITEM
import app.atvremote.protocol.AirPlayClient.Companion.MRP_UPDATE_CONTENT_ITEM_ARTWORK

/** What the Apple TV is playing, as shown in the app. */
data class NowPlaying(
    val title: String,
    val subtitle: String?,
    val bundleId: String?,
    val appName: String?,
    val playing: Boolean,
    val duration: Double?,
    /** Position in seconds at [positionAtMs] (System.currentTimeMillis()). */
    val position: Double?,
    val positionAtMs: Long,
    val rate: Double,
    val itemId: String?,
    val artworkAvailable: Boolean,
) {
    fun positionNow(nowMs: Long = System.currentTimeMillis()): Double? {
        val p = position ?: return null
        val live = if (playing) p + (nowMs - positionAtMs) / 1000.0 * rate else p
        return duration?.let { live.coerceIn(0.0, it) } ?: live.coerceAtLeast(0.0)
    }
}

/**
 * Follows MRP state messages: several apps ("clients") can each have players with a playback
 * queue; the TV says which client and player are active. Mirrors pyatv's PlayerStateManager.
 */
class NowPlayingTracker {
    private class Player {
        var state: Int? = null
        var items: List<ByteArray> = emptyList() // raw ContentItem messages; updates are merged by concatenation
        var location = 0
        var receivedAtMs = System.currentTimeMillis()
        var legacy: ProtoMsg? = null // SetState.nowPlayingInfo, used when there is no queue
    }

    private class Client(var displayName: String?) {
        val players = HashMap<String, Player>()
        var activePlayer: String? = null
        fun active(): Player? =
            players[activePlayer ?: DEFAULT_PLAYER] ?: players[DEFAULT_PLAYER] ?: players[""] ?: players.values.singleOrNull()
    }

    private val clients = HashMap<String, Client>()
    private var activeClient: String? = null

    private fun client(c: ProtoMsg?): Client {
        val bundle = c?.string(2) ?: ""
        return clients.getOrPut(bundle) { Client(c?.string(7)) }.also { cl -> c?.string(7)?.takeIf { it.isNotEmpty() }?.let { cl.displayName = it } }
    }

    private fun player(path: ProtoMsg?): Player {
        val id = path?.msg(3)?.string(1) ?: ""
        return client(path?.msg(2)).players.getOrPut(id) { Player() }
    }

    /** Applies one ProtocolMessage. */
    fun handle(m: ProtoMsg) {
        when (m.int(1)) {
            MRP_SET_STATE -> {
                val s = m.msg(9) ?: return
                val p = player(s.msg(9))
                s.int(6)?.let { p.state = it }
                s.msg(3)?.let { q -> p.items = q.rawItems(); p.location = q.int(1) ?: 0 }
                s.msg(1)?.let { p.legacy = it }
                p.receivedAtMs = System.currentTimeMillis()
            }
            MRP_UPDATE_CONTENT_ITEM -> {
                val u = m.msg(60) ?: return
                val p = player(u.msg(2))
                val updates = u.rawRepeated(1)
                p.items = p.items.map { existing ->
                    val id = Proto.decode(existing).string(1)
                    updates.firstOrNull { Proto.decode(it).string(1) == id }?.let { existing + it } ?: existing
                }
                p.receivedAtMs = System.currentTimeMillis()
            }
            MRP_SET_NOW_PLAYING_CLIENT -> {
                val c = m.msg(50)?.msg(1)
                client(c)
                activeClient = c?.string(2) ?: ""
            }
            MRP_SET_NOW_PLAYING_PLAYER -> {
                val path = m.msg(51)?.msg(1)
                client(path?.msg(2)).activePlayer = path?.msg(3)?.string(1)?.takeIf { it.isNotEmpty() }
            }
            MRP_UPDATE_CLIENT -> client(m.msg(59)?.msg(1))
            MRP_REMOVE_CLIENT -> {
                val bundle = m.msg(57)?.msg(1)?.string(2) ?: return
                clients.remove(bundle)
                if (bundle == activeClient) activeClient = null
            }
            MRP_REMOVE_PLAYER -> {
                val path = m.msg(58)?.msg(1) ?: return
                val c = clients[path.msg(2)?.string(2) ?: ""] ?: return
                val id = path.msg(3)?.string(1) ?: return
                c.players.remove(id)
                if (c.activePlayer == id) c.activePlayer = null
            }
        }
    }

    /** Artwork carried by a message (pushed artwork updates or an answer to a queue request), by item id. */
    fun artwork(m: ProtoMsg): Pair<String?, ByteArray>? {
        val items = when (m.int(1)) {
            MRP_SET_STATE -> m.msg(9)?.msg(3)?.msgs(2)
            MRP_UPDATE_CONTENT_ITEM -> m.msg(60)?.msgs(1)
            MRP_UPDATE_CONTENT_ITEM_ARTWORK -> m.msg(61)?.msgs(1)
            else -> null
        } ?: return null
        val item = items.firstOrNull { it.bytes(3)?.isNotEmpty() == true } ?: return null
        return item.string(1) to item.bytes(3)!!
    }

    fun current(): NowPlaying? {
        // Before the TV names the active app, fall back to whichever app reports something playing.
        val bundle = activeClient
            ?: clients.entries.firstOrNull { e -> e.value.players.values.any { it.state == 1 } }?.key
            ?: clients.entries.firstOrNull { e -> e.value.players.values.any { it.items.isNotEmpty() || it.legacy != null } }?.key
            ?: return null
        val c = clients[bundle] ?: return null
        val p = c.active() ?: return null
        val item = p.items.getOrNull(p.location)?.let(Proto::decode)
        val md = item?.msg(2)
        val legacy = p.legacy
        val title = md?.string(1)?.takeIf { it.isNotBlank() } ?: legacy?.string(9)?.takeIf { it.isNotBlank() } ?: return null

        val series = md?.string(63)
        val subtitle = when {
            !series.isNullOrBlank() -> listOfNotNull(
                series,
                listOfNotNull(md.int(10)?.let { "S$it" }, md.int(11)?.let { "E$it" }).joinToString(" ").ifEmpty { null },
            ).joinToString(" · ")
            else -> listOfNotNull(md?.string(7) ?: legacy?.string(2), md?.string(6) ?: legacy?.string(1))
                .filter { it.isNotBlank() }.joinToString(" — ").ifEmpty { md?.string(2) }
        }

        val rate = (md?.float(39) ?: legacy?.float(5))?.toDouble()
        val playing = p.state == 1
        val elapsed = md?.double(35) ?: legacy?.double(4)
        val stamp = md?.double(74) ?: legacy?.double(8) // CFAbsoluteTime: seconds since 2001-01-01
        val atMs = stamp?.let { ((it + CF_EPOCH) * 1000).toLong() } ?: p.receivedAtMs
        return NowPlaying(
            title = title,
            subtitle = subtitle?.ifBlank { null },
            bundleId = bundle.ifEmpty { null },
            appName = c.displayName,
            playing = playing,
            duration = (md?.double(14) ?: legacy?.double(3))?.takeIf { it > 0 && it.isFinite() },
            position = elapsed,
            positionAtMs = atMs,
            rate = if (playing) (rate?.takeIf { it > 0 } ?: 1.0) else 0.0,
            itemId = item?.string(1),
            artworkAvailable = md?.bool(19) ?: true,
        )
    }

    private fun ProtoMsg.rawItems(): List<ByteArray> = rawRepeated(2)

    companion object {
        private const val DEFAULT_PLAYER = "MediaRemote-DefaultPlayer"
        private const val CF_EPOCH = 978307200.0

        /** Asks for the current item's artwork (answered with a SET_STATE carrying artworkData). */
        fun artworkRequest(identifier: String): ByteArray = AirPlayClient.mrp(AirPlayClient.MRP_PLAYBACK_QUEUE_REQUEST, identifier) {
            msg(37) { int(1, 0); int(2, 1); double(4, -1.0); double(5, 600.0); bool(13, true) }
        }
    }
}
