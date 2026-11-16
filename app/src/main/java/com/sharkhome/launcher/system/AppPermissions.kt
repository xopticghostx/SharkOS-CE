package com.sharkhome.launcher.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.media.session.MediaSessionManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.sharkhome.launcher.media.MediaListenerService
import com.sharkhome.launcher.system.LocalAdb.Companion.q
import kotlinx.coroutines.delay

data class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?)

data class RuntimePerm(val name: String, val label: String, val granted: Boolean)

/** A special-access switch BYD hides (display over apps, battery, ...), changed through adb. */
data class SpecialAccess(val key: String, val title: String, val on: Boolean)

data class SelfGrantResult(val ok: Boolean, val message: String)

/** Reads apps and their permissions locally; changes them through [LocalAdb] (pm grant / appops / deviceidle). */
class AppPermissions(private val ctx: Context, private val adb: LocalAdb) {

    private val pm: PackageManager get() = ctx.packageManager
    private val selfListenerFlat: String
        get() = ComponentName(ctx, MediaListenerService::class.java).flattenToString()

    /** User-installed apps (and updated system apps), with SharkOS-CE pinned first for easy permission management. */
    fun installedApps(): List<InstalledApp> =
        pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .map { ai ->
                InstalledApp(
                    ai.packageName,
                    ai.loadLabel(pm).toString(),
                    runCatching { pm.getApplicationIcon(ai).toBitmap(64, 64).asImageBitmap() }.getOrNull(),
                )
            }
            .sortedWith(compareBy<InstalledApp> { it.pkg != ctx.packageName }.thenBy { it.label.lowercase() })

    private fun info(pkg: String): PackageInfo? = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()

    /** The app's runtime ("dangerous") permissions: the ones a Settings permission screen would show. */
    fun runtimePerms(pkg: String): List<RuntimePerm> {
        val pi = info(pkg) ?: return emptyList()
        val names = pi.requestedPermissions ?: return emptyList()
        val flags = pi.requestedPermissionsFlags ?: IntArray(names.size)
        return names.indices.mapNotNull { i ->
            val p = runCatching { pm.getPermissionInfo(names[i], 0) }.getOrNull() ?: return@mapNotNull null
            if (p.protection != PermissionInfo.PROTECTION_DANGEROUS) return@mapNotNull null
            val label = p.loadLabel(pm).toString().replaceFirstChar { it.uppercase() }
            RuntimePerm(names[i], label, flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
        }.distinctBy { it.name }.sortedBy { it.label }
    }

    private fun requests(pkg: String, perm: String) = info(pkg)?.requestedPermissions?.contains(perm) == true

    private fun isSelfMediaAccessWorking(): Boolean {
        if (!NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) return false
        return runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java))
            true
        }.getOrDefault(false)
    }

    /** Special access that applies to [pkg], with the current state read through adb. */
    suspend fun specialAccess(pkg: String): List<SpecialAccess> {
        val out = mutableListOf<SpecialAccess>()
        if (pkg == ctx.packageName) {
            out += SpecialAccess(MEDIA_ACCESS, "Media access (Notification listener)", isSelfMediaAccessWorking())
            val secureGranted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            out += SpecialAccess(WRITE_SECURE_SETTINGS, "Modify secure settings (1-tap USB debugging)", secureGranted)
        }
        for ((op, perm, title) in APP_OPS) {
            if (!requests(pkg, perm)) continue
            val r = adb.shell("appops get ${q(pkg)} $op")
            out += SpecialAccess(op, title, r.output.contains(": allow"))
        }
        val idle = adb.shell("dumpsys deviceidle whitelist").output
        out += SpecialAccess(BATTERY, "Ignore battery optimisation (keeps running in the background)", idle.contains(",$pkg,"))
        return out
    }

    suspend fun setPerm(pkg: String, perm: String, grant: Boolean): LocalAdb.Result =
        adb.shell("pm ${if (grant) "grant" else "revoke"} ${q(pkg)} ${q(perm)}")

    suspend fun setSpecial(pkg: String, key: String, on: Boolean): LocalAdb.Result = when (key) {
        BATTERY -> adb.shell("cmd deviceidle whitelist ${if (on) "+" else "-"}$pkg")
        MEDIA_ACCESS -> {
            val cmd = if (on) "allow_listener" else "disallow_listener"
            adb.shell("cmd notification $cmd ${q(selfListenerFlat)}").also { delay(200L) }
        }
        WRITE_SECURE_SETTINGS -> setPerm(pkg, Manifest.permission.WRITE_SECURE_SETTINGS, on)
        else -> adb.shell("appops set ${q(pkg)} $key ${if (on) "allow" else "default"}")
    }

    /**
     * Grants SharkOS-CE all permissions that BYD's settings hide using Local ADB:
     * runtime dangerous permissions, WRITE_SECURE_SETTINGS, MediaListenerService notification access,
     * overlay / install appops, car permissions, and battery whitelist (+ wake helper if installed).
     */
    suspend fun grantSelfAllPermissions(): SelfGrantResult = try {
        grantSelfAllPermissionsOrThrow()
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        // adb dropped part-way (USB debugging switched off, prompt dismissed): report it instead of crashing.
        SelfGrantResult(false, "Couldn't finish: ${t.message ?: t.javaClass.simpleName}. Check USB debugging and try again.")
    }

    private suspend fun grantSelfAllPermissionsOrThrow(): SelfGrantResult {
        val connected = adb.state.value == LocalAdb.State.CONNECTED || adb.connect()
        if (!connected) {
            return SelfGrantResult(false, adb.message.value)
        }
        val pkg = ctx.packageName
        var actions = 0
        val warnings = mutableListOf<String>()

        for (p in runtimePerms(pkg).filter { !it.granted }) {
            val r = setPerm(pkg, p.name, true)
            if (r.ok) actions++ else warnings += p.name.substringAfterLast('.')
        }

        val secureRes = setPerm(pkg, Manifest.permission.WRITE_SECURE_SETTINGS, true)
        if (secureRes.ok) actions++ else warnings += "WRITE_SECURE_SETTINGS"

        // Grant car/projection permissions that may require runtime grant on some Android Automotive builds.
        val extraCarPerms = info(pkg)?.requestedPermissions.orEmpty().filter {
            it.startsWith("android.car.permission.") || it.startsWith("car.permission.")
        }
        for (perm in extraCarPerms) {
            val r = setPerm(pkg, perm, true)
            if (r.ok) actions++
            // "not a changeable permission type" means already granted at install as normal/signature.
        }

        val mediaRes = setSpecial(pkg, MEDIA_ACCESS, true)
        if (mediaRes.ok) actions++ else warnings += "media listener"

        for (op in listOf("SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES")) {
            val r = setSpecial(pkg, op, true)
            if (r.ok) actions++ else warnings += op
        }

        val idleRes = setSpecial(pkg, BATTERY, true)
        if (idleRes.ok) actions++ else warnings += "battery whitelist"

        // Also keep the wake helper whitelisted from battery optimisation if installed.
        runCatching { adb.shell("cmd deviceidle whitelist +tunein.player") }

        delay(250L)
        val mediaOk = isSelfMediaAccessWorking()
        val secureOk = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val missingRuntime = runtimePerms(pkg).count { !it.granted }

        val summary = buildString {
            append("Granted SharkOS-CE permissions via Local ADB")
            append(" · Media access: ${if (mediaOk) "ON" else "OFF"}")
            append(" · Secure settings: ${if (secureOk) "ON" else "OFF"}")
            if (missingRuntime > 0) append(" · $missingRuntime runtime pending")
            if (warnings.isNotEmpty() && !mediaOk) append(" (${warnings.joinToString(", ")})")
        }
        return SelfGrantResult(mediaOk && secureOk && missingRuntime == 0, summary)
    }

    companion object {
        const val BATTERY = "BATTERY"
        const val MEDIA_ACCESS = "MEDIA_ACCESS"
        const val WRITE_SECURE_SETTINGS = "WRITE_SECURE_SETTINGS"
        /** appop, the manifest permission that shows the app wants it, and a plain-English title. */
        val APP_OPS = listOf(
            Triple("SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW", "Display over other apps"),
            Triple("REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES", "Install other apps"),
            Triple("GET_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS", "Usage access"),
            Triple("MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "All files access"),
            Triple("WRITE_SETTINGS", "android.permission.WRITE_SETTINGS", "Modify system settings"),
        )
    }
}
