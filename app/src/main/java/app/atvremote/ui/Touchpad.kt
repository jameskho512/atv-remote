package app.atvremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.atvremote.protocol.HidCommand
import kotlin.math.abs

/** Share of the width/height taken by the middle (swipe) section when the pad has edge sections. */
private const val MIDDLE = 0.56f

/**
 * Touch surface. In the middle: tap = select, long press = hold select, and every [step] of finger
 * travel sends one arrow press, so a long drag scrolls through a list. With [edgeSections], touches
 * that start near an edge press that direction instead (held for as long as the finger stays down),
 * like the ring of the Siri Remote's clickpad.
 */
@Composable
fun Touchpad(
    onButton: (HidCommand, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    edgeSections: Boolean = true,
    shape: Shape = RoundedCornerShape(32.dp),
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val haptics = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 37.dp.toPx() }
    val tapSlop = with(LocalDensity.current) { 12.dp.toPx() }
    val button = rememberUpdatedState(onButton)
    var pressedEdge by remember { mutableStateOf<HidCommand?>(null) }
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier
            .clip(shape)
            .background(color)
            .pointerInput(edgeSections) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position

                    // Edge sections: press and hold a direction.
                    if (edgeSections) {
                        val dx = start.x / size.width - 0.5f
                        val dy = start.y / size.height - 0.5f
                        if (abs(dx) > MIDDLE / 2 || abs(dy) > MIDDLE / 2) {
                            val dir = if (abs(dx) > abs(dy)) {
                                if (dx > 0) HidCommand.Right else HidCommand.Left
                            } else if (dy > 0) HidCommand.Down else HidCommand.Up
                            pressedEdge = dir
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            button.value(dir, true)
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Main)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.id == down.id && it.pressed })
                            button.value(dir, false)
                            pressedEdge = null
                            return@awaitEachGesture
                        }
                    }

                    // Middle: swipe / tap / long press.
                    val startClock = System.currentTimeMillis()
                    var origin = start
                    var moved = false
                    var arrows = 0
                    var held = false
                    while (true) {
                        // A resting finger produces no events, so wait with a timeout to catch a long press.
                        val waitingForHold = !held && !moved && arrows == 0
                        val event = if (waitingForHold) {
                            withTimeoutOrNull(maxOf(1L, 500 - (System.currentTimeMillis() - startClock))) { awaitPointerEvent(PointerEventPass.Main) }
                        } else awaitPointerEvent(PointerEventPass.Main)
                        if (event == null) {
                            held = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            button.value(HidCommand.Select, true)
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = change.position
                        if ((pos - start).getDistance() > tapSlop) moved = true

                        if (!change.pressed) {
                            when {
                                held -> button.value(HidCommand.Select, false)
                                !moved && arrows == 0 -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    button.value(HidCommand.Select, true); button.value(HidCommand.Select, false)
                                }
                            }
                            break
                        }
                        change.consume()

                        val d = pos - origin
                        val dir = when {
                            abs(d.x) >= step && abs(d.x) > abs(d.y) -> if (d.x > 0) HidCommand.Right else HidCommand.Left
                            abs(d.y) >= step && abs(d.y) >= abs(d.x) -> if (d.y > 0) HidCommand.Down else HidCommand.Up
                            else -> null
                        }
                        if (dir != null && !held) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            button.value(dir, true); button.value(dir, false)
                            arrows++
                            origin = pos
                        }
                    }
                }
            },
    ) {
        if (edgeSections) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val inner = Size(w * MIDDLE, h * MIDDLE)
                val topLeft = Offset((w - inner.width) / 2, (h - inner.height) / 2)
                // Middle section outline
                drawRoundRect(
                    lineColor.copy(alpha = 0.18f), topLeft, inner, CornerRadius(24.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
                // Direction chevrons, brighter on the section being pressed
                val c = 7.dp.toPx()
                val m = (1 - MIDDLE) / 4
                listOf(
                    HidCommand.Up to Offset(w / 2, h * m), HidCommand.Down to Offset(w / 2, h * (1 - m)),
                    HidCommand.Left to Offset(w * m, h / 2), HidCommand.Right to Offset(w * (1 - m), h / 2),
                ).forEach { (dir, p) ->
                    val path = Path().apply {
                        when (dir) {
                            HidCommand.Up -> { moveTo(p.x - c, p.y + c / 2); lineTo(p.x, p.y - c / 2); lineTo(p.x + c, p.y + c / 2) }
                            HidCommand.Down -> { moveTo(p.x - c, p.y - c / 2); lineTo(p.x, p.y + c / 2); lineTo(p.x + c, p.y - c / 2) }
                            HidCommand.Left -> { moveTo(p.x + c / 2, p.y - c); lineTo(p.x - c / 2, p.y); lineTo(p.x + c / 2, p.y + c) }
                            else -> { moveTo(p.x - c / 2, p.y - c); lineTo(p.x + c / 2, p.y); lineTo(p.x - c / 2, p.y + c) }
                        }
                    }
                    drawPath(
                        path, lineColor.copy(alpha = if (pressedEdge == dir) 0.9f else 0.35f),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
