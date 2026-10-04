package app.atvremote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atvremote.NowPlayingLink
import app.atvremote.Pairing
import app.atvremote.RemoteViewModel
import app.atvremote.protocol.HidCommand
import kotlinx.coroutines.delay

/** What's playing on the TV, or a prompt to set it up. Shows nothing when there is nothing to show. */
@Composable
fun NowPlayingArea(vm: RemoteViewModel) {
    val np by vm.now.nowPlaying.collectAsState()
    val link by vm.now.link.collectAsState()
    val declined by vm.nowPlayingDeclined.collectAsState()
    val pairing by vm.now.pairing.collectAsState()

    when {
        np != null -> NowPlayingCard(vm)
        link == NowPlayingLink.NeedsPairing && !declined -> SetupPrompt(vm)
    }
    pairing?.let { NowPlayingPairDialog(vm, it) }
}

@Composable
private fun NowPlayingCard(vm: RemoteViewModel) {
    val np = vm.now.nowPlaying.collectAsState().value ?: return
    val art by vm.now.artwork.collectAsState()
    val appNames by vm.appNames.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(np.playing, np.positionAtMs) {
        while (np.playing) { nowMs = System.currentTimeMillis(); delay(500) }
        nowMs = System.currentTimeMillis()
    }
    val position = np.positionNow(nowMs)
    val appName = np.appName ?: np.bundleId?.let { appNames[it] }

    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bmp = art?.takeIf { it.first == np.itemId || np.itemId == null }?.second
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                when {
                    bmp != null -> Image(bmp.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
                    np.bundleId != null -> AppIconSmall(vm, np.bundleId, appName ?: "")
                    else -> Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                np.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                appName?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.primary) }
            }
            IconButton(onClick = { vm.click(HidCommand.PlayPause) }) {
                Icon(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.playing) "Pause" else "Play")
            }
        }
        val duration = np.duration
        if (duration != null && position != null) {
            // Drag to scrub; the TV seeks when you let go.
            var dragging by remember { mutableStateOf<Float?>(null) }
            val shown: Double = dragging?.let { it * duration } ?: position
            Slider(
                value = dragging ?: (position / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.seek(it * duration) }; dragging = null },
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(shown), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("-" + clock(duration - shown), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppIconSmall(vm: RemoteViewModel, bundle: String, name: String) {
    var bmp by remember(bundle) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bundle) { bmp = vm.icons.icon(bundle, name) }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop) }
        ?: Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun clock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SetupPrompt(vm: RemoteViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "See what's playing on your TV", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TextButton(onClick = { vm.declineNowPlaying() }) { Text("Not now") }
        TextButton(onClick = { vm.pairNowPlaying() }) { Text("Set up") }
    }
}

@Composable
private fun NowPlayingPairDialog(vm: RemoteViewModel, state: Pairing) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.now.cancelPairing() },
        title = { Text("Show what's playing") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    Pairing.Starting -> CircularProgressIndicator()
                    Pairing.AwaitingPin, Pairing.Verifying -> {
                        Text("Your Apple TV needs one more pairing for this. Enter the code shown on the TV.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            pin,
                            { v -> pin = v.filter(Char::isDigit).take(4); if (pin.length == 4) vm.submitNowPlayingPin(pin) },
                            enabled = state == Pairing.AwaitingPin,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 10.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (pin.length == 4) vm.submitNowPlayingPin(pin) }),
                            modifier = Modifier.widthIn(max = 200.dp).focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        if (state == Pairing.Verifying) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }
                    }
                    is Pairing.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            if (state is Pairing.Failed) TextButton(onClick = { pin = ""; vm.pairNowPlaying() }) { Text("Try again") }
        },
        dismissButton = { TextButton(onClick = { vm.now.cancelPairing() }) { Text("Cancel") } },
    )
}
