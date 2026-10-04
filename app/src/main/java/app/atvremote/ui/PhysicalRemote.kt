package app.atvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atvremote.RemoteViewModel
import app.atvremote.protocol.HidCommand
import kotlin.math.abs
import kotlin.math.hypot

private val Key = Color(0xFF1C1C1F)
private val KeyPressed = Color(0xFF3A3A40)
private val KeyIcon = Color(0xFFF2F2F4)
private val Silver = Brush.verticalGradient(listOf(Color(0xFF8C8E94), Color(0xFF75777D), Color(0xFF63656B)))

/** Layout of the aluminium Siri Remote (2nd/3rd gen): power, clickpad, back/TV, play/pause/mute, volume. */
@Composable
fun PhysicalRemote(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth * 0.82f, maxHeight / 2.4f, 340.dp)
        val inner = w * 0.82f
        val key = inner * 0.4f
        val gap = inner * 0.07f
        Column(
            Modifier.width(w).clip(RoundedCornerShape(w * 0.2f)).background(Silver)
                .padding(horizontal = (w - inner) / 2, vertical = w * 0.07f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status light and power button
            Box(Modifier.fillMaxWidth().height(w * 0.16f)) {
                Box(Modifier.align(Alignment.Center).size(w * 0.05f, w * 0.016f).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2E)))
                var powerDown by remember { mutableStateOf(false) }
                Box(
                    Modifier.align(Alignment.CenterEnd).size(w * 0.16f).clip(CircleShape)
                        .background(if (powerDown) Color(0xFF7E8086) else Color(0xFF9A9CA2))
                        .border(1.dp, Color(0xFF5A5C62), CircleShape)
                        .tapOrHold(onPressedChange = { powerDown = it }, onTap = { vm.powerTap() }, onHold = { vm.turnOff() }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PowerSettingsNew, "Power (hold to turn off)", Modifier.size(w * 0.08f), tint = Color(0xFF2A2A2E)) }
            }
            Spacer(Modifier.height(w * 0.05f))
            Clickpad(vm, inner)
            Spacer(Modifier.height(w * 0.08f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    PressKey(key, CircleShape, onPress = { vm.button(HidCommand.Menu, it) }) { KeyIconOf(Icons.Outlined.ChevronLeft, "Back", key) }
                    PressKey(key, CircleShape, onPress = { vm.button(HidCommand.PlayPause, it) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, "Play/Pause", Modifier.size(key * 0.28f), tint = KeyIcon)
                            Icon(Icons.Filled.Pause, null, Modifier.size(key * 0.28f).offset(x = -key * 0.05f), tint = KeyIcon)
                        }
                    }
                    PressKey(key, CircleShape, onPress = { if (it) vm.toggleMute() }) { KeyIconOf(Icons.AutoMirrored.Outlined.VolumeOff, "Mute", key) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    PressKey(key, CircleShape, onPress = { vm.button(HidCommand.Home, it) }) { KeyIconOf(Icons.Outlined.Tv, "TV / Home", key) }
                    // Volume rocker spans the bottom two rows.
                    Column(Modifier.width(key).height(key * 2 + gap).clip(RoundedCornerShape(50)).background(Key)) {
                        PressKey(key, RoundedCornerShape(0), height = key + gap / 2, onPress = { vm.button(HidCommand.VolumeUp, it) }) { KeyIconOf(Icons.Filled.Add, "Volume up", key) }
                        PressKey(key, RoundedCornerShape(0), height = key + gap / 2, onPress = { vm.button(HidCommand.VolumeDown, it) }) { KeyIconOf(Icons.Filled.Remove, "Volume down", key) }
                    }
                }
            }
        }
    }
}

/** Outer ring clicks up/down/left/right (hold to repeat); the center is the touch surface. */
@Composable
private fun Clickpad(vm: RemoteViewModel, size: Dp) {
    val haptics = LocalHapticFeedback.current
    var pressedDir by remember { mutableStateOf<HidCommand?>(null) }
    val centerSize = size * 0.56f
    Box(
        Modifier.size(size).clip(CircleShape).background(Key)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val d = down.position - c
                    if (hypot(d.x, d.y) < centerSize.toPx() / 2) return@awaitEachGesture // the touch surface handles it
                    val dir = if (abs(d.x) > abs(d.y)) {
                        if (d.x > 0) HidCommand.Right else HidCommand.Left
                    } else if (d.y > 0) HidCommand.Down else HidCommand.Up
                    pressedDir = dir
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    vm.button(dir, true)
                    waitForUpOrCancellation()
                    vm.button(dir, false)
                    pressedDir = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dot = size * 0.022f
        val edge = size * 0.06f
        listOf(Alignment.TopCenter, Alignment.BottomCenter, Alignment.CenterStart, Alignment.CenterEnd).forEach {
            Box(Modifier.align(it).padding(edge).size(dot).clip(CircleShape).background(KeyIcon.copy(alpha = 0.85f)))
        }
        pressedDir?.let { dir ->
            val a = when (dir) { HidCommand.Up -> Alignment.TopCenter; HidCommand.Down -> Alignment.BottomCenter; HidCommand.Left -> Alignment.CenterStart; else -> Alignment.CenterEnd }
            Box(Modifier.align(a).padding(edge / 2).size(size * 0.12f).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        }
        Touchpad(
            onButton = vm::button,
            modifier = Modifier.size(centerSize).border(1.dp, Color(0xFF2E2E33), CircleShape),
            edgeSections = false, // the ring around it is the directional part
            shape = CircleShape,
            color = Color(0xFF161618),
        )
    }
}

@Composable
private fun KeyIconOf(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, key: Dp) =
    Icon(icon, label, Modifier.size(key * 0.4f), tint = KeyIcon)

@Composable
private fun PressKey(
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    height: Dp = size,
    color: Color = Key,
    pressedColor: Color = KeyPressed,
    onPress: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.size(size, height).clip(shape).background(if (pressed) pressedColor else color)
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
    ) { content() }
}
