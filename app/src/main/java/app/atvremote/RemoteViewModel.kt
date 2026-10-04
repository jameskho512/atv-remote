package app.atvremote

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.atvremote.protocol.CompanionClient
import app.atvremote.protocol.Credentials
import app.atvremote.protocol.HidCommand
import app.atvremote.protocol.KeyedArchive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Connected : Conn
    data class Failed(val message: String) : Conn
}

sealed interface Pairing {
    data object Starting : Pairing
    data object AwaitingPin : Pairing
    data object Verifying : Pairing
    data class Failed(val message: String) : Pairing
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val discovery = Discovery(app)
    private val phoneName = Build.MODEL ?: "Android"

    private val saved = MutableStateFlow(store.load())

    /** Paired devices first (with fresh addresses from discovery), then unpaired ones nearby. */
    val devices: StateFlow<List<Device>> = combine(saved, discovery.found) { s, found ->
        val pairedIds = s.map { it.id }.toSet()
        s.map { p -> found.firstOrNull { it.id == p.id }?.let { p.copy(host = it.host, port = it.port) } ?: p } +
            found.filter { it.id !in pairedIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val current = MutableStateFlow<Device?>(null)
    val conn = MutableStateFlow<Conn>(Conn.Idle)
    val pairing = MutableStateFlow<Pairing?>(null)
    val apps = MutableStateFlow<List<Pair<String, String>>?>(null)
    val message = MutableStateFlow<String?>(null)
    /** Text of the focused text field on the TV, or null when none is focused. */
    val tvKeyboard = MutableStateFlow<String?>(null)
    /** Changes each time a text field gets focus on the TV, so the input bar resets. */
    val tvKeyboardSession = MutableStateFlow(0)
    private var tiSession: ByteArray? = null
    val physicalLayout = MutableStateFlow(store.physicalLayout)
    /** Bottom tab: 0 Apple TV, 1 Bravia. */
    val tab = MutableStateFlow(store.tab)
    val icons = AppIcons(app)
    /** Apple TV power state: 1 asleep, 2 screensaver, 3 awake, 4 idle; null if unknown. */
    val power = MutableStateFlow<Long?>(null)
    val now = NowPlayingSession(viewModelScope, phoneName)
    val nowPlayingDeclined = MutableStateFlow(false)
    /** Bundle id → app name from the TV, for naming the app in the now-playing card. */
    val appNames = MutableStateFlow<Map<String, String>>(emptyMap())

    fun setTab(t: Int) { tab.value = t; store.tab = t }

    private var client: CompanionClient? = null
    private var pairingClient: CompanionClient? = null
    private var connectJob: Job? = null
    private val commands = Channel<suspend CompanionClient.() -> Unit>(Channel.UNLIMITED)

    init {
        // Commands run one at a time, in order, so a button's down/up and touch strokes never interleave.
        viewModelScope.launch {
            for (cmd in commands) {
                val c = client ?: continue
                try { c.cmd() } catch (e: Exception) {
                    if (c.closed.isCompleted) onDisconnected() else message.value = e.message ?: "Command failed"
                }
            }
        }
        if (DemoMode.enabled) loadDemo() else {
            store.lastDeviceId?.let { id -> saved.value.firstOrNull { it.id == id }?.let { select(it) } }
            followForNotification(app)
        }
    }

    /** Placeholder device and playback for README screenshots; nothing is connected or saved. */
    private fun loadDemo() {
        current.value = Device(DemoMode.DEVICE_NAME, "0.0.0.0", 0, credentials = "demo")
        conn.value = Conn.Connected
        power.value = 3L
        physicalLayout.value = DemoMode.screen == "apple-siri"
        tab.value = if (DemoMode.screen == "bravia") 1 else 0
        now.showDemo(DemoMode.nowPlaying(), DemoMode.artwork())
    }

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    fun setPhysicalLayout(on: Boolean) { physicalLayout.value = on; store.physicalLayout = on }

    val coffeeHidden = MutableStateFlow(store.coffeeHidden)

    /** Hides the Buy me a coffee button permanently. */
    fun hideCoffee() { coffeeHidden.value = true; store.coffeeHidden = true }

    fun addManual(host: String, port: Int) {
        val d = Device(host, host.trim(), port)
        select(d)
    }

    /** Opens a device: connects if paired, otherwise starts pairing. */
    fun select(device: Device) {
        val d = devices.value.firstOrNull { it.id == device.id } ?: device
        disconnect()
        current.value = d
        if (d.paired) connect() else startPairing(d)
    }

    fun closeDevice() {
        disconnect()
        cancelPairing()
        current.value = null
        store.lastDeviceId = null
    }

    fun forget(device: Device) {
        saved.value = saved.value.filter { it.id != device.id }
        store.save(saved.value)
        if (current.value?.id == device.id) closeDevice()
    }

    // ---- pairing ----

    private fun startPairing(d: Device) {
        pairing.value = Pairing.Starting
        viewModelScope.launch {
            try {
                val c = CompanionClient(d.host, d.port)
                pairingClient = c
                c.connect()
                c.startPairing()
                pairing.value = Pairing.AwaitingPin
            } catch (e: Exception) {
                pairing.value = Pairing.Failed(friendly(e, "Could not reach the Apple TV"))
            }
        }
    }

    fun retryPairing() { current.value?.let { cancelPairing(); startPairing(it) } }

    fun submitPin(pin: String) {
        val c = pairingClient ?: return
        val d = current.value ?: return
        pairing.value = Pairing.Verifying
        viewModelScope.launch {
            try {
                val creds = c.finishPairing(pin, phoneName)
                c.close()
                pairingClient = null
                val paired = d.copy(credentials = creds.serialize())
                saved.value = saved.value.filter { it.id != d.id } + paired
                store.save(saved.value)
                pairing.value = null
                current.value = paired
                connect()
            } catch (e: Exception) {
                c.close()
                pairingClient = null
                pairing.value = Pairing.Failed(friendly(e, "Pairing failed"))
            }
        }
    }

    fun cancelPairing() {
        pairingClient?.close()
        pairingClient = null
        pairing.value = null
    }

    // ---- connection ----

    fun connect() {
        val d = current.value ?: return
        val creds = d.credentials?.let { runCatching { Credentials.parse(it) }.getOrNull() } ?: return
        connectJob?.cancel()
        conn.value = Conn.Connecting
        connectJob = viewModelScope.launch {
            val c = CompanionClient(d.host, d.port)
            try {
                c.connect()
                c.openSession(creds, phoneName)
                client = c
                store.lastDeviceId = d.id
                conn.value = Conn.Connected
                launch { watch(c) }
                power.value = c.attentionState()
                startNowPlaying(d, creds)
                launch { runCatching { appNames.value = c.apps() } }
                // Register for keyboard focus events; also tells us if a text field is focused right now.
                runCatching { c.textInputStart() }.getOrNull()?.let { onTextFocus(it) }
                launch { c.closed.await(); if (client === c) onDisconnected() }
            } catch (e: Exception) {
                c.close()
                conn.value = Conn.Failed(friendly(e, "Could not connect"))
            }
        }
    }

    private suspend fun watch(c: CompanionClient) {
        c.events.collect { (id, content) ->
            when (id) {
                "_tiStarted" -> (content["_tiD"] as? ByteArray)?.let { onTextFocus(it) }
                "_tiStopped" -> if (System.currentTimeMillis() > ignoreTextEventsUntil) { tvKeyboard.value = null; tiSession = null }
                "SystemStatus", "TVSystemStatus" -> (content["state"] as? Number)?.let { power.value = it.toLong() }
            }
        }
    }

    private var ignoreTextEventsUntil = 0L

    private fun onTextFocus(archive: ByteArray) {
        val uuid = runCatching { KeyedArchive.property(archive, "sessionUUID") as? ByteArray }.getOrNull()
        if (uuid != null && tiSession?.contentEquals(uuid) == true && tvKeyboard.value != null) return
        tiSession = uuid
        tvKeyboard.value = currentText(archive)
        tvKeyboardSession.value++
    }

    private fun currentText(archive: ByteArray): String =
        runCatching { KeyedArchive.property(archive, "documentState", "docSt", "contextBeforeInput") as? String }.getOrNull() ?: ""

    /** Reconnects when the app returns to the foreground after the TV dropped the connection. */
    fun onResume() {
        if (current.value?.paired == true && (conn.value is Conn.Failed || conn.value == Conn.Idle)) connect()
    }

    private fun onDisconnected() {
        client?.close()
        client = null
        now.stop()
        power.value = null
        tvKeyboard.value = null
        if (conn.value == Conn.Connected) conn.value = Conn.Failed("Connection lost")
    }

    private fun disconnect() {
        connectJob?.cancel()
        client?.close()
        client = null
        now.stop()
        now.cancelPairing()
        power.value = null
        appNames.value = emptyMap()
        conn.value = Conn.Idle
        apps.value = null
        tvKeyboard.value = null
        tiSession = null
        volumeBeforeMute = null
    }

    // ---- remote commands ----

    private fun send(cmd: suspend CompanionClient.() -> Unit) { if (client != null) commands.trySend(cmd) }

    // Buttons skip the command queue: the press is handed to the client's writer thread at once.
    fun button(cmd: HidCommand, down: Boolean) { client?.hid(cmd, down) }
    fun click(cmd: HidCommand) { client?.press(cmd) }
    fun skip(seconds: Double) = send { skip(seconds) }

    /**
     * Power button tap, like the Siri Remote: wakes the Apple TV when it is asleep, otherwise opens
     * Control Center, where its power options are.
     */
    fun powerTap() = send {
        val asleep = (power.value ?: attentionState()) == 1L
        press(if (asleep) HidCommand.Wake else HidCommand.PageDown)
        if (asleep) watchPowerChange(wasOn = false)
    }

    /** Puts the Apple TV to sleep (and the TV off, if it follows the Apple TV over HDMI). */
    fun turnOff() = send {
        press(HidCommand.Sleep)
        watchPowerChange(wasOn = true)
    }

    private var powerWatch: Job? = null

    /**
     * After a power press, checks every 200 ms for up to 10 s and stops once the Apple TV is on
     * when it was off, or off when it was on. Status events pushed by the TV also end it.
     */
    private fun watchPowerChange(wasOn: Boolean) {
        powerWatch?.cancel()
        powerWatch = viewModelScope.launch {
            repeat(50) {
                if (power.value.let { it != null && (it != 1L) != wasOn }) return@launch
                delay(200)
                client?.attentionState()?.let { power.value = it }
            }
        }
    }

    // ---- now playing ----

    private fun startNowPlaying(d: Device, companion: Credentials) {
        nowPlayingDeclined.value = d.id in store.nowPlayingDeclined
        now.start(d.host, companion, d.airplayCredentials?.let { runCatching { Credentials.parse(it) }.getOrNull() })
    }

    fun pairNowPlaying() { current.value?.let { now.startPairing(it.host) } }

    fun submitNowPlayingPin(pin: String) {
        now.submitPin(pin) { creds ->
            val d = current.value ?: return@submitPin
            val updated = d.copy(airplayCredentials = creds.serialize())
            saved.value = saved.value.map { if (it.id == d.id) updated else it }
            store.save(saved.value)
            current.value = updated
            d.credentials?.let { Credentials.parse(it) }?.let { startNowPlaying(updated, it) }
        }
    }

    fun declineNowPlaying() {
        val id = current.value?.id ?: return
        store.nowPlayingDeclined = store.nowPlayingDeclined + id
        nowPlayingDeclined.value = true
    }

    private var volumeBeforeMute: Double? = null

    /**
     * Mute toggles like the Siri Remote's mute button: an HID Mute key over the AirPlay link, which
     * the Apple TV passes to the TV over HDMI. Without that link it falls back to setting the
     * volume to 0 and back, which only works when the Apple TV controls the volume itself.
     */
    fun toggleMute() {
        if (now.hidKey(0x0C, 0xE2)) return
        send {
            try {
                val restore = volumeBeforeMute
                if (restore != null) { setVolume(restore); volumeBeforeMute = null }
                else { val v = volume(); setVolume(0.0); volumeBeforeMute = if (v > 0) v else 0.3 }
            } catch (e: Exception) {
                if (closed.isCompleted) throw e
                message.value = if (now.link.value == NowPlayingLink.NeedsPairing) "Set up \"See what's playing\" to enable mute"
                else "Mute isn't available right now"
            }
        }
    }

    fun loadApps() = send {
        val list = apps().toList().sortedBy { it.second.lowercase() }
        this@RemoteViewModel.apps.value = list
    }

    fun launch(bundleId: String) = send { launchApp(bundleId) }

    private var textJob: Job? = null

    /** Sends the text to the TV's focused field, debounced while typing. */
    fun typeText(text: String) {
        textJob?.cancel()
        textJob = viewModelScope.launch {
            delay(150)
            send {
                val session = tiSession
                // Without a known session, setText restarts text input, which can echo a "stopped" event.
                if (session == null) ignoreTextEventsUntil = System.currentTimeMillis() + 1500
                if (!setText(text, session)) message.value = "No text field is selected on the TV"
            }
        }
    }

    fun seek(seconds: Double) { if (!now.seek(seconds)) message.value = "Can't seek right now" }

    fun nextTrack() = send { runCatching { nextTrack() }.onFailure { if (closed.isCompleted) throw it; message.value = "Nothing to skip to" } }
    fun previousTrack() = send { runCatching { previousTrack() }.onFailure { if (closed.isCompleted) throw it; message.value = "Nothing to go back to" } }

    private fun friendly(e: Exception, fallback: String): String = when (e) {
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "$fallback. Check the phone and the Apple TV are on the same network."
        is kotlinx.coroutines.TimeoutCancellationException -> "$fallback: the Apple TV did not respond."
        else -> e.message ?: fallback
    }

    /** Feeds the now-playing notification, starting its service when something plays. */
    private fun followForNotification(app: Application) {
        NowPlayingBridge.controls = object : NowPlayingBridge.Controls {
            override fun playPause() = click(HidCommand.PlayPause)
            override fun next() = nextTrack()
            override fun previous() = previousTrack()
            override fun seek(seconds: Double) = this@RemoteViewModel.seek(seconds)
        }
        viewModelScope.launch {
            combine(now.nowPlaying, now.artwork, appNames, current) { np, art, names, device ->
                np?.let {
                    NowPlayingBridge.State(
                        it,
                        art?.takeIf { a -> a.first == it.itemId || it.itemId == null }?.second,
                        it.appName ?: it.bundleId?.let(names::get),
                        device?.name ?: "Apple TV",
                    )
                }
            }.collect { s ->
                NowPlayingBridge.state.value = s
                if (s != null && !NowPlayingBridge.running) {
                    // Android only lets a foreground service start while the app is visible; otherwise skip it.
                    runCatching { app.startForegroundService(Intent(app, NowPlayingService::class.java)) }
                }
            }
        }
    }

    override fun onCleared() {
        NowPlayingBridge.state.value = null
        NowPlayingBridge.controls = null
        disconnect()
        cancelPairing()
        discovery.stop()
    }
}
