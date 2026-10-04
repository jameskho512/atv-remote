package app.atvremote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BraviaException(message: String) : IOException(message)

/**
 * Sony Bravia IP control (Settings > Network > Home network > IP control on the TV).
 * Buttons go over IRCC-IP (SOAP); power and the TV's list of button codes over the JSON REST API.
 * Both authenticate with the pre-shared key set on the TV.
 */
class BraviaClient(private val host: String, private val psk: String) {

    /** Button name → IRCC code, as the TV reports them (e.g. "VolumeUp" → "AAAAAQAAAAEAAAASAw=="). */
    suspend fun remoteCodes(): Map<String, String> {
        val result = rpc("system", "getRemoteControllerInfo")
        val list = result.optJSONArray(1) ?: throw BraviaException("The TV didn't list its remote buttons")
        val codes = LinkedHashMap<String, String>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            codes[o.getString("name")] = o.getString("value")
        }
        return codes
    }

    /** The TV's model code (e.g. "K-65XR80M2"), or null if it doesn't say. */
    suspend fun model(): String? =
        rpc("system", "getSystemInformation").getJSONObject(0).optString("model").ifBlank { null }

    /** True when the TV is on, false in standby. */
    suspend fun isOn(): Boolean =
        rpc("system", "getPowerStatus").getJSONObject(0).optString("status") == "active"

    suspend fun setPower(on: Boolean) {
        rpc("system", "setPowerStatus", JSONObject().put("status", on))
    }

    /** Current picture mode, the modes offered right now, and the kind of picture being shown. */
    data class Picture(val mode: String?, val modes: List<String>, val format: String)

    suspend fun picture(): Picture {
        val list = rpc("video", "getPictureQualitySettings", JSONObject().put("target", "")).getJSONArray(0)
        val byTarget = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.optString("target") }
        val pm = byTarget["pictureMode"]
        val mode = pm?.optString("currentValue")?.ifEmpty { null }
        val modes = pm?.optJSONArray("candidate")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optString("value").ifEmpty { null } } }.orEmpty()
        // Dolby Vision content switches the TV to its Dolby Vision picture modes (checked on a Bravia 8 II).
        // Other HDR is assumed to lock the colour space; provisional until checked against HDR10 content.
        val format = when {
            mode?.contains("dolbyVision", ignoreCase = true) == true -> "Dolby Vision"
            byTarget["colorSpace"]?.optBoolean("isAvailable", true) == false -> "HDR"
            else -> "SDR"
        }
        return Picture(mode, modes, format)
    }

    suspend fun setPictureMode(mode: String) {
        val setting = JSONObject().put("target", "pictureMode").put("value", mode)
        rpc("video", "setPictureQualitySettings", JSONObject().put("settings", JSONArray().put(setting)))
    }

    /** "off", "low", "high", or "pictureOff" (screen off, sound on). */
    suspend fun powerSavingMode(): String = rpc("system", "getPowerSavingMode").getJSONObject(0).optString("mode")

    suspend fun setPowerSavingMode(mode: String) { rpc("system", "setPowerSavingMode", JSONObject().put("mode", mode)) }

    suspend fun reboot() { rpc("system", "requestReboot") }

    /** Presses the button with this IRCC [code]. */
    suspend fun press(code: String) {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$code</IRCCCode></u:X_SendIRCC>""" +
            """</s:Body></s:Envelope>"""
        post("IRCC", body, "text/xml; charset=UTF-8", mapOf("SOAPACTION" to "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\""))
    }

    private suspend fun rpc(service: String, method: String, param: JSONObject? = null): JSONArray {
        val req = JSONObject()
            .put("method", method).put("id", 1).put("version", "1.0")
            .put("params", JSONArray().apply { if (param != null) put(param) })
        val resp = JSONObject(post(service, req.toString(), "application/json"))
        resp.optJSONArray("error")?.let { err ->
            throw BraviaException(
                if (err.optInt(0) == 403 || err.optInt(0) == 401) "The TV rejected the pre-shared key"
                else "The TV returned an error: ${err.optString(1)}"
            )
        }
        return resp.optJSONArray("result") ?: JSONArray()
    }

    private suspend fun post(service: String, body: String, type: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            // No disconnect(): reading each response to the end lets the next press reuse the open connection.
            val conn = URL("http://$host/sony/$service").openConnection() as HttpURLConnection
            run {
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.setRequestProperty("X-Auth-PSK", psk)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code == 403 || code == 401) throw BraviaException("The TV rejected the pre-shared key")
                if (code == 404) throw BraviaException("IP control isn't turned on on the TV")
                if (code !in 200..299) {
                    runCatching { conn.errorStream?.use { it.readBytes() } }
                    throw BraviaException(
                        if (service == "IRCC") "The TV didn't accept that button (HTTP $code)" else "The TV answered HTTP $code"
                    )
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        }
}
