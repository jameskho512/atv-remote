package app.atvremote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import app.atvremote.BraviaState
import app.atvremote.BraviaViewModel
import app.atvremote.Conn
import kotlinx.coroutines.flow.MutableStateFlow
import app.atvremote.Device
import app.atvremote.Pairing
import app.atvremote.RemoteViewModel
import app.atvremote.protocol.HidCommand

@Composable
fun App(vm: RemoteViewModel, bravia: BraviaViewModel) {
    val current by vm.current.collectAsState()
    val pairing by vm.pairing.collectAsState()
    val storedTab by vm.tab.collectAsState()
    // The Bravia tab only exists once a Sony TV has been added.
    val sonyAdded by bravia.configured.collectAsState()
    val tab = if (sonyAdded) storedTab else 0
    // Tab icons are green while that device is on (the Apple TV's screensaver counts as on).
    val atvPower by vm.power.collectAsState()
    val atvConn by vm.conn.collectAsState()
    val atvOn = atvConn == Conn.Connected && atvPower != null && atvPower != 1L
    val braviaPower by bravia.power.collectAsState()
    val braviaState by bravia.state.collectAsState()
    val braviaOn = braviaState == BraviaState.Ready && braviaPower == true
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == 1 -> BraviaScreen(bravia)
                    current == null -> DevicesScreen(vm, bravia)
                    pairing != null -> PairingScreen(vm, current!!, pairing!!)
                    else -> RemoteScreen(vm, bravia, current!!)
                }
            }
            if (sonyAdded) NavigationBar(Modifier.height(64.dp), windowInsets = WindowInsets(0)) {
                NavigationBarItem(
                    tab == 0, { vm.setTab(0) }, icon = { Icon(Icons.Filled.Tv, if (atvOn) "On" else null) }, label = { Text("Apple TV") },
                    colors = powerColors(atvOn),
                )
                NavigationBarItem(
                    tab == 1, { vm.setTab(1); bravia.refreshPower() }, icon = { Icon(Icons.Outlined.SettingsRemote, if (braviaOn) "On" else null) },
                    label = { Text("Bravia") }, colors = powerColors(braviaOn),
                )
            }
        }
    }
    Toast(vm.message)
    Toast(bravia.message)
    HoldOverlay()
}

private val PowerOn = Color(0xFF34C759)

@Composable
private fun powerColors(on: Boolean) =
    if (on) NavigationBarItemDefaults.colors(selectedIconColor = PowerOn, unselectedIconColor = PowerOn) else NavigationBarItemDefaults.colors()

@Composable
private fun Toast(message: MutableStateFlow<String?>) {
    val msg = message.collectAsState().value ?: return
    LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); message.value = null }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 16.dp, end = 16.dp, bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface) {
            Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

// ---------------------------------------------------------------- devices

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevicesScreen(vm: RemoteViewModel, bravia: BraviaViewModel) {
    val devices by vm.devices.collectAsState()
    val sonyAdded by bravia.configured.collectAsState()
    val coffeeHidden by vm.coffeeHidden.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var addSony by remember { mutableStateOf(false) }
    var confirmHideCoffee by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<Device?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Apple TVs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Searching your network…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = { vm.select(d) }, onLongClick = { if (d.paired) forget = d })
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (d.paired) "Paired · ${d.host}" else "Tap to pair · ${d.host}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.paired) Icon(Icons.Filled.CheckCircle, "Paired", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (devices.isEmpty()) item {
                Text(
                    "No Apple TV found yet. Make sure your phone is on the same Wi-Fi as the Apple TV.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        Column(Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = { manual = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add by IP address")
            }
            // Sony TVs are optional: the Bravia tab only appears after one has been added here.
            if (!sonyAdded) TextButton(onClick = { addSony = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Sony Remote")
            }
        }
        if (!coffeeHidden) CoffeeLink(onHide = { confirmHideCoffee = true })
    }

    if (confirmHideCoffee) AlertDialog(
        onDismissRequest = { confirmHideCoffee = false },
        title = { Text("Remove this button?") },
        text = { Text("It won't appear again.") },
        confirmButton = { TextButton(onClick = { vm.hideCoffee(); confirmHideCoffee = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmHideCoffee = false }) { Text("Keep") } },
    )

    if (addSony) SonyDialog(bravia, onDismiss = { addSony = false }) { addSony = false; vm.setTab(1) }

    if (manual) ManualDialog(onDismiss = { manual = false }) { host, port -> manual = false; vm.addManual(host, port) }
    forget?.let { d ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Forget ${d.name}?") },
            text = { Text("You'll need to pair again to control it.") },
            confirmButton = { TextButton(onClick = { vm.forget(d); forget = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } },
        )
    }
}

/** Footer link to the developer's Buy me a coffee page (opened only when tapped); "Dismiss" removes it for good. */
@Composable
private fun CoffeeLink(onHide: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/j.ho"))) }
            }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The link itself stands out; "Dismiss" stays muted.
            Icon(Icons.Filled.LocalCafe, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Buy me a coffee", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Text("·", style = MaterialTheme.typography.bodySmall, color = muted)
        Text(
            "Dismiss", style = MaterialTheme.typography.bodySmall, color = muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHide).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("49153") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Apple TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("The port is usually 49153.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && port.isNotEmpty(), onClick = { onAdd(host.trim(), port.toInt()) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- pairing

@Composable
private fun PairingScreen(vm: RemoteViewModel, device: Device, state: Pairing) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.closeDevice() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Filled.Tv, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Pair with ${device.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        when (state) {
            Pairing.Starting -> {
                Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp)); CircularProgressIndicator()
            }
            Pairing.AwaitingPin, Pairing.Verifying -> {
                Text("Enter the 4-digit code shown on your TV.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    pin,
                    { v -> pin = v.filter(Char::isDigit).take(4); if (pin.length == 4) vm.submitPin(pin) },
                    enabled = state == Pairing.AwaitingPin,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length == 4) vm.submitPin(pin) }),
                    modifier = Modifier.widthIn(max = 220.dp).focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                if (state == Pairing.Verifying) { Spacer(Modifier.height(24.dp)); CircularProgressIndicator() }
            }
            is Pairing.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { pin = ""; vm.retryPairing() }) { Text("Try again") }
            }
        }
    }
}

// ---------------------------------------------------------------- remote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(vm: RemoteViewModel, bravia: BraviaViewModel, device: Device) {
    val conn by vm.conn.collectAsState()
    val tvKeyboard by vm.tvKeyboard.collectAsState()
    val physical by vm.physicalLayout.collectAsState()
    val power by vm.power.collectAsState()
    var showApps by remember { mutableStateOf(false) }
    val connected = conn == Conn.Connected
    // The header is crowded: when the device name gets cut off, the Bravia badge shortens ("Dolby Vision" → "DV").
    var crowded by remember(device.name, physical) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { vm.closeDevice() }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val status = when {
                    !connected -> null
                    power == 1L -> "Off"
                    power == 2L -> "Screensaver"
                    power == 3L || power == 4L -> "On"
                    else -> null
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (it.hasVisualOverflow) crowded = true },
                    )
                    if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowDown, "Choose device")
            }
            PictureBadge(bravia, compact = crowded)
            IconButton(onClick = { vm.setPhysicalLayout(!physical) }) {
                Icon(if (physical) Icons.Outlined.Dashboard else Icons.Outlined.SettingsRemote, if (physical) "Simple layout" else "Siri Remote layout")
            }
            IconButton(onClick = { vm.loadApps(); showApps = true }, enabled = connected) { Icon(Icons.Filled.Apps, "Apps") }
            if (!physical) Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .then(if (connected) Modifier.tapOrHold(onTap = { vm.powerTap() }, onHold = { vm.turnOff() }) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew, "Power (hold to turn off)",
                    tint = if (connected) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
                )
            }
        }

        when (val c = conn) {
            Conn.Connecting, Conn.Idle -> StatusBanner("Connecting…", null, null)
            is Conn.Failed -> StatusBanner(c.message, "Reconnect") { vm.connect() }
            Conn.Connected -> {}
        }
        if (connected && tvKeyboard != null) TvKeyboardBar(vm)
        // The touchpad keeps the size it has with the now-playing card showing, so remember the card's height.
        val density = LocalDensity.current
        var cardHeight by remember { mutableStateOf(150.dp) }
        val nowPlaying by vm.now.nowPlaying.collectAsState()
        if (connected) Box(Modifier.onSizeChanged { if (it.height > 0) cardHeight = with(density) { it.height.toDp() } }) { NowPlayingArea(vm) }

        if (physical) {
            Spacer(Modifier.height(8.dp))
            PhysicalRemote(vm, modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                val reserved = if (connected && nowPlaying != null) 0.dp else cardHeight
                val side = minOf(maxWidth, maxHeight - reserved).coerceAtLeast(120.dp)
                Touchpad(onButton = vm::button, modifier = Modifier.size(side))
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HoldButton(Icons.Outlined.ChevronLeft, "Back") { vm.button(HidCommand.Menu, it) }
                HoldButton(Icons.Outlined.PlayArrow, "Play/Pause", badge = "❚❚") { vm.button(HidCommand.PlayPause, it) }
                HoldButton(Icons.AutoMirrored.Outlined.VolumeOff, "Mute") { if (it) vm.toggleMute() }
                HoldButton(Icons.Filled.Tv, "TV / Home") { vm.button(HidCommand.Home, it) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                HoldButton(Icons.Filled.SkipPrevious, "Previous", small = true) { if (it) vm.previousTrack() }
                HoldButton(Icons.Filled.Replay10, "Back 10 seconds", small = true) { if (it) vm.skip(-10.0) }
                VolumeRocker(onUp = { vm.button(HidCommand.VolumeUp, it) }, onDown = { vm.button(HidCommand.VolumeDown, it) })
                HoldButton(Icons.Filled.Forward10, "Forward 10 seconds", small = true) { if (it) vm.skip(10.0) }
                HoldButton(Icons.Filled.SkipNext, "Next", small = true) { if (it) vm.nextTrack() }
            }
        }
    }

    if (showApps) {
        ModalBottomSheet(onDismissRequest = { showApps = false; vm.apps.value = null }) { AppsSheet(vm) { showApps = false; vm.apps.value = null } }
    }
}

@Composable
private fun StatusBanner(text: String, action: String?, onAction: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A round button that reports press and release, so holding works like on the real remote. */
@Composable
private fun HoldButton(icon: ImageVector, label: String, small: Boolean = false, badge: String? = null, onPress: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val size = if (small) 48.dp else 60.dp
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (badge != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, label, Modifier.size(24.dp))
                Text(badge, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else Icon(icon, label, Modifier.size(if (small) 24.dp else 28.dp))
    }
}

@Composable
private fun VolumeRocker(onUp: (Boolean) -> Unit, onDown: (Boolean) -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RockerHalf(Icons.Filled.Remove, "Volume down", onDown)
        Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        RockerHalf(Icons.Filled.Add, "Volume up", onUp)
    }
}

@Composable
private fun RockerHalf(icon: ImageVector, label: String, onPress: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.width(60.dp).height(48.dp)
            .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label) }
}

@Composable
private fun AppsSheet(vm: RemoteViewModel, onDone: () -> Unit) {
    val apps by vm.apps.collectAsState()
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
        Text("Apps", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.first }) { (bundle, name) ->
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.launch(bundle); onDone() }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(vm, bundle, name)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            name, style = MaterialTheme.typography.labelMedium, maxLines = 2, minLines = 2,
                            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** App Store artwork when available; otherwise a symbol for tvOS system apps, or the app's initial. */
@Composable
private fun AppIcon(vm: RemoteViewModel, bundle: String, name: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, bundle) { value = vm.icons.icon(bundle, name) }
    val shape = RoundedCornerShape(14.dp)
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), name, Modifier.size(60.dp).clip(shape), contentScale = ContentScale.Crop)
        return
    }
    val symbol = SystemAppSymbols[bundle]
    val hue = (bundle.hashCode() and 0x7fffffff) % 360
    Box(
        Modifier.size(60.dp).clip(shape).background(if (symbol != null) Color(0xFF3A3B42) else Color.hsl(hue.toFloat(), 0.45f, 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        if (symbol != null) Icon(symbol, null, Modifier.size(30.dp), tint = Color.White)
        else Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val SystemAppSymbols: Map<String, ImageVector> = mapOf(
    "com.apple.TVSettings" to Icons.Filled.Settings,
    "com.apple.TVPhotos" to Icons.Filled.Photo,
    "com.apple.TVAppStore" to Icons.Filled.Shop,
    "com.apple.TVSearch" to Icons.Filled.Search,
    "com.apple.Arcade" to Icons.Filled.SportsEsports,
    "com.apple.TVHomeSharing" to Icons.Filled.Computer,
    "com.apple.TVMovies" to Icons.Filled.Movie,
    "com.apple.TVShows" to Icons.Filled.LiveTv,
    "com.apple.TVWatchList" to Icons.Filled.Tv,
    "com.apple.TVMusic" to Icons.Filled.MusicNote,
    "com.apple.podcasts" to Icons.Filled.Podcasts,
    "com.apple.Fitness" to Icons.Filled.FitnessCenter,
    "com.apple.facetime" to Icons.Filled.Videocam,
)

/**
 * Appears while a text field is selected on the TV: opens the phone's keyboard right away and
 * sends what you type. Goes away (closing the keyboard) when the TV deselects the field.
 */
@Composable
private fun TvKeyboardBar(vm: RemoteViewModel) {
    val session by vm.tvKeyboardSession.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var text by remember(session) { mutableStateOf(TextFieldValue(vm.tvKeyboard.value ?: "", TextRange((vm.tvKeyboard.value ?: "").length))) }
    LaunchedEffect(session) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    OutlinedTextField(
        text,
        { v -> val changed = v.text != text.text; text = v; if (changed) vm.typeText(v.text) },
        singleLine = true,
        placeholder = { Text("Type on TV") },
        leadingIcon = { Icon(Icons.Filled.Keyboard, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
    )
}
