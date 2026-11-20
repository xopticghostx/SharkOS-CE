package com.sharkhome.launcher.system

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.sharkhome.launcher.BuildConfig
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Checks GitHub Releases for newer SharkOS-CE builds, downloads the APK with live progress,
 * and installs it either silently via [LocalAdb] (`pm install -r -g`), via Android's package
 * installer (`FileProvider`), or by saving it to `Downloads/` for BYD File Management.
 */
class AppUpdater(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val adb: LocalAdb,
) {
    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    )

    data class GitHubRelease(
        val tagName: String,
        val versionString: String,
        val title: String,
        val notes: String,
        val publishedAt: String,
        val htmlUrl: String,
        val prerelease: Boolean,
        val apkAsset: ReleaseAsset?,
        val helperAsset: ReleaseAsset? = null,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val release: GitHubRelease, val currentVersion: String) : State
        data class UpdateAvailable(val release: GitHubRelease, val currentVersion: String) : State
        data class Downloading(val release: GitHubRelease, val bytesRead: Long, val totalBytes: Long) : State
        data class ReadyToInstall(val release: GitHubRelease, val apkFile: File) : State
        data class Installing(val release: GitHubRelease, val viaAdb: Boolean) : State
        data class SavedToDownloads(val release: GitHubRelease, val location: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _hasUpdate = MutableStateFlow(false)
    val hasUpdate: StateFlow<Boolean> = _hasUpdate

    fun currentVersionName(): String =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: BuildConfig.VERSION_NAME

    fun currentVersionCode(): Long =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        }.getOrNull() ?: BuildConfig.VERSION_CODE.toLong()

    /** Checks automatically on launch at most once every 6 hours when enabled. */
    fun checkIfStale() {
        if (!prefs.autoCheckUpdates) return
        val elapsed = System.currentTimeMillis() - prefs.lastUpdateCheckMs
        if (prefs.lastUpdateCheckMs > 0L && elapsed in 0L until 6.hours.inWholeMilliseconds) return
        checkForUpdates(silentOnError = true)
    }

    fun checkForUpdates(silentOnError: Boolean = false) {
        if (_state.value is State.Checking || _state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        scope.launch(Dispatchers.IO) {
            _state.value = State.Checking
            val repo = normalizeRepo(prefs.githubRepo)
            if (repo == null) {
                _hasUpdate.value = false
                if (!silentOnError) _state.value = State.Error("Enter a valid GitHub repo (owner/repo)")
                else _state.value = State.Idle
                return@launch
            }
            try {
                val release = fetchLatestRelease(repo)
                prefs.lastUpdateCheckMs = System.currentTimeMillis()
                if (release == null) {
                    _hasUpdate.value = false
                    _state.value = if (silentOnError) State.Idle else State.Error("No releases published yet on github.com/$repo")
                    return@launch
                }
                val current = currentVersionName()
                val newer = isNewerVersion(release.versionString, current)
                _hasUpdate.value = newer
                if (newer) {
                    val existing = existingApkFor(release)
                    _state.value = if (existing != null) {
                        State.ReadyToInstall(release, existing)
                    } else {
                        State.UpdateAvailable(release, current)
                    }
                } else {
                    _state.value = State.UpToDate(release, current)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Update check failed", t)
                if (silentOnError) {
                    _state.value = State.Idle
                } else {
                    _state.value = State.Error("Check failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    fun downloadUpdate(release: GitHubRelease) {
        val asset = release.apkAsset ?: run {
            _state.value = State.Error("Release ${release.tagName} does not attach an .apk file")
            return
        }
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        // Claim the state before the coroutine starts, so a double tap can't start a second download.
        _state.value = State.Downloading(release, 0L, asset.sizeBytes)
        scope.launch(Dispatchers.IO) {
            try {
                val dir = updatesDir()
                dir.listFiles()?.forEach { old ->
                    if (old.name != asset.name) runCatching { old.delete() }
                }
                val target = File(dir, asset.name)
                val part = File(dir, "${asset.name}.part")
                if (part.exists()) part.delete()

                _state.value = State.Downloading(release, 0L, asset.sizeBytes)

                val conn = openFollowRedirects(asset.downloadUrl)
                val status = conn.responseCode
                if (status !in 200..299) {
                    error("HTTP $status downloading ${asset.name}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
                var downloaded = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var lastEmit = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 120L) {
                                _state.value = State.Downloading(release, downloaded, total)
                                lastEmit = now
                            }
                        }
                        out.flush()
                    }
                }
                if (downloaded <= 0L || (asset.sizeBytes > 0L && downloaded != asset.sizeBytes)) {
                    part.delete()
                    error("Incomplete download ($downloaded / ${asset.sizeBytes} bytes)")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                _state.value = State.ReadyToInstall(release, target)
            } catch (t: Throwable) {
                Log.e(TAG, "Download failed", t)
                _state.value = State.Error("Download failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Installs [apkFile]:
     * 1. Tries [LocalAdb] (`pm install -r -g`) if local ADB (`127.0.0.1:5555`) is reachable.
     * 2. Falls back to Android's package installer via [FileProvider].
     */
    fun installApk(release: GitHubRelease, apkFile: File) {
        if (!apkFile.isFile) {
            _state.value = State.Error("Downloaded APK is missing — tap Download again")
            return
        }
        scope.launch {
            _state.value = State.Installing(release, viaAdb = true)
            val adbResult = withContext(Dispatchers.IO) {
                withTimeoutOrNull(1800.milliseconds) {
                    runCatching { adb.connect() }.getOrDefault(false)
                } ?: false
            }

            if (adbResult) {
                prefs.markReopenAfterUpdate()
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val size = apkFile.length()
                        val p = LocalAdb.q(apkFile.absolutePath)
                        // Replacing our own package kills this process and drops the loopback ADB socket.
                        // `trap '' HUP` lets the chained `am start` survive SIGHUP, and `markReopenAfterUpdate()`
                        // makes BootReceiver/StartupService reopen MainActivity on MY_PACKAGE_REPLACED as a backup.
                        adb.shell("trap '' HUP; cat $p | pm install -r -g -S $size && am start -n ${ctx.packageName}/.MainActivity >/dev/null 2>&1 </dev/null")
                    }
                }
                val out = res.getOrNull()?.output?.trim().orEmpty()
                if (res.isSuccess && out.contains("Success")) {
                    _hasUpdate.value = false
                    _state.value = State.UpToDate(release, release.versionString)
                    return@launch
                }
                prefs.clearReopenAfterUpdate()
                if (res.isSuccess && out.isNotBlank() && !out.contains("Success")) {
                    _state.value = State.Error("ADB install failed: ${out.take(180)}")
                    return@launch
                }
            }

            val launched = launchSystemInstaller(apkFile)
            if (launched) {
                _state.value = State.ReadyToInstall(release, apkFile)
            } else {
                val saved = saveToDownloads(release, apkFile)
                _state.value = if (saved != null) {
                    State.SavedToDownloads(release, saved)
                } else {
                    State.Error("Package installer unavailable — try Save to Downloads and open BYD File Management")
                }
            }
        }
    }

    fun exportToDownloads(release: GitHubRelease, apkFile: File) {
        scope.launch(Dispatchers.IO) {
            val saved = saveToDownloads(release, apkFile)
            _state.value = if (saved != null) {
                State.SavedToDownloads(release, saved)
            } else {
                State.Error("Couldn't save APK to Downloads")
            }
        }
    }

    // ---- Wake helper (package tunein.player) ----

    sealed interface HelperState {
        data object Unknown : HelperState
        data object Installed : HelperState
        data object Missing : HelperState
        data object Downloading : HelperState
        data object Installing : HelperState
        data class Error(val message: String) : HelperState
    }

    private val _helper = MutableStateFlow<HelperState>(HelperState.Unknown)
    val helper: StateFlow<HelperState> = _helper

    fun isHelperInstalled(): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(HELPER_PKG, 0) }.isSuccess

    /** Re-reads whether the wake helper is installed (call when the screen opens). */
    fun refreshHelper() {
        if (_helper.value is HelperState.Downloading || _helper.value is HelperState.Installing) return
        _helper.value = if (isHelperInstalled()) HelperState.Installed else HelperState.Missing
    }

    /** Downloads the wake helper APK from the latest GitHub release and installs it. */
    fun downloadHelper() {
        if (_helper.value is HelperState.Downloading || _helper.value is HelperState.Installing) return
        _helper.value = HelperState.Downloading
        scope.launch(Dispatchers.IO) {
            try {
                val repo = normalizeRepo(prefs.githubRepo) ?: DEFAULT_REPO
                val release = fetchLatestRelease(repo) ?: error("No releases published yet on github.com/$repo")
                val asset = release.helperAsset ?: error("Release ${release.tagName} has no SharkOS-CE helper APK attached")
                val dir = updatesDir()
                val part = File(dir, "${asset.name}.part").also { if (it.exists()) it.delete() }
                val target = File(dir, asset.name)
                val conn = openFollowRedirects(asset.downloadUrl)
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} downloading ${asset.name}")
                var downloaded = 0L
                conn.inputStream.use { input -> FileOutputStream(part).use { out -> downloaded = input.copyTo(out) } }
                if (downloaded <= 0L || (asset.sizeBytes > 0L && downloaded != asset.sizeBytes)) {
                    part.delete()
                    error("Incomplete download ($downloaded / ${asset.sizeBytes} bytes)")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }

                _helper.value = HelperState.Installing
                val viaAdb = withTimeoutOrNull(1800.milliseconds) { runCatching { adb.connect() }.getOrDefault(false) } ?: false
                if (viaAdb) {
                    val out = runCatching {
                        adb.shell("cat ${LocalAdb.q(target.absolutePath)} | pm install -r -g -S ${target.length()}")
                    }.getOrNull()?.output?.trim().orEmpty()
                    if (out.contains("Success")) {
                        runCatching { adb.shell("cmd deviceidle whitelist +$HELPER_PKG") }
                        com.sharkhome.launcher.StartupService.startWakeHelper(ctx)
                        _helper.value = HelperState.Installed
                        return@launch
                    }
                    if (out.isNotBlank()) error("ADB install failed: ${out.take(160)}")
                }
                // No local ADB: hand over to Android's package installer, then re-check when the user returns.
                if (!launchSystemInstaller(target)) error("Package installer unavailable. Enable USB debugging for silent install.")
                _helper.value = HelperState.Missing
            } catch (t: Throwable) {
                Log.e(TAG, "Helper download failed", t)
                _helper.value = HelperState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Installs the wake helper that ships inside this app when it is missing or older than the bundled copy.
     * Silent: uses the head unit's own adb (the same connection used for everything else). Safe to call often; it
     * does nothing when the helper is current and gives up quietly when adb isn't available.
     */
    suspend fun ensureHelperInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (_helper.value is HelperState.Downloading || _helper.value is HelperState.Installing) return@withContext false
        try {
            val bundled = File(updatesDir(), "wakehelper-bundled.apk")
            ctx.assets.open("wakehelper.apk").use { input -> FileOutputStream(bundled).use { input.copyTo(it) } }
            @Suppress("DEPRECATION")
            val bundledCode = ctx.packageManager.getPackageArchiveInfo(bundled.absolutePath, 0)?.versionCode ?: return@withContext false
            val installedCode = runCatching { @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(HELPER_PKG, 0).versionCode }.getOrNull()
            // Also compare the bundled file's content, so a changed helper still updates if its versionCode wasn't bumped.
            val bundledHash = apkContentHash(bundled)
            if (installedCode != null && installedCode >= bundledCode) {
                // First run with this check: remember the hash (the installed helper was current by version) instead of reinstalling.
                if (prefs.helperHash.isEmpty()) prefs.helperHash = bundledHash
                if (prefs.helperHash == bundledHash) {
                    _helper.value = HelperState.Installed
                    return@withContext true
                }
                Log.e(TAG, "Bundled helper differs from the last installed one (same or lower versionCode): updating")
            }
            _helper.value = HelperState.Installing
            val viaAdb = withTimeoutOrNull(4000.milliseconds) { runCatching { adb.connect() }.getOrDefault(false) } ?: false
            if (!viaAdb) { _helper.value = HelperState.Missing; return@withContext false }
            val out = adb.shell("cat ${LocalAdb.q(bundled.absolutePath)} | pm install -r -g -S ${bundled.length()}").output.trim()
            if (!out.contains("Success")) { _helper.value = HelperState.Error("Helper install failed: ${out.take(120)}"); return@withContext false }
            runCatching { adb.shell("cmd deviceidle whitelist +$HELPER_PKG") }
            com.sharkhome.launcher.StartupService.startWakeHelper(ctx)
            prefs.helperHash = bundledHash
            _helper.value = HelperState.Installed
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Bundled helper install failed", t)
            _helper.value = HelperState.Error(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    /**
     * SHA-256 over every entry of an APK except META-INF (names and bytes, sorted). Two builds of the same source give
     * the same value even though their signature/build-info blocks differ, so the helper is only reinstalled when it changed.
     */
    private fun apkContentHash(apk: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                .sortedBy { it.name }.forEach { e ->
                    md.update(e.name.toByteArray())
                    zip.getInputStream(e).use { s -> val buf = ByteArray(65536); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun openReleasesPage() {
        val repo = normalizeRepo(prefs.githubRepo) ?: DEFAULT_REPO
        val url = "https://github.com/$repo/releases"
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun launchSystemInstaller(apkFile: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${ctx.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (permIntent.resolveActivity(ctx.packageManager) != null &&
                runCatching { ctx.startActivity(permIntent) }.isSuccess
            ) {
                return true
            }
        }
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent.resolveActivity(ctx.packageManager) != null &&
            runCatching { ctx.startActivity(intent) }.isSuccess
    }

    private fun saveToDownloads(release: GitHubRelease, apkFile: File): String? = runCatching {
        val name = "SharkOS-CE-${release.versionString}.apk"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            apkFile.inputStream().use { it.copyTo(out) }
        } ?: return null
        "Downloads/$name"
    }.getOrNull()

    private fun updatesDir(): File {
        val base = ctx.getExternalFilesDir("updates") ?: File(ctx.cacheDir, "updates")
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun existingApkFor(release: GitHubRelease): File? {
        val asset = release.apkAsset ?: return null
        val f = File(updatesDir(), asset.name)
        return f.takeIf { it.isFile && it.length() > 0L && (asset.sizeBytes <= 0L || it.length() == asset.sizeBytes) }
    }

    private fun fetchLatestRelease(repo: String): GitHubRelease? {
        val latestUrl = "https://api.github.com/repos/$repo/releases/latest"
        val conn = openJson(latestUrl)
        return when (conn.responseCode) {
            in 200..299 -> parseRelease(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            404 -> {
                // Fallback: repository may have pre-releases only (which /releases/latest omits).
                val listConn = openJson("https://api.github.com/repos/$repo/releases?per_page=5")
                if (listConn.responseCode !in 200..299) {
                    if (listConn.responseCode == 404) error("Repository github.com/$repo not found")
                    error("GitHub API HTTP ${listConn.responseCode}")
                }
                val arr = JSONArray(listConn.inputStream.bufferedReader().use { it.readText() })
                if (arr.length() == 0) null else parseRelease(arr.getJSONObject(0))
            }
            403 -> error("GitHub API rate limit reached — try again shortly")
            else -> error("GitHub API HTTP ${conn.responseCode}")
        }
    }

    private fun parseRelease(o: JSONObject): GitHubRelease {
        val tag = o.optString("tag_name", "").trim()
        val version = cleanVersion(tag)
        val assetsArr = o.optJSONArray("assets") ?: JSONArray()
        val apkAssets = (0 until assetsArr.length()).mapNotNull { i ->
            val a = assetsArr.getJSONObject(i)
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) null
            else ReleaseAsset(name, url, a.optLong("size", 0L))
        }
        fun isHelper(a: ReleaseAsset): Boolean {
            val l = a.name.lowercase()
            return "helper" in l
        }
        val nonHelper = apkAssets.filterNot(::isHelper)
        val preferred = nonHelper.firstOrNull {
            val l = it.name.lowercase()
            "sharkos" in l || "sharklauncher" in l || "app-release" in l
        } ?: nonHelper.firstOrNull() ?: apkAssets.firstOrNull()

        return GitHubRelease(
            tagName = tag.ifBlank { "unknown" },
            versionString = version,
            title = o.optString("name", "").ifBlank { tag },
            notes = o.optString("body", "").trim(),
            publishedAt = o.optString("published_at", "").take(10),
            htmlUrl = o.optString("html_url", ""),
            prerelease = o.optBoolean("prerelease", false),
            apkAsset = preferred,
            helperAsset = apkAssets.firstOrNull(::isHelper),
        )
    }

    private fun openJson(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SharkOS-CE/${currentVersionName()}")
        }

    private fun openFollowRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SharkOS-CE/${currentVersionName()}")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        error("Too many redirects")
    }

    companion object {
        private const val TAG = "SharkUpdater"
        const val DEFAULT_REPO = "xopticghostx/SharkOS-CE"
        const val HELPER_PKG = com.sharkhome.launcher.StartupService.HELPER_PKG

        fun normalizeRepo(raw: String): String? {
            val cleaned = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .trim('/')
                .removeSuffix(".git")
            val parts = cleaned.split('/').filter { it.isNotBlank() }
            return if (parts.size == 2) "${parts[0]}/${parts[1]}" else null
        }

        fun cleanVersion(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V")

        /** Returns true if [remote] is strictly newer than [local] using numeric dot-separated segments. */
        fun isNewerVersion(remote: String, local: String): Boolean {
            val rCore = cleanVersion(remote).substringBefore('-')
            val lCore = cleanVersion(local).substringBefore('-')
            val rParts = rCore.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val lParts = lCore.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val n = maxOf(rParts.size, lParts.size)
            for (i in 0 until n) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }
    }
}
