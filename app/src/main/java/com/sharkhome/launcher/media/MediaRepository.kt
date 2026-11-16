package com.sharkhome.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy

/**
 * Exists so Android lets us read media sessions. Notifications are counted for the bell in the status bar and, only
 * while the pull-down panel is open, listed there so they can be opened or dismissed. Nothing is stored or logged.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile private var instance: MediaListenerService? = null

        /** Notifications the driver could dismiss (not ongoing ones like "music playing"); null without access. */
        fun count(): Int? = runCatching { instance?.activeNotifications?.count { !it.isOngoing && it.isClearable } }.getOrNull()

        /** What the notification panel shows. Read on demand and only drawn on screen; never stored or logged. */
        data class Item(val key: String, val app: String, val title: String, val text: String, val open: android.app.PendingIntent?)

        /** Dismissable notifications, newest first; null without notification access. */
        fun items(ctx: Context): List<Item>? {
            val svc = instance ?: return null
            return runCatching {
                val pm = ctx.packageManager
                svc.activeNotifications.filter { !it.isOngoing && it.isClearable }.sortedByDescending { it.postTime }.map { sbn ->
                    val ex = sbn.notification.extras
                    val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    Item(
                        sbn.key, app,
                        ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty(),
                        (ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))?.toString().orEmpty(),
                        sbn.notification.contentIntent,
                    )
                }
            }.getOrNull()
        }

        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) } }

        fun dismissAll() {
            runCatching { instance?.let { s -> s.activeNotifications.filter { !it.isOngoing && it.isClearable }.forEach { s.cancelNotification(it.key) } } }
        }
    }
}

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val pkg: String,
)

data class AaNavInfo(
    val status: Int = 0, // 0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE, 3=REROUTING
    val maneuverIcon: Bitmap? = null,
    val nextRoad: String = "",
    val currentRoad: String = "",
    val distanceText: String = "",
    val etaText: String = "",
    val destination: String = "",
)

class MediaRepository(private val ctx: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "SharkMedia"
        const val AA_PKG = "com.ts.androidauto.app"
        const val CP_PKG = "com.ts.carplay.app"
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(ctx, MediaListenerService::class.java)
    private var controller: MediaController? = null
    private var listening = false
    private var ticker: Job? = null
    /** True while the app's screen is showing (set by MainActivity). */
    @Volatile var uiVisible = true
    // Last metadata / state handed to the controller callback, so the 1 s ticker makes no binder calls (each
    // controller.metadata call copies the album art across processes).
    private var ctrlMeta: MediaMetadata? = null
    private var ctrlState: PlaybackState? = null
    private var ppCheck: Runnable? = null

    // ---- ThunderSoft Android Auto (ts.car.service.androidauto.IAndroidAutoAppManager & IAndroidAutoServiceManager) state ----
    private var aaInitAttempted = false
    private var aaManager: Any? = null
    private var aaMgrIface: Class<*>? = null
    private var aaServiceConnected = false
        set(v) {
            field = v
            _aaAppServiceReady.value = v
        }
    private val _aaAppServiceReady = MutableStateFlow(false)
    val aaAppServiceReady: StateFlow<Boolean> = _aaAppServiceReady

    private var aaServiceInitAttempted = false
    private var aaServiceMgr: Any? = null
    private var aaServiceIface: Class<*>? = null
    private var aaServiceMgrConnected = false
        set(v) {
            field = v
            _aaServiceReady.value = v
        }
    private val _aaServiceReady = MutableStateFlow(false)
    /** True while IAndroidAutoServiceManager (projection video surface & touch) is connected. */
    val aaServiceReady: StateFlow<Boolean> = _aaServiceReady

    private val _aaProjectionError = MutableStateFlow<String?>(null)
    val aaProjectionError: StateFlow<String?> = _aaProjectionError

    private var attachedSurface: Surface? = null
    private var attachedCluster = false
    private var attachedWidth = 0
    private var attachedHeight = 0

    private val _androidAutoConnected = MutableStateFlow(false)
    /** True while an Android Auto phone is projecting (ThunderSoft session status 4). Used by the map card. */
    val androidAutoConnected: StateFlow<Boolean> = _androidAutoConnected

    private val _aaNavAppActive = MutableStateFlow(false)
    /** True when Android Auto's route-guidance app (e.g. Google Maps or Waze) is active. */
    val aaNavAppActive: StateFlow<Boolean> = _aaNavAppActive

    private val _aaNavStatus = MutableStateFlow(0)
    /** AndroidAutoConst.NavigationStatus: 0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE, 3=REROUTING. */
    val aaNavStatus: StateFlow<Int> = _aaNavStatus

    private val _aaNavInfo = MutableStateFlow<AaNavInfo?>(null)
    val aaNavInfo: StateFlow<AaNavInfo?> = _aaNavInfo

    private val _aaVideoFocus = MutableStateFlow(0)
    val aaVideoFocus: StateFlow<Int> = _aaVideoFocus

    private var aaConnected = false
        set(v) {
            field = v
            _androidAutoConnected.value = v
        }
    private var aaUseNativeNav = true // ThunderSoft NavigationFocusManager defaults mUseNativeNavigation = true
    private var aaTbtActive = false
    private var aaPlaying = false
    private var aaTitle = ""
    private var aaArtist = ""
    private var aaArt: Bitmap? = null
    private var aaArtKey = ""
    private var aaDurationMs = 0L
    private var aaPositionMs = 0L
    private var aaPositionRealtime = 0L

    private val _carPlayConnected = MutableStateFlow(false)
    /** True while an iPhone is projecting over CarPlay. */
    val carPlayConnected: StateFlow<Boolean> = _carPlayConnected

    private fun recomputeAaNavActive() {
        _aaNavAppActive.value = aaConnected && (!aaUseNativeNav || _aaNavStatus.value == 1 || _aaNavStatus.value == 3 || aaTbtActive)
    }

    // ---- ThunderSoft Apple CarPlay (ts.car.service.carplay.ICarPlayAppManager) state ----
    private var cpInitAttempted = false
    private var cpManager: Any? = null
    private var cpMgrIface: Class<*>? = null
    private var cpNowPlayingProxy: Any? = null
    private var cpServiceConnected = false
    private var cpConnected = false
    private var cpPlaying = false
    private var cpMediaFocus = false
    private var cpTitle = ""
    private var cpArtist = ""
    private var cpArt: Bitmap? = null
    private var cpArtKey = ""
    private var cpDurationMs = 0L
    private var cpPositionMs = 0L
    private var cpPositionRealtime = 0L

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { ctrlState = state; update() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { ctrlMeta = metadata; update() }
        override fun onSessionDestroyed() {
            pick(runCatching { msm.getActiveSessions(component) }.getOrNull())
        }
    }

    private var autoAdbGrantTried = false

    /** Safe to call repeatedly (e.g. on every resume) — picks up access granted in Settings or directly. */
    fun start() {
        initProjectionServices()

        var has = canReadMediaSessions()
        if (!has) {
            has = tryEnableDirectly()
        }
        _hasAccess.value = has

        if (has && !listening) {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                listening = true
                pick(msm.getActiveSessions(component))
            } catch (e: SecurityException) {
                _hasAccess.value = false
                listening = false
            }
        }

        if (!_hasAccess.value && !autoAdbGrantTried && adb?.state?.value == com.sharkhome.launcher.system.LocalAdb.State.CONNECTED) {
            autoAdbGrantTried = true
            scope.launch { grantAccessViaAdb() }
        }

        if (ticker == null) {
            ticker = scope.launch(Dispatchers.Main) {
                var ticks = 0
                while (isActive) {
                    // Nothing to draw while another app is in front: skip the binder calls until we're back.
                    if (!uiVisible) { delay(1000); continue }
                    if (ticks % 2 == 0) {
                        pollProjectionState()
                    }
                    update()
                    ticks++
                    delay(1000)
                }
            }
        }
    }

    /**
     * Connects to ThunderSoft's IAndroidAutoAppManager and ICarPlayAppManager via
     * com.ts.car.core.ServiceProvider (exact same mechanism used by BYD's BydLocalMedia.apk).
     */
    private fun initProjectionServices() {
        if (!aaInitAttempted) {
            aaInitAttempted = true
            runCatching { initAndroidAuto() }
                .onFailure { Log.w(TAG, "initAndroidAuto failed: ${unwrapError(it)}") }
        }
        if (!aaServiceInitAttempted) {
            aaServiceInitAttempted = true
            runCatching { initAndroidAutoService() }
                .onFailure { Log.w(TAG, "initAndroidAutoService failed: ${unwrapError(it)}") }
        }
        if (!cpInitAttempted) {
            cpInitAttempted = true
            runCatching { initCarPlay() }
                .onFailure { Log.w(TAG, "initCarPlay failed: ${unwrapError(it)}") }
        }
    }

    private fun unwrapError(t: Throwable): String {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun getTsClassLoader(fallbackPkg: String): ClassLoader? {
        val fwJars = listOf(
            "/system/framework/ts-platform-library.jar",
            "/system/framework/ts-dm-foundation-lib.jar",
            "/system/framework/ts-dcm-foundation-lib.jar",
        ).filter { java.io.File(it).exists() }
        if (fwJars.isNotEmpty()) {
            val cl = runCatching {
                dalvik.system.PathClassLoader(fwJars.joinToString(java.io.File.pathSeparator), ctx.classLoader)
            }.getOrNull()
            if (cl != null && runCatching { cl.loadClass("com.ts.car.core.ServiceProvider") }.isSuccess) {
                return cl
            }
        }

        val candidates = listOf("com.byd.localmusic", fallbackPkg, "com.byd.Launcher")
        for (pkg in candidates) {
            val cl = runCatching {
                ctx.createPackageContext(
                    pkg,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                ).classLoader
            }.getOrNull()
            if (cl != null) {
                val hasProvider = runCatching { cl.loadClass("com.ts.car.core.ServiceProvider") }.isSuccess
                if (hasProvider) return cl
            }
        }
        return null
    }

    // Each bridge is set up again after its service drops. The generation counters make the listeners from an
    // earlier set-up do nothing, so events are never handled twice and old listeners can't schedule more set-ups.
    @Volatile private var aaGen = 0
    @Volatile private var aaSvcGen = 0
    @Volatile private var cpGen = 0
    private var aaConnProxy: Any? = null
    private var aaSvcConnProxy: Any? = null
    private var cpConnProxy: Any? = null

    /** Registers [next] as the connection listener and removes the previous one (if the service allows it). */
    private fun swapConnListener(mgrClass: Class<*>, listenerClass: Class<*>, mgr: Any, previous: Any?, next: Any) {
        if (previous != null) {
            runCatching { mgrClass.getMethod("unregisterConnectionListener", listenerClass).invoke(mgr, previous) }
        }
        mgrClass.getMethod("registerConnectionListener", listenerClass).invoke(mgr, next)
    }

    private fun safeProxy(
        cl: ClassLoader,
        iface: Class<*>,
        alive: () -> Boolean = { true },
        onInvoke: (methodName: String, args: Array<out Any?>?) -> Unit
    ): Any {
        return Proxy.newProxyInstance(cl, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SharkProxy(${iface.simpleName})"
                else -> {
                    if (alive()) runCatching { onInvoke(method.name, args) }
                        .onFailure { Log.w(TAG, "Proxy ${iface.simpleName}.${method.name} error: ${unwrapError(it)}") }
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 0
                        Long::class.javaPrimitiveType, Long::class.javaObjectType -> 0L
                        else -> null
                    }
                }
            }
        }
    }

    private fun initAndroidAuto() {
        val cl = getTsClassLoader("com.ts.androidauto.projectionservice") ?: return
        val providerClass = cl.loadClass("com.ts.car.core.ServiceProvider")
        val connListenerClass = cl.loadClass("ts.car.service.IServiceConnectionListener")
        val aaMgrClass = cl.loadClass("ts.car.service.androidauto.IAndroidAutoAppManager")
        val sessionListenerClass = cl.loadClass("ts.car.service.androidauto.IAndroidAutoSessionStatusListener")
        val mediaListenerClass = cl.loadClass("ts.car.service.androidauto.IMediaDataListener")
        val appListenerClass = runCatching { cl.loadClass("ts.car.service.androidauto.IAndroidAutoAppListener") }.getOrNull()
        val navListenerClass = runCatching { cl.loadClass("ts.car.service.androidauto.INavigationDataListener") }.getOrNull()

        val mgr = providerClass.getMethod("get", Class::class.java, Context::class.java)
            .invoke(null, aaMgrClass, ctx.applicationContext) ?: return
        aaManager = mgr
        aaMgrIface = aaMgrClass
        val gen = ++aaGen // listeners from an earlier init go quiet

        val sessionProxy = safeProxy(cl, sessionListenerClass, { gen == aaGen }) { methodName, args ->
            when (methodName) {
                "onSessionStatusChanged", "onSessionConnectStatusChanged" -> {
                    val linkStatus = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                    main.post {
                        val prev = aaConnected
                        aaConnected = (linkStatus == 4)
                        if (prev != aaConnected) {
                            Log.w(TAG, "AA session status changed: linkStatus=$linkStatus aaConnected=$aaConnected")
                        }
                        if (!aaConnected) {
                            aaPlaying = false
                            clearAaMediaState()
                            resetAaNavState()
                        } else {
                            syncAndroidAutoState()
                        }
                        update()
                    }
                }
                "onProjectionAppStatesChanged" -> {
                    val appType = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                    val enabled = args?.getOrNull(1) as? Boolean ?: false
                    main.post {
                        if (appType == 0) {
                            // ThunderSoft APP_TYPE_ROUTE_GUIDANCE passes mIsUseNativeNavigation:
                            // true = BYD native navigation has focus, false = Android Auto navigation has focus.
                            aaUseNativeNav = enabled
                            recomputeAaNavActive()
                            Log.w(TAG, "AA onProjectionAppStatesChanged(type=0, useNativeNav=$enabled) -> aaConnected=$aaConnected, aaNavAppActive=${_aaNavAppActive.value}")
                        }
                    }
                }
            }
        }

        val mediaProxy = safeProxy(cl, mediaListenerClass, { gen == aaGen }) { methodName, args ->
            when (methodName) {
                "onMediaMetadataChanged" -> {
                    val md = args?.getOrNull(0)
                    main.post {
                        parseAaMetadata(md)
                        update()
                    }
                }
                "onMediaProgressChanged" -> {
                    val progressSec = (args?.getOrNull(0) as? Number)?.toLong() ?: 0L
                    main.post {
                        aaPositionMs = (progressSec * 1000L).coerceAtLeast(0L)
                        aaPositionRealtime = SystemClock.elapsedRealtime()
                        update()
                    }
                }
                "onMusicStatusChanged" -> {
                    val status = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                    main.post {
                        aaPlaying = (status == 1)
                        if (aaPlaying) aaConnected = true
                        update()
                    }
                }
            }
        }

        val appProxy = appListenerClass?.let { cls ->
            safeProxy(cl, cls, { gen == aaGen }) { methodName, args ->
                if (methodName == "onVideoFocusStatusChanged") {
                    val focus = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                    main.post { _aaVideoFocus.value = focus }
                }
            }
        }

        val navProxy = navListenerClass?.let { cls ->
            safeProxy(cl, cls, { gen == aaGen }) { methodName, args ->
                when (methodName) {
                    "onNavigationStatusChanged" -> {
                        val status = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                        main.post {
                            if (!aaConnected && (status == 1 || status == 3)) {
                                syncAndroidAutoState()
                            }
                            _aaNavStatus.value = status
                            if (aaConnected && (status == 1 || status == 3)) {
                                _aaNavInfo.value = (_aaNavInfo.value ?: AaNavInfo()).copy(status = status)
                            } else {
                                _aaNavInfo.value = null
                            }
                            recomputeAaNavActive()
                        }
                    }
                    "onNextTurnChanged" -> {
                        val data = args?.getOrNull(0)
                        main.post { parseAaNextTurn(data) }
                    }
                    "onNextTurnDistanceChanged" -> {
                        val meters = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                        val displayE3 = (args?.getOrNull(2) as? Number)?.toInt() ?: -1
                        val unit = (args?.getOrNull(3) as? Number)?.toInt() ?: 0
                        main.post {
                            val formatted = formatAaDistance(meters, displayE3, unit)
                            if (formatted.isNotEmpty()) {
                                val cur = _aaNavInfo.value ?: AaNavInfo(status = _aaNavStatus.value.takeIf { it != 0 } ?: 1)
                                _aaNavInfo.value = cur.copy(distanceText = formatted)
                            }
                        }
                    }
                    "onNavigationCurrentPositionChanged" -> {
                        val posData = args?.getOrNull(0)
                        main.post { parseAaNavPosition(posData) }
                    }
                    "onNavigationStateChanged" -> {
                        val stateData = args?.getOrNull(0)
                        main.post { parseAaNavState(stateData) }
                    }
                }
            }
        }

        fun registerAaCallbacks() {
            val activeMgr = aaManager ?: return
            runCatching {
                aaMgrClass.getMethod("registerAndroidAutoSessionStatusListener", sessionListenerClass)
                    .invoke(activeMgr, sessionProxy)
                aaMgrClass.getMethod("registerMediaDataListener", mediaListenerClass)
                    .invoke(activeMgr, mediaProxy)
                if (appListenerClass != null && appProxy != null) {
                    runCatching {
                        aaMgrClass.getMethod("registerAndroidAutoAppListener", appListenerClass)
                            .invoke(activeMgr, appProxy)
                    }
                }
                if (navListenerClass != null && navProxy != null) {
                    runCatching {
                        aaMgrClass.getMethod("registerNavigationDataListener", navListenerClass)
                            .invoke(activeMgr, navProxy)
                    }.onFailure { Log.w(TAG, "registerNavigationDataListener failed: ${unwrapError(it)}") }
                }
                syncAndroidAutoState()
            }.onFailure { Log.w(TAG, "registerAaCallbacks failed: ${unwrapError(it)}") }
        }

        val connProxy = safeProxy(cl, connListenerClass, { gen == aaGen }) { methodName, args ->
            when (methodName) {
                "onServiceConnected" -> {
                    val connectedService = args?.getOrNull(0)
                    if (connectedService != null && aaMgrClass.isInstance(connectedService)) {
                        aaManager = connectedService
                    }
                    aaServiceConnected = true
                    main.post {
                        registerAaCallbacks()
                        update()
                    }
                }
                "onServiceDisconnected" -> {
                    aaServiceConnected = false
                    main.post {
                        aaConnected = false
                        aaPlaying = false
                        resetAaNavState()
                        update()
                    }
                    main.postDelayed({
                        if (!aaServiceConnected) {
                            runCatching { initAndroidAuto() }
                        }
                    }, 2000L)
                }
            }
        }

        swapConnListener(aaMgrClass, connListenerClass, mgr, aaConnProxy, connProxy)
        aaConnProxy = connProxy
    }

    private fun initAndroidAutoService() {
        val cl = getTsClassLoader("com.ts.androidauto.projectionservice") ?: return
        val providerClass = cl.loadClass("com.ts.car.core.ServiceProvider")
        val connListenerClass = cl.loadClass("ts.car.service.IServiceConnectionListener")
        val svcMgrClass = cl.loadClass("ts.car.service.androidauto.IAndroidAutoServiceManager")
        val svcListenerClass = runCatching { cl.loadClass("ts.car.service.androidauto.IAndroidAutoServiceListener") }.getOrNull()

        val mgr = providerClass.getMethod("get", Class::class.java, Context::class.java)
            .invoke(null, svcMgrClass, ctx.applicationContext) ?: return
        aaServiceMgr = mgr
        aaServiceIface = svcMgrClass
        val gen = ++aaSvcGen // listeners from an earlier init go quiet

        val svcListenerProxy = svcListenerClass?.let { cls ->
            safeProxy(cl, cls, { gen == aaSvcGen }) { methodName, args ->
                when (methodName) {
                    "onAppStatesChanged" -> {
                        val appState = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                        val enabled = args?.getOrNull(1) as? Boolean ?: false
                        Log.w(TAG, "AA service onAppStatesChanged($appState, $enabled)")
                        main.post {
                            aaTbtActive = enabled
                            recomputeAaNavActive()
                        }
                    }
                    "onVideoFocusStatusChanged" -> {
                        val focus = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                        main.post { _aaVideoFocus.value = focus }
                    }
                }
            }
        }

        val connProxy = safeProxy(cl, connListenerClass, { gen == aaSvcGen }) { methodName, args ->
            when (methodName) {
                "onServiceConnected" -> {
                    val connectedService = args?.getOrNull(0)
                    if (connectedService != null && svcMgrClass.isInstance(connectedService)) {
                        aaServiceMgr = connectedService
                    }
                    aaServiceMgrConnected = true
                    _aaProjectionError.value = null
                    Log.w(TAG, "IAndroidAutoServiceManager connected")
                    val activeMgr = aaServiceMgr
                    if (activeMgr != null && svcListenerClass != null && svcListenerProxy != null) {
                        runCatching {
                            svcMgrClass.getMethod("registerAndroidAutoServiceListener", svcListenerClass)
                                .invoke(activeMgr, svcListenerProxy)
                        }.onFailure { Log.w(TAG, "registerAndroidAutoServiceListener failed: ${unwrapError(it)}") }
                    }
                }
                "onServiceDisconnected" -> {
                    aaServiceMgrConnected = false
                    attachedSurface = null
                    attachedWidth = 0
                    attachedHeight = 0
                    main.postDelayed({
                        if (!aaServiceMgrConnected) {
                            runCatching { initAndroidAutoService() }
                        }
                    }, 2000L)
                }
            }
        }

        swapConnListener(svcMgrClass, connListenerClass, mgr, aaSvcConnProxy, connProxy)
        aaSvcConnProxy = connProxy
    }

    private fun clearAaMediaState() {
        aaTitle = ""
        aaArtist = ""
        aaArt = null
        aaArtKey = ""
        aaDurationMs = 0L
        aaPositionMs = 0L
        aaPositionRealtime = 0L
    }

    private fun resetAaNavState() {
        aaUseNativeNav = true
        aaTbtActive = false
        _aaNavAppActive.value = false
        _aaNavStatus.value = 0
        _aaNavInfo.value = null
        _aaVideoFocus.value = 0
        _aaProjectionError.value = null
    }

    fun clearProjectionError() {
        _aaProjectionError.value = null
    }

    private fun formatAaDistance(distanceMeters: Int, displayDistanceE3: Int, unit: Int): String {
        if (displayDistanceE3 > 0) {
            return when (unit) {
                1 -> "${(displayDistanceE3 / 1000.0).toInt()} m"
                2 -> "${(displayDistanceE3 / 1000.0).toInt()} km"
                3 -> "${"%.1f".format(displayDistanceE3 / 1000.0)} km"
                4 -> "${(displayDistanceE3 / 1000.0).toInt()} mi"
                5 -> "${"%.1f".format(displayDistanceE3 / 1000.0)} mi"
                6 -> "${(displayDistanceE3 / 1000.0).toInt()} ft"
                7 -> "${(displayDistanceE3 / 1000.0).toInt()} yd"
                else -> ""
            }
        }
        return when {
            distanceMeters < 0 -> ""
            distanceMeters < 1000 -> "$distanceMeters m"
            else -> "${"%.1f".format(distanceMeters / 1000.0)} km"
        }
    }

    private fun unitSuffix(unit: Int): String = when (unit) {
        1 -> "m"
        2, 3 -> "km"
        4, 5 -> "mi"
        6 -> "ft"
        7 -> "yd"
        else -> ""
    }

    private fun parseAaNextTurn(data: Any?) {
        if (data == null) return
        runCatching {
            val cls = data.javaClass
            val road = (cls.getMethod("getRoad").invoke(data) as? String)?.trim().orEmpty()
            val imgBytes = cls.getMethod("getImage").invoke(data) as? ByteArray
            val icon = if (imgBytes != null && imgBytes.isNotEmpty()) {
                runCatching { BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size) }.getOrNull()
            } else null
            if (!aaConnected) return@runCatching
            val cur = _aaNavInfo.value ?: AaNavInfo(status = _aaNavStatus.value.takeIf { it != 0 } ?: 1)
            _aaNavInfo.value = cur.copy(
                nextRoad = road.ifEmpty { cur.nextRoad },
                maneuverIcon = icon ?: cur.maneuverIcon,
            )
            recomputeAaNavActive()
        }.onFailure { Log.w(TAG, "parseAaNextTurn error: ${unwrapError(it)}") }
    }

    private fun parseAaNavPosition(posData: Any?) {
        if (posData == null) return
        runCatching {
            val cls = posData.javaClass
            val currentRoad = (cls.getMethod("getCurrentRoad").invoke(posData) as? String)?.trim().orEmpty()
            val dispVal = (cls.getMethod("getDisplayValue").invoke(posData) as? String)?.trim().orEmpty()
            val dispUnits = (cls.getMethod("getDisplayUnits").invoke(posData) as? Number)?.toInt() ?: 0
            val meters = (cls.getMethod("getMeters").invoke(posData) as? Number)?.toInt() ?: -1
            val distText = when {
                dispVal.isNotEmpty() -> listOfNotNull(dispVal, unitSuffix(dispUnits).ifEmpty { null }).joinToString(" ")
                meters >= 0 -> formatAaDistance(meters, -1, 0)
                else -> ""
            }
            val destList = cls.getMethod("getNavigationDestDistanceData").invoke(posData) as? List<*>
            val firstDest = destList?.firstOrNull()
            val eta = if (firstDest != null) {
                runCatching {
                    val dCls = firstDest.javaClass
                    val est = (dCls.getMethod("getEstimatedTime").invoke(firstDest) as? String)?.trim().orEmpty()
                    val remVal = (dCls.getMethod("getDisplayValue").invoke(firstDest) as? String)?.trim().orEmpty()
                    val remUnit = unitSuffix((dCls.getMethod("getDisplayUnits").invoke(firstDest) as? Number)?.toInt() ?: 0)
                    val remText = if (remVal.isNotEmpty()) listOfNotNull(remVal, remUnit.ifEmpty { null }).joinToString(" ") else ""
                    listOfNotNull(est.ifEmpty { null }, remText.ifEmpty { null }).joinToString(" · ")
                }.getOrDefault("")
            } else ""

            val cur = _aaNavInfo.value ?: AaNavInfo(status = _aaNavStatus.value.takeIf { it != 0 } ?: 1)
            _aaNavInfo.value = cur.copy(
                currentRoad = currentRoad.ifEmpty { cur.currentRoad },
                distanceText = distText.ifEmpty { cur.distanceText },
                etaText = eta.ifEmpty { cur.etaText },
            )
        }.onFailure { Log.w(TAG, "parseAaNavPosition error: ${unwrapError(it)}") }
    }

    private fun parseAaNavState(stateData: Any?) {
        if (stateData == null) return
        runCatching {
            val dests = stateData.javaClass.getMethod("getNavigationDestinations").invoke(stateData) as? List<*>
            val destName = dests?.firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
            if (destName.isNotEmpty()) {
                val cur = _aaNavInfo.value ?: AaNavInfo(status = _aaNavStatus.value.takeIf { it != 0 } ?: 1)
                _aaNavInfo.value = cur.copy(destination = destName)
            }
        }.onFailure { Log.w(TAG, "parseAaNavState error: ${unwrapError(it)}") }
    }

    /**
     * Attaches a [Surface] to ThunderSoft's IAndroidAutoServiceManager so Android Auto's map video
     * renders inside the launcher's Map widget. Returns true if the IPC call succeeded.
     */
    @Synchronized
    fun attachProjectionSurface(
        surface: Surface,
        width: Int,
        height: Int,
        useCluster: Boolean = false,
        requestNaviUi: Boolean = false,
        force: Boolean = false,
    ): Boolean {
        if (!aaServiceMgrConnected) return false
        val mgr = aaServiceMgr ?: return false
        val iface = aaServiceIface ?: mgr.javaClass
        if (!surface.isValid || width <= 0 || height <= 0) return false
        if (!force && attachedSurface === surface && attachedCluster == useCluster && attachedWidth == width && attachedHeight == height) {
            return true
        }
        return runCatching {
            if (attachedSurface != null && attachedCluster != useCluster) {
                detachProjectionSurface(useCluster = attachedCluster)
            }
            val methodName = if (useCluster) "setClusterSurface" else "setSurface"
            Log.w(TAG, "AA $methodName(${width}x$height, requestNaviUi=$requestNaviUi, force=$force)")
            iface.getMethod(methodName, Surface::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(mgr, surface, width, height)
            attachedSurface = surface
            attachedCluster = useCluster
            attachedWidth = width
            attachedHeight = height
            _aaProjectionError.value = null
            if (!useCluster && requestNaviUi) {
                requestNaviUi()
            }
            true
        }.getOrElse {
            val err = unwrapError(it)
            Log.w(TAG, "attachProjectionSurface(cluster=$useCluster, ${width}x$height) failed: $err")
            _aaProjectionError.value = err
            false
        }
    }

    /** Releases the Map widget's [Surface] so full-screen Android Auto (AapActivity) can reclaim it cleanly. */
    @Synchronized
    fun detachProjectionSurface(surface: Surface? = null, useCluster: Boolean? = null) {
        val current = attachedSurface ?: return
        if (surface != null && current !== surface) return
        val mgr = aaServiceMgr
        val iface = aaServiceIface ?: mgr?.javaClass
        val cluster = useCluster ?: attachedCluster
        if (aaServiceMgrConnected && mgr != null && iface != null) {
            runCatching {
                val methodName = if (cluster) "setClusterSurface" else "setSurface"
                Log.w(TAG, "AA $methodName(null, 0x0)")
                iface.getMethod(methodName, Surface::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(mgr, null, 0, 0)
            }.onFailure { Log.w(TAG, "detachProjectionSurface(cluster=$cluster) failed: ${unwrapError(it)}") }
        }
        attachedSurface = null
        attachedWidth = 0
        attachedHeight = 0
    }

    /** Forwards touch gestures on the Map widget's AA projection surface into Android Auto. */
    fun sendProjectionTouch(event: MotionEvent, viewWidth: Int = 0, viewHeight: Int = 0) {
        if (!aaServiceMgrConnected) return
        val mgr = aaServiceMgr ?: return
        val iface = aaServiceIface ?: mgr.javaClass
        val scaled = if (viewWidth > 0 && viewHeight > 0 && (viewWidth != 1920 || viewHeight != 1080)) {
            scaleMotionEvent(event, viewWidth, viewHeight)
        } else {
            null
        }
        try {
            iface.getMethod("screenTouchEvent", MotionEvent::class.java).invoke(mgr, scaled ?: event)
        } catch (t: Throwable) {
            Log.w(TAG, "sendProjectionTouch failed: ${unwrapError(t)}")
        } finally {
            scaled?.recycle()
        }
    }

    private fun scaleMotionEvent(src: MotionEvent, viewWidth: Int, viewHeight: Int): MotionEvent {
        // MediaCodec configures SurfaceView with SCALE_TO_FIT_WITH_CROPPING of the 1920x1080 AA stream.
        val videoW = 1920f
        val videoH = 1080f
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val videoAspect = videoW / videoH
        val (offsetX, visibleW, offsetY, visibleH) = if (viewAspect > videoAspect) {
            val h = videoW / viewAspect
            floatArrayOf(0f, videoW, (videoH - h) * 0.5f, h)
        } else {
            val w = videoH * viewAspect
            floatArrayOf((videoW - w) * 0.5f, w, 0f, videoH)
        }
        val count = src.pointerCount
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().also { src.getPointerProperties(i, it) }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().also { c ->
                src.getPointerCoords(i, c)
                c.x = offsetX + (c.x / viewWidth.toFloat()) * visibleW
                c.y = offsetY + (c.y / viewHeight.toFloat()) * visibleH
            }
        }
        return MotionEvent.obtain(
            src.downTime,
            src.eventTime,
            src.action,
            count,
            props,
            coords,
            src.metaState,
            src.buttonState,
            src.xPrecision,
            src.yPrecision,
            src.deviceId,
            src.edgeFlags,
            src.source,
            src.flags,
        )
    }

    /** Asks Android Auto to show the Navigation/Maps screen (LAUNCH_MODE_NAVI = 4). */
    fun requestNaviUi() {
        val svc = aaServiceMgr
        if (aaServiceMgrConnected && svc != null) {
            val ok = runCatching {
                (aaServiceIface ?: svc.javaClass).getMethod("requestUi", Int::class.javaPrimitiveType).invoke(svc, 4)
            }.isSuccess
            if (ok) return
        }
        val app = aaManager
        if (aaServiceConnected && app != null) {
            runCatching {
                (aaMgrIface ?: app.javaClass).getMethod("requestUi", Int::class.javaPrimitiveType).invoke(app, 4)
            }.onFailure { Log.w(TAG, "requestNaviUi failed: ${unwrapError(it)}") }
        }
    }

    private fun syncAndroidAutoState() {
        if (!aaServiceConnected) return
        val mgr = aaManager ?: return
        val iface = aaMgrIface ?: mgr.javaClass
        runCatching {
            val sessionStatus = (iface.getMethod("getAndroidAutoSessionStatus").invoke(mgr) as? Number)?.toInt() ?: 0
            val musicStatus = (iface.getMethod("getMusicStatus").invoke(mgr) as? Number)?.toInt() ?: 0
            val progressSec = (iface.getMethod("getMediaProgress").invoke(mgr) as? Number)?.toLong() ?: 0L
            val metadata = iface.getMethod("getMediaMetadata").invoke(mgr)
            // ThunderSoft getAndroidAutoAppStatus(0) returns mIsUseNativeNavigation:
            // true = BYD native navigation has focus, false = Android Auto navigation has focus.
            val useNativeNav = runCatching {
                iface.getMethod("getAndroidAutoAppStatus", Int::class.javaPrimitiveType).invoke(mgr, 0) as? Boolean ?: true
            }.getOrDefault(true)
            val videoFocus = runCatching {
                (iface.getMethod("getVideoFocusStatus").invoke(mgr) as? Number)?.toInt() ?: 0
            }.getOrDefault(0)

            val prevConnected = aaConnected
            aaConnected = (sessionStatus == 4) || (musicStatus == 1)
            if (prevConnected != aaConnected) {
                Log.w(TAG, "AA sync: sessionStatus=$sessionStatus musicStatus=$musicStatus useNativeNav=$useNativeNav -> aaConnected=$aaConnected")
            }
            aaPlaying = (musicStatus == 1)
            aaUseNativeNav = useNativeNav
            _aaVideoFocus.value = videoFocus
            if (!aaConnected) {
                clearAaMediaState()
                resetAaNavState()
            } else {
                recomputeAaNavActive()
                if (progressSec >= 0L) {
                    aaPositionMs = progressSec * 1000L
                    aaPositionRealtime = SystemClock.elapsedRealtime()
                }
                if (metadata != null) {
                    parseAaMetadata(metadata)
                }
            }
        }
    }

    private fun parseAaMetadata(md: Any?) {
        if (md == null) return
        runCatching {
            val cls = md.javaClass
            val song = (runCatching { cls.getMethod("getSong").invoke(md) as? String }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mSong").apply { isAccessible = true }.get(md) as? String }.getOrNull())
                ?.trim().orEmpty()
            val artist = (runCatching { cls.getMethod("getArtist").invoke(md) as? String }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mArtist").apply { isAccessible = true }.get(md) as? String }.getOrNull())
                ?.trim().orEmpty()
            val album = (runCatching { cls.getMethod("getAlbum").invoke(md) as? String }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mAlbum").apply { isAccessible = true }.get(md) as? String }.getOrNull())
                ?.trim().orEmpty()
            val durationSec = (runCatching { cls.getMethod("getDurationSeconds").invoke(md) as? Number }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mDurationSeconds").apply { isAccessible = true }.get(md) as? Number }.getOrNull())
                ?.toLong() ?: 0L
            val artBytes = runCatching { cls.getMethod("getAlbumArt").invoke(md) as? ByteArray }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mAlbumArt").apply { isAccessible = true }.get(md) as? ByteArray }.getOrNull()
            val artPath = runCatching { cls.getMethod("getArtworkPath").invoke(md) as? String }.getOrNull()
                ?: runCatching { cls.getDeclaredField("mArtworkPath").apply { isAccessible = true }.get(md) as? String }.getOrNull()

            if (song.isNotEmpty()) {
                aaTitle = song
            }
            aaArtist = when {
                artist.isNotEmpty() -> artist
                album.isNotEmpty() -> album
                else -> aaArtist
            }
            if (durationSec > 0L) {
                aaDurationMs = durationSec * 1000L
            }

            val newArtKey = "$song|$artist|${artBytes?.size ?: 0}|${artPath.orEmpty()}"
            if (newArtKey != aaArtKey) {
                aaArtKey = newArtKey
                aaArt = when {
                    artBytes != null && artBytes.isNotEmpty() ->
                        runCatching { BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) }.getOrNull()
                    !artPath.isNullOrBlank() ->
                        runCatching { BitmapFactory.decodeFile(artPath) }.getOrNull()
                    else -> null
                }
            }
        }.onFailure { Log.w(TAG, "parseAaMetadata error: ${unwrapError(it)}") }
    }

    private fun initCarPlay() {
        val cl = getTsClassLoader("com.ts.carplay") ?: return
        val providerClass = cl.loadClass("com.ts.car.core.ServiceProvider")
        val connListenerClass = cl.loadClass("ts.car.service.IServiceConnectionListener")
        val cpMgrClass = cl.loadClass("ts.car.service.carplay.ICarPlayAppManager")
        val stateListenerClass = cl.loadClass("ts.car.service.carplay.ICarPlayStateListener")
        val nowPlayingListenerClass = cl.loadClass("ts.car.service.carplay.INowPlayingListener")

        val mgr = providerClass.getMethod("get", Class::class.java, Context::class.java)
            .invoke(null, cpMgrClass, ctx.applicationContext) ?: return
        cpManager = mgr
        cpMgrIface = cpMgrClass
        val gen = ++cpGen // listeners from an earlier init go quiet

        val npProxy = safeProxy(cl, nowPlayingListenerClass, { gen == cpGen }) { methodName, args ->
            if (methodName == "onNowPlayingInfoChanged" || methodName == "onArtworkUpdate") {
                val info = args?.getOrNull(0)
                main.post {
                    parseCpNowPlaying(info)
                    update()
                }
            }
        }
        cpNowPlayingProxy = npProxy

        fun registerNowPlaying() {
            val activeMgr = cpManager ?: return
            runCatching {
                cpMgrClass.getMethod("registerNowPlayingListener", nowPlayingListenerClass).invoke(activeMgr, npProxy)
            }.onFailure { Log.w(TAG, "registerNowPlayingListener failed: ${unwrapError(it)}") }
        }

        // ICarPlayStateListener on this firmware (ts-platform-library.jar): onSessionStateChanged(int),
        // onSessionConnectStateChanged(int, boolean, String), onCarPlaySessionConnectStateChanged(int, boolean,
        // String, boolean), onCarPlayMediaFocusStateChanged(boolean), onAppStateChanged(int, boolean, int).
        // SessionStatus: 1 = activating, 2 = activated, 3 = deactivating, 4 = deactivated, 5 = connect failed.
        val stateProxy = safeProxy(cl, stateListenerClass, { gen == cpGen }) { methodName, args ->
            when (methodName) {
                "onSessionStateChanged", "onSessionConnectStateChanged", "onCarPlaySessionConnectStateChanged" -> {
                    val status = (args?.getOrNull(0) as? Number)?.toInt()
                    main.post {
                        val was = cpConnected
                        if (methodName == "onSessionStateChanged") cpConnected = (status == 1 || status == 2)
                        syncCarPlayState()
                        Log.w(TAG, "CarPlay $methodName(${args?.joinToString()}) -> cpConnected=$cpConnected")
                        if (cpConnected && !was) registerNowPlaying()
                        update()
                    }
                }
                "onCarPlayMediaFocusStateChanged" -> {
                    val focus = args?.getOrNull(0) as? Boolean ?: false
                    main.post {
                        cpMediaFocus = focus
                        Log.w(TAG, "CarPlay media focus=$focus")
                        update()
                    }
                }
            }
        }

        fun registerCpCallbacks() {
            val activeMgr = cpManager ?: return
            runCatching {
                cpMgrClass.getMethod("registerCarPlayStateListener", stateListenerClass)
                    .invoke(activeMgr, stateProxy)
            }.onFailure { Log.w(TAG, "registerCarPlayStateListener failed: ${unwrapError(it)}") }
            // Always listen for now-playing: the session usually starts long after this service connects.
            registerNowPlaying()
            syncCarPlayState()
            Log.w(TAG, "CarPlay bridge connected, cpConnected=$cpConnected")
        }

        val connProxy = safeProxy(cl, connListenerClass, { gen == cpGen }) { methodName, args ->
            when (methodName) {
                "onServiceConnected" -> {
                    val connectedService = args?.getOrNull(0)
                    if (connectedService != null && cpMgrClass.isInstance(connectedService)) {
                        cpManager = connectedService
                    }
                    cpServiceConnected = true
                    main.post {
                        registerCpCallbacks()
                        update()
                    }
                }
                "onServiceDisconnected" -> {
                    cpServiceConnected = false
                    main.post {
                        cpConnected = false
                        clearCpState()
                        update()
                    }
                    main.postDelayed({
                        if (!cpServiceConnected) {
                            runCatching { initCarPlay() }
                        }
                    }, 2000L)
                }
            }
        }

        swapConnListener(cpMgrClass, connListenerClass, mgr, cpConnProxy, connProxy)
        cpConnProxy = connProxy
    }

    private fun syncCarPlayState() {
        if (!cpServiceConnected) return
        val mgr = cpManager ?: return
        val iface = cpMgrIface ?: mgr.javaClass
        runCatching {
            val status = (iface.getMethod("getCarPlaySessionStatus").invoke(mgr) as? Number)?.toInt() ?: 0
            val was = cpConnected
            cpConnected = (status == 1 || status == 2)
            if (was != cpConnected) Log.w(TAG, "CarPlay session status=$status cpConnected=$cpConnected")
            if (!cpConnected) clearCpState()
        }.onFailure { Log.w(TAG, "getCarPlaySessionStatus failed: ${unwrapError(it)}") }
    }

    /** Forgets the last CarPlay track, so a stale title can't keep the CarPlay card up after the phone leaves. */
    private fun clearCpState() {
        cpPlaying = false
        cpMediaFocus = false
        cpTitle = ""
        cpArtist = ""
        cpArt = null
        cpArtKey = ""
        cpDurationMs = 0L
        cpPositionMs = 0L
        cpPositionRealtime = 0L
    }

    private fun parseCpNowPlaying(info: Any?) {
        if (info == null) return
        runCatching {
            val cls = info.javaClass
            val title = (runCatching { cls.getMethod("getMediaItemTitle").invoke(info) as? String }.getOrNull())
                ?.trim().orEmpty()
            val artist = (runCatching { cls.getMethod("getMediaItemArtist").invoke(info) as? String }.getOrNull())
                ?.trim().orEmpty()
            val album = (runCatching { cls.getMethod("getAlbumTitle").invoke(info) as? String }.getOrNull())
                ?.trim().orEmpty()
            val durationMs = (runCatching { cls.getMethod("getMediaItemPlaybackDurationMs").invoke(info) as? Number }.getOrNull())
                ?.toLong() ?: 0L
            val elapsedMs = (runCatching { cls.getMethod("getPlaybackElapsedTimeMs").invoke(info) as? Number }.getOrNull())
                ?.toLong() ?: 0L
            val status = (runCatching { cls.getMethod("getPlaybackStatus").invoke(info) as? Number }.getOrNull())
                ?.toInt() ?: 0
            val artBytes = runCatching { cls.getMethod("getArtworkData").invoke(info) as? ByteArray }.getOrNull()
            val artPath = runCatching { cls.getMethod("getArtworkPath").invoke(info) as? String }.getOrNull()

            if (title.isNotEmpty()) cpTitle = title
            cpArtist = when {
                artist.isNotEmpty() -> artist
                album.isNotEmpty() -> album
                else -> cpArtist
            }
            if (durationMs > 0L) cpDurationMs = durationMs
            if (elapsedMs >= 0L) {
                cpPositionMs = elapsedMs
                cpPositionRealtime = SystemClock.elapsedRealtime()
            }
            cpPlaying = (status == 1)
            if (cpPlaying) cpConnected = true

            val newArtKey = "$title|$artist|${artBytes?.size ?: 0}|${artPath.orEmpty()}"
            if (newArtKey != cpArtKey) {
                cpArtKey = newArtKey
                cpArt = when {
                    artBytes != null && artBytes.isNotEmpty() ->
                        runCatching { BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) }.getOrNull()
                    !artPath.isNullOrBlank() ->
                        runCatching { BitmapFactory.decodeFile(artPath) }.getOrNull()
                    else -> cpArt
                }
            }
        }.onFailure { Log.w(TAG, "parseCpNowPlaying error: ${it.message}") }
    }

    private fun pollProjectionState() {
        syncAndroidAutoState()
        syncCarPlayState()
    }

    private fun canReadMediaSessions(): Boolean {
        if (!NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) return false
        return try {
            msm.getActiveSessions(component)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Tries to write enabled_notification_listeners directly if WRITE_SECURE_SETTINGS is granted.
     */
    private fun tryEnableDirectly(): Boolean {
        return try {
            val flat = component.flattenToString()
            val cr = ctx.contentResolver
            val current = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: ""
            if (!current.split(":").contains(flat)) {
                val updated = if (current.isEmpty()) flat else "$current:$flat"
                Settings.Secure.putString(cr, "enabled_notification_listeners", updated)
            }
            canReadMediaSessions()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Grants notification listener access through LocalAdb (`cmd notification allow_listener`), which is
     * required on Android 11+ because editing Settings.Secure alone does not bind the listener service.
     */
    suspend fun grantAccessViaAdb(): Boolean {
        val a = adb ?: return false
        val listener = com.sharkhome.launcher.system.LocalAdb.q(component.flattenToString())
        val pkg = com.sharkhome.launcher.system.LocalAdb.q(ctx.packageName)
        val ok = kotlinx.coroutines.withTimeoutOrNull(5000L) {
            runCatching {
                if (a.state.value != com.sharkhome.launcher.system.LocalAdb.State.CONNECTED && !a.connect()) {
                    return@runCatching false
                }
                a.shell("cmd notification allow_listener $listener")
                a.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                delay(250L)
                true
            }.getOrDefault(false)
        } ?: false
        if (!ok) return false
        tryEnableDirectly()
        val granted = canReadMediaSessions()
        if (granted) {
            kotlinx.coroutines.withContext(Dispatchers.Main) { start() }
        }
        return granted
    }

    /**
     * Enables media-session access in-app: tries direct write first, then Local ADB (`cmd notification allow_listener`),
     * then falls back to Settings if Local ADB is unavailable.
     */
    suspend fun enableAccessWithAdb(): Boolean {
        if (tryEnableDirectly()) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                start()
                android.widget.Toast.makeText(ctx, "Media access enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (grantAccessViaAdb()) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, "Media access enabled via Local ADB", android.widget.Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return kotlinx.coroutines.withContext(Dispatchers.Main) {
            openAccessSettings()
        }
    }

    /**
     * Opens the screen where media access is granted. BYD head units have no notification-access
     * settings page, so this first tries direct activation (if WRITE_SECURE_SETTINGS is held),
     * then the usual Android settings screens, and finally falls back to the adb explanation.
     */
    fun openAccessSettings(): Boolean {
        if (tryEnableDirectly()) {
            start()
            android.widget.Toast.makeText(ctx, "Media access enabled", android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        val pm = ctx.packageManager
        val candidates = listOf(
            Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component.flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")),
        )
        for (i in candidates) {
            if (i.resolveActivity(pm) == null) continue
            val ok = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return true
        }
        android.widget.Toast.makeText(
            ctx,
            "Turn on USB Debugging in Quick Settings (or accept the ADB prompt), then tap Allow media access again.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        return false
    }

    private fun pick(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
            ctrlMeta = runCatching { next?.metadata }.getOrNull()
            ctrlState = runCatching { next?.playbackState }.getOrNull()
        }
        update()
    }

    private fun buildAaNowPlaying(): NowPlaying? {
        if (!aaConnected && !aaPlaying && aaTitle.isBlank()) return null
        val pos = if (aaPlaying && aaPositionRealtime > 0L) {
            aaPositionMs + (SystemClock.elapsedRealtime() - aaPositionRealtime).coerceAtLeast(0L)
        } else {
            aaPositionMs
        }
        return NowPlaying(
            title = aaTitle.ifBlank { "Android Auto" },
            artist = aaArtist.ifBlank { if (aaPlaying) "Playing via Android Auto" else "Android Auto connected" },
            art = aaArt,
            durationMs = aaDurationMs.coerceAtLeast(0L),
            positionMs = if (aaDurationMs > 0L) pos.coerceIn(0L, aaDurationMs) else pos.coerceAtLeast(0L),
            playing = aaPlaying,
            pkg = AA_PKG,
        )
    }

    private fun buildCpNowPlaying(): NowPlaying? {
        if (!cpConnected && !cpPlaying) return null
        val pos = if (cpPlaying && cpPositionRealtime > 0L) {
            cpPositionMs + (SystemClock.elapsedRealtime() - cpPositionRealtime).coerceAtLeast(0L)
        } else {
            cpPositionMs
        }
        return NowPlaying(
            title = cpTitle.ifBlank { "Apple CarPlay" },
            artist = cpArtist.ifBlank { if (cpPlaying) "Playing via CarPlay" else "CarPlay connected" },
            art = cpArt,
            durationMs = cpDurationMs.coerceAtLeast(0L),
            positionMs = if (cpDurationMs > 0L) pos.coerceIn(0L, cpDurationMs) else pos.coerceAtLeast(0L),
            playing = cpPlaying,
            pkg = CP_PKG,
        )
    }

    private fun buildControllerNowPlaying(): NowPlaying? {
        val c = controller ?: return null
        val md = ctrlMeta
        val st = ctrlState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val position = when {
            st == null -> 0L
            playing -> st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            else -> st.position
        }
        return NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = position.coerceAtLeast(0L),
            playing = playing,
            pkg = c.packageName,
        )
    }

    private fun update() {
        _carPlayConnected.value = cpConnected
        val aa = buildAaNowPlaying()
        val cp = buildCpNowPlaying()
        val mc = buildControllerNowPlaying()

        _nowPlaying.value = when {
            // 1. Any source actively playing takes priority
            aa?.playing == true -> aa
            cp?.playing == true -> cp
            mc?.playing == true -> mc
            // 1b. CarPlay holds the car's media focus (e.g. paused on the phone): show it, not an idle head-unit app
            cp != null && cpMediaFocus -> cp
            // 2. Connected projection session with track metadata
            aa != null && aaTitle.isNotBlank() -> aa
            cp != null && cpTitle.isNotBlank() -> cp
            // 3. Standard MediaSession with real metadata
            mc != null && mc.title != "Nothing playing" -> mc
            // 4. Connected projection session even before first track metadata arrives
            aa != null -> aa
            cp != null -> cp
            // 5. Fallback to idle MediaController or null
            else -> mc
        }
    }

    fun playPause() {
        val currentPkg = _nowPlaying.value?.pkg
        when (currentPkg) {
            AA_PKG -> {
                val mgr = aaManager ?: return
                val iface = aaMgrIface ?: mgr.javaClass
                runCatching {
                    if (aaPlaying) {
                        iface.getMethod("pause").invoke(mgr)
                        aaPlaying = false
                    } else {
                        iface.getMethod("play").invoke(mgr)
                        aaPlaying = true
                    }
                    update()
                }.onFailure { Log.w(TAG, "AA playPause failed: ${unwrapError(it)}") }
            }
            CP_PKG -> {
                sendCarPlayKey(64)
                cpPlaying = !cpPlaying
                update()
            }
            else -> {
                val c = controller ?: return
                val wasPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING
                val key = if (wasPlaying) android.view.KeyEvent.KEYCODE_MEDIA_PAUSE else android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                if (c.packageName in buttonOnlyPkgs && sendMediaButton(c.packageName, key)) return
                if (wasPlaying) c.transportControls.pause() else c.transportControls.play()
                // Some apps (Spotify on this head unit) ignore controllers they don't recognise. If nothing
                // changed, press the app's own media button instead and remember that for next time.
                // A second tap inside the 700 ms window toggles back, which would look like "ignored": in that case
                // drop the check instead of pressing the button a third time.
                val pending = ppCheck
                if (pending != null) {
                    main.removeCallbacks(pending)
                    ppCheck = null
                    return
                }
                val check = Runnable {
                    ppCheck = null
                    val now = controller
                    if (now?.packageName == c.packageName &&
                        (now.playbackState?.state == PlaybackState.STATE_PLAYING) == wasPlaying &&
                        sendMediaButton(c.packageName, key)
                    ) {
                        Log.w(TAG, "${c.packageName} ignored the transport command; used its media button")
                        buttonOnlyPkgs.add(c.packageName)
                    }
                }
                ppCheck = check
                main.postDelayed(check, 700L)
            }
        }
    }

    /** Apps that refuse commands from a controller they don't recognise (found at run time; Spotify is known). */
    private val buttonOnlyPkgs = mutableSetOf("com.spotify.music")

    /** Set by the app container: the head unit's own adb, used to press media keys the way the steering wheel does. */
    @Volatile var adb: com.sharkhome.launcher.system.LocalAdb? = null

    /**
     * Presses a media key through the head unit's own adb shell (`input keyevent`). Android then delivers it as a
     * system key, which Spotify accepts (verified 13 Nov: keyevent 126 started playback where
     * transportControls.play() logged "Failed to validate package"). Returns false when adb isn't available.
     */
    private fun sendMediaButton(pkg: String, keyCode: Int): Boolean {
        // Needs no adb: AudioManager hands the key to the current media-button session, the same place the steering
        // wheel buttons go. The adb route (input keyevent) is the fallback.
        val viaAudio = runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val t0 = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(android.view.KeyEvent(t0, t0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(t0, SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, keyCode, 0))
            true
        }.getOrDefault(false)
        if (viaAudio) return true
        val a = adb ?: return false
        scope.launch {
            runCatching { a.shell("input keyevent $keyCode") }
                .onFailure { Log.w(TAG, "media key $keyCode for $pkg failed: ${unwrapError(it)}") }
        }
        return true
    }

    private fun skip(next: Boolean) {
        val c = controller ?: return
        val key = if (next) android.view.KeyEvent.KEYCODE_MEDIA_NEXT else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        if (c.packageName in buttonOnlyPkgs && sendMediaButton(c.packageName, key)) return
        if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
    }

    fun next() {
        when (_nowPlaying.value?.pkg) {
            AA_PKG -> {
                val mgr = aaManager ?: return
                val iface = aaMgrIface ?: mgr.javaClass
                runCatching { iface.getMethod("next").invoke(mgr) }
                    .onFailure { Log.w(TAG, "AA next failed: ${unwrapError(it)}") }
            }
            CP_PKG -> sendCarPlayKey(4)
            else -> skip(next = true)
        }
    }

    fun previous() {
        when (_nowPlaying.value?.pkg) {
            AA_PKG -> {
                val mgr = aaManager ?: return
                val iface = aaMgrIface ?: mgr.javaClass
                runCatching { iface.getMethod("previous").invoke(mgr) }
                    .onFailure { Log.w(TAG, "AA previous failed: ${unwrapError(it)}") }
            }
            CP_PKG -> sendCarPlayKey(8)
            else -> skip(next = false)
        }
    }

    private fun sendCarPlayKey(cmd: Int) {
        val mgr = cpManager ?: return
        val iface = cpMgrIface ?: mgr.javaClass
        runCatching {
            val m = iface.getMethod("sendRemoteMediaPlayback", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(mgr, cmd, 0)
            m.invoke(mgr, cmd, 1)
        }.onFailure { Log.w(TAG, "CarPlay sendRemoteMediaPlayback($cmd) failed: ${unwrapError(it)}") }
    }
}
