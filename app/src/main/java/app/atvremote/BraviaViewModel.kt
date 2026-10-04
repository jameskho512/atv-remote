package app.atvremote

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.atvremote.protocol.BraviaClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Buttons of the Bravia remote. Each lists the IRCC names it may go by, best first: models differ,
 * so the name is looked up in the list of codes the TV reports.
 */
enum class BraviaKey(val label: String, vararg val names: String) {
    Tv("TV", "Tv", "TV"),
    Tools("Tools", "Options", "Tools", "Help"),
    Input("Input", "Input"),
    Menu("Menu", "ActionMenu", "AndroidMenu", "Options"),
    Back("Back", "Return", "Back"),
    Home("Home", "Home"),
    VolumeUp("Volume up", "VolumeUp"),
    VolumeDown("Volume down", "VolumeDown"),
    ChannelUp("Channel up", "ChannelUp"),
    ChannelDown("Channel down", "ChannelDown"),
    Power("Power", "TvPower", "Power"),
    WakeUp("Wake up", "WakeUp"),
    Up("Up", "Up", "CursorUp"),
    Down("Down", "Down", "CursorDown"),
    Left("Left", "Left", "CursorLeft"),
    Right("Right", "Right", "CursorRight"),
    Ok("OK", "Confirm", "DpadCenter"),
    Mute("Mute", "Mute"),
    PlayPause("Play/Pause", "PlayPause"),
    Settings("Settings", "GoogleDashboard", "Customize"),
    Guide("Guide", "GGuide", "EPG"),
    Assistant("Google Assistant", "MicPressedFromPairedRC", "MicPressedFromUnpairedRC", "Assists"),
}

sealed interface BraviaState {
    data object NotSetUp : BraviaState
    data object Connecting : BraviaState
    data object Ready : BraviaState
    data class Failed(val message: String) : BraviaState
}

class BraviaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bravia", Context.MODE_PRIVATE)

    val host = MutableStateFlow(prefs.getString("host", "") ?: "")
    val psk = MutableStateFlow(prefs.getString("psk", "") ?: "")
    /** True once a Sony TV has been added; until then the Bravia tab and its badge stay hidden. */
    val configured: StateFlow<Boolean> = combine(host, psk) { h, p -> h.isNotBlank() && p.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, host.value.isNotBlank() && psk.value.isNotBlank())
    val state = MutableStateFlow<BraviaState>(BraviaState.NotSetUp)
    /** True on, false standby, null unknown. */
    val power = MutableStateFlow<Boolean?>(null)
    val message = MutableStateFlow<String?>(null)
    /** The connected TV's model code, shown under its name; null until known. */
    val model = MutableStateFlow<String?>(null)
    /** "Dolby Vision", "HDR" or "SDR" for what the TV is showing; null when it's off or unknown. */
    val pictureFormat = MutableStateFlow<String?>(null)
    val pictureMode = MutableStateFlow<String?>(null)
    /** Picture modes the TV offers right now; the list follows the content (e.g. Dolby Vision modes). */
    val pictureModes = MutableStateFlow<List<String>>(emptyList())
    /** Screen off with sound on (power saving "pictureOff"). */
    val pictureOff = MutableStateFlow(false)
    private var savingModeBeforePictureOff = "off"

    private var client: BraviaClient? = null
    /** BraviaKey → IRCC code, resolved from the TV's own list. */
    private var codes: Map<BraviaKey, String> = emptyMap()
    private var connectJob: Job? = null
    private var repeatJob: Job? = null
    private val commands = Channel<suspend BraviaClient.() -> Unit>(Channel.UNLIMITED)

    init {
        // One request at a time, in order, so held buttons and taps don't race.
        viewModelScope.launch {
            for (cmd in commands) {
                val c = client ?: continue
                try { c.cmd() } catch (e: Exception) { message.value = e.message ?: "The TV didn't respond" }
            }
        }
        if (DemoMode.enabled) loadDemo() else if (host.value.isNotBlank() && psk.value.isNotBlank()) connect()
    }

    /** Placeholder state for README screenshots; nothing is connected. */
    private fun loadDemo() {
        host.value = "demo"; psk.value = "demo" // not saved; makes the Bravia tab appear
        state.value = BraviaState.Ready
        power.value = true
        pictureFormat.value = "Dolby Vision"
        pictureMode.value = "dolbyVisionBright"
        pictureModes.value = listOf("dolbyVisionBright", "dolbyVisionDark", "dolbyVisionVivid")
    }

    fun save(newHost: String, newPsk: String) {
        host.value = newHost.trim()
        psk.value = newPsk.trim()
        prefs.edit().putString("host", host.value).putString("psk", psk.value).apply()
        connect()
    }

    fun forget() {
        prefs.edit().clear().apply()
        host.value = ""; psk.value = ""
        connectJob?.cancel()
        client = null
        state.value = BraviaState.NotSetUp
    }

    fun connect() {
        if (host.value.isBlank() || psk.value.isBlank()) { state.value = BraviaState.NotSetUp; return }
        connectJob?.cancel()
        val c = BraviaClient(host.value, psk.value)
        client = c
        state.value = BraviaState.Connecting
        connectJob = viewModelScope.launch {
            try {
                val tv = c.remoteCodes()
                codes = BraviaKey.entries.mapNotNull { k -> k.names.firstNotNullOfOrNull { tv[it] }?.let { k to it } }.toMap()
                model.value = runCatching { c.model() }.getOrNull()
                power.value = runCatching { c.isOn() }.getOrNull()
                state.value = BraviaState.Ready
            } catch (e: Exception) {
                state.value = BraviaState.Failed(e.message ?: "Couldn't reach the TV at ${host.value}")
            }
        }
    }

    private var pollJob: Job? = null

    /** Keeps [power] current while the app is in the foreground (the TV doesn't push changes). */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) { refreshPower(); delay(3_000) }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    /** Refreshes power, picture mode and format, and picture-off; off the command queue so buttons never wait. */
    fun refreshPower() {
        if (state.value != BraviaState.Ready) { if (state.value is BraviaState.Failed) connect(); return }
        val c = client ?: return
        viewModelScope.launch {
            val on = runCatching { c.isOn() }.getOrNull()
            power.value = on
            if (on != true) { pictureFormat.value = null; return@launch }
            runCatching { c.picture() }.getOrNull()?.let {
                pictureMode.value = it.mode
                pictureModes.value = it.modes
                pictureFormat.value = it.format
            }
            runCatching { c.powerSavingMode() }.getOrNull()?.let {
                pictureOff.value = it == "pictureOff"
                if (it != "pictureOff" && it.isNotEmpty()) savingModeBeforePictureOff = it
            }
        }
    }

    fun setPictureMode(mode: String) {
        pictureMode.value = mode
        commands.trySend { setPictureMode(mode) }
    }

    /** Turns the screen off (sound stays on), or back on, restoring the previous power-saving setting. */
    fun togglePictureOff() {
        val off = !pictureOff.value
        pictureOff.value = off
        commands.trySend { setPowerSavingMode(if (off) "pictureOff" else savingModeBeforePictureOff) }
    }

    fun restart() {
        message.value = "Restarting the TV"
        commands.trySend { reboot() }
    }

    fun press(key: BraviaKey) {
        val code = codes[key]
        if (code == null) { message.value = "This TV has no \"${key.label}\" button code"; return }
        commands.trySend { press(code) }
    }

    /** Press-and-hold: sends once, then repeats like a held button on the real remote. */
    fun hold(key: BraviaKey, down: Boolean) {
        repeatJob?.cancel()
        if (!down) return
        press(key)
        repeatJob = viewModelScope.launch {
            delay(400)
            while (true) { press(key); delay(150) }
        }
    }

    /** Power tap, like the Apple TV tab: turns the TV on from standby. */
    fun powerTap() {
        if (power.value == true) { message.value = "Hold for power options"; return }
        setPower(true, BraviaKey.WakeUp)
    }

    /** Power hold: turns the TV off. */
    fun powerOff() = setPower(false, BraviaKey.Power)

    /** Sets power over the REST API, falling back to a button code when that fails. */
    private fun setPower(on: Boolean, fallback: BraviaKey) {
        commands.trySend {
            if (runCatching { setPower(on) }.isFailure) {
                codes[fallback]?.let { press(it) } ?: throw IllegalStateException("The TV didn't respond to power")
            }
            watchPowerChange(on)
        }
    }

    private var powerWatch: Job? = null

    /** After a power press, checks every 200 ms for up to 10 s and stops as soon as the TV reports [target]. */
    private fun watchPowerChange(target: Boolean) {
        powerWatch?.cancel()
        val c = client ?: return
        powerWatch = viewModelScope.launch {
            repeat(50) {
                runCatching { c.isOn() }.getOrNull()?.let { power.value = it }
                if (power.value == target) return@launch
                delay(200)
            }
        }
    }
}
