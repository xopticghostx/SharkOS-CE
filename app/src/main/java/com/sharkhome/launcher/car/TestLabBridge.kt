package com.sharkhome.launcher.car

import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color as AColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClusterWidget(val id: String, val label: String) {
    SPEED("speed", "Speed & Gear"),
    POWER("power", "Power & Regen"),
    BATTERY("battery", "Battery & Range"),
    TYRES("tyres", "Tyre Pressures"),
    TILT("tilt", "Attitude"),
    TRIP("trip", "Trip Stats"),
    HEALTH("health", "12V & Oil"),
    CLOCK("clock", "Clock"),
}

data class DisplaySummary(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val isPresentation: Boolean,
)

/**
 * Experimental bridge for second-screen cluster Presentation (displayId = 1, 1920x720),
 * ClusterCommManager IPC, AvmService AIDL, BydAutoService broadcasts, and cluster media metadata.
 */
object TestLabBridge {
    private const val TAG = "SharkLab"

    // ClusterCommManager constants from BydCarSettings InstrumentMediaManagerImpl
    const val DATA_TYPE_NAVI_SEND_DISPLAY_MODE = 30
    const val DATA_TYPE_NAVI_SEND_PRESENTATION_STATE = 31
    const val DATA_TYPE_NAVI_TBT_INFO = 32
    const val DATA_TYPE_NAVI_REQUEST_MAP_FOCUS = 33
    const val DATA_TYPE_NAVI_ABANDON_MAP_FOCUS = 34

    private var presentation: ClusterPresentation? = null
    private val _presenting = MutableStateFlow(false)
    val presenting: StateFlow<Boolean> = _presenting

    private val _avmBound = MutableStateFlow(false)
    val avmBound: StateFlow<Boolean> = _avmBound
    private var avmBinder: Any? = null
    private var avmConn: ServiceConnection? = null

    fun listDisplays(ctx: Context): List<DisplaySummary> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        val presIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        return dm.displays.map { d ->
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            DisplaySummary(
                id = d.displayId,
                name = d.name ?: "Display ${d.displayId}",
                width = m.widthPixels,
                height = m.heightPixels,
                dpi = m.densityDpi,
                isPresentation = d.displayId in presIds || (d.flags and Display.FLAG_PRESENTATION) != 0,
            )
        }
    }

    private var feedJob: kotlinx.coroutines.Job? = null
    private var provider: (() -> ClusterData)? = null
    private val _selected = MutableStateFlow(setOf(ClusterWidget.SPEED, ClusterWidget.BATTERY, ClusterWidget.TYRES))
    /** Widgets shown on the cluster screen (drawn in enum order). Remembered between launches. */
    val selected: StateFlow<Set<ClusterWidget>> = _selected

    private fun ordered() = ClusterWidget.entries.filter { it in _selected.value }

    /** Called once at start-up: restores the widget choice and keeps a running cluster screen up to date. */
    fun configure(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, dataProvider: () -> ClusterData) {
        provider = dataProvider
        val saved = ctx.getSharedPreferences("cluster", Context.MODE_PRIVATE).getString("widgets", null)
            ?.split(",")?.mapNotNull { id -> ClusterWidget.entries.firstOrNull { it.id == id } }?.toSet()
        if (!saved.isNullOrEmpty()) _selected.value = ClusterWidget.entries.filter { it in saved }.take(MAX_WIDGETS).toSet()
        feedJob?.cancel()
        feedJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                presentation?.update(ordered(), dataProvider())
                kotlinx.coroutines.delay(500)
            }
        }
    }

    const val MAX_WIDGETS = 5

    /** Returns false (and changes nothing) when more than [MAX_WIDGETS] are asked for: they stop being readable. */
    fun setWidgets(ctx: Context, widgets: Set<ClusterWidget>): Boolean {
        if (widgets.size > MAX_WIDGETS) return false
        _selected.value = widgets
        ctx.getSharedPreferences("cluster", Context.MODE_PRIVATE).edit().putString("widgets", widgets.joinToString(",") { it.id }).apply()
        provider?.let { presentation?.update(ordered(), it()) }
        return true
    }

    fun startPresentation(ctx: Context, displayId: Int): String {
        stopPresentation()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return "DisplayManager unavailable"
        val target = dm.getDisplay(displayId)
            ?: dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            ?: return "No presentation display found (displayId=$displayId)"
        return try {
            val p = ClusterPresentation(ctx, target, ordered())
            p.show()
            presentation = p
            _presenting.value = true
            provider?.let { p.update(ordered(), it()) }
            "Showing the cluster screen on display ${target.displayId} (${target.name})"
        } catch (t: Throwable) {
            Log.e(TAG, "Presentation failed", t)
            "Cluster screen error: ${t.message ?: t.javaClass.simpleName}"
        }
    }


    fun stopPresentation(): String {
        val p = presentation ?: return "Presentation not active"
        return try {
            p.dismiss()
            presentation = null
            _presenting.value = false
            "Presentation dismissed"
        } catch (t: Throwable) {
            presentation = null
            _presenting.value = false
            "Dismiss error: ${t.message}"
        }
    }

    fun getDashMapMode(byd: BydAutoBackend): Int? {
        val dev = byd.target("instrument.BYDAutoInstrumentDevice") ?: return null
        return try {
            dev.javaClass.getMethod("getInsNaviDisplayMode").invoke(dev) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    fun setDashMapMode(byd: BydAutoBackend, mode: Int): String {
        val dev = byd.target("instrument.BYDAutoInstrumentDevice")
            ?: return "BYDAutoInstrumentDevice unavailable"
        return try {
            val r = dev.javaClass.getMethod("setInsNaviDisplayMode", Int::class.javaPrimitiveType).invoke(dev, mode)
            "setInsNaviDisplayMode($mode) -> $r"
        } catch (t: Throwable) {
            "setInsNaviDisplayMode($mode) error: ${(t.cause ?: t).message}"
        }
    }

    fun sendClusterCommand(ctx: Context, dataType: Int, bundle: Bundle): String {
        return try {
            val cl = SystemApk.classLoader(ctx, "com.byd.carsettings")
            val cls = Class.forName("com.ts.lib.cluster.ClusterCommManager", true, cl)
            val mgr = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                ?: return "ClusterCommManager.getInstance returned null"
            val ready = cls.getMethod("isServiceReady").invoke(mgr) as? Boolean ?: false
            val ok = cls.getMethod("sendCommand", Int::class.javaPrimitiveType, Bundle::class.java)
                .invoke(mgr, dataType, bundle)
            "ClusterCommManager.sendCommand($dataType, $bundle) ready=$ready -> $ok"
        } catch (t: Throwable) {
            val c = t.cause ?: t
            Log.e(TAG, "ClusterCommManager failed", c)
            "ClusterComm error: ${c.javaClass.simpleName}: ${c.message}"
        }
    }

    fun bindAvm(ctx: Context, onResult: (String) -> Unit) {
        if (avmBinder != null) {
            onResult("AvmService already bound")
            return
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val cl = SystemApk.classLoader(ctx, "com.ts.avm")
                    val stub = Class.forName("com.ts.avm.IAvmServiceInterface\$Stub", true, cl)
                    avmBinder = stub.getMethod("asInterface", IBinder::class.java).invoke(null, service)
                    _avmBound.value = avmBinder != null
                    onResult("AvmService connected: ${avmBinder != null}")
                } catch (t: Throwable) {
                    val c = t.cause ?: t
                    onResult("AvmService bind error: ${c.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                avmBinder = null
                _avmBound.value = false
            }
        }
        avmConn = conn
        val intent = Intent().apply {
            component = ComponentName("com.ts.avm", "com.ts.avm.AvmAndroidService")
        }
        val ok = try {
            ctx.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            onResult("bindService failed: ${t.message}")
            false
        }
        if (!ok) onResult("bindService(com.ts.avm.AvmAndroidService) returned false")
    }

    fun callAvm(method: String): String {
        val b = avmBinder ?: return "AvmService not bound"
        return try {
            val res = b.javaClass.getMethod(method).invoke(b)
            "AvmService.$method() -> $res"
        } catch (t: Throwable) {
            val c = t.cause ?: t
            "AvmService.$method() error: ${c.javaClass.simpleName}: ${c.message}"
        }
    }

    fun sendBydBroadcast(ctx: Context, action: String): String {
        return try {
            ctx.sendBroadcast(Intent(action))
            "Sent broadcast: $action"
        } catch (t: Throwable) {
            "Broadcast error ($action): ${t.message}"
        }
    }

    fun sendClusterMedia(byd: BydAutoBackend, title: String, artist: String): String {
        val dev = byd.target("instrument.BYDAutoInstrumentDevice")
            ?: return "BYDAutoInstrumentDevice unavailable"
        return try {
            val cls = dev.javaClass
            val r1 = cls.getMethod("setMusicName", String::class.java).invoke(dev, title)
            val r2 = cls.getMethod("setMusicSingerName", String::class.java).invoke(dev, artist)
            cls.getMethod("setPadTotalTime", Int::class.javaPrimitiveType).invoke(dev, 210)
            cls.getMethod("setPadPrsntTime", Int::class.javaPrimitiveType).invoke(dev, 45)
            "Sent cluster media '$title - $artist' -> name=$r1 artist=$r2"
        } catch (t: Throwable) {
            val c = t.cause ?: t
            "Cluster media error: ${c.javaClass.simpleName}: ${c.message}"
        }
    }
}

/** The 1920x720 cluster display: one custom-drawn view, see [ClusterView]. */
private class ClusterPresentation(
    outerContext: Context,
    display: Display,
    private var widgets: List<ClusterWidget>,
) : Presentation(outerContext, display) {

    private lateinit var view: ClusterView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = ClusterView(context)
        view.widgets = widgets
        setContentView(view)
    }

    fun update(newWidgets: List<ClusterWidget>, data: ClusterData) {
        widgets = newWidgets
        if (::view.isInitialized) view.post { view.widgets = newWidgets; view.data = data }
    }
}
