package app.atvremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * App icons for the app drawer. The Apple TV only reports bundle IDs and names, so icons come
 * from the public App Store lookup API and are cached on disk. Apple's built-in tvOS apps use
 * different bundle IDs than their App Store versions, so those are mapped first.
 */
class AppIcons(context: Context) {
    private val dir = File(context.cacheDir, "app-icons").apply { mkdirs() }
    private val memory = HashMap<String, Bitmap?>()
    private val lock = Mutex()

    suspend fun icon(bundleId: String, name: String): Bitmap? {
        lock.withLock { if (memory.containsKey(bundleId)) return memory[bundleId] }
        val bmp = withContext(Dispatchers.IO) { loadOrFetch(bundleId, name) }
        lock.withLock { memory[bundleId] = bmp }
        return bmp
    }

    private fun loadOrFetch(bundleId: String, name: String): Bitmap? {
        val file = File(dir, "$bundleId.png")
        val miss = File(dir, "$bundleId.none")
        if (file.isFile) BitmapFactory.decodeFile(file.path)?.let { return it }
        // Retry failed lookups after a week (an app may have since appeared in the store).
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < 7 * 24 * 3600_000L) return null

        val artwork = runCatching { artworkUrl(bundleId, name) }.getOrElse { return null } // offline: try again next time
        val bytes = artwork?.let { runCatching { get(it.replace("100x100bb", "256x256bb")) }.getOrNull() }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) { miss.writeText(""); return null }
        file.writeBytes(bytes)
        miss.delete()
        return bmp
    }

    private fun artworkUrl(bundleId: String, name: String): String? {
        val id = STORE_IDS[bundleId] ?: bundleId
        lookup("lookup?bundleId=" + enc(id))?.let { return it.optString("artworkUrl100").ifEmpty { null } }
        // Fall back to a name search, accepted only when the names clearly match.
        val hit = lookup("search?entity=software&limit=1&term=" + enc(name)) ?: return null
        val a = norm(hit.optString("trackName")); val b = norm(name)
        return if (a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith(b) || b.startsWith(a))) hit.optString("artworkUrl100").ifEmpty { null } else null
    }

    private fun lookup(query: String): JSONObject? {
        val results = JSONObject(String(get("https://itunes.apple.com/$query"))).optJSONArray("results")
        return if (results != null && results.length() > 0) results.getJSONObject(0) else null
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        /** tvOS system app → bundle ID of the same app in the App Store. */
        private val STORE_IDS = mapOf(
            "com.apple.TVWatchList" to "com.apple.tv",
            "com.apple.TVMusic" to "com.apple.Music",
            "com.apple.podcasts" to "com.apple.podcasts",
            "com.apple.Fitness" to "com.apple.Fitness",
            "com.apple.TVHomeSharing" to "com.apple.tv",
            "com.apple.facetime" to "com.apple.facetime",
        )
    }
}
