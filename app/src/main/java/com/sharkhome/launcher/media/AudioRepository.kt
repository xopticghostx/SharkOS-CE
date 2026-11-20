package com.sharkhome.launcher.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.sharkhome.launcher.car.Reflect
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * BYD's own sound settings (Settings › System › Audio on the Shark 6 with Dynaudio), read from the copy the car keeps in
 * Settings.Global. Null = key not found. Codes verified on the car 20 Nov 2026 by choosing each option in BYD's picker.
 */
/** Android BassBoost effect on the output mix. strength is 0..1000. */
data class BassState(val available: Boolean = false, val enabled: Boolean = false, val strength: Int = 0, val error: String? = null)

data class EqBand(val index: Int, val centerHz: Int, val level: Int)

data class EqState(
    val available: Boolean,
    val error: String? = null,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val presets: List<String> = emptyList(),
    val currentPreset: Int = -1,
)

data class BydSound(val preset: Int?, val focus: Int?) {
    val presetName: String? get() = preset?.let { PRESETS.getOrElse(it) { "Code $it" } }
    val focusName: String? get() = focus?.let { FOCUS.getOrElse(it) { "Code $it" } }

    companion object {
        /** android.car.EQUALIZER/PRESET */
        val PRESETS = listOf("Custom", "Acoustic", "Dynamic", "Soft", "Speech")
        /** android.car.AUDIO_CONTROL/dyna_sound_field_focus */
        val FOCUS = listOf("Whole", "Driver", "Front Passenger", "Rear", "Surround", "Custom")
    }
}

/** Volume (car volume group / music stream) and BYD's Dynaudio sound settings (read only). */
class AudioRepository(
    private val ctx: Context,
    private val prefs: Prefs,
    private val bydAuto: com.sharkhome.launcher.car.BydAutoBackend,
) {

    // Never load BYDAutoAudioDevice: its getInstance() registers a car volume callback on BYD's own background thread,
    // which needs the system-only android.car.permission.CAR_CONTROL_AUDIO_VOLUME. Without it the app crashes on
    // that thread a few seconds after start (seen on the car 2026-11-07), and the crash can't be caught.

    companion object {
        private const val TAG = "SharkAudio"
        private const val VOLUME_GROUP_KEY = "android.car.VOLUME_GROUP/0"
        private const val PRESET_KEY = "android.car.EQUALIZER/PRESET"
        private const val FOCUS_KEY = "android.car.AUDIO_CONTROL/dyna_sound_field_focus"
        const val BYD_SETTINGS_PKG = "com.byd.carsettings"
    }

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var carAudioManager: Any? = null
    private var car: Any? = null

    val maxVolume: Int get() = readMaxVolume()

    private val _volume = MutableStateFlow(0)
    val volume: StateFlow<Int> = _volume

    /*
     * Why there is no equaliser in the app (checked on the car 20 Nov 2026):
     * - Android's audiofx Equalizer on session 0 attached to the REMOTE_SUBMIX output (screen-capture audio), not to the
     *   bus0_media_out bus that feeds the Dynaudio amplifier, so it never changed what the speakers play.
     * - BYD's real EQ is android.car.media.CarEqualizer (car service "enhanced_audio"), which needs
     *   android.car.permission.CAR_CONTROL_ENHANCED_AUDIO (signature|privileged). The app and the adb shell are refused.
     * - Writing android.car.EQUALIZER/PRESET in Settings.Global only changes BYD's saved copy: BYD's picker kept showing
     *   the old preset. So the app shows BYD's choice and opens BYD Settings to change it.
     */
    private val _sound = MutableStateFlow(BydSound(null, null))
    val sound: StateFlow<BydSound> = _sound

    // Two separate things live here:
    // 1. Android's own audiofx Equalizer on the output mix (_eq below). This is the testable EQ with bands + presets
    //    (bass boost etc.). A car-mixer dump showed it on the REMOTE_SUBMIX output while scrcpy was capturing, so whether
    //    it reaches the Dynaudio speakers is being verified by ear — keep it until that's settled.
    // 2. BYD's own Dynaudio preset/focus (_sound above), read only: the car refuses every app the permission to set it
    //    (checkCallingOrSelfPermission(CAR_CONTROL_ENHANCED_AUDIO) in com.ts.car.enhancedaudio.CarEnhancedAudioCore,
    //    signature|privileged), so the app shows it and opens BYD Settings to change it.
    private val _eq = MutableStateFlow(EqState(available = false))
    val eq: StateFlow<EqState> = _eq

    private var equalizer: Equalizer? = null

    private val _bass = MutableStateFlow(BassState())
    val bass: StateFlow<BassState> = _bass
    private var bassBoost: BassBoost? = null

    init {
        initCarAudio()
        setupObservers()
        refresh()
        refreshSound()
    }

    private fun readGlobalInt(key: String): Int? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key)?.trim()?.toIntOrNull() }.getOrNull()

    fun refreshSound() {
        _sound.value = BydSound(readGlobalInt(PRESET_KEY), readGlobalInt(FOCUS_KEY))
    }

    private fun initCarAudio() {
        if (carAudioManager != null) return
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            val car = createCar.invoke(null, ctx)
            this.car = car
            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            carAudioManager = getCarManager.invoke(car, "audio")
        } catch (t: Throwable) {
            Log.w(TAG, "CarAudioManager not available: ${t.message}")
        }
    }

    private fun readCurrentVolume(): Int {
        if (carAudioManager == null) initCarAudio()
        carAudioManager?.let { cam ->
            runCatching {
                val v = (Reflect.invoke(cam, "getGroupVolume", listOf(0)) as? Number)?.toInt()
                    ?: (Reflect.invoke(cam, "getGroupVolume", listOf(0, 0)) as? Number)?.toInt()
                if (v != null && v >= 0) return v
            }
        }
        try {
            val v = Settings.System.getInt(ctx.contentResolver, VOLUME_GROUP_KEY, -1)
            if (v >= 0) return v
        } catch (_: Throwable) {}
        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun readMaxVolume(): Int {
        if (carAudioManager == null) initCarAudio()
        carAudioManager?.let { cam ->
            runCatching {
                val m = (Reflect.invoke(cam, "getGroupMaxVolume", listOf(0)) as? Number)?.toInt()
                    ?: (Reflect.invoke(cam, "getGroupMaxVolume", listOf(0, 0)) as? Number)?.toInt()
                if (m != null && m > 0) return m
            }
        }
        try {
            if (Settings.System.getInt(ctx.contentResolver, VOLUME_GROUP_KEY, -1) >= 0) {
                return 39
            }
        } catch (_: Throwable) {}
        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private fun setupObservers() {
        val handler = Handler(Looper.getMainLooper())
        try {
            val uri = Settings.System.getUriFor(VOLUME_GROUP_KEY)
            ctx.contentResolver.registerContentObserver(uri, false, object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    refresh()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register ContentObserver: ${t.message}")
        }
        try {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = refreshSound()
            }
            for (k in listOf(PRESET_KEY, FOCUS_KEY)) {
                ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(k), false, obs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to watch BYD sound settings: ${t.message}")
        }
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    refresh()
                }
            }, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register BroadcastReceiver: ${t.message}")
        }
    }

    fun refresh() {
        // While queued key presses are still being sent, the optimistic value is the one to show: an in-between
        // reading here would make the next tap count from the wrong number.
        if (volWorker) return
        _volume.value = readCurrentVolume()
    }

    /** Set by the app container: the head unit's own adb, used to press the volume keys. */
    @Volatile var adb: com.sharkhome.launcher.system.LocalAdb? = null
    private val volScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val volLock = Any()
    private var pendingSteps = 0
    @Volatile private var volWorker = false
    @Volatile private var volTarget = 0

    /**
     * Changes the media volume. Apps can't set the car's volume directly (CarAudioManager.setGroupVolume needs the
     * system-only CAR_CONTROL_AUDIO_VOLUME), but a volume key press does exactly what the knob does: verified 8 Nov,
     * `input keyevent KEYCODE_VOLUME_UP` moved android.car.VOLUME_GROUP/0 from 10 to 11. So the steps are sent as key
     * presses through the head unit's own adb. Rapid taps are queued and sent together.
     */
    fun setVolume(v: Int) {
        val target = v.coerceIn(0, maxVolume)
        setVolumeViaKeys(target)
    }

    private fun setVolumeViaKeys(target: Int) {
        val a = adb
        if (a != null) {
            val delta = target - _volume.value
            if (delta == 0) return
            _volume.value = target
            volTarget = target
            synchronized(volLock) {
                pendingSteps += delta
                if (volWorker) return
                volWorker = true
            }
            volScope.launch {
                while (true) {
                    val steps = synchronized(volLock) {
                        val s = pendingSteps
                        pendingSteps = 0
                        if (s == 0) volWorker = false
                        s
                    }
                    if (steps == 0) break
                    val key = if (steps > 0) 24 else 25 // KEYCODE_VOLUME_UP / KEYCODE_VOLUME_DOWN
                    val cmd = List(kotlin.math.abs(steps)) { "input keyevent $key" }.joinToString("; ")
                    val r = runCatching { a.shell(cmd) }
                    Log.e(TAG, "volume ${if (steps > 0) "+" else ""}$steps via key presses -> ${r.getOrNull()?.exitCode ?: r.exceptionOrNull()}")
                    if (r.isFailure) {
                        synchronized(volLock) { pendingSteps = 0; volWorker = false }
                        // No working adb (USB debugging off): use the direct route instead of dropping the change.
                        setVolumeDirect(volTarget)
                        break
                    }
                }
                refresh()
            }
            return
        }
        setVolumeDirect(target)
    }

    /** Old route (no adb): only works where the app is allowed to set the stream volume. */
    private fun setVolumeDirect(target: Int) {
        var handled = false
        if (carAudioManager == null) initCarAudio()
        if (!handled) carAudioManager?.let { cam ->
            val ok = runCatching {
                Reflect.invoke(cam, "setGroupVolume", listOf(0, target, 0))
                true
            }.recoverCatching {
                Reflect.invoke(cam, "setGroupVolume", listOf(0, 0, target, 0))
                true
            }.getOrDefault(false)
            if (ok) handled = true
        }
        // The car audio service stores group volumes in android.car.VOLUME_GROUP/<n> itself (the knob updates it too).
        // Don't write that key when setGroupVolume worked: writing it would only change the number, not the sound, and
        // makes Android treat the key as ours (it is deleted if SharkOS-CE is uninstalled).
        if (!handled) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
        _volume.value = target
    }

    // ---- Android output-mix equaliser (bands + presets). Testable; may or may not reach the Dynaudio speakers. ----

    /** Runs an equaliser call; if the effect has died (audio service restart), drops it so the next use rebuilds it. */
    private inline fun eqSafe(block: (Equalizer) -> Unit): Boolean {
        val e = equalizer ?: return false
        return try {
            block(e)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "equaliser call failed, will re-create", t)
            runCatching { e.release() }
            equalizer = null
            _eq.value = EqState(available = false, error = t.message ?: t.javaClass.simpleName)
            false
        }
    }

    fun initEq() {
        initBass()
        if (equalizer != null) return
        try {
            // Session 0 = the global output mix. Not every head unit allows it.
            val e = Equalizer(0, 0)
            equalizer = e
            val saved = prefs.eqLevels
            if (saved.size == e.numberOfBands.toInt()) {
                saved.forEachIndexed { i, lvl -> e.setBandLevel(i.toShort(), lvl.toShort()) }
            }
            e.setEnabled(prefs.eqEnabled)
            publish(-1)
        } catch (t: Throwable) {
            _eq.value = EqState(available = false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun publish(preset: Int) {
        val e = equalizer ?: return
        val range = e.bandLevelRange
        val bands = (0 until e.numberOfBands.toInt()).map { i ->
            EqBand(i, e.getCenterFreq(i.toShort()) / 1000, e.getBandLevel(i.toShort()).toInt())
        }
        val presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
        _eq.value = EqState(true, null, e.enabled, bands, range[0].toInt(), range[1].toInt(), presets, preset)
    }

    fun setEnabled(on: Boolean) {
        if (equalizer == null) initEq()
        eqSafe { e ->
            e.setEnabled(on)
            prefs.eqEnabled = on
            publish(_eq.value.currentPreset)
        }
    }

    fun setBand(index: Int, level: Int) {
        if (equalizer == null) initEq()
        eqSafe { e ->
            val range = e.bandLevelRange
            e.setBandLevel(index.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            if (!e.enabled) {
                e.setEnabled(true)
                prefs.eqEnabled = true
            }
            saveLevels()
            publish(-1)
        }
    }

    fun usePreset(index: Int) {
        if (equalizer == null) initEq()
        eqSafe { e ->
            if (index >= 0 && index < e.numberOfPresets.toInt()) {
                e.usePreset(index.toShort())
                if (!e.enabled) {
                    e.setEnabled(true)
                    prefs.eqEnabled = true
                }
                saveLevels()
                publish(index)
            }
        }
    }

    private fun initBass() {
        if (bassBoost != null) return
        try {
            val b = BassBoost(0, 0)
            bassBoost = b
            if (b.strengthSupported) b.setStrength(prefs.bassStrength.toShort())
            b.setEnabled(prefs.bassEnabled)
            publishBass()
        } catch (t: Throwable) {
            _bass.value = BassState(false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun publishBass() {
        val b = bassBoost ?: return
        _bass.value = BassState(true, b.enabled, if (b.strengthSupported) b.roundedStrength.toInt() else prefs.bassStrength)
    }

    private inline fun bassSafe(block: (BassBoost) -> Unit) {
        if (bassBoost == null) initBass()
        val b = bassBoost ?: return
        try {
            block(b)
            publishBass()
        } catch (t: Throwable) {
            Log.e(TAG, "bass boost call failed, will re-create", t)
            runCatching { b.release() }
            bassBoost = null
            _bass.value = BassState(false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    fun setBassEnabled(on: Boolean) = bassSafe { b -> b.setEnabled(on); prefs.bassEnabled = on }

    /** strength 0..1000; any strength above 0 also switches the effect on. */
    fun setBassStrength(strength: Int) = bassSafe { b ->
        val s = strength.coerceIn(0, 1000)
        if (b.strengthSupported) b.setStrength(s.toShort())
        prefs.bassStrength = s
        val on = s > 0
        b.setEnabled(on)
        prefs.bassEnabled = on
    }

    private fun saveLevels() {
        val e = equalizer ?: return
        prefs.eqLevels = (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
    }
}
