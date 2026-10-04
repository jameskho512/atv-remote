package app.atvremote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.atvremote.protocol.NowPlaying

/**
 * Placeholder data for README screenshots. Only a debuggable build honours it
 * (`adb shell am start -n app.atvremote/.MainActivity -e demo apple|apple-siri|bravia`), and while it's on
 * the app never connects to a real device or reads saved ones.
 */
object DemoMode {
    @Volatile var enabled = false
    /** "apple", "apple-siri" or "bravia": which screen to show. */
    @Volatile var screen = "apple"

    const val DEVICE_NAME = "Living Room"

    fun nowPlaying() = NowPlaying(
        title = "Sample Song",
        subtitle = "Example Artist — Sample Album",
        bundleId = null,
        appName = "Sample App",
        playing = true,
        duration = 215.0,
        position = 84.0,
        positionAtMs = System.currentTimeMillis(),
        rate = 1.0,
        itemId = "demo",
        artworkAvailable = true,
    )

    /** A plain gradient with a disc, so no real artwork appears in screenshots. */
    fun artwork(): Bitmap {
        val size = 300
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF5B7FBF.toInt(), 0xFF1E2A44.toInt(), Shader.TileMode.CLAMP) },
        )
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 14f }
        canvas.drawCircle(150f, 150f, 95f, ring)
        canvas.drawCircle(150f, 150f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() })
        return bmp
    }
}
