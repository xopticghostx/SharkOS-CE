package com.sharkhome.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.sharkhome.launcher.apps.AppsRepository
import com.sharkhome.launcher.car.BydAutoBackend
import com.sharkhome.launcher.car.CarAdapterBackend
import com.sharkhome.launcher.car.CarCodes
import com.sharkhome.launcher.car.CarExplorer
import com.sharkhome.launcher.car.StartupMemory
import com.sharkhome.launcher.car.VehicleRepository
import com.sharkhome.launcher.media.AudioRepository
import com.sharkhome.launcher.media.MediaRepository
import com.sharkhome.launcher.sensors.MotionRepository
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SharkOsCeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (container.prefs.autoStart) StartupService.start(this)
        container.updater.checkIfStale()
    }
}

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val codes = CarCodes.load(app)
    val carAdapter = CarAdapterBackend(app, scope)
    val bydAuto = BydAutoBackend(app)
    val vehicle = VehicleRepository(app, scope, codes, carAdapter, bydAuto, prefs)
    val media = MediaRepository(app, scope)
    val audio = AudioRepository(app, prefs, bydAuto)
    val motion = MotionRepository(app, prefs)
    val apps = AppsRepository(app, prefs)
    val nav = NavLauncher(app, prefs)
    val weather = com.sharkhome.launcher.weather.WeatherRepository(scope, motion)
    val explorer = CarExplorer(app, scope, carAdapter, bydAuto)
    val startupMemory = StartupMemory(scope, vehicle, audio, prefs)
    val adb = com.sharkhome.launcher.system.LocalAdb(app).also { audio.adb = it; media.adb = it }
    val updater = com.sharkhome.launcher.system.AppUpdater(app, scope, prefs, adb)
    val dashcam = com.sharkhome.launcher.dashcam.DashCamEngine(app, scope, adb, vehicle)

    init {
        // Keeps the cluster (second) screen widgets fed with live car data, whichever screen is open.
        // Installs/updates the bundled wake helper a little after start-up (silent, over the head unit's own adb).
        scope.launch { kotlinx.coroutines.delay(25_000); updater.ensureHelperInstalled() }
        com.sharkhome.launcher.car.TestLabBridge.configure(app, scope) {
            val r = vehicle.readings.value
            val gear = r["vehicle.gear"]?.raw?.let { vehicle.labelFor("vehicle.gear", it) }
            com.sharkhome.launcher.car.ClusterData.from(r, gear, motion.motion.value)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as SharkOsCeApp).container

/** Hands navigation off to the map app the user picked (or any app that handles geo: links). */
class NavLauncher(private val ctx: Context, private val prefs: Prefs) {

    /** Opens the chosen map app, else Google Maps, Waze, BYD's navigation, or anything that handles geo: links. */
    fun openNavApp() {
        val pm = ctx.packageManager
        val chosen = prefs.navPackage?.let { pm.getLaunchIntentForPackage(it) }
        val known = KNOWN_NAV.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        val byLabel = run {
            val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            all.firstOrNull { it.loadLabel(pm).toString().equals("Navigation", ignoreCase = true) }
                ?.let { Intent(Intent.ACTION_MAIN).setClassName(it.activityInfo.packageName, it.activityInfo.name) }
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).takeIf { it.resolveActivity(pm) != null }
        val target = chosen ?: known ?: byLabel ?: geo
        if (target == null) toast("No navigation app found — pick one in Display & themes") else start(target)
    }

    /**
     * Opens BYD's built-in Navigation (com.neusoft.na.navigation) the same way its own home-screen widget does:
     * com.neusoft.na.mots.MainActivity with an int extra "type" (-1 = map, 1 = Home, 2 = Work; read from BydAppWidget).
     * Home and Work are the places saved in BYD Navigation.
     */
    fun openBydNav(type: Int = BYD_MAP, split: Boolean = false): Boolean {
        val i = Intent().setClassName(BYD_NAV_PKG, BYD_NAV_ACTIVITY).putExtra("type", type)
        return launch(i, split)
    }

    /** Opens the Android Auto projection screen (ThunderSoft). */
    fun openAndroidAuto(split: Boolean = false): Boolean =
        launch(Intent().setClassName("com.ts.androidauto.app", "com.ts.androidauto.app.display.AapActivity"), split)

    /** Starts [i] full screen, or in split screen next to SharkOS-CE. Returns false if nothing could be started. */
    private fun launch(i: Intent, split: Boolean): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (split) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = if (split) splitOptions() else null
        return try {
            ctx.startActivity(i, options)
            true
        } catch (t: Throwable) {
            android.util.Log.e("SharkNav", "launch ${i.component} split=$split failed", t)
            false
        }
    }

    /** ActivityOptions asking for the split-screen primary window (hidden API; ignored if the head unit refuses). */
    private fun splitOptions(): android.os.Bundle = android.app.ActivityOptions.makeBasic().also { opts ->
        runCatching {
            android.app.ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
        }
    }.toBundle()

    private fun start(i: Intent) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            toast("No navigation app found — pick one in Vehicle › Display")
        }
    }

    companion object {
        private val KNOWN_NAV = listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "com.sygic.aura")
        const val BYD_NAV_PKG = "com.neusoft.na.navigation"
        const val BYD_NAV_ACTIVITY = "com.neusoft.na.mots.MainActivity"
        const val BYD_MAP = -1
        const val BYD_HOME = 1
        const val BYD_WORK = 2
        private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
