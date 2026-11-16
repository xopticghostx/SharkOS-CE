package com.sharkhome.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.system.AppInstaller
import com.sharkhome.launcher.system.AppPermissions
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.DesignCanvas
import com.sharkhome.launcher.ui.SharkTheme
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Answers "install this APK" requests (a file, a content link or a web link opened from a file manager, browser or another
 * app) with a small confirm screen, then installs through local ADB with every permission granted. It exists because BYD's
 * own chooser and installer can't be relied on. It cannot take over apps that install silently through the system
 * installer's session confirm box.
 */
class InstallRequestActivity : ComponentActivity() {

    private sealed interface Step {
        data object Preparing : Step
        data class Ready(val path: String, val label: String, val pkg: String?, val version: String?, val installed: String?) : Step
        data object Installing : Step
        data class Done(val ok: Boolean, val message: String, val pkg: String?) : Step
        data class Failed(val message: String) : Step
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        val perms = AppPermissions(this, c.adb)
        val installer = AppInstaller(this, c.adb, perms)
        val uri: Uri? = intent.data ?: @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val wantResult = intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)
        val dark = c.prefs.themeMode == "dark" || c.prefs.themeMode == "shark"

        setContent {
            var step by remember { mutableStateOf<Step>(Step.Preparing) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                step = try {
                    if (uri == null) Step.Failed("Nothing to install: the request had no file.")
                    else {
                        val path = withContext(Dispatchers.IO) { resolve(uri, installer) }
                        val peek = installer.peek(path)
                        val label = runCatching {
                            @Suppress("DEPRECATION")
                            val pi = packageManager.getPackageArchiveInfo(path, 0)
                            pi?.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path; it.loadLabel(packageManager).toString() }
                        }.getOrNull() ?: File(path).name
                        Step.Ready(path, label, peek?.first, peek?.second, peek?.first?.let { installer.installedVersion(it) })
                    }
                } catch (t: Throwable) { Step.Failed(t.message ?: t.javaClass.simpleName) }
            }

            SharkTheme(accent = Color(c.prefs.accent), dark = dark, shark = c.prefs.themeMode == "shark") {
                DesignCanvas {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { }, contentAlignment = Alignment.Center) {
                        DCard(Modifier.width(720.dp), padding = 28.dp, spacing = 14.dp) {
                            when (val s = step) {
                                Step.Preparing -> { T("Getting the APK…", 24, FontWeight.SemiBold); T("Downloading or reading the file.", 16, color = C.InkMuted) }
                                is Step.Ready -> {
                                    T("Install ${s.label}?", 26, FontWeight.SemiBold)
                                    T(
                                        buildString {
                                            s.pkg?.let { append(it) }
                                            s.version?.let { append(" · v$it") }
                                            if (s.installed != null) append(" (installed v${s.installed})") else append(" (not installed yet)")
                                        }.ifBlank { File(s.path).name },
                                        16, color = C.InkSecondary, maxLines = 2,
                                    )
                                    T("It will be installed with all its permissions granted, and exempted from battery limits.", 15, color = C.InkMuted, maxLines = 3)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DButton("Install", {
                                            step = Step.Installing
                                            scope.launch {
                                                val o = runCatching { installer.install(s.path, s.pkg) }.getOrElse { com.sharkhome.launcher.system.InstallOutcome(false, null, null, "Failed: ${it.message}") }
                                                step = Step.Done(o.ok, o.message, o.pkg ?: s.pkg)
                                            }
                                        }, style = BtnStyle.Primary, minWidth = 220.dp)
                                        DButton("Cancel", { finishWith(false, wantResult) }, style = BtnStyle.Lightest)
                                    }
                                }
                                Step.Installing -> { T("Installing…", 24, FontWeight.SemiBold); T("This can take a few seconds.", 16, color = C.InkMuted) }
                                is Step.Done -> {
                                    T(if (s.ok) "Installed" else "Couldn't install", 26, FontWeight.SemiBold, if (s.ok) C.Ok else C.Alert)
                                    T(s.message, 16, color = C.InkSecondary, maxLines = 4)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (s.ok && s.pkg != null) DButton("Open", {
                                            packageManager.getLaunchIntentForPackage(s.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                            finishWith(true, wantResult)
                                        }, style = BtnStyle.Primary, minWidth = 180.dp)
                                        DButton("Done", { finishWith(s.ok, wantResult) }, style = BtnStyle.Lightest)
                                    }
                                }
                                is Step.Failed -> {
                                    T("Can't install that", 26, FontWeight.SemiBold, C.Alert)
                                    T(s.message, 16, color = C.InkSecondary, maxLines = 4)
                                    DButton("Close", { finishWith(false, wantResult) }, style = BtnStyle.Lightest)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishWith(ok: Boolean, wantResult: Boolean) {
        if (wantResult) setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    /** Turns the request into a file path that adb can read: used as is when it is already on shared storage, else copied or downloaded. */
    private suspend fun resolve(uri: Uri, installer: AppInstaller): String {
        when (uri.scheme) {
            "file" -> {
                val p = uri.path ?: error("Bad file link")
                if (p.startsWith("/sdcard/") || p.startsWith("/storage/") || p.startsWith("/data/media/")) return p
            }
            "http", "https" -> {
                val name = android.webkit.URLUtil.guessFileName(uri.toString(), null, "application/vnd.android.package-archive")
                return installer.download(uri.toString(), name) { }.absolutePath
            }
        }
        // content:// (or a private file): copy to the folder adb can read
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "install.apk"
        val target = File(installer.downloadsPath(), if (name.endsWith(".apk", true)) name else "$name.apk").also { it.parentFile?.mkdirs() }
        contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("Couldn't open that file")
        return target.absolutePath
    }
}
