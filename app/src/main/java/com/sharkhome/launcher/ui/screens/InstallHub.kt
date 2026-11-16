package com.sharkhome.launcher.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sharkhome.launcher.container
import com.sharkhome.launcher.system.AppInstaller
import com.sharkhome.launcher.system.AppPermissions
import com.sharkhome.launcher.system.AppUpdater
import com.sharkhome.launcher.system.LocalAdb
import com.sharkhome.launcher.system.LocalAdb.Companion.q
import com.sharkhome.launcher.system.ReleaseInfo
import com.sharkhome.launcher.system.STORE_APPS
import com.sharkhome.launcher.system.StoreApp
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Install apps: get two useful managers from GitHub, browse the unit's folders for APK files, or search the web
 * in a small built-in browser. Every install uses pm install -g and then grants the app its special access.
 */
@Composable
fun InstallScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val perms = remember { AppPermissions(ctx, c.adb) }
    val installer = remember { AppInstaller(ctx, c.adb, perms) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var filesPath by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    val adbState by c.adb.state.collectAsState()
    // Read access to APK files (to show versions) comes from the all-files appop, granted over local ADB.
    LaunchedEffect(adbState) {
        if (adbState == LocalAdb.State.CONNECTED) runCatching { c.adb.shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow") }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Install apps", 32, FontWeight.Medium)
                T("Every install grants the app its permissions and special access.", 16, color = C.InkMuted)
            }
            LineSegmented(listOf("Get apps", "Files", "Web browser"), tab, { tab = it }, Modifier.width(520.dp), height = 52.dp)
        }
        if (tab != 2) AdbBar()
        when (tab) {
            0 -> GetAppsTab(installer) { url -> webUrl = url; tab = 2 }
            1 -> FilesTab(installer, filesPath) { filesPath = it }
            else -> WebTab(installer, webUrl, { webUrl = null }) { filesPath = installer.downloadsPath() + "/"; tab = 1 }
        }
    }
}

// ------------------------------------------------------------------------------------------------- Get apps

@Composable
private fun GetAppsTab(installer: AppInstaller, openWeb: (String) -> Unit) {
    val dev = remember { installer.device() }
    val wifi = remember { installer.onWifi() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 4.dp) {
            T(if (wifi) "Install these apps over Wi-Fi" else "You're on mobile data right now. Connect to Wi-Fi before downloading", 18, FontWeight.SemiBold, if (wifi) C.Ink else C.Alert)
            T("These downloads are large (Firefox is about 135 MB, Morphe about 50 MB, App Manager about 28 MB). Use Wi-Fi so they don't use the car's cell data.", 15, color = C.InkSecondary, maxLines = 3)
        }
        DCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 6.dp) {
            T("This head unit", 18, FontWeight.SemiBold)
            T("Android ${dev.android} (API ${dev.api}) · ${dev.abi} · ${dev.dpi} dpi", 16)
            T("Which APK to install: " + dev.advice, 15, color = C.InkSecondary, maxLines = 3)
        }
        STORE_APPS.forEach { StoreCard(it, installer, openWeb) }
        InstallRequestsCard()
    }
}

@Composable
private fun StoreCard(app: StoreApp, installer: AppInstaller, openWeb: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var result by remember { mutableStateOf<String?>(null) }
    var okResult by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf(app.pkg) }
    var installed by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        val known = pkg
        installed = if (known != null) installer.installedVersion(known) else {
            // Package name unknown until we have installed it once: look for it by name.
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).firstOrNull { it.loadLabel(pm).toString().contains(app.title.substringBefore(' '), ignoreCase = true) }
                ?.also { pkg = it.packageName }?.let { installer.installedVersion(it.packageName) }
        }
    }
    LaunchedEffect(Unit) {
        refreshInstalled()
        runCatching { installer.latest(app) }.onSuccess { release = it }.onFailure { error = it.message }
    }

    val rel = release
    val newer = rel != null && installed != null && AppUpdater.isNewerVersion(rel.tag, installed!!)
    DCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(app.title, 22, FontWeight.SemiBold)
                T(app.blurb, 15, color = C.InkSecondary, maxLines = 2)
                T((if (app.mozilla != null) "archive.mozilla.org" else "github.com/${app.repo}"), 13, color = C.InkMuted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(if (installed != null) "Installed v$installed" else "Not installed", 15, FontWeight.Medium, if (installed != null) C.Ok else C.InkMuted)
                T(rel?.let { "Latest ${it.tag}${if (it.published.isNotBlank()) " · ${it.published}" else ""}" } ?: (error ?: "Checking GitHub…"), 14, color = if (error != null) C.Alert else C.InkMuted)
            }
        }
        T("Which version: " + app.advice, 14, color = C.InkMuted, maxLines = 3)
        rel?.let { T("File: ${it.apkName} (${formatSize(it.apkSize)})", 14, color = C.InkMuted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DButton(
                text = when {
                    busy != null -> busy!!
                    rel == null -> "Download & install"
                    installed == null -> "Download & install ${rel.tag}"
                    newer -> "Update to ${rel.tag}"
                    else -> "Reinstall ${rel.tag}"
                },
                onClick = {
                    val r = rel ?: return@DButton
                    if (busy != null) return@DButton
                    scope.launch {
                        result = null
                        try {
                            busy = "Downloading…"; progress = 0f
                            val f = installer.download(r.apkUrl, r.apkName) { progress = it }
                            busy = "Installing…"
                            val out = installer.install(f.absolutePath, app.pkg)
                            okResult = out.ok; result = out.message
                            if (out.pkg != null) pkg = out.pkg
                            refreshInstalled()
                        } catch (t: Throwable) {
                            okResult = false; result = "Failed: ${t.message ?: t.javaClass.simpleName}"
                        } finally { busy = null; progress = -1f }
                    }
                },
                style = BtnStyle.Primary, minWidth = 300.dp,
            )
            DButton("Release notes", { rel?.let { openWeb(it.pageUrl) } ?: openWeb(if (app.mozilla != null) "https://www.mozilla.org/firefox/android/notes/" else "https://github.com/${app.repo}/releases") }, style = BtnStyle.Lightest)
            if (busy == "Downloading…" && progress >= 0f) T("${(progress * 100).toInt()} %", 16, color = C.InkSecondary)
        }
        result?.let { T(it, 15, FontWeight.Medium, if (okResult) C.Ok else C.Alert, maxLines = 3) }
    }
}

// ------------------------------------------------------------------------------------------------- Files

private data class FileEntry(val path: String, val isDir: Boolean, val size: Long) {
    val name get() = path.trimEnd('/').substringAfterLast('/')
    val folder get() = path.trimEnd('/').substringBeforeLast('/').removePrefix("/sdcard").ifBlank { "/" }
}

@Composable
private fun FilesTab(installer: AppInstaller, path: String, setPath: (String) -> Unit) {
    val adb = LocalContext.current.container.adb
    val state by adb.state.collectAsState()
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var hidden by remember { mutableStateOf(0) }
    var searchMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }

    suspend fun load(dir: String) {
        searchMode = false
        val d = if (dir.endsWith("/")) dir else "$dir/"
        val ls = adb.shell("ls -1Ap ${q(d)} 2>&1").output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dirs = ls.filter { it.endsWith("/") }.map { FileEntry(d + it, true, 0) }
        val apkNames = ls.filter { !it.endsWith("/") && it.lowercase().let { n -> n.endsWith(".apk") } }
        val sizes = if (apkNames.isEmpty()) emptyMap() else adb.shell("find ${q(d)} -maxdepth 1 -type f -iname '*.apk' -exec stat -c '%s|%n' {} +").output.lines()
            .mapNotNull { l -> l.indexOf('|').takeIf { it > 0 }?.let { l.substring(it + 1).trim() to (l.substring(0, it).toLongOrNull() ?: 0L) } }.toMap()
        val apks = apkNames.map { FileEntry(d + it, false, sizes[d + it] ?: 0L) }
        hidden = ls.size - dirs.size - apks.size
        entries = dirs.sortedBy { it.name.lowercase() } + apks.sortedBy { it.name.lowercase() }
    }
    suspend fun searchAll() {
        searchMode = true
        entries = null
        val r = adb.shell("find /sdcard/ /storage/ -maxdepth 5 -type f -iname '*.apk' -exec stat -c '%s|%n' {} + 2>/dev/null")
        entries = r.output.lines().mapNotNull { l -> l.indexOf('|').takeIf { it > 0 }?.let { FileEntry(l.substring(it + 1).trim(), false, l.substring(0, it).toLongOrNull() ?: 0L) } }
            .distinctBy { it.path }.sortedBy { it.name.lowercase() }
        hidden = 0
    }
    LaunchedEffect(state, path) { if (state == LocalAdb.State.CONNECTED) runCatching { load(path) } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DButton("Up", { val up = path.trimEnd('/').substringBeforeLast('/', "/").ifBlank { "/" }; setPath(if (up.endsWith("/")) up else "$up/") }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("Download", { setPath("/sdcard/Download/") }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("Internal storage", { setPath("/sdcard/") }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("USB / SD card", { setPath("/storage/") }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("Browser downloads", { setPath(installer.downloadsPath() + "/") }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("Search all storage", { scope.launch { runCatching { searchAll() } } }, style = BtnStyle.Lightest, height = 46.dp, textSize = 15)
            DButton("Refresh", { scope.launch { runCatching { if (searchMode) searchAll() else load(path) } } }, height = 46.dp, textSize = 15)
        }
        T(if (searchMode) "Search results: every APK on the unit" else path, 15, FontWeight.Medium, C.InkSecondary, maxLines = 1)
        DCard(Modifier.fillMaxWidth().weight(1f), padding = 0.dp, spacing = 0.dp) {
            val list = entries
            when {
                state != LocalAdb.State.CONNECTED -> Empty("Connect first to browse files.")
                list == null -> Empty("Looking…")
                list.isEmpty() -> Empty("No folders or APK files here." + if (hidden > 0) " ($hidden other files hidden)" else "")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.path }) { e ->
                        if (e.isDir) {
                            Row(
                                Modifier.fillMaxWidth().height(60.dp).clickable { setPath(e.path) }.padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                T("▸", 20, color = C.InkMuted)
                                T(e.name, 18, FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                        } else {
                            val peek = remember(e.path) { installer.peek(e.path) }
                            val inst = peek?.let { installer.installedVersion(it.first) }
                            val res = results[e.path]
                            Row(
                                Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    T(e.name, 18, FontWeight.Medium, maxLines = 1)
                                    T(
                                        res?.second ?: buildString {
                                            append(formatSize(e.size)); append(" · "); append(if (searchMode) e.folder else "this folder")
                                            if (peek != null) { append(" · v"); append(peek.second); if (inst != null) append(" (installed v$inst)") }
                                        },
                                        14, color = when { res == null -> C.InkMuted; res.first -> C.Ok; else -> C.Alert }, maxLines = 2,
                                    )
                                }
                                DButton(
                                    if (busy == e.path) "Installing…" else "Install",
                                    {
                                        if (busy == null) scope.launch {
                                            busy = e.path
                                            val o = runCatching { installer.install(e.path) }.getOrElse { com.sharkhome.launcher.system.InstallOutcome(false, null, null, "Failed: ${it.message}") }
                                            results = results + (e.path to (o.ok to o.message))
                                            busy = null
                                        }
                                    },
                                    style = BtnStyle.Primary, minWidth = 140.dp,
                                )
                            }
                        }
                        Hairline()
                    }
                }
            }
        }
        if (hidden > 0 && !searchMode) T("$hidden other files hidden (only folders and .apk files are shown).", 13, color = C.InkMuted)
    }
}

// ------------------------------------------------------------------------------------------------- Web browser

private data class Download(val name: String, val path: String?, val progress: Float, val error: String?, val done: Boolean)

private fun normaliseUrl(input: String): String {
    val t = input.trim()
    return when {
        t.isEmpty() -> "https://duckduckgo.com/"
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains(' ') || !t.contains('.') -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        else -> "https://$t"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebTab(installer: AppInstaller, pending: String?, consumed: () -> Unit, showDownloads: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val dev = remember { installer.device() }
    var field by remember { mutableStateOf("") }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateListOf<Download>() }
    var installMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showHelp by remember { mutableStateOf(true) }

    val web: WebView? = remember {
        runCatching {
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                        canBack = v.canGoBack(); canFwd = v.canGoForward(); url?.let { field = it }
                    }
                    override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { loading = true }
                    override fun onPageFinished(v: WebView, url: String?) { loading = false }
                    override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean =
                        r.url.scheme !in listOf("http", "https") // ignore intent:, market: and other app links
                }
                webChromeClient = WebChromeClient()
                setDownloadListener { url, ua, cd, mime, _ ->
                    val name = URLUtil.guessFileName(url, cd, mime)
                    val idx = downloads.size
                    downloads.add(Download(name, null, 0f, null, false))
                    val cookie = CookieManager.getInstance().getCookie(url)
                    scope.launch {
                        try {
                            val f = installer.download(url, name, ua, cookie) { p -> downloads[idx] = downloads[idx].copy(progress = p) }
                            downloads[idx] = downloads[idx].copy(path = f.absolutePath, progress = 1f, done = true)
                        } catch (t: Throwable) {
                            downloads[idx] = downloads[idx].copy(error = t.message ?: t.javaClass.simpleName, done = true)
                        }
                    }
                }
                loadUrl("https://www.apkmirror.com/")
            }
        }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { web?.stopLoading() } }
    BackHandler(enabled = canBack) { web?.goBack() }
    LaunchedEffect(pending) { pending?.let { web?.loadUrl(it); consumed() } }

    if (web == null) {
        DCard(Modifier.fillMaxWidth(), padding = 24.dp, spacing = 12.dp) {
            T("This head unit has no built-in web view", 20, FontWeight.SemiBold)
            T("Use BYD's own browser to find the APK, then come back to the Files tab to install it.", 16, color = C.InkSecondary)
            DButton("Open BYD browser", { c.apps.launchPackage("com.byd.browser", "BYD browser") }, style = BtnStyle.Primary)
        }
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton("‹", { web.goBack() }, style = BtnStyle.Lightest, height = 48.dp, minWidth = 56.dp)
            DButton("›", { web.goForward() }, style = BtnStyle.Lightest, height = 48.dp, minWidth = 56.dp)
            DButton(if (loading) "✕" else "⟳", { if (loading) web.stopLoading() else web.reload() }, style = BtnStyle.Lightest, height = 48.dp, minWidth = 56.dp)
            Box(
                Modifier.weight(1f).height(48.dp).clip(Radius4).background(C.Card2).border(1.dp, C.Line, Radius4).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    field, { field = it }, singleLine = true,
                    textStyle = TextStyle(color = C.Ink, fontSize = 16.sp), cursorBrush = SolidColor(C.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web.loadUrl(normaliseUrl(field)) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DButton("Go", { web.loadUrl(normaliseUrl(field)) }, style = BtnStyle.Primary, height = 48.dp, minWidth = 80.dp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((label, url) in listOf(
                "APKMirror" to "https://www.apkmirror.com/", "F-Droid" to "https://f-droid.org/", "GitHub" to "https://github.com/",
                "Search the web" to "https://duckduckgo.com/",
            )) DButton(label, { web.loadUrl(url) }, style = BtnStyle.Lightest, height = 40.dp, textSize = 14)
            DButton(if (showHelp) "Hide advice" else "Which APK?", { showHelp = !showHelp }, style = BtnStyle.Lightest, height = 40.dp, textSize = 14)
        }
        if (showHelp) DCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 2.dp) {
            T("Which APK to download on this unit (Android ${dev.android}, ${dev.abi}, ${dev.dpi} dpi)", 15, FontWeight.SemiBold)
            T(dev.advice + " If the newest version needs a newer Android, choose an older release. Download over Wi-Fi, not mobile data.", 14, color = C.InkSecondary, maxLines = 3)
        }
        downloads.toList().forEachIndexed { i, d ->
            DCard(Modifier.fillMaxWidth(), padding = 12.dp, spacing = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        T(d.name, 16, FontWeight.Medium, maxLines = 1)
                        T(
                            when {
                                d.error != null -> "Download failed: ${d.error}"
                                !d.done -> if (d.progress >= 0f) "Downloading ${(d.progress * 100).toInt()} %" else "Downloading…"
                                d.name.endsWith(".apk", true) -> installMsg?.second ?: "Downloaded"
                                else -> "Downloaded, but this isn't a plain .apk (bundles like .apkm / .xapk can't be installed). Go back and choose the APK variant."
                            }, 14, color = if (d.error != null || (d.done && !d.name.endsWith(".apk", true))) C.Alert else C.InkMuted, maxLines = 2,
                        )
                    }
                    if (d.done && d.error == null && d.name.endsWith(".apk", true) && d.path != null) {
                        DButton("Install", {
                            scope.launch {
                                installMsg = true to "Installing…"
                                val o = runCatching { installer.install(d.path) }.getOrElse { com.sharkhome.launcher.system.InstallOutcome(false, null, null, "Failed: ${it.message}") }
                                installMsg = o.ok to o.message
                            }
                        }, style = BtnStyle.Primary, height = 44.dp, textSize = 15)
                    }
                    DButton("Remove", { downloads.removeAt(i) }, style = BtnStyle.Lightest, height = 44.dp, textSize = 15)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(Radius4).border(1.dp, C.Line, Radius4)) {
            AndroidView({ web }, Modifier.fillMaxSize())
        }
    }
}

// ------------------------------------------------------------------------------------------------- install requests

/**
 * SharkOS-CE answers "install this APK" requests itself (file managers, browsers, other apps). If BYD's own chooser or
 * installer doesn't work, the stock installers can be switched off so that ours is the only answer. Reversible.
 * Apps that install silently through the system installer's confirm box still use the stock installer.
 */
@Composable
private fun InstallRequestsCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val others = remember(tick) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/file/1"), "application/vnd.android.package-archive")
        val pm = ctx.packageManager
        val live = pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val off = c.prefs.disabledInstallers.split(',').filter { it.isNotBlank() }.map { p ->
            p to (runCatching { pm.getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString() }.getOrDefault(p))
        }
        (live.filter { it.first != ctx.packageName } + off).distinctBy { it.first }
    }
    DCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 8.dp) {
        T("APK install requests", 20, FontWeight.SemiBold)
        T("When you open an APK from a file manager, a browser or another app, SharkOS-CE now offers its own install screen (all permissions granted). If BYD's chooser doesn't work, switch off the stock installer below so ours is the only answer.", 15, color = C.InkSecondary, maxLines = 4)
        T("Apps that install in the background, like Morphe patching an app, still use the system's own confirm box and can't be intercepted. Switching a stock installer off may stop those from installing: switch it back on if so.", 14, color = C.Warn, maxLines = 3)
        if (others.isEmpty()) T("No other system installer answers APK requests on this unit.", 15, color = C.InkMuted)
        others.forEach { (pkg, label) ->
            val off = pkg in c.prefs.disabledInstallers.split(',')
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    T(label, 17, FontWeight.Medium)
                    T(pkg + if (off) " · switched off" else "", 13, color = C.InkMuted)
                }
                DButton(if (off) "Switch back on" else "Switch off", {
                    scope.launch {
                        val r = runCatching { c.adb.shell(if (off) "pm enable ${q(pkg)}" else "pm disable-user --user 0 ${q(pkg)}") }.getOrNull()
                        if (r != null && r.ok) {
                            val set = c.prefs.disabledInstallers.split(',').filter { it.isNotBlank() }.toMutableSet()
                            if (off) set.remove(pkg) else set.add(pkg)
                            c.prefs.disabledInstallers = set.joinToString(",")
                            msg = if (off) "$label switched back on" else "$label switched off. SharkOS-CE now answers APK requests."
                        } else msg = "Couldn't change it: ${r?.output?.take(120) ?: "no ADB connection"}"
                        tick++
                    }
                }, style = if (off) BtnStyle.Primary else BtnStyle.Lightest, height = 46.dp, textSize = 15)
            }
        }
        msg?.let { T(it, 15, FontWeight.Medium, C.InkSecondary, maxLines = 2) }
    }
}
