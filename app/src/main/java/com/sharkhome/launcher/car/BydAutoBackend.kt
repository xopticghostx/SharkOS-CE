package com.sharkhome.launcher.car

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * BYD's android.hardware.bydauto.* device classes (the route HomeSpace uses).
 * Tried first from the app's own class loader, then from the factory climate app com.byd.hvac.
 */
class BydAutoBackend(private val ctx: Context) {

    private val instances = ConcurrentHashMap<String, Any>()
    private val failures = ConcurrentHashMap<String, String>()
    private val failedTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var loader: ClassLoader? = null

    fun target(device: String): Any? {
        // Never load this device: its getInstance() starts a car-volume callback on BYD's own thread that needs a
        // system-only permission, and the app then crashes a few seconds later (seen on the car 7 and 16 Nov 2026).
        if (device.endsWith("BYDAutoAudioDevice")) return null
        instances[device]?.let { return it }
        // A device that failed is tried again after 30 s: BYD's classes may not be ready right after a cold boot.
        val failedAt = failedTimes[device]
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 30_000L) return null
        return try {
            val cls = Class.forName("android.hardware.bydauto.$device", true, classLoader())
            val inst = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                ?: error("getInstance returned null")
            instances[device] = inst
            failures.remove(device)
            failedTimes.remove(device)
            inst
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            failures[device] = cause.message ?: cause.javaClass.simpleName
            failedTimes[device] = android.os.SystemClock.elapsedRealtime()
            null
        }
    }

    fun loaded(): Map<String, Any> = instances.toMap()
    fun failed(): Map<String, String> = failures.toMap()

    fun retry() {
        failures.clear()
        failedTimes.clear()
    }

    private fun classLoader(): ClassLoader {
        loader?.let { return it }
        val l = try {
            Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice", false, ctx.classLoader)
            ctx.classLoader
        } catch (_: ClassNotFoundException) {
            SystemApk.classLoader(ctx, "com.byd.hvac")
        }
        loader = l
        return l
    }
}
