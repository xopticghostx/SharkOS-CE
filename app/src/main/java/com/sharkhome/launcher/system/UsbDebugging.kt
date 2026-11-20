package com.sharkhome.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

object UsbDebugging {

    data class EnableResult(
        val enabled: Boolean,
        val openedFallback: Boolean,
        val message: String,
    )

    fun isEnabled(ctx: Context): Boolean =
        runCatching {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

    /**
     * Turns USB debugging (Settings.Global.ADB_ENABLED = 1) on in a single tap.
     *
     * 1. Writes Settings.Global.ADB_ENABLED directly if WRITE_SECURE_SETTINGS is granted.
     * 2. Otherwise tries LocalAdb (127.0.0.1:5555) to grant WRITE_SECURE_SETTINGS and enable ADB.
     * 3. If neither is permitted yet, opens BYD's ADB settings (or Android Developer Options) as a fallback.
     */
    suspend fun enable(ctx: Context, adb: LocalAdb): EnableResult {
        val wroteDirectly = runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1)
        }.getOrDefault(false)

        if (isEnabled(ctx)) {
            return EnableResult(
                enabled = true,
                openedFallback = false,
                message = "USB debugging is ON",
            )
        }

        // If WRITE_SECURE_SETTINGS isn't granted yet, try self-granting over local ADB if port 5555 is listening.
        if (!wroteDirectly) {
            val adbOk = withTimeoutOrNull(1800.milliseconds) {
                runCatching {
                    val cmd = "pm grant ${LocalAdb.q(ctx.packageName)} android.permission.WRITE_SECURE_SETTINGS && settings put global adb_enabled 1"
                    adb.shell(cmd).ok
                }.getOrDefault(false)
            } ?: false

            if (adbOk && isEnabled(ctx)) {
                return EnableResult(
                    enabled = true,
                    openedFallback = false,
                    message = "USB debugging is ON",
                )
            }
        }

        val opened = openBydAdbScreen(ctx)
        val nowOn = isEnabled(ctx)
        val msg = when {
            nowOn -> "USB debugging is ON"
            opened -> "Opened BYD ADB screen — tap the first option to toggle USB debugging"
            !wroteDirectly -> "Not allowed: grant WRITE_SECURE_SETTINGS once via ADB (grant-permissions.bat)"
            else -> "The head unit didn't accept the change"
        }
        return EnableResult(enabled = nowOn, openedFallback = opened, message = msg)
    }

    private var watching = false

    /**
     * Keeps debugging on while [keepOn] says so. BYD's OTA app (com.byd.ota) writes adb_enabled = 0 every time it
     * starts ("UsbUpdateLogger: disableAdb", seen 20 Nov 2026 when it crashed and restarted mid-scrcpy), which drops
     * the PC's adb and scrcpy. Needs WRITE_SECURE_SETTINGS (granted by the installer); without it this does nothing.
     */
    fun startWatchdog(ctx: Context, keepOn: () -> Boolean) {
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val restore = Runnable {
            if (keepOn() && !isEnabled(app)) {
                val ok = runCatching { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) }.getOrDefault(false)
                android.util.Log.e("SharkAdb", "debugging was turned off; switched back on: ${ok && isEnabled(app)}")
            }
        }
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Wait 1.5 s so a quick off/on toggle settles first. Turning it off on purpose needs this switch off.
                handler.removeCallbacks(restore)
                if (keepOn() && !isEnabled(app)) handler.postDelayed(restore, 1500)
            }
        }
        runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer) }
        handler.post(restore)
    }

    /** Opens BYD's AdbActivity (`byd_pad://secretcode:9521/adbactivity`), or Android Developer Options as fallback. */
    fun openBydAdbScreen(ctx: Context): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, "byd_pad://secretcode:9521/adbactivity".toUri())
                .setComponent(ComponentName("com.byd.secretcode", "com.byd.secretcode.activity.AdbActivity")),
            Intent().setComponent(ComponentName("com.byd.secretcode", "com.byd.secretcode.activity.AdbActivity")),
            Intent(Intent.ACTION_VIEW, "byd_pad://secretcode:9521/adbactivity".toUri()),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = ctx.packageManager.resolveActivity(intent, 0) != null
            if (resolved && runCatching { ctx.startActivity(intent) }.isSuccess) {
                return true
            }
        }
        return false
    }
}
