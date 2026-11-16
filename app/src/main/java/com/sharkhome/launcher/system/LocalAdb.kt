package com.sharkhome.launcher.system

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shell access through the head unit's own adb over Wi-Fi (it listens on :::5555, so 127.0.0.1:5555 works).
 * BYD hides Android's permission and install screens, but `adb shell` can still run `pm grant`, `appops set` and
 * `pm install`. The first connection makes the head unit show "Allow USB debugging?"; tick "Always allow" once.
 * The key pair is kept in the app's private files, so only SharkOS-CE can use this connection.
 */
class LocalAdb(private val ctx: Context) {

    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _message = MutableStateFlow("Not connected")
    val message: StateFlow<String> = _message

    private val lock = Mutex()
    private var dadb: Dadb? = null

    private val keyPair: AdbKeyPair by lazy {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.isFile || !pub.isFile) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    /** Connects (or checks the connection). Returns true when shell commands work. */
    suspend fun connect(): Boolean {
        _state.value = State.CONNECTING
        _message.value = "Connecting… if the car asks \"Allow USB debugging?\", tick Always allow and tap Allow"
        return try {
            val r = shell("echo ok")
            val ok = r.output.trim() == "ok"
            _state.value = if (ok) State.CONNECTED else State.FAILED
            _message.value = if (ok) "Connected to the head unit" else "Unexpected reply: ${r.output.take(120)}"
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            _state.value = State.FAILED
            _message.value = "Couldn't connect: ${t.message ?: t.javaClass.simpleName}. " +
                "Check Wireless debugging is on, accept the prompt on screen, then try again."
            false
        }
    }

    /** Runs [command] in `adb shell`. Reconnects once if the connection dropped. */
    suspend fun shell(command: String): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            var lastError: Throwable? = null
            repeat(2) {
                try {
                    val d = dadb ?: Dadb.create(HOST, PORT, keyPair).also { dadb = it }
                    val r = d.shell(command)
                    Log.e(TAG, "shell[$command] -> ${r.exitCode}")
                    return@withContext Result(r.exitCode, r.allOutput)
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { dadb?.close() }
                    dadb = null
                }
            }
            _state.value = State.FAILED
            throw lastError ?: IllegalStateException("adb shell failed")
        }
    }

    companion object {
        private const val TAG = "SharkAdb"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555

        /** Single-quotes [s] for the shell (handles spaces and quotes in file names). */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
