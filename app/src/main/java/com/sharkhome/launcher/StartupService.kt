package com.sharkhome.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Auto start. Runs quietly in the background from boot so that:
 *  - Advanced Settings are restored after a cold start, and again each time the head unit wakes
 *    (the Shark 6 usually sleeps rather than rebooting, so boot alone isn't enough);
 *  - SharkOS-CE can be brought to the front after start-up if the user asked for that.
 *
 * Unlike third-party wake receivers, nothing here can be triggered by other apps: the only entry
 * points are the system's own boot broadcasts and screen-on, which apps can't send.
 */
class StartupService : Service() {

    private var lastRestore = 0L
    private var screenOffAt = 0L

    private val wake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    // Only a real wake (screen was off for a while) counts as "start-up"; a quick display toggle doesn't.
                    val slept = screenOffAt > 0 && SystemClock.elapsedRealtime() - screenOffAt >= WAKE_AFTER_MS
                    // Restore only after a real sleep: a quick display toggle must not undo what the driver just changed.
                    if (slept) restore("wake")
                    if (slept && container.prefs.openOnStart) openShark()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                ACTION_BYD_POWER -> if (intent.getBooleanExtra("power_mode", false)) {
                    restore("wake")
                    if (container.prefs.openOnStart) openShark()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_BYD_POWER)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wake, f, Context.RECEIVER_EXPORTED) else registerReceiver(wake, f)
        container.vehicle.start()
        startWakeHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!container.prefs.autoStart) {
            stopSelf()
            return START_NOT_STICKY
        }
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "start"
        Log.e(TAG, "startup service: $reason")
        // intent == null is Android restarting the service after a memory kill mid-drive: not a start-up, so the
        // driver's current settings are left alone.
        if (intent != null) restore(reason)
        // Only a real boot or the car waking (display on, reported by the wake helper) brings SharkOS-CE to the
        // front. The process also restarts whenever Android or BYD's memory manager kills it in the background (or
        // the media listener rebinds); opening the app then would yank the driver out of whatever they were using.
        if ((reason == "boot" || reason == "wake") && container.prefs.openOnStart) openShark()
        return START_STICKY
    }

    /** Restores the remembered Advanced Settings. Never changes which app is on screen. */
    private fun restore(reason: String) {
        // Screen-on can fire several times in a row; one restore per 30 s is plenty.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestore < 30_000) return
        lastRestore = now
        container.startupMemory.restoreAll(reason)
    }

    private fun openShark() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        runCatching { startActivity(i) }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SharkOS-CE background", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SharkOS-CE")
            .setContentText("Keeping your Advanced Settings")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wake) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "background"
        const val EXTRA_REASON = "reason"
        /** Screen must have been off this long before screen-on counts as the car waking up. */
        private const val WAKE_AFTER_MS = 60_000L
        private const val TAG = "SharkCar"
        /** BYD's own power broadcast; power_mode = true when the car switches on. */
        private const val ACTION_BYD_POWER = "com.byd.autoservice.ACTION_POWER"
        const val HELPER_PKG = "tunein.player"

        /**
         * Starts the wake helper (if installed). Explicit + FLAG_INCLUDE_STOPPED_PACKAGES so it also works right
         * after the helper is installed, while Android still has it marked as stopped.
         */
        fun startWakeHelper(ctx: Context) {
            val i = Intent("com.sharkhome.wakehelper.START")
                .setClassName(HELPER_PKG, "com.sharkhome.wakehelper.StartReceiver")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            runCatching { ctx.sendBroadcast(i) }
        }

        fun start(ctx: Context, reason: String = "start") {
            val i = Intent(ctx, StartupService::class.java).putExtra(EXTRA_REASON, reason)
            runCatching { ctx.startForegroundService(i) }
        }
    }
}

/** Boot hook. System boot and power broadcasts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action.isBlank()) return
        val prefs = context.container.prefs
        // An app update isn't a start-up: keep the helper running but don't change what's on screen.
        val reason = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "update" else "boot"
        if (prefs.autoStart) {
            StartupService.start(context, reason) // the service brings SharkOS-CE to the front on "boot"
        } else if (reason == "boot" && prefs.openOnStart) {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            runCatching { context.startActivity(i) }
        }
    }
}
