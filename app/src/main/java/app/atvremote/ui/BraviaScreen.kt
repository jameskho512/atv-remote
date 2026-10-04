package app.atvremote.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TvOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atvremote.BraviaKey
import app.atvremote.BraviaState
import app.atvremote.BraviaViewModel

private val Body = Brush.verticalGradient(listOf(Color(0xFF26272C), Color(0xFF1B1C20)))
private val SonyKey = Color(0xFF0E0E10)
private val SonyKeyPressed = Color(0xFF3A3A40)
private val SonyIcon = Color(0xFFEDEDF0)
private val PowerGreen = Color(0xFF3DDC5A)
/** A toggle button that is on (e.g. picture off). */
private val SonyKeyLit = Color(0xFF2E4A7A)
private val AssistantBlue = Color(0xFF7FA7FF)
/** Finger travel per arrow when swiping the d-pad's middle; same as the Apple TV touchpad. */
private val SwipeStep = 37.dp
/** The d-pad's middle (OK / swipe area) as a share of its diameter. */
private const val CenterShare = 0.5f

@Composable
fun BraviaScreen(vm: BraviaViewModel) {
    val state by vm.state.collectAsState()
    val power by vm.power.collectAsState()
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = when (state) {
                BraviaState.Ready -> when (power) {
                    true -> "On"
                    false -> "Off"
                    null -> null
                }
                BraviaState.Connecting -> "Connecting…"
                else -> null
            }
            Column(Modifier.weight(1f)) {
                Text("Sony Bravia", style = MaterialTheme.typography.titleMedium)
                val model by vm.model.collectAsState()
                listOfNotNull(status, model).joinToString(" · ").ifEmpty { null }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state != BraviaState.NotSetUp) IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Settings, "TV settings") }
        }

        val s = state
        when {
            s == BraviaState.NotSetUp || editing -> SetupForm(vm, onDone = { editing = false }, canCancel = s != BraviaState.NotSetUp)
            else -> {
                if (s is BraviaState.Failed) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.message, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.connect() }) { Text("Retry") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Scrolls if the remote is taller than the screen.
                Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), contentAlignment = Alignment.TopCenter) {
                    SonyRemote(vm, enabled = s == BraviaState.Ready)
                }
            }
        }
    }
}

/** The kept buttons of the Bravia remote, in its layout. */
@Composable
private fun SonyRemote(vm: BraviaViewModel, enabled: Boolean) {
    Column(
        Modifier.widthIn(max = 280.dp).fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(40.dp)).background(Body)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val format by vm.pictureFormat.collectAsState()
        val pictureOff by vm.pictureOff.collectAsState()
        var showPowerMenu by remember { mutableStateOf(false) }
        var showModes by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FormatBadge(format) { vm.refreshPower(); showModes = true }
            var powerDown by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(if (powerDown) SonyKeyPressed else SonyKey)
                        .then(if (enabled) Modifier.tapOrHold(onPressedChange = { powerDown = it }, onTap = { vm.powerTap() }, onHold = { showPowerMenu = true }) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, "Power (hold for options)", tint = PowerGreen, modifier = Modifier.size(26.dp))
                }
                DropdownMenu(expanded = showPowerMenu, onDismissRequest = { showPowerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Restart") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                        onClick = { showPowerMenu = false; vm.restart() },
                    )
                    DropdownMenuItem(
                        text = { Text("Turn off", color = Color(0xFFFF8A80)) }, leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null, tint = Color(0xFFFF8A80)) },
                        onClick = { showPowerMenu = false; vm.powerOff() },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Tv) }) { KeyText("TV", 16) }
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Tools) }) { KeyIcon(Icons.Filled.Build, "Tools") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Input) }) { KeyIcon(Icons.AutoMirrored.Filled.Input, "Input") }
            SonyButton(enabled, lit = pictureOff, onTap = { vm.togglePictureOff() }) {
                KeyIcon(Icons.Filled.TvOff, if (pictureOff) "Picture off (tap to turn the screen on)" else "Picture off")
            }
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Menu) }) { KeyText("MENU", 11) }
        }
        if (showModes) PictureModeSheet(vm) { showModes = false }
        DPad(vm, enabled)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Back) }) { KeyIcon(Icons.AutoMirrored.Filled.Undo, "Back") }
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Home) }) { KeyIcon(Icons.Filled.Home, "Home") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SonyRocker(enabled, null, onUp = { vm.hold(BraviaKey.VolumeUp, it) }, onDown = { vm.hold(BraviaKey.VolumeDown, it) }, what = "Volume")
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SonyButton(enabled, onTap = { vm.press(BraviaKey.Assistant) }) {
                    Icon(Icons.Filled.Mic, "Google Assistant", tint = AssistantBlue, modifier = Modifier.size(24.dp))
                }
                SonyButton(enabled, onTap = { vm.press(BraviaKey.Mute) }) { KeyIcon(Icons.AutoMirrored.Filled.VolumeOff, "Mute") }
            }
            SonyRocker(enabled, "CH", onUp = { vm.hold(BraviaKey.ChannelUp, it) }, onDown = { vm.hold(BraviaKey.ChannelDown, it) }, what = "Channel")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Settings) }) { KeyIcon(Icons.Filled.Settings, "Settings") }
            SonyButton(enabled, onTap = { vm.press(BraviaKey.PlayPause) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, "Play/Pause", tint = SonyIcon, modifier = Modifier.size(18.dp))
                    Icon(Icons.Filled.Pause, null, tint = SonyIcon, modifier = Modifier.size(16.dp))
                }
            }
            SonyButton(enabled, onTap = { vm.press(BraviaKey.Guide) }) { KeyIcon(Icons.AutoMirrored.Filled.ListAlt, "Guide") }
        }
    }
}

/**
 * Round d-pad: four arrow zones around the OK button. Arrows press at once and repeat while held.
 * The middle works like the Apple TV touchpad: tap for OK, or drag from it to send one arrow per
 * [SwipeStep] of travel (the finger may leave the pad).
 */
@Composable
private fun DPad(vm: BraviaViewModel, enabled: Boolean) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf<BraviaKey?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ring = minOf(maxWidth, 168.dp)
        Box(
            Modifier.size(ring).clip(CircleShape).background(SonyKey)
                .then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = size.width / 2f
                        val dx = down.position.x - c
                        val dy = down.position.y - c
                        val key = when {
                            hypot(dx, dy) < c * CenterShare -> BraviaKey.Ok
                            abs(dx) > abs(dy) -> if (dx > 0) BraviaKey.Right else BraviaKey.Left
                            else -> if (dy > 0) BraviaKey.Down else BraviaKey.Up
                        }
                        pressed = key
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (key != BraviaKey.Ok) {
                            vm.hold(key, true)
                            waitForUpOrCancellation()
                            vm.hold(key, false)
                        } else {
                            val step = SwipeStep.toPx()
                            val slop = 12.dp.toPx()
                            var origin = down.position
                            var moved = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!moved) vm.press(BraviaKey.Ok)
                                    break
                                }
                                change.consume()
                                if ((change.position - down.position).getDistance() > slop) moved = true
                                val d = change.position - origin
                                val dir = when {
                                    abs(d.x) >= step && abs(d.x) > abs(d.y) -> if (d.x > 0) BraviaKey.Right else BraviaKey.Left
                                    abs(d.y) >= step && abs(d.y) >= abs(d.x) -> if (d.y > 0) BraviaKey.Down else BraviaKey.Up
                                    else -> null
                                }
                                if (dir != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.press(dir)
                                    origin = change.position
                                }
                            }
                        }
                        pressed = null
                    }
                }),
            contentAlignment = Alignment.Center,
        ) {
            fun tint(k: BraviaKey) = if (pressed == k) Color.White else SonyIcon.copy(alpha = 0.75f)
            Icon(Icons.Filled.ArrowDropUp, "Up", tint = tint(BraviaKey.Up), modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(28.dp))
            Icon(Icons.Filled.ArrowDropDown, "Down", tint = tint(BraviaKey.Down), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(28.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowLeft, "Left", tint = tint(BraviaKey.Left), modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(28.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowRight, "Right", tint = tint(BraviaKey.Right), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(28.dp))
            Box(
                Modifier.size(ring * CenterShare).clip(CircleShape).background(if (pressed == BraviaKey.Ok) SonyKeyPressed else Color(0xFF1E1F23)),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(5.dp).clip(CircleShape).background(SonyIcon)) }
        }
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, label: String) = Icon(icon, label, tint = SonyIcon, modifier = Modifier.size(24.dp))

@Composable
private fun KeyText(text: String, size: Int) = Text(text, color = SonyIcon, fontSize = size.sp, fontWeight = FontWeight.Bold)

/** Reports press (true) and release (false), with the pressed look and a haptic tick. */
private fun Modifier.pressable(enabled: Boolean, haptic: () -> Unit, setPressed: (Boolean) -> Unit, onPress: (Boolean) -> Unit) =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            setPressed(true); haptic(); onPress(true)
            waitForUpOrCancellation()
            setPressed(false); onPress(false)
        }
    }

@Composable
private fun SonyButton(enabled: Boolean, lit: Boolean = false, onTap: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(if (pressed) SonyKeyPressed else if (lit) SonyKeyLit else SonyKey)
            .pressable(enabled, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, { pressed = it }) { if (it) onTap() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** What the TV is showing: Dolby Vision, HDR or SDR (nothing while it's off). Tap it to pick a picture mode. */
@Composable
fun FormatBadge(format: String?, compact: Boolean = false, onClick: () -> Unit) {
    if (format == null) { Spacer(Modifier.size(1.dp)); return }
    val (bg, fg) = when (format) {
        "Dolby Vision" -> Color(0xFF3A2A55) to Color(0xFFE2D4FF)
        "SDR" -> Color(0xFF2A2B30) to Color(0xFFC4C6CE)
        else -> Color(0xFF4A3410) to Color(0xFFFFDCA0)
    }
    Text(
        if (compact && format == "Dolby Vision") "DV" else format,
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    )
}

/** The TV's picture modes for the current content; tap one to switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureModeSheet(vm: BraviaViewModel, onDone: () -> Unit) {
    val modes by vm.pictureModes.collectAsState()
    val current by vm.pictureMode.collectAsState()
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Picture mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            if (modes.isEmpty()) Text("The TV didn't list its picture modes", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            modes.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (mode == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { vm.setPictureMode(mode); onDone() }.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(prettyPictureMode(mode), Modifier.weight(1f))
                    if (mode == current) Icon(Icons.Filled.Check, "Current", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** The badge for the Apple TV pages: shown only while the Bravia is set up and connected; tap for picture modes. */
@Composable
fun PictureBadge(vm: BraviaViewModel, compact: Boolean = false) {
    val state by vm.state.collectAsState()
    val format by vm.pictureFormat.collectAsState()
    var show by remember { mutableStateOf(false) }
    if (state == BraviaState.Ready) FormatBadge(format, compact) { vm.refreshPower(); show = true }
    if (show) PictureModeSheet(vm) { show = false }
}

/** "dolbyVisionBright" → "Dolby Vision Bright". */
private fun prettyPictureMode(value: String) =
    value.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

/** Tall pill with + on top and − below; holding repeats. */
@Composable
private fun SonyRocker(enabled: Boolean, label: String?, onUp: (Boolean) -> Unit, onDown: (Boolean) -> Unit, what: String) {
    Column(Modifier.width(56.dp).clip(RoundedCornerShape(28.dp)).background(SonyKey), horizontalAlignment = Alignment.CenterHorizontally) {
        RockerKey(enabled, Icons.Filled.Add, "$what up", onUp)
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            if (label != null) KeyText(label, 12)
            else Text("◢", color = SonyIcon.copy(alpha = 0.7f), fontSize = 14.sp) // the volume wedge
        }
        RockerKey(enabled, Icons.Filled.Remove, "$what down", onDown)
    }
}

@Composable
private fun RockerKey(enabled: Boolean, icon: ImageVector, label: String, onPress: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.size(56.dp).background(if (pressed) SonyKeyPressed else Color.Transparent)
            .pressable(enabled, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, { pressed = it }, onPress),
        contentAlignment = Alignment.Center,
    ) { KeyIcon(icon, label) }
}

private const val SetupSteps =
    "On the TV, open Settings › Network & Internet › Home network setup › IP control:\n" +
        "• Authentication: Normal and Pre-Shared Key\n" +
        "• Pre-Shared Key: choose a key, e.g. 1234\n" +
        "• Simple IP control: on\n" +
        "To turn the TV on from the app, also turn on Remote start in the same Network menu.\n\n" +
        "The TV's IP address is under Settings › Network & Internet › Advanced options › Network status."

/** Adds a Sony TV from the device list; [onAdded] runs after Connect, once the Bravia tab exists. */
@Composable
fun SonyDialog(vm: BraviaViewModel, onDismiss: () -> Unit, onAdded: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Sony Remote") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(SetupSteps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(), label = { Text("TV IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    psk, { psk = it }, Modifier.fillMaxWidth(), label = { Text("Pre-shared key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && psk.isNotBlank(), onClick = { vm.save(host, psk); onAdded() }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetupForm(vm: BraviaViewModel, onDone: () -> Unit, canCancel: Boolean) {
    var host by remember { mutableStateOf(vm.host.value) }
    var psk by remember { mutableStateOf(vm.psk.value) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to your Bravia", style = MaterialTheme.typography.titleMedium)
        Text(SetupSteps, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            host, { host = it }, Modifier.fillMaxWidth(), label = { Text("TV IP address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            psk, { psk = it }, Modifier.fillMaxWidth(), label = { Text("Pre-shared key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.save(host, psk); onDone() }, enabled = host.isNotBlank() && psk.isNotBlank()) { Text("Connect") }
            if (canCancel) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.forget(); onDone() }) { Text("Forget TV") }
            }
        }
    }
}
