package app.atvremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Hold-to-turn-off feedback, drawn by [HoldOverlay] above everything so a thumb or a clipped parent can't hide it. */
class HoldIndicator {
    var center by mutableStateOf(Offset.Unspecified)
    val progress = Animatable(0f)
}

val LocalHoldIndicator = staticCompositionLocalOf { HoldIndicator() }

private val HoldFill = Color(0xFFFF453A).copy(alpha = 0.45f)

/** The growing circle: from nothing to 160 dp across, much wider than a thumb. Place it last, filling the window. */
@Composable
fun HoldOverlay() {
    val indicator = LocalHoldIndicator.current
    Canvas(Modifier.fillMaxSize()) {
        val p = indicator.progress.value
        if (p > 0f && indicator.center.isSpecified) drawCircle(HoldFill, radius = 80.dp.toPx() * p, center = indicator.center)
    }
}

/**
 * Tap runs [onTap]; holding for [holdMs] runs [onHold] instead (with a haptic), as soon as the time is up.
 * While held, [HoldOverlay] grows a circle around the button that is full exactly when [onHold] fires;
 * letting go early shrinks it back.
 */
fun Modifier.tapOrHold(
    holdMs: Long = 500,
    onPressedChange: (Boolean) -> Unit = {},
    onTap: () -> Unit,
    onHold: () -> Unit,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val tap = rememberUpdatedState(onTap)
    val hold = rememberUpdatedState(onHold)
    val pressed = rememberUpdatedState(onPressedChange)
    val indicator = LocalHoldIndicator.current
    var center by remember { mutableStateOf(Offset.Unspecified) }
    val scope = rememberCoroutineScope()
    onGloballyPositioned { center = it.boundsInRoot().center }.pointerInput(holdMs) {
        awaitEachGesture {
            awaitFirstDown()
            pressed.value(true)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            indicator.center = center
            val grow = scope.launch {
                indicator.progress.snapTo(0f)
                indicator.progress.animateTo(1f, tween(holdMs.toInt(), easing = LinearEasing))
            }
            var released = false
            val up = withTimeoutOrNull(holdMs) { waitForUpOrCancellation().also { released = true } }
            if (released) {
                grow.cancel()
                scope.launch { indicator.progress.animateTo(0f, tween(150)) }
                if (up != null) tap.value() // null = the gesture was cancelled (e.g. scrolled away)
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hold.value()
                waitForUpOrCancellation()
                scope.launch { indicator.progress.animateTo(0f, tween(250)) }
            }
            pressed.value(false)
        }
    }
}
