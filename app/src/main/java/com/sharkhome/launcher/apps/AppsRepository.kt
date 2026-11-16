package com.sharkhome.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val activity: String, val label: String, val icon: ImageBitmap?)

class AppsRepository(private val ctx: Context, private val prefs: Prefs) {

    private companion object {
        /** Activity names that look like a launcher's all-apps screen. */
        val APP_LIST = Regex("(?i)allapp|applist|appsactivity|appdrawer|appgrid|drawer|myapp")
    }

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                AppEntry(ri.activityInfo.packageName, ri.activityInfo.name, ri.loadLabel(pm).toString(), icon)
            }
            .distinctBy { it.pkg + "/" + it.activity }
            .sortedBy { it.label.lowercase() }
        _apps.value = list
        if (!prefs.dockSeeded && list.isNotEmpty()) {
            prefs.dockSeeded = true
            if (prefs.dock.isEmpty())
            prefs.dock = list.take(5).map { it.pkg }
        }
        Unit
    }

    fun dock(all: List<AppEntry>, dockPkgs: List<String>): List<AppEntry> =
        dockPkgs.mapNotNull { pkg -> all.firstOrNull { it.pkg == pkg } }

    fun togglePin(pkg: String): Boolean {
        val d = prefs.dock
        val pinned = !d.contains(pkg)
        prefs.dock = if (pinned) d + pkg else d - pkg
        return pinned
    }

    fun launch(entry: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(entry.pkg, entry.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(i, entry.label)
    }

    fun launchPackage(pkg: String, label: String = pkg): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Toast.makeText(ctx, "$label isn't installed", Toast.LENGTH_SHORT).show()
            return false
        }
        return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), label)
    }

    /**
     * Opens BYD's own app screen: the head unit's stock home launcher (any other HOME app, system ones first).
     * SharkOS-CE is the Home app, so the car's own launcher is otherwise out of reach.
     */
    fun openBydApps(): Boolean {
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val stock = pm.queryIntentActivities(home, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .sortedByDescending { (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            .firstOrNull()
        if (stock == null) {
            Toast.makeText(ctx, "Couldn't find BYD's app screen", Toast.LENGTH_SHORT).show()
            return false
        }
        val stockPkg = stock.activityInfo.packageName
        // Prefer the launcher's own app list (the grid BYD's dock button shows) over its home screen.
        val appList = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(stockPkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities
        }.getOrNull()?.firstOrNull { a ->
            a.exported && APP_LIST.containsMatchIn(a.name.substringAfterLast('.'))
        }
        if (appList != null) {
            val list = Intent(Intent.ACTION_MAIN).setClassName(stockPkg, appList.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(list) }.isSuccess) return true
        }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(stockPkg, stock.activityInfo.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(i, "BYD Apps")
    }

    /** Moves a favourite one place left (-1) or right (+1). */
    /** Launches an app in adjacent split screen mode so SharkOS-CE stays on the left. */
    fun launchAdjacent(pkg: String, label: String = pkg): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )
        return try {
            val options = android.app.ActivityOptions.makeBasic()
            ctx.startActivity(i, options.toBundle())
            true
        } catch (_: Throwable) {
            start(i, label)
        }
    }

    private fun start(i: Intent, label: String): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (t: Throwable) {
        Toast.makeText(ctx, "Couldn't open $label", Toast.LENGTH_SHORT).show()
        false
    }
}
