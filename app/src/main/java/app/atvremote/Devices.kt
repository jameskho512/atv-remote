package app.atvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** An Apple TV, found on the network or saved after pairing. [id] is its Bonjour service name. */
data class Device(
    val id: String,
    val host: String,
    val port: Int,
    val model: String? = null,
    val credentials: String? = null,
    /** Separate AirPlay pairing, only when the TV does not accept [credentials] for AirPlay. */
    val airplayCredentials: String? = null,
) {
    val name: String get() = id
    val paired: Boolean get() = credentials != null
}

/** Paired devices and the last-used one, kept in SharedPreferences. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun load(): List<Device> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        List(arr.length()) { i ->
            arr.getJSONObject(i).run {
                Device(
                    getString("id"), getString("host"), getInt("port"), optString("model").ifEmpty { null },
                    optString("creds").ifEmpty { null }, optString("apcreds").ifEmpty { null },
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(devices: List<Device>) {
        val arr = JSONArray()
        devices.filter { it.paired }.forEach {
            arr.put(JSONObject().put("id", it.id).put("host", it.host).put("port", it.port).put("model", it.model ?: "").put("creds", it.credentials).put("apcreds", it.airplayCredentials ?: ""))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var lastDeviceId: String?
        get() = prefs.getString("last", null)
        set(v) { prefs.edit().putString("last", v).apply() }

    /** Device ids for which the user turned down setting up "now playing". */
    var nowPlayingDeclined: Set<String>
        get() = prefs.getStringSet("npDeclined", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("npDeclined", v).apply() }

    var physicalLayout: Boolean
        get() = prefs.getBoolean("physicalLayout", false)
        set(v) { prefs.edit().putBoolean("physicalLayout", v).apply() }

    /** The user hid the Buy me a coffee button for good. */
    var coffeeHidden: Boolean
        get() = prefs.getBoolean("coffeeHidden", false)
        set(v) { prefs.edit().putBoolean("coffeeHidden", v).apply() }

    /** The bottom tab last shown: 0 Apple TV, 1 Bravia. */
    var tab: Int
        get() = prefs.getInt("tab", 0)
        set(v) { prefs.edit().putInt("tab", v).apply() }

}

/**
 * Finds Apple TVs via Bonjour (`_companion-link._tcp`). Macs, iPads and HomePods advertise the
 * same service, so devices whose model (rpMd) is known and not an Apple TV are skipped.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val executor = Executors.newSingleThreadExecutor()

    private val _found = MutableStateFlow<List<Device>>(emptyList())
    val found: StateFlow<List<Device>> = _found

    @Synchronized
    fun start() {
        if (listener != null) return
        lock = wifi.createMulticastLock("atvremote").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.value = _found.value.filter { it.id != info.serviceName }
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.release(); lock = null
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.removeFirstOrNull()
        resolving = next != null
        if (next == null) return
        @Suppress("DEPRECATION") // resolveService allows one resolve at a time; the queue serializes them
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                onResolved(info)
                resolveNext()
            }
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) nsd.resolveService(next, executor, cb) else nsd.resolveService(next, cb)
    }

    private fun onResolved(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val addrs = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val host = (addrs.firstOrNull { it is java.net.Inet4Address } ?: addrs.firstOrNull())?.hostAddress ?: return
        val model = info.attributes.entries.firstOrNull { it.key.equals("rpMd", ignoreCase = true) }?.value?.let { String(it) }
        if (model != null && !model.startsWith("AppleTV")) return
        val d = Device(info.serviceName, host.substringBefore('%'), info.port, model)
        synchronized(this) { _found.value = _found.value.filter { it.id != d.id } + d }
    }

    companion object {
        const val SERVICE_TYPE = "_companion-link._tcp"
    }
}
