package com.sharkhome.launcher.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Shark 6's own car service (CarAdapterService):
 * bind com.ts.appservice.caradapter.CarAdapterService, then ask it for each named adapter
 * ("hvac", "body", ...) and wrap each binder in its AIDL interface by reflection.
 *
 * The interface class for each binder is found from the binder's own descriptor, so we don't
 * need to know the firmware's class names in advance.
 */
class CarAdapterBackend(private val ctx: Context, private val scope: CoroutineScope) {

    data class AdapterInfo(val name: String, val descriptor: String?, val iface: Any?, val error: String?)

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status

    @Volatile
    var adapters: Map<String, AdapterInfo> = emptyMap()
        private set

    @Volatile
    var rootDescriptor: String? = null
        private set

    private var bound = false
    private var batteryBound = false
    @Volatile
    private var batteryInfo: AdapterInfo? = null

    @Volatile
    private var root: Any? = null
    private val extras = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                _status.value = "Connected, but the service returned no binder"
                return
            }
            scope.launch(Dispatchers.IO) { resolve(service) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            adapters = batteryInfo?.let { mapOf(BATTERY_ADAPTER to it) } ?: emptyMap()
            root = null
            extras.clear()
            _status.value = "Disconnected"
        }
    }

    /**
     * Binds com.ts.ota.otasdkmgr/.OtaTsUpdateService (IOtaSdkManager), exposed here as "battery_service"
     * to read 12V auxiliary battery voltage (getBatteryVoltage), battery/ambient temp (getBatteryTemp),
     * DC-DC state, and V2L discharge contactor state.
     */
    private val batteryConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            scope.launch(Dispatchers.IO) {
                val info = try {
                    val desc = service.interfaceDescriptor ?: OTA_DESC
                    val iface = asInterfaceFromPkg(desc, service, OTA_PKG)
                    AdapterInfo(BATTERY_ADAPTER, desc, iface, null)
                } catch (t: Throwable) {
                    AdapterInfo(BATTERY_ADAPTER, null, null, t.message ?: t.javaClass.simpleName)
                }
                batteryInfo = info
                adapters = adapters + (BATTERY_ADAPTER to info)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            batteryInfo = null
            adapters = adapters - BATTERY_ADAPTER
        }
    }

    fun connect() {
        val intent = Intent(ACTION).setClassName(PKG, SERVICE)
        try {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            _status.value = if (bound) "Binding…" else "Car service not found on this head unit"
        } catch (e: SecurityException) {
            _status.value = "Car service refused the connection: ${e.message}"
        }
        val otaIntent = Intent(OTA_ACTION).setClassName(OTA_PKG, OTA_SERVICE)
        runCatching {
            batteryBound = ctx.bindService(otaIntent, batteryConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun reconnect() {
        if (bound) {
            runCatching { ctx.unbindService(connection) }
            bound = false
        }
        if (batteryBound) {
            runCatching { ctx.unbindService(batteryConnection) }
            batteryBound = false
        }
        batteryInfo = null
        adapters = emptyMap()
        connect()
    }

    fun target(name: String): Any? = adapters[name]?.iface ?: if (name == BATTERY_ADAPTER) batteryInfo?.iface else null

    fun connected(): List<AdapterInfo> = adapters.values.filter { it.iface != null }

    private fun resolve(binder: IBinder) {
        try {
            val desc = binder.interfaceDescriptor ?: error("service binder has no interface descriptor")
            rootDescriptor = desc
            val root = asInterface(desc, binder)
            this.root = root
            extras.clear()
            missing.clear()
            val result = LinkedHashMap<String, AdapterInfo>()
            for (n in ADAPTERS) {
                result[n] = try {
                    val sub = Reflect.invoke(root, "getCarAdapterService", listOf(n)) as? IBinder
                        ?: error("no adapter returned")
                    val d = sub.interfaceDescriptor ?: error("adapter binder has no descriptor")
                    AdapterInfo(n, d, asInterface(d, sub), null)
                } catch (t: Throwable) {
                    AdapterInfo(n, null, null, t.message ?: t.javaClass.simpleName)
                }
            }
            batteryInfo?.let { result[BATTERY_ADAPTER] = it }
            adapters = result
            _status.value = "Connected · ${result.values.count { it.iface != null }} of ${result.size} adapters"
        } catch (t: Throwable) {
            _status.value = "Connected but unusable: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Asks the car service for an adapter outside [ADAPTERS] (used by the explorer to find more of the car).
     * Returns null if the firmware has no adapter by that name.
     */
    fun probeExtra(name: String): Any? {
        if (name == BATTERY_ADAPTER) return batteryInfo?.iface
        extras[name]?.let { return it }
        if (name in missing) return null
        val r = root ?: return null
        return try {
            val sub = Reflect.invoke(r, "getCarAdapterService", listOf(name)) as? IBinder
            val d = sub?.interfaceDescriptor
            if (sub == null || d == null) {
                missing.add(name)
                return null
            }
            asInterface(d, sub).also { extras[name] = it }
        } catch (_: Throwable) {
            missing.add(name)
            null
        }
    }

    private fun asInterface(descriptor: String, binder: IBinder): Any =
        asInterfaceFromPkg(descriptor, binder, PKG)

    private fun asInterfaceFromPkg(descriptor: String, binder: IBinder, pkg: String): Any {
        val stub = loadClass("$descriptor\$Stub", pkg)
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("asInterface returned null for $descriptor")
    }

    private fun loadClass(name: String, pkg: String = PKG): Class<*> {
        try {
            return Class.forName(name)
        } catch (_: ClassNotFoundException) {
            // Not on the boot class path: load it from the service's own APK.
        }
        return Class.forName(name, true, SystemApk.classLoader(ctx, pkg))
    }

    companion object {
        const val PKG = "com.ts.appservice.caradapter"
        const val SERVICE = "com.ts.appservice.caradapter.CarAdapterService"
        const val ACTION = "com.ts.lib.caradapter.ICarAdapterService"

        const val BATTERY_ADAPTER = "battery_service"
        const val OTA_PKG = "com.ts.ota.otasdkmgr"
        const val OTA_SERVICE = "com.ts.ota.otasdkmgr.OtaTsUpdateService"
        const val OTA_ACTION = "android.Otasdk.OtaTsUpdateService"
        const val OTA_DESC = "com.ts.ota.otasdkmgr.IOtaSdkManager"

        val ADAPTERS = listOf("hvac", "body", "setting", "seat", "power", "cabin", "assist", "general", "sensor", "cloud")
    }
}
