package com.skyrimayn

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Nexus Mods on the top screen, as a native mod browser: search, mod pages, requirements and files,
 * with one-tap download & install. Only signing in (and, for free accounts, Nexus's one-click download
 * confirmation) shows Nexus's own web pages.
 */
class NexusActivity : ComponentActivity() {
    private var onGamepadBack: (() -> Unit)? = null

    companion object {
        /** Opens the browser on the top screen (from the bottom-screen mod manager). */
        /** [modId]: open straight on that mod's page (e.g. the Script Extender). */
        /** [getSkse]: setup mode. Sign in if needed, then download and install SKSE, and close once it's in. */
        fun open(from: Context, modId: Int = 0, getSkse: Boolean = false) {
            val intent = Intent(from, NexusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("mod", if (getSkse) ModManager.SKSE_NEXUS_ID else modId).putExtra("getSkse", getSkse)
            from.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModManager.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Opened from an nxm:// link elsewhere: hand it to the mod manager.
        intent?.data?.takeIf { it.scheme.equals("nxm", true) }?.let { ModManager.handleNxm(it.toString()) }
        val startMod = intent?.getIntExtra("mod", 0) ?: 0
        val getSkse = intent?.getBooleanExtra("getSkse", false) == true
        setContent { NexusRoot(onClose = ::finish, registerBack = { onGamepadBack = it }, startMod = startMod, getSkse = getSkse) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP) (onGamepadBack ?: ::finish).invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Browse state kept while a mod page is open, so Back returns to the same results. */
private class BrowseState {
    var query by mutableStateOf("")
    var applied by mutableStateOf("")
    var sort by mutableStateOf(NexusApi.Sort.POPULAR)
    /** Only this creator's mods, when set. */
    var author by mutableStateOf<String?>(null)
    /** The adult-mods setting the current results were loaded with. */
    var loadedAdult: Boolean? = null
    val mods = mutableStateListOf<NexusApi.Mod>()
    var total by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var generation = 0
}

@Composable
private fun NexusRoot(onClose: () -> Unit, registerBack: ((() -> Unit)?) -> Unit, startMod: Int = 0, getSkse: Boolean = false) {
    val account by ModManager.account.collectAsState()
    if (getSkse) {
        // Setup: as soon as there's a Nexus account, fetch SKSE; close when it's in the game folder.
        var asked by remember { mutableStateOf(false) }
        LaunchedEffect(account) {
            if (account != null && !asked && skseIfInstalled().isEmpty()) { asked = true; ModManager.getSkse() }
        }
        LaunchedEffect(Unit) {
            while (skseIfInstalled().isEmpty()) delay(1000)
            onClose()
        }
    }
    val confirm by ModManager.freeConfirm.collectAsState()
    val reqPrompt by ModManager.reqPrompt.collectAsState()
    var openMod by remember { mutableStateOf<Int?>(startMod.takeIf { it > 0 }) }
    val history = remember { mutableStateListOf<Int>() }
    val browse = remember { BrowseState() }
    var browseFirst by remember { mutableStateOf(false) }

    fun back() {
        when {
            reqPrompt != null -> ModManager.answerRequirements(null)
            confirm != null -> ModManager.cancelFreeConfirm()
            history.isNotEmpty() -> openMod = history.removeAt(history.lastIndex)
            openMod != null -> openMod = null
            else -> onClose()
        }
    }
    DisposableEffect(Unit) { registerBack(::back); onDispose { registerBack(null) } }
    BackHandler { back() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
        Column(Modifier.fillMaxSize()) {
            Header(account, onClose)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    account == null && !browseFirst -> SignInPage(onSkip = { browseFirst = true })
                    openMod != null -> ModPage(openMod!!, onOpen = { id -> openMod?.let(history::add); openMod = id }, onBack = ::back,
                        onNeedSignIn = { browseFirst = false },
                        onAuthor = { name -> browse.author = name; browse.mods.clear(); history.clear(); openMod = null })
                    else -> BrowsePage(browse, onOpen = { openMod = it })
                }
            }
            DownloadStrip()
        }
        confirm?.let { FreeConfirmPanel(it) }
        reqPrompt?.let { RequirementsDialog(it) }
    }
}

/** "This mod needs other mods": get them first (recommended), only this one, or cancel. */
@Composable
private fun RequirementsDialog(p: ModManager.RequirementsPrompt) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Color(0xFF0B0B0B)).border(1.dp, Sky.Frame).padding(20.dp)) {
            SkyText("${p.mod.name.uppercase()} NEEDS", 18.sp, Sky.Text, 3.sp, maxLines = 2)
            Rule(Modifier.padding(vertical = 8.dp), color = Sky.Faint)
            p.missing.forEach { r ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    DiamondMark(false, Sky.Health, Modifier.size(8.dp)); Spacer(Modifier.width(10.dp))
                    Column {
                        SkyText(r.name, 16.sp, Sky.Text, 0.3.sp, maxLines = 2)
                        if (r.notes.isNotBlank()) SkyText(r.notes, 12.sp, Sky.Dim, 0.3.sp, light = true, maxLines = 2)
                    }
                }
            }
            SkyText("Without these the mod may not work, or the game may not start.", 12.sp, Sky.Dim, 0.5.sp, light = true,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkyText("GET THEM FIRST ›", 15.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { ModManager.answerRequirements(true) }
                    .border(1.dp, Sky.Line).padding(horizontal = 14.dp, vertical = 9.dp))
                Spacer(Modifier.width(16.dp))
                SkyText("ONLY THIS MOD", 13.sp, Sky.Body, 2.sp, modifier = Modifier.clickable { ModManager.answerRequirements(false) }.padding(9.dp))
                Spacer(Modifier.weight(1f))
                SkyText("CANCEL", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable { ModManager.answerRequirements(null) }.padding(9.dp))
            }
        }
    }
}


@Composable
private fun Header(account: NexusApi.User?, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SkyText("NEXUS MODS", 20.sp, Sky.Text, 5.sp)
                SkyText("SKYRIM SPECIAL EDITION", 10.sp, Sky.Dim, 3.sp)
            }
            if (account != null) {
                Column(horizontalAlignment = Alignment.End) {
                    SkyText(account.name, 14.sp, Sky.Text, 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkyText(if (account.premium) "PREMIUM MEMBER" else "FREE MEMBER", 9.sp, Sky.Dim, 2.sp)
                        SkyText("   ·   SIGN OUT", 9.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable { ModManager.signOut() }.padding(vertical = 4.dp))
                    }
                }
                Spacer(Modifier.width(28.dp))
                Box(Modifier.width(1.dp).height(30.dp).background(Sky.Faint))
                Spacer(Modifier.width(20.dp))
            }
            SkyText("DONE", 16.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Rule(Modifier.padding(top = 4.dp))
    }
}

// ================================================================ sign in

private const val KEY_PAGE = "https://next.nexusmods.com/settings/api-keys"
private const val LOGIN_PAGE = "https://users.nexusmods.com/auth/sign_in"

/** Finds a personal API key on the page (they look like "abc…==--def…--ghi…==="). */
private const val FIND_KEY_JS = """(function(){var re=/[A-Za-z0-9+\/=_-]{20,}--[A-Za-z0-9+\/=_-]{8,}--[A-Za-z0-9+\/=_-]{8,}/;
var t=document.body?document.body.innerText:'';var m=t.match(re);if(m)return m[0];
var ins=document.querySelectorAll('input,textarea');for(var i=0;i<ins.length;i++){var v=ins[i].value||'';var mm=v.match(re);if(mm)return mm[0];}
return '';})()"""

/** On the API keys page: if there's no personal key yet, press "Request API key"; reveal a hidden key. Never deletes anything. */
private const val REVEAL_KEY_JS = """(function(){var bs=Array.from(document.querySelectorAll('button,a'));
var ok=function(e){var t=(e.innerText||e.getAttribute('aria-label')||'').trim();return t.length<40&&!/delete|remove|revoke|regenerate/i.test(t)?t:'';};
var req=bs.find(function(e){return /request.*key|generate.*key|create.*key/i.test(ok(e));});
if(req&&!window.__skReq){window.__skReq=1;req.click();return 'requested';}
var show=bs.find(function(e){return /^(show|reveal|view)( key)?$/i.test(ok(e));});
if(show&&!window.__skShow){window.__skShow=1;show.click();return 'shown';}
return '';})()"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInPage(onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loggedIn by remember { mutableStateOf(false) }
    fun tryKey(key: String) {
        if (busy) return
        busy = true; status = "Checking your key…"
        scope.launch { status = ModManager.signIn(key); busy = false }
    }
    // Poll the page for the key once the player is logged in and on the API keys page.
    DisposableEffect(web) {
        val h = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                web?.evaluateJavascript(FIND_KEY_JS) { v -> val k = v.trim('"'); if (k.length > 40 && !busy) tryKey(k) }
                if (web?.url?.startsWith(KEY_PAGE) == true) web?.evaluateJavascript(REVEAL_KEY_JS, null)
                h.postDelayed(this, 1500)
            }
        }
        h.postDelayed(poll, 1500)
        onDispose { h.removeCallbacks(poll) }
    }
    Row(Modifier.fillMaxSize().padding(18.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight().padding(end = 18.dp)) {
            SkyText("SIGN IN", 22.sp, Sky.Text, 4.sp)
            Rule(Modifier.padding(vertical = 8.dp), color = Sky.Faint)
            SkyText(
                (if (loggedIn) "LOGGED IN  ·  GETTING YOUR KEY…\n\n"
                 else "Log in to Nexus Mods on the right. A free account is fine. That's all: your personal API key is collected for you.\n\n") +
                    "WHY: Nexus Mods only lets signed-in members download. The key lets this app search, download and install mods for you, " +
                    "like Vortex or Mod Organizer on PC. It stays on this device and you can sign out any time.",
                14.sp, Sky.Body, 0.3.sp, light = true, maxLines = 16,
            )
            Spacer(Modifier.height(14.dp))
            SkyText("OR PASTE YOUR KEY", 11.sp, Sky.Dim, 2.sp)
            Spacer(Modifier.height(4.dp))
            SkyrimField(pasted, { pasted = it }, "API key")
            Spacer(Modifier.height(8.dp))
            SkyText("USE THIS KEY ›", 15.sp, if (pasted.length > 20) Sky.Text else Sky.Faint, 3.sp,
                modifier = Modifier.clickable(enabled = pasted.length > 20) { tryKey(pasted) }.padding(vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            status?.let { SkyText(it, 13.sp, if (busy) Sky.Dim else Sky.Health, 0.5.sp, maxLines = 3) }
            SkyText("BROWSE FIRST ›", 12.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable(onClick = onSkip).padding(vertical = 6.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row {
                SkyText("LOG IN", 14.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { web?.loadUrl(LOGIN_PAGE) }.padding(end = 20.dp, bottom = 6.dp))
                SkyText("MY API KEY", 14.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { web?.loadUrl(KEY_PAGE) }.padding(bottom = 6.dp))
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // Once you're logged in (Nexus sends you away from the sign-in pages), go to the key page by itself.
                            override fun onPageFinished(view: WebView, url: String) {
                                val onAuth = url.contains("users.nexusmods.com") || url.contains("/auth") || url.contains("sign_in") || url.contains("login")
                                if (!onAuth && url.contains("nexusmods.com")) {
                                    loggedIn = true
                                    if (!url.startsWith(KEY_PAGE)) view.loadUrl(KEY_PAGE)
                                }
                            }
                        }
                        // Start at the sign-in page; if you're already logged in Nexus forwards you on, and we go to the key page.
                        loadUrl(LOGIN_PAGE)
                        web = this
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize().border(1.dp, Sky.Faint),
            )
        }
    }
}

// ================================================================ browse

@Composable
private fun BrowsePage(s: BrowseState, onOpen: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val installed = ModManager.mods.collectAsState().value.map { it.nexusId }.toSet() + skseIfInstalled()
    val adult = LauncherSettings.values.collectAsState().value[LauncherSettings.adultMods.key] == "Shown"
    fun load(reset: Boolean) {
        if (s.loading && !reset) return
        val gen = if (reset) ++s.generation else s.generation
        if (reset) { s.mods.clear(); s.total = 0 }
        s.loading = true; s.error = null
        scope.launch {
            try {
                val (page, total) = NexusApi.browse(s.applied, s.sort, s.mods.size, author = s.author, adult = adult)
                if (gen == s.generation) { s.mods.addAll(page.filter { m -> s.mods.none { it.modId == m.modId } }); s.total = total }
            } catch (e: Exception) {
                if (gen == s.generation) s.error = e.message ?: "Couldn't reach Nexus Mods"
            } finally {
                if (gen == s.generation) s.loading = false
            }
        }
    }
    // First open, returning from a creator link, or the adult setting changed.
    LaunchedEffect(adult, s.author) { if (s.mods.isEmpty() || s.loadedAdult != adult) { s.loadedAdult = adult; load(true) } }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= s.mods.size - 6 } }
    LaunchedEffect(nearEnd, s.mods.size) { if (nearEnd && s.mods.isNotEmpty() && s.mods.size < s.total && !s.loading) load(false) }

    var mine by remember { mutableStateOf(false) }
    val myMods by ModManager.mods.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "BROWSE", true to "MY MODS (${myMods.size})").forEach { (m, label) ->
                SkyText(label, if (mine == m) 15.sp else 13.sp, if (mine == m) Sky.Text else Sky.Dim, 3.sp,
                    modifier = Modifier.clickable { mine = m }.padding(end = 26.dp, top = 4.dp, bottom = 4.dp))
            }
        }
        if (mine) { MyMods(myMods, onOpen); return@Column }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(300.dp).border(1.dp, Sky.Frame).padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (s.query.isEmpty()) SkyText("Search mods…", 15.sp, Sky.Faint, 0.5.sp, light = true)
                // Dual-screen: the keyboard opens on the bottom screen and fills this box as you type.
                val typing = rememberBottomTyping("Search mods", { s.query },
                    action = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,
                    onChange = { s.query = it }, onDone = { s.query = s.query.trim(); s.applied = s.query; load(true) })
                BasicTextField(
                    s.query, { s.query = it }, singleLine = true, readOnly = typing.readOnly,
                    interactionSource = typing.interaction,
                    textStyle = TextStyle(color = Sky.Text, fontSize = 15.sp, fontFamily = Sky.Font),
                    cursorBrush = SolidColor(Sky.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.applied = s.query; load(true) }),
                    modifier = Modifier.fillMaxWidth().then(typing.modifier),
                )
            }
            SkyText(if (s.applied.isNotEmpty() && s.query == s.applied) "CLEAR" else "SEARCH", 13.sp, Sky.Text, 2.sp, modifier = Modifier.clickable {
                if (s.applied.isNotEmpty() && s.query == s.applied) s.query = ""
                s.applied = s.query; load(true)
            }.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            if (s.applied.isEmpty()) NexusApi.Sort.entries.forEach { o ->
                val sel = o == s.sort
                SkyText(o.label, if (sel) 13.sp else 12.sp, if (sel) Sky.Text else Sky.Dim, 2.sp,
                    modifier = Modifier.clickable { s.sort = o; load(true) }.padding(horizontal = 9.dp, vertical = 8.dp))
            } else SkyText("${s.total} RESULTS FOR \"${s.applied.uppercase()}\"", 12.sp, Sky.Dim, 2.sp)
        }
        s.author?.let { name ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyText("MODS BY ${name.uppercase()}  ·  ${s.total}", 13.sp, Sky.Text, 2.sp)
                SkyText("SHOW ALL", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable { s.author = null; s.mods.clear() }.padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                GridCells.Adaptive(190.dp), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.mods, key = { it.modId }) { m -> ModCard(m, m.modId in installed) { onOpen(m.modId) } }
            }
            when {
                s.error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    SkyText(s.error!!, 14.sp, Sky.Dim, 0.5.sp)
                    SkyText("TRY AGAIN", 14.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { load(true) }.padding(10.dp))
                }
                s.loading && s.mods.isEmpty() -> CircularProgressIndicator(color = Sky.Text, modifier = Modifier.align(Alignment.Center).size(32.dp))
                !s.loading && s.mods.isEmpty() -> SkyText("No mods found", 15.sp, Sky.Dim, 1.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Installed mods as cards (Nexus details looked up by id), with local-only mods listed by name. */
@Composable
private fun MyMods(mods: List<ModManager.InstalledMod>, onOpen: (Int) -> Unit) {
    val info = remember { androidx.compose.runtime.mutableStateMapOf<Int, NexusApi.Mod>() }
    LaunchedEffect(mods.map { it.nexusId }) {
        for (m in mods) if (m.nexusId > 0 && m.nexusId !in info) runCatching { info[m.nexusId] = NexusApi.mod(m.nexusId).first }
    }
    if (mods.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SkyText("No mods installed yet", 15.sp, Sky.Dim, 1.sp) }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(190.dp), modifier = Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(mods.sortedByDescending { it.installedAt }, key = { it.id }) { m ->
            val n = info[m.nexusId]
            if (n != null) ModCard(n, true) { onOpen(n.modId) }
            else Column(Modifier.background(Color(0xFF141414)).border(1.dp, Sky.Line).padding(10.dp)) {
                SkyText(m.name, 14.sp, Sky.Text, 0.3.sp, maxLines = 2)
                SkyText(if (m.version.isNotBlank()) "v${m.version}  ·  INSTALLED" else "INSTALLED", 10.sp, Sky.Dim, 1.5.sp)
            }
        }
    }
}

@Composable
private fun ModCard(m: NexusApi.Mod, installed: Boolean, onTap: () -> Unit) {
    Column(Modifier.background(Color(0xFF141414)).border(1.dp, if (installed) Sky.Line else Color(0xFF262626)).clickable(onClick = onTap)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1C1C1C))) {
            CoilImage(imageModel = { m.thumbnail ?: m.picture }, imageOptions = ImageOptions(contentScale = ContentScale.Crop), modifier = Modifier.fillMaxSize())
            if (installed) SkyText("INSTALLED", 9.sp, Color.Black, 2.sp, modifier = Modifier.align(Alignment.TopEnd).background(Sky.Text).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            SkyText(m.name, 14.sp, Sky.Text, 0.3.sp, maxLines = 2)
            SkyText(m.author, 10.sp, Sky.Dim, 0.5.sp)
            Spacer(Modifier.height(3.dp))
            SkyText("${compact(m.endorsements)} ENDORSEMENTS  ·  ${compact(m.downloads)} DL", 9.sp, Sky.Dim, 1.sp)
        }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> "$n"
}

// ================================================================ mod page

@Composable
private fun ModPage(modId: Int, onOpen: (Int) -> Unit, onBack: () -> Unit, onNeedSignIn: () -> Unit, onAuthor: (String) -> Unit) {
    var mod by remember(modId) { mutableStateOf<NexusApi.Mod?>(null) }
    var description by remember(modId) { mutableStateOf("") }
    var files by remember(modId) { mutableStateOf<List<NexusApi.ModFile>?>(null) }
    var error by remember(modId) { mutableStateOf<String?>(null) }
    var showOld by remember(modId) { mutableStateOf(false) }
    val mods by ModManager.mods.collectAsState()
    val installedIds = mods.map { it.nexusId }.toSet() + skseIfInstalled()
    LaunchedEffect(modId) {
        try {
            val (m, d) = NexusApi.mod(modId); mod = m; description = d
            files = NexusApi.files(modId)
        } catch (e: Exception) { error = e.message ?: "Couldn't load this mod" }
    }
    val m = mod
    if (m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) SkyText(error!!, 14.sp, Sky.Dim, 0.5.sp) else CircularProgressIndicator(color = Sky.Text, modifier = Modifier.size(32.dp))
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 18.dp)) {
            SkyText("‹  BACK", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable(onClick = onBack).padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1C1C1C))) {
                CoilImage(imageModel = { m.picture ?: m.thumbnail }, imageOptions = ImageOptions(contentScale = ContentScale.Fit), modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            SkyText(m.name.uppercase(), 22.sp, Sky.Text, 2.sp, maxLines = 2)
            SkyText("MORE BY ${NexusApi.mainAuthor(m.author).uppercase()}  ›", 11.sp, Sky.Text, 2.sp, modifier = Modifier.clickable { onAuthor(NexusApi.mainAuthor(m.author)) }.padding(vertical = 5.dp))
            SkyText("BY ${m.author.uppercase()}   ·   VERSION ${m.version}   ·   ${m.category.uppercase()}", 10.sp, Sky.Dim, 2.sp, maxLines = 2)
            SkyText("${compact(m.endorsements)} ENDORSEMENTS   ·   ${compact(m.downloads)} DOWNLOADS", 10.sp, Sky.Dim, 2.sp)
            Rule(Modifier.padding(vertical = 8.dp), color = Sky.Faint)
            SkyText(m.summary, 15.sp, Sky.Text, 0.3.sp, maxLines = 6)
            Spacer(Modifier.height(10.dp))
            SkyText(description.take(6000), 13.sp, Sky.Body, 0.2.sp, light = true, maxLines = 400)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (m.requirements.isNotEmpty()) {
                SkyText("REQUIRES", 12.sp, Sky.Dim, 3.sp, modifier = Modifier.padding(top = 4.dp))
                Rule(Modifier.padding(vertical = 4.dp), color = Sky.Faint)
                m.requirements.forEach { r ->
                    val have = r.modId in installedIds
                    Row(Modifier.fillMaxWidth().clickable { onOpen(r.modId) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DiamondMark(have, if (have) Sky.Text else Sky.Health, Modifier.size(8.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            SkyText(r.name, 14.sp, Sky.Text, 0.3.sp, maxLines = 2)
                            if (r.notes.isNotBlank()) SkyText(r.notes, 11.sp, Sky.Dim, 0.3.sp, light = true, maxLines = 2)
                        }
                        SkyText(
                            when { have -> "INSTALLED"; else -> "GET ›" },
                            11.sp, if (have) Sky.Dim else Sky.Text, 2.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            SkyText("FILES", 12.sp, Sky.Dim, 3.sp, modifier = Modifier.padding(top = 4.dp))
            Rule(Modifier.padding(vertical = 4.dp), color = Sky.Faint)
            val fs = files
            if (m.modId == ModManager.SKSE_NEXUS_ID && skseIfInstalled().isNotEmpty()) {
                Column(Modifier.fillMaxWidth().border(1.dp, Sky.Line).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DiamondMark(true, Sky.Text, Modifier.size(9.dp)); Spacer(Modifier.width(8.dp))
                        SkyText("ALREADY INSTALLED", 16.sp, Sky.Text, 3.sp)
                    }
                    SkyText("The Script Extender for your game (SKSE 2.3.1 for Skyrim 1.7.104) is in your Skyrim folder. " +
                        "There's no need to download it again.", 13.sp, Sky.Body, 0.3.sp, light = true, maxLines = 5, modifier = Modifier.padding(top = 6.dp))
                }
            } else if (fs == null) LinearProgressIndicator(color = Sky.Text, trackColor = Sky.Faint, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            else {
                val shown = fs.filter { it.current || showOld }
                if (shown.isEmpty()) SkyText("No current files", 13.sp, Sky.Dim, 0.5.sp)
                shown.forEach { f -> FileRow(m, f, onNeedSignIn) }
                if (fs.any { !it.current }) SkyText(if (showOld) "HIDE OLDER FILES" else "SHOW OLDER FILES", 11.sp, Sky.Dim, 2.sp,
                    modifier = Modifier.clickable { showOld = !showOld }.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun FileRow(m: NexusApi.Mod, f: NexusApi.ModFile, onNeedSignIn: () -> Unit) {
    val main = f.category == "MAIN"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (main && f.primary) Color(0x14FFFFFF) else Color.Transparent)
            .border(1.dp, if (main) Sky.Frame else Color(0xFF262626)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SkyText(f.name, 15.sp, Sky.Text, 0.3.sp, maxLines = 2)
                SkyText(listOf(f.category.replace('_', ' '), "v${f.version}", size(f.sizeBytes)).joinToString("   ·   "), 10.sp, Sky.Dim, 1.5.sp)
            }
            val downloads by ModManager.downloads.collectAsState()
            val mods by ModManager.mods.collectAsState()
            val state = remember(downloads, mods) { ModManager.fileState(m.modId, f.fileId, f.fileName) }
            when (state) {
                ModManager.FileState.INSTALLED -> SkyText("INSTALLED", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                ModManager.FileState.DOWNLOADING -> SkyText("DOWNLOADING…", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                else -> SkyText(if (state == ModManager.FileState.DOWNLOADED) "INSTALL ›" else "DOWNLOAD ›", 13.sp, Sky.Text, 2.sp,
                    modifier = Modifier.clickable { if (ModManager.apiKey() == null) onNeedSignIn() else ModManager.request(m, f) }
                        .border(1.dp, Sky.Line).padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        if (f.description.isNotBlank()) SkyText(f.description, 11.sp, Sky.Dim, 0.2.sp, light = true, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun size(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

// ================================================================ free-account confirmation

/**
 * Nexus asks free members to confirm each download on its own page (Premium skips this). We show just
 * that page; its "Slow download" button answers with an nxm:// link, which finishes here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FreeConfirmPanel(c: ModManager.FreeConfirm) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.9f).background(Color(0xFF0B0B0B)).border(1.dp, Sky.Frame).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SkyText("ONE TAP  ·  ${c.file.name.uppercase()}", 15.sp, Sky.Text, 2.sp)
                    SkyText("Nexus Mods asks free members to press SLOW DOWNLOAD themselves for each file. Everything after that happens here. Premium members never see this.",
                        12.sp, Sky.Dim, 0.5.sp, light = true, maxLines = 2)
                }
                SkyText("CANCEL", 13.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { ModManager.cancelFreeConfirm() }.padding(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            // A new page for each file: without the key, the next queued file kept showing the previous file's page.
            androidx.compose.runtime.key(c.mod.modId, c.file.fileId) { AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                if (request.url.scheme.equals("nxm", true)) ModManager.handleNxm(request.url.toString()) else false
                            // Bring the download button into view so it's the first thing you see.
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript("""(function(){var b=document.getElementById('slowDownloadButton')||Array.from(document.querySelectorAll('button,a')).find(function(e){return /slow download/i.test(e.textContent)});if(b){b.scrollIntoView({block:'center'});}})()""", null)
                            }
                        }
                        setDownloadListener { url, ua, cd, mime, _ ->
                            ModManager.cancelFreeConfirm()
                            ModManager.download(url, cd, mime, CookieManager.getInstance().getCookie(url), ua, fileName = c.file.fileName)
                        }
                        loadUrl(NexusApi.freeDownloadPage(c.mod.modId, c.file.fileId))
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            ) }
        }
    }
}

// ================================================================ status strip

/** What's downloading / installing, so the player sees their tap did something. */
@Composable
internal fun DownloadStrip(everything: Boolean = true) {
    val downloads by ModManager.downloads.collectAsState()
    val busy by ModManager.busy.collectAsState()
    val notice by ModManager.notice.collectAsState()
    val fomod by ModManager.fomod.collectAsState()
    val queued by ModManager.queued.collectAsState()
    if (everything) LaunchedEffect(notice) { if (notice != null) { delay(3500); ModManager.clearNotice() } }
    val active = downloads.firstOrNull { it.state == ModManager.DlState.DOWNLOADING }
    val line = when {
        everything && fomod != null -> "${fomod!!.modName.uppercase()}  ·  choose options on the bottom screen"
        everything && busy != null -> "${busy!!.title}  ·  ${busy!!.detail}"
        active != null -> "DOWNLOADING  ·  ${active.name}" + (if (active.total > 0) "  ·  ${(active.progress * 100).toInt()}%" else "") +
            (if (queued > 0) "  ·  $queued MORE QUEUED" else "")
        everything && notice != null -> notice!!
        else -> return
    }
    Column(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 18.dp, vertical = 6.dp)) {
        Rule(color = Sky.Faint)
        SkyText(line, 14.sp, Sky.Text, 1.sp, modifier = Modifier.padding(top = 4.dp))
        val shownBusy = busy.takeIf { everything }
        val p = shownBusy?.progress ?: active?.progress ?: -1f
        if (shownBusy != null || active != null) {
            val mod = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp)
            if (p >= 0f) LinearProgressIndicator(progress = { p }, color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
            else LinearProgressIndicator(color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
        }
    }
}

/** The Script Extender's id when its files are in the game folder, so pages show it as installed. */
private fun skseIfInstalled(): Set<Int> =
    if (GameFolder.modded()?.let { SkyrimEngine.skseInstalled(it) } == true) setOf(ModManager.SKSE_NEXUS_ID) else emptySet()
