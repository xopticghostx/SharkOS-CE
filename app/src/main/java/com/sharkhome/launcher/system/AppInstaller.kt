package com.sharkhome.launcher.system

import android.content.Context
import android.os.Build
import android.util.Log
import com.sharkhome.launcher.system.LocalAdb.Companion.q
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** An app offered on the Get apps tab, installed from its latest GitHub release. */
class StoreApp(
    val id: String,
    val title: String,
    val blurb: String,
    val repo: String,
    /** Plain-English advice on which file to pick, shown on the card. */
    val advice: String,
    /** Known package name, if any (otherwise it is read from the downloaded APK). */
    val pkg: String?,
    /** Set for apps Mozilla publishes itself (not on GitHub): the folder name on archive.mozilla.org, e.g. "fenix". */
    val mozilla: String? = null,
)

val STORE_APPS = listOf(
    StoreApp(
        "firefox", "Firefox", "The full Firefox browser with tabs, extensions and sync, for browsing on the unit. The unit has no Google Play Store.",
        "mozilla/firefox-android",
        "Mozilla's own release for this unit's processor (arm64 on the car), straight from Mozilla's server. About 135 MB, so use Wi-Fi.",
        "org.mozilla.firefox", mozilla = "fenix",
    ),
    StoreApp(
        "appmanager", "App Manager", "Inspect, back up and manage apps, permissions and tracking, using this unit's ADB.",
        "MuntashirAkon/AppManager",
        "Install the universal APK of the latest stable release. It needs no root here: it uses the unit's ADB.",
        "io.github.muntashirakon.AppManager",
    ),
    StoreApp(
        "morphe", "Morphe Manager", "Patch apps with Morphe (the ReVanced fork) on the unit itself.",
        "MorpheApp/morphe-manager",
        "Install the latest stable release (not a -dev build). The universal APK runs on Android 8 and later, so it suits Android 11.",
        "app.morphe.manager",
    ),
)

data class ReleaseInfo(val tag: String, val name: String, val published: String, val apkName: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

data class InstallOutcome(val ok: Boolean, val pkg: String?, val version: String?, val message: String)

/** What this head unit is, for choosing the right APK. */
data class DeviceInfo(val android: String, val api: Int, val abi: String, val dpi: Int) {
    val advice: String
        get() = "Choose the " + (if (abi.contains("arm64")) "arm64-v8a or universal" else "$abi or universal") +
            " build, with a minimum Android of $android or lower, and density nodpi or about ${dpi} dpi. " +
            "Avoid x86 builds and .apkm / .xapk bundles."
}

class AppInstaller(private val ctx: Context, private val adb: LocalAdb, private val perms: AppPermissions) {

    fun device() = DeviceInfo(
        Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", ctx.resources.displayMetrics.densityDpi,
    )

    /** Latest stable GitHub release of [repo] and the APK to install from it (universal / arm64 preferred). */
    suspend fun latest(app: StoreApp): ReleaseInfo {
        val repo = app.repo
        val hit = releaseCache[repo]
        if (hit != null && System.currentTimeMillis() - hit.first < 10 * 60_000L) return hit.second
        return try {
            (if (app.mozilla != null) fetchMozilla(app.mozilla) else fetchLatest(repo)).also { releaseCache[repo] = System.currentTimeMillis() to it }
        } catch (t: Throwable) {
            // GitHub allows 60 anonymous lookups an hour: fall back to the last answer rather than showing nothing
            hit?.second ?: throw t
        }
    }

    /** Latest stable Firefox for this unit's processor, from Mozilla's release server (no Play Store needed). */
    private suspend fun fetchMozilla(product: String): ReleaseInfo = withContext(Dispatchers.IO) {
        val j = URL("https://product-details.mozilla.org/1.0/mobile_versions.json").openStream().bufferedReader().use { it.readText() }
        val ver = JSONObject(j).optString("version").ifBlank { error("Couldn't read Firefox's current version") }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64") } ?: "arm64-v8a"
        val name = "$product-$ver.multi.android-$abi.apk"
        val url = "https://archive.mozilla.org/pub/$product/releases/$ver/android/$product-$ver-android-$abi/$name"
        val head = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; connectTimeout = 10_000; readTimeout = 15_000 }
        if (head.responseCode !in 200..299) error("Mozilla's server has no $abi build of Firefox $ver")
        ReleaseInfo(ver, "Firefox $ver", "", name, url, head.contentLengthLong, "https://www.mozilla.org/firefox/android/notes/")
    }

    /** True when the unit is connected through Wi-Fi or Ethernet (not mobile data). */
    fun onWifi(): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(true)

    private suspend fun fetchLatest(repo: String): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SharkOS-CE")
        }
        when (conn.responseCode) {
            in 200..299 -> Unit
            404 -> error("No stable release published for $repo")
            403 -> error("GitHub rate limit reached, try again shortly")
            else -> error("GitHub HTTP ${conn.responseCode}")
        }
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val assets = o.optJSONArray("assets") ?: JSONArray()
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", true) }
        fun score(n: String): Int {
            val l = n.lowercase()
            return when {
                "x86" in l || "armeabi" in l || "arm-v7" in l || "v7a" in l -> -10
                "arm64" in l -> 3
                "universal" in l -> 2
                else -> 1
            }
        }
        val best = apks.maxByOrNull { score(it.optString("name")) } ?: error("The release has no APK file attached")
        ReleaseInfo(
            o.optString("tag_name"), o.optString("name").ifBlank { o.optString("tag_name") }, o.optString("published_at").take(10),
            best.optString("name"), best.optString("browser_download_url"), best.optLong("size"), o.optString("html_url"),
        )
    }

    /** Downloads [url] to the app's own download folder, reporting progress as a fraction (or -1 when the size is unknown). */
    suspend fun download(url: String, name: String, userAgent: String? = null, cookie: String? = null, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = (ctx.getExternalFilesDir("downloads") ?: File(ctx.cacheDir, "downloads")).also { it.mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        var current = url
        var conn: HttpURLConnection? = null
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent ?: "SharkOS-CE")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            if (c.responseCode in 300..399) {
                val loc = c.getHeaderField("Location") ?: return@repeat
                c.disconnect(); current = URL(URL(current), loc).toString()
            } else { conn = c; return@repeat }
        }
        val c = conn ?: error("Too many redirects")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} downloading $name")
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { input -> FileOutputStream(part).use { out ->
            val buf = ByteArray(32 * 1024); var last = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n
                val now = System.currentTimeMillis()
                if (now - last > 150) { onProgress(if (total > 0) done.toFloat() / total else -1f); last = now }
            }
        } }
        if (done <= 0L) error("Empty download")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, true); part.delete() }
        target
    }

    private suspend fun packages(): Set<String> =
        adb.shell("pm list packages -3").output.lines().mapNotNull { it.trim().removePrefix("package:").takeIf { s -> s.isNotBlank() } }.toSet()

    /** The package name and version inside an APK file, if this app is allowed to read it. */
    fun peek(path: String): Pair<String, String>? = runCatching {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageArchiveInfo(path, 0)
        pi?.let { it.packageName to (it.versionName ?: "?") }
    }.getOrNull()

    fun installedVersion(pkg: String): String? = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    /**
     * Installs [path] with every runtime permission (pm install -g), then grants the special access the app asks for
     * (display over apps, install other apps, all-files access, usage access, settings) and exempts it from battery limits.
     */
    suspend fun install(path: String, knownPkg: String? = null): InstallOutcome {
        if (adb.state.value != LocalAdb.State.CONNECTED && !adb.connect()) {
            return InstallOutcome(false, null, null, "Turn on USB debugging first: " + adb.message.value)
        }
        val before = runCatching { packages() }.getOrDefault(emptySet())
        val size = adb.shell("stat -c %s ${q(path)}").output.trim().toLongOrNull()
            ?: return InstallOutcome(false, null, null, "Can't read that file")
        val peeked = peek(path)
        val r = adb.shell("cat ${q(path)} | pm install -r -g -S $size")
        val out = r.output.trim()
        if (!out.contains("Success")) {
            return InstallOutcome(false, null, null, when {
                "INSTALL_FAILED_OLDER_SDK" in out -> "This app needs a newer Android than this unit has (Android ${Build.VERSION.RELEASE}). Pick an older version."
                "INSTALL_FAILED_NO_MATCHING_ABIS" in out -> "Wrong build for this unit's processor (${device().abi}). Pick the arm64-v8a or universal APK."
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out || "signatures do not match" in out -> "Installed already with a different signature. Uninstall that copy first."
                "INSTALL_FAILED_VERSION_DOWNGRADE" in out -> "A newer version is already installed."
                "INSTALL_PARSE_FAILED" in out -> "That isn't a normal APK (bundle files like .apkm or .xapk can't be installed this way)."
                else -> "Failed: " + out.take(160)
            })
        }
        val after = runCatching { packages() }.getOrDefault(emptySet())
        val pkg = knownPkg ?: peeked?.first ?: (after - before).singleOrNull()
        if (pkg == null) return InstallOutcome(true, null, peeked?.second, "Installed. Couldn't tell which app it was, so permissions were granted by -g only.")
        val granted = runCatching { grantAll(pkg) }.getOrDefault(0)
        return InstallOutcome(true, pkg, installedVersion(pkg) ?: peeked?.second, "Installed, $granted permissions and access switches granted")
    }

    /** Grants everything it can for [pkg]. Returns how many permissions / switches were set. */
    suspend fun grantAll(pkg: String): Int {
        var n = 0
        val pi = runCatching { ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS) }.getOrNull()
        for (p in pi?.requestedPermissions.orEmpty()) {
            if (p.startsWith("android.permission.") || p.startsWith("android.car.")) {
                val r = perms.setPerm(pkg, p, true)
                if (r.ok) n++
            }
        }
        for ((op, perm, _) in AppPermissions.APP_OPS) {
            if (pi?.requestedPermissions?.contains(perm) == true) {
                if (perms.setSpecial(pkg, op, true).ok) n++
            }
        }
        if (perms.setSpecial(pkg, AppPermissions.BATTERY, true).ok) n++
        Log.e("SharkInstall", "granted $n for $pkg")
        return n
    }

    private companion object {
        val releaseCache = HashMap<String, Pair<Long, ReleaseInfo>>()
    }

    /** Where downloads from the built-in browser and GitHub are kept (readable by adb, so the Files tab can install them). */
    fun downloadsPath(): String = (ctx.getExternalFilesDir("downloads") ?: File(ctx.cacheDir, "downloads")).absolutePath
}
