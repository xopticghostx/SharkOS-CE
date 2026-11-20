package com.sharkhome.launcher.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sharkhome.launcher.MainActivity
import com.sharkhome.launcher.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Built-in ADB test hook so agents and the owner can navigate screens, dump Settings › Test marks,
 * hot-reload a shark6_codes.json override file, and run read / safe verify-and-restore probes from ADB
 * without installing any extra app on the head unit.
 *
 * Protected in AndroidManifest.xml with android:permission="android.permission.DUMP", so only ADB shell
 * (UID 2000) and system can trigger it — normal third-party apps cannot send this broadcast.
 *
 * Usage:
 *   adb -s <car> shell am broadcast -a com.sharkhome.launcher.ADB_TEST -n com.sharkhome.launcher/.car.AdbTestReceiver --es cmd <cmd> [extras]
 *
 * Commands:
 *   status                                         Version, codes origin/override path, backend state, ACC, mark counts
 *   open    --es page <route>                      Jump directly to a page/tab (home, climate, controls:0|1|2, drive:0|1|2, apps, car, dashcam, settings:0..6, test, explorer)
 *   marks                                          Dump persisted Settings › Test checklist marks (works / fails / later)
 *   reload                                         Hot-reload shark6_codes.json (external override file if present, else built-in)
 *   read    --es keys "k1,k2" | --es prefix "p."   Live-read signals and log raw, value, label, source, error
 *   verify  --es keys "k1,k2" [--es target "..."]  Read before -> write -> read-back -> restore original (refuses safety keys unless --ez allowSafety true)
 *   play    --es file name.wav                     Play a sound file from the app's files folder (e.g. a recorded voice command for BYD's assistant)
 */
class AdbTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase().orEmpty()
        val c = context.container
        when (cmd) {
            "status" -> handleStatus(context)
            "open" -> handleOpen(context, intent)
            "marks" -> handleMarks(context)
            "reload" -> handleReload(context)
            "read" -> {
                val pending = goAsync()
                val keysArg = intent.getStringExtra("keys")
                val prefixArg = intent.getStringExtra("prefix")
                c.scope.launch(Dispatchers.IO) {
                    try {
                        handleRead(context, keysArg, prefixArg)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ADB_TEST read failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            "verify" -> {
                val pending = goAsync()
                val keysArg = intent.getStringExtra("keys")
                val targetArg = intent.getStringExtra("target")
                val waitMs = intent.getIntExtra("waitMs", 500).coerceIn(100, 8000).toLong()
                val allowSafety = intent.getBooleanExtra("allowSafety", false)
                c.scope.launch(Dispatchers.IO) {
                    try {
                        handleVerify(context, keysArg, targetArg, waitMs, allowSafety)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ADB_TEST verify failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            "play" -> {
                val pending = goAsync()
                val fileArg = intent.getStringExtra("file")
                c.scope.launch(Dispatchers.IO) {
                    try {
                        handlePlay(context, fileArg)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ADB_TEST play failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> Log.e(
                TAG,
                "ADB_TEST help: cmds = status | open (--es page <route>) | marks | reload | read (--es keys k1,k2 | --es prefix p.) | verify (--es keys k1,k2 [--es target v] [--ei waitMs 500] [--ez allowSafety false]) | play (--es file name.wav, from the app's files folder)",
            )
        }
    }

    /**
     * Plays a sound file through the car's normal audio path (media stream), so a recorded voice command can be
     * "spoken" to BYD's voice assistant from ADB. The file must be in the app's own files folder (copy it there
     * with `cp` from /sdcard, as for shark6_codes.json). Plays nothing else and writes nothing to the car.
     */
    private fun handlePlay(ctx: Context, name: String?) {
        val f = name?.trim()?.takeIf { it.isNotEmpty() && !it.contains("/") && !it.contains("..") }
            ?.let { java.io.File(ctx.getExternalFilesDir(null), it) }
        if (f == null || !f.isFile) {
            Log.e(TAG, "ADB_TEST play: file not found in ${ctx.getExternalFilesDir(null)} (name=$name)")
            return
        }
        val done = java.util.concurrent.CountDownLatch(1)
        val mp = android.media.MediaPlayer()
        try {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, w, e -> Log.e(TAG, "ADB_TEST play error what=$w extra=$e"); done.countDown(); true }
            mp.prepare()
            Log.e(TAG, "ADB_TEST play START ${f.name} ${mp.duration} ms")
            mp.start()
            done.await(30, java.util.concurrent.TimeUnit.SECONDS)
            Log.e(TAG, "ADB_TEST play DONE ${f.name}")
        } finally {
            mp.release()
        }
    }

    private fun handleStatus(ctx: Context) {
        val c = ctx.container
        val version = c.updater.currentVersionName()
        val code = c.updater.currentVersionCode()
        val override = CarCodes.overrideFile(ctx)
        val marks = c.prefs.testMarks
        val powerMode = c.vehicle.readings.value["vehicle.powerMode"]?.let { "${it.raw}(${c.vehicle.labelFor("vehicle.powerMode", it.raw)})" } ?: "unknown"
        Log.e(
            TAG,
            "ADB_TEST status: v$version ($code) origin=${c.codes.origin} signals=${c.codes.signals.size} " +
                "overridePath=${override?.absolutePath} overrideExists=${override?.isFile == true} " +
                "carAdapter=${c.carAdapter.status.value} (${c.carAdapter.adapters.size} adapters) " +
                "bydAutoLoaded=${c.bydAuto.loaded().size} bydAutoFailed=${c.bydAuto.failed().size} " +
                "powerMode=$powerMode testMarks=${marks.size}",
        )
    }

    private fun handleOpen(ctx: Context, intent: Intent) {
        val page = intent.getStringExtra("page")?.trim()?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(MainActivity.EXTRA_OPEN)?.trim()?.takeIf { it.isNotEmpty() }
        if (page == null) {
            Log.e(TAG, "ADB_TEST open: missing --es page <route>")
            return
        }
        val launch = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN, page)
        }
        ctx.startActivity(launch)
        Log.e(TAG, "ADB_TEST open: dispatched page=$page")
    }

    private fun handleMarks(ctx: Context) {
        val marks = ctx.container.prefs.testMarks
        if (marks.isEmpty()) {
            Log.e(TAG, "ADB_TEST marks: (none)")
            return
        }
        val works = marks.values.count { it == "works" }
        val fails = marks.values.count { it == "fails" }
        val later = marks.values.count { it == "later" }
        Log.e(TAG, "ADB_TEST marks: total=${marks.size} works=$works fails=$fails later=$later")
        for ((id, state) in marks.toSortedMap()) {
            Log.e(TAG, "ADB_TEST mark $id = $state")
        }
    }

    private fun handleReload(ctx: Context) {
        val c = ctx.container
        val loaded = c.reloadCodes()
        val override = CarCodes.overrideFile(ctx)
        Log.e(
            TAG,
            "ADB_TEST reload: origin=${loaded.origin} signals=${loaded.signals.size} override=${override?.absolutePath} exists=${override?.isFile == true}",
        )
    }

    private fun handleRead(ctx: Context, keysArg: String?, prefixArg: String?) {
        val c = ctx.container
        val keys: List<String> = when {
            !keysArg.isNullOrBlank() -> keysArg.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            !prefixArg.isNullOrBlank() -> c.codes.signals.keys.filter { it.startsWith(prefixArg.trim()) }.sorted()
            else -> {
                Log.e(TAG, "ADB_TEST read: pass --es keys \"k1,k2\" or --es prefix \"trip.\"")
                return
            }
        }
        if (keys.isEmpty()) {
            Log.e(TAG, "ADB_TEST read: no matching keys")
            return
        }
        for (key in keys) {
            val sig = c.codes.signals[key]
            if (sig == null) {
                Log.e(TAG, "ADB_TEST read $key: UNKNOWN signal")
                continue
            }
            val r = c.vehicle.read(sig)
            val label = c.vehicle.labelFor(key, r.raw)
            Log.e(TAG, "ADB_TEST read $key: raw=${r.raw} val=${r.value} label=$label via=${r.source} err=${r.error}")
        }
        Log.e(TAG, "ADB_TEST read DONE count=${keys.size}")
    }

    private suspend fun handleVerify(
        ctx: Context,
        keysArg: String?,
        targetArg: String?,
        waitMs: Long,
        allowSafety: Boolean,
    ) {
        val c = ctx.container
        val keys = keysArg?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (keys.isEmpty()) {
            Log.e(TAG, "ADB_TEST verify: pass --es keys \"k1,k2\"")
            return
        }
        for (key in keys) {
            if (!allowSafety && isSafetyKey(key)) {
                Log.e(TAG, "ADB_TEST verify REFUSED safety key=$key (pass --ez allowSafety true only with explicit owner OK)")
                continue
            }
            val sig = c.codes.signals[key]
            if (sig == null) {
                Log.e(TAG, "ADB_TEST verify $key: UNKNOWN signal")
                continue
            }
            val before = c.vehicle.read(sig)
            val beforeRaw = before.raw
            val beforeVal = before.value
            if (beforeRaw == null || beforeVal == null) {
                Log.e(TAG, "ADB_TEST verify $key: SKIP unreadable (${before.error})")
                continue
            }
            val beforeLabel = c.vehicle.labelFor(key, beforeRaw)
            val explicitTarget = if (keys.size == 1) targetArg?.trim()?.takeIf { it.isNotEmpty() } else null

            val targetLabel: String?
            val targetNumber: Double?
            if (explicitTarget != null) {
                if (sig.labels.containsKey(explicitTarget)) {
                    targetLabel = explicitTarget
                    targetNumber = null
                } else {
                    targetLabel = null
                    targetNumber = explicitTarget.toDoubleOrNull()
                }
            } else if (sig.labels.isNotEmpty()) {
                val curInt = beforeRaw.toInt()
                targetLabel = when {
                    beforeLabel == "on" && sig.labels.containsKey("off") -> "off"
                    beforeLabel == "off" && sig.labels.containsKey("on") -> "on"
                    else -> sig.labels.entries.firstOrNull { it.value >= 0 && it.value != curInt }?.key
                }
                targetNumber = null
            } else {
                targetLabel = null
                val step = if (sig.scale != 0.0) sig.scale else 1.0
                targetNumber = if (beforeVal >= 10.0) beforeVal - step else beforeVal + step
            }

            if (targetLabel == null && targetNumber == null) {
                Log.e(TAG, "ADB_TEST verify $key: SKIP no alternate target (before=$beforeRaw/$beforeLabel)")
                continue
            }

            val sentDesc = targetLabel ?: targetNumber.toString()
            val writeResult: WriteResult
            val after: Reading
            try {
                writeResult = if (targetLabel != null) {
                    c.vehicle.writeLabel(key, targetLabel)
                } else {
                    c.vehicle.write(key, targetNumber!!)
                }
                delay(waitMs)
                after = c.vehicle.read(sig)
            } finally {
                // Always restore original value even if the test write didn't confirm.
                if (beforeLabel != null && (sig.labels.containsKey(beforeLabel) || sig.writeLabels.containsKey(beforeLabel))) {
                    c.vehicle.writeLabel(key, beforeLabel)
                } else {
                    c.vehicle.write(key, beforeVal)
                }
                delay(waitMs)
            }
            val restored = c.vehicle.read(sig)
            val restoredOk = restored.raw == beforeRaw
            Log.e(
                TAG,
                "ADB_TEST verify $key: before=$beforeRaw($beforeLabel) sent=$sentDesc " +
                    "confirmed=${writeResult.confirmed} after=${after.raw}(${c.vehicle.labelFor(key, after.raw)}) " +
                    "restored=${restored.raw}(${c.vehicle.labelFor(key, restored.raw)}) restoredOk=$restoredOk",
            )
        }
        Log.e(TAG, "ADB_TEST verify DONE count=${keys.size}")
    }

    private fun isSafetyKey(key: String): Boolean {
        val k = key.lowercase()
        return k == "chassis.stability" ||
            k == "drive.roadmode" ||
            k == "vehicle.roadmode" ||
            k.startsWith("adas.") ||
            k.startsWith("safety.") ||
            k.contains("aeb") ||
            k.contains("esc") ||
            k.contains("abs") ||
            k.contains("airbag") ||
            k.contains("brake") ||
            k.contains("collision")
    }

    companion object {
        const val ACTION = "com.sharkhome.launcher.ADB_TEST"
        private const val TAG = "SharkCar"
    }
}
