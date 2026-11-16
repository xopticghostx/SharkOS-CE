package com.sharkhome.wakehelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log

/**
 * Keeps running through car standby (this package is on BYD's force-stop whitelist) and wakes SharkOS-CE
 * when the car's display comes on.
 *
 * Display events come from BYD's CarPowerService:
 * bind com.ts.appservice.power/.CarPowerService (action com.ts.intent.action.ts.carpower), then call
 * ICarPowerBinder transaction 1 with our ICarPowerEventListener binder. The service calls the listener with
 * transaction 4 (type, panel, value); type 2 + panel 1 is the main display: value 1 = on, 2 = off.
 * BYD's com.byd.autoservice.ACTION_POWER broadcast (power_mode = true) and screen-on are used as backups.
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var binding = false
    private var destroyed = false
    private var displayOn = true
    private var lastWake = 0L
    private var screenOffAt = 0L

    private val listener = object : Binder() {
        init {
            attachInterface(null, LISTENER_DESC)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_DESC)
                return true
            }
            try {
                data.enforceInterface(LISTENER_DESC)
                if (code == DISPLAY_CODE) {
                    val type = data.readInt()
                    val panel = data.readInt()
                    val value = data.readInt()
                    Log.e(TAG, "display event type=$type panel=$panel value=$value")
                    if (type == 2 && panel == 1) handler.post { onDisplay(value) }
                } else {
                    Log.e(TAG, "power callback code=$code")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bad power callback code=$code", t)
            }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binding = false
            remote = service
            runCatching { service.linkToDeath({ handler.post { lost("died") } }, 0) }
            register(service)
        }

        override fun onServiceDisconnected(name: ComponentName) = lost("disconnected")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BYD_POWER -> {
                    val on = intent.getBooleanExtra("power_mode", false)
                    Log.e(TAG, "BYD power broadcast power_mode=$on")
                    if (on) wake("byd power")
                }
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    val off = SystemClock.elapsedRealtime() - screenOffAt
                    if (screenOffAt > 0 && off >= 30_000) wake("screen on after ${off / 1000} s")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val f = IntentFilter().apply {
            addAction(ACTION_BYD_POWER)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // BYD's power broadcast comes from another app, so the receiver must be exported (Android 13+ asks).
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, f)
        bind()
        Log.e(TAG, "helper started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (remote == null && !binding) bind()
        return START_STICKY
    }

    private fun bind() {
        if (destroyed) return
        val i = Intent(POWER_ACTION).setComponent(ComponentName(POWER_PKG, POWER_SERVICE))
        binding = runCatching { bindService(i, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        Log.e(TAG, "bind CarPowerService: $binding")
        if (!binding) handler.postDelayed({ bind() }, 10_000)
    }

    private fun register(service: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(POWER_DESC)
            data.writeStrongBinder(listener)
            val ok = service.transact(REGISTER_CODE, data, reply, 0)
            Log.e(TAG, "register display listener: $ok")
        } catch (t: Throwable) {
            Log.e(TAG, "register display listener failed", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun lost(why: String) {
        Log.e(TAG, "CarPowerService $why, rebinding")
        remote = null
        binding = false
        runCatching { unbindService(connection) }
        handler.postDelayed({ bind() }, 3_000)
    }

    private fun onDisplay(value: Int) {
        when (value) {
            2 -> displayOn = false
            1 -> if (!displayOn) {
                displayOn = true
                wake("display on")
            }
        }
    }

    /** Starts SharkOS-CE's StartupService (clears its stopped state); it decides whether to come to the front. */
    private fun wake(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < 15_000) return
        lastWake = now
        val i = Intent().setClassName(SHARK_PKG, "$SHARK_PKG.StartupService").putExtra("reason", "wake")
        try {
            startForegroundService(i)
            Log.e(TAG, "woke SharkOS-CE ($reason)")
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't wake SharkOS-CE ($reason)", t)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SharkOS-CE helper", NotificationManager.IMPORTANCE_MIN))
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setContentTitle("SharkOS-CE")
            .setContentText("Opens SharkOS-CE when the car starts")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "SharkWake"
        private const val CHANNEL = "wake"
        private const val SHARK_PKG = "com.sharkhome.launcher"
        private const val POWER_PKG = "com.ts.appservice.power"
        private const val POWER_SERVICE = "com.ts.appservice.power.CarPowerService"
        private const val POWER_ACTION = "com.ts.intent.action.ts.carpower"
        private const val POWER_DESC = "com.ts.lib.power.ICarPowerBinder"
        private const val LISTENER_DESC = "com.ts.lib.power.ICarPowerEventListener"
        private const val REGISTER_CODE = 1
        private const val DISPLAY_CODE = 4
        private const val ACTION_BYD_POWER = "com.byd.autoservice.ACTION_POWER"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WakeService::class.java)) }
                .onFailure { Log.e(TAG, "couldn't start helper", it) }
        }
    }
}

class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WakeService.start(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WakeService.start(context)
}
