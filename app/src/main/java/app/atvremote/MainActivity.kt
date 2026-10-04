package app.atvremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import app.atvremote.protocol.HidCommand
import app.atvremote.ui.App

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()
    private val bravia: BraviaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Screenshot mode (debug builds only); must be decided before the view models are created.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("demo")?.let { DemoMode.enabled = true; DemoMode.screen = it }
        }
        // For the now-playing notification (Android 13+ asks once; declining just hides it).
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = Colors) { App(vm, bravia) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startDiscovery()
        vm.onResume()
        if (!DemoMode.enabled) bravia.startPolling()
    }

    override fun onPause() {
        vm.stopDiscovery()
        bravia.stopPolling()
        super.onPause()
    }

    /** The phone's volume keys control the volume of whichever TV's tab is open, while connected. */
    // Lint flags the super call as restricted API; it is a plain Activity method.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.tab.value == 1 && bravia.configured.value) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> BraviaKey.VolumeUp
                KeyEvent.KEYCODE_VOLUME_DOWN -> BraviaKey.VolumeDown
                else -> null
            }
            if (key != null && bravia.state.value == BraviaState.Ready) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) bravia.hold(key, true)
                    KeyEvent.ACTION_UP -> bravia.hold(key, false)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> HidCommand.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> HidCommand.VolumeDown
            else -> null
        }
        if (cmd != null && vm.conn.value == Conn.Connected && vm.pairing.value == null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) vm.button(cmd, true)
                KeyEvent.ACTION_UP -> vm.button(cmd, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        // Back on the device list exits; anywhere else it returns to the device list.
        if ((vm.tab.value == 0 || !bravia.configured.value) && vm.current.value != null) vm.closeDevice() else super.onBackPressed()
    }

    private companion object {
        val Colors = darkColorScheme(
            primary = Color(0xFF8AB4FF),
            background = Color(0xFF101114),
            surface = Color(0xFF16171B),
            surfaceVariant = Color(0xFF24262C),
            onSurfaceVariant = Color(0xFFC4C6CE),
            secondaryContainer = Color(0xFF2B3040),
        )
    }
}
