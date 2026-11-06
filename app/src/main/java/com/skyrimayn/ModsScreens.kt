package com.skyrimayn

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.mods.FomodGroupType
import app.gamenative.mods.FomodPlugin
import app.gamenative.mods.FomodPluginType
import app.gamenative.mods.effectiveType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class ModTab(val label: String) { MODS("MODS"), LOAD_ORDER("LOAD ORDER"), DOWNLOADS("DOWNLOADS") }

/** The Mod Manager hub on the bottom screen. */
@Composable
internal fun ModManagerPage(gameDir: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ModTab.MODS) }
    val mods by ModManager.mods.collectAsState()
    val downloads by ModManager.downloads.collectAsState()
    val busy by ModManager.busy.collectAsState()
    val notice by ModManager.notice.collectAsState()
    val dirty by LoadOrder.dirty.collectAsState()
    val plugins by LoadOrder.plugins.collectAsState()
    val account by ModManager.account.collectAsState()
    LaunchedEffect(gameDir) { ModManager.init(context); ModManager.load(); LoadOrder.load(context, gameDir); LoadOrder.autoSort(gameDir) }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); ModManager.clearNotice() } }
    LaunchedEffect(tab) { if (tab == ModTab.DOWNLOADS) ModManager.load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(ModManager::importArchive) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkyText("‹", 30.sp, Sky.Text, 0.sp, modifier = Modifier.clickable {
                    if (dirty) scope.launch { LoadOrder.save(context) }
                    onBack()
                }.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    SkyText("MOD MANAGER", 22.sp, Sky.Text, 4.sp)
                    SkyText("${mods.size} installed  ·  ${plugins.count { !it.locked && it.active }} plugins active  ·  " +
                        (account?.let { "Nexus: ${it.name}" } ?: "Nexus: not signed in"), 12.sp, Sky.Dim, 1.sp, light = true)
                }
                SkyText("GET MODS ›", 16.sp, Sky.Text, 3.sp, modifier = Modifier.clickable { NexusActivity.open(context) }.padding(10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ModTab.entries.forEach { t ->
                    val sel = t == tab
                    val badge = if (t == ModTab.DOWNLOADS && downloads.any { it.state == ModManager.DlState.DOWNLOADING }) " ●" else ""
                    Box(Modifier.weight(1f).clickable { tab = t }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        SkyText(t.label + badge, if (sel) 16.sp else 14.sp, if (sel) Sky.Text else Sky.Dim, 2.sp)
                    }
                }
            }
            Rule()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ModTab.MODS -> InstalledList(mods, onImport = { picker.launch(arrayOf("*/*")) }, onGetMods = { NexusActivity.open(context) })
                    ModTab.LOAD_ORDER -> LoadOrderList(context, gameDir)
                    ModTab.DOWNLOADS -> DownloadsList(downloads, mods.map { it.archive }.toSet(), onKey = { NexusActivity.open(context) })
                }
            }
            SkyText(
                notice ?: when (tab) {
                    ModTab.MODS -> "Tap a mod to select it. GET MODS opens Nexus Mods on the top screen; downloads install themselves."
                    ModTab.LOAD_ORDER -> "Sorted automatically: masters first, each plugin after what it needs."
                    ModTab.DOWNLOADS -> "Archives are kept here so you can reinstall without downloading again."
                },
                13.sp, if (notice != null) Sky.Text else Sky.Faint, 0.5.sp, light = notice == null, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center,
            )
        }
        busy?.let { BusyPanel(it) }
    }
}

// ---------------------------------------------------------------- installed mods

@Composable
private fun InstalledList(mods: List<ModManager.InstalledMod>, onImport: () -> Unit, onGetMods: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val sel = mods.firstOrNull { it.id == selected }
    LaunchedEffect(selected) { confirm = false }
    Column(Modifier.fillMaxSize()) {
        if (mods.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                SkyText("NO MODS INSTALLED", 18.sp, Sky.Dim, 3.sp)
                Spacer(Modifier.height(10.dp))
                SkyText("Tap GET MODS to browse Nexus Mods on the top screen,\nor install an archive you already have.", 14.sp, Sky.Faint, 0.5.sp,
                    light = true, align = TextAlign.Center, maxLines = 3)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(mods.sortedByDescending { it.installedAt }, key = { it.id }) { m ->
                val isSel = m.id == selected
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (isSel) null else m.id }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { DiamondMark(true, Sky.Text) }
                    if (isSel) SkyText("[", 20.sp, Sky.Text, 0.sp, modifier = Modifier.padding(start = 6.dp))
                    Column(Modifier.padding(horizontal = 6.dp).weight(1f, fill = false)) {
                        SkyText(m.name, 18.sp, Sky.Text, 0.3.sp)
                        SkyText(
                            listOfNotNull(m.version.takeIf { it.isNotBlank() }?.let { "v$it" }, "${m.files.size} file${if (m.files.size == 1) "" else "s"}", size(m.sizeBytes),
                                m.plugins.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString("  ·  "),
                            12.sp, Sky.Dim, 0.5.sp, light = true,
                        )
                    }
                    if (isSel) SkyText("]", 20.sp, Sky.Text, 0.sp)
                }
            }
        }
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ModAction("INSTALL FROM FILE", true, onTap = onImport)
            ModAction(if (confirm) "CONFIRM DELETE" else "DELETE", sel != null, danger = confirm) {
                if (!confirm) confirm = true else { sel?.let(ModManager::uninstall); selected = null }
            }
        }
    }
}

// ---------------------------------------------------------------- load order

@Composable
private fun LoadOrderList(context: android.content.Context, gameDir: String) {
    val scope = rememberCoroutineScope()
    val plugins by LoadOrder.plugins.collectAsState()
    val dirty by LoadOrder.dirty.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val sel = plugins.firstOrNull { it.name == selected }
    var full = 0
    var light = 0
    val indexed = plugins.map { p ->
        p to when {
            !p.active -> "  "
            p.light -> "FE:%03X".format(light++)
            else -> "%02X".format(full++)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(indexed, key = { _, it -> it.first.name }) { _, (p, idx) ->
                val isSel = p.name == selected
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (isSel) null else p.name }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkyText(idx, 13.sp, Sky.Faint, 0.5.sp, modifier = Modifier.width(54.dp))
                    Box(Modifier.width(22.dp).clickable(enabled = !p.locked) { LoadOrder.toggle(p.name) }, contentAlignment = Alignment.Center) {
                        DiamondMark(p.active, if (p.locked) Sky.Faint else Sky.Text)
                    }
                    if (isSel) SkyText("[", 19.sp, Sky.Text, 0.sp, modifier = Modifier.padding(start = 6.dp))
                    SkyText(
                        p.name, 17.sp, when { p.locked -> Sky.Dim; p.active -> Sky.Text; else -> Sky.Faint }, 0.3.sp,
                        modifier = Modifier.padding(horizontal = 6.dp).weight(1f, fill = false),
                    )
                    if (isSel) SkyText("]", 19.sp, Sky.Text, 0.sp)
                    Spacer(Modifier.weight(1f))
                    if (p.missingMasters.isNotEmpty()) SkyText("NEEDS ${p.missingMasters.joinToString(", ")}", 10.sp, Sky.Health, 1.sp, maxLines = 1)
                    if (p.locked) SkyText(if (p.name.startsWith("cc", true) || p.name.startsWith("_")) "CREATION" else "BASE GAME", 11.sp, Sky.Faint, 2.sp)
                }
            }
        }
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val canEdit = sel != null && !sel.locked
            ModAction("UP", canEdit) { sel?.let { LoadOrder.move(it.name, -1) } }
            ModAction("DOWN", canEdit) { sel?.let { LoadOrder.move(it.name, 1) } }
            ModAction(if (sel?.active == true) "DISABLE" else "ENABLE", canEdit) { sel?.let { LoadOrder.toggle(it.name) } }
            ModAction("AUTO SORT", true) { scope.launch { LoadOrder.autoSort(gameDir); LoadOrder.save(context) } }
            ModAction("SAVE", dirty) { scope.launch { LoadOrder.save(context) } }
        }
    }
}

// ---------------------------------------------------------------- downloads

@Composable
private fun DownloadsList(downloads: List<ModManager.Download>, installed: Set<String>, onKey: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val sel = downloads.firstOrNull { it.id == selected }
    Column(Modifier.fillMaxSize()) {
        if (downloads.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                SkyText("NO DOWNLOADS", 18.sp, Sky.Dim, 3.sp)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(downloads, key = { it.id }) { d ->
                val isSel = d.id == selected
                Column(Modifier.fillMaxWidth().clickable { selected = if (isSel) null else d.id }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSel) SkyText("[ ", 19.sp, Sky.Text, 0.sp)
                        SkyText(d.name + if (isSel) " ]" else "", 17.sp, if (d.state == ModManager.DlState.FAILED) Sky.Dim else Sky.Text, 0.3.sp, modifier = Modifier.weight(1f))
                        if (d.file.name in installed) SkyText("INSTALLED   ", 11.sp, Sky.Dim, 2.sp)
                        SkyText(
                            when (d.state) {
                                ModManager.DlState.DOWNLOADING -> if (d.total > 0) "${(d.progress * 100).toInt()}%" else size(d.bytes)
                                ModManager.DlState.READY -> size(d.total)
                                ModManager.DlState.FAILED -> "FAILED"
                            }, 13.sp, Sky.Dim, 1.sp,
                        )
                    }
                    if (d.state == ModManager.DlState.DOWNLOADING) {
                        val mod = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)
                        if (d.progress >= 0) LinearProgressIndicator(progress = { d.progress }, color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
                        else LinearProgressIndicator(color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
                    }
                    d.error?.let { SkyText(it, 12.sp, Sky.Health, 0.5.sp, light = true) }
                }
            }
        }
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val ready = sel?.state == ModManager.DlState.READY
            ModAction("INSTALL", ready) { sel?.let { ModManager.install(it.file) } }
            ModAction("DELETE", sel != null && sel.state != ModManager.DlState.DOWNLOADING) { sel?.let { ModManager.deleteDownload(it); selected = null } }
            ModAction("NEXUS ACCOUNT", true, onTap = onKey)
        }
    }
}

// ---------------------------------------------------------------- FOMOD wizard

/** A FOMOD installer, one page per step, styled like the game's menus. */
@Composable
internal fun FomodWizard(session: ModManager.FomodSession) {
    val steps = session.installer.steps.filter { it.groups.isNotEmpty() }
    var stepIndex by remember(session.id) { mutableStateOf(0) }
    // Choices per step/group: plugin indices.
    val choices = remember(session.id) {
        mutableStateListOf<Set<Int>>().apply {
            steps.forEach { s -> s.groups.forEach { g -> add(defaultChoice(g.type, g.plugins)) } }
        }
    }
    fun groupOffset(step: Int) = steps.take(step).sumOf { it.groups.size }
    var focus by remember(session.id, stepIndex) { mutableStateOf<FomodPlugin?>(null) }

    val selectedPlugins = steps.flatMapIndexed { si, s -> s.groups.flatMapIndexed { gi, g -> choices[groupOffset(si) + gi].map { g.plugins[it] } } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SkyText(session.modName.uppercase(), 20.sp, Sky.Text, 3.sp)
        SkyText(if (steps.isEmpty()) "Ready to install" else "${steps[stepIndex].name.uppercase()}   ·   ${stepIndex + 1} OF ${steps.size}", 13.sp, Sky.Dim, 2.sp)
        Rule(Modifier.padding(top = 6.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
                if (steps.isNotEmpty()) {
                    val step = steps[stepIndex]
                    step.groups.forEachIndexed { gi, g ->
                        val slot = groupOffset(stepIndex) + gi
                        SkyText(g.name.uppercase(), 13.sp, Sky.Dim, 2.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        g.plugins.forEachIndexed { pi, p ->
                            val type = p.effectiveType(emptyMap(), session.env)
                            val on = pi in choices[slot]
                            val enabled = type != FomodPluginType.NOT_USABLE && type != FomodPluginType.REQUIRED
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    focus = p
                                    if (enabled) choices[slot] = toggle(g.type, choices[slot], pi)
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                    DiamondMark(on, if (enabled) Sky.Text else Sky.Faint)
                                }
                                SkyText(p.name, 16.sp, if (type == FomodPluginType.NOT_USABLE) Sky.Faint else Sky.Text, 0.3.sp,
                                    modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 2)
                                if (type == FomodPluginType.RECOMMENDED) SkyText("RECOMMENDED", 10.sp, Sky.Dim, 1.5.sp)
                            }
                        }
                    }
                }
            }
            // Description and picture of the focused option, like MO2/Vortex do.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 10.dp)) {
                val f = focus
                if (f != null) {
                    val img = remember(f.imagePath) {
                        f.imagePath.takeIf { it.isNotBlank() }?.let { path ->
                            val base = session.installer.basePath.trim('/')
                            val rel = path.replace('\\', '/').trim('/')
                            val target = (if (base.isEmpty()) rel else "$base/$rel").lowercase()
                            session.staging.walkTopDown().firstOrNull { it.isFile && it.relativeTo(session.staging).invariantSeparatorsPath.lowercase() == target }
                        }?.let { file -> runCatching { decodeScaled(file) }.getOrNull() }
                    }
                    img?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                    SkyText(f.name.uppercase(), 15.sp, Sky.Text, 1.5.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 2)
                    SkyText(f.description.trim(), 13.sp, Sky.Body, 0.3.sp, light = true, maxLines = 40, modifier = Modifier.padding(top = 4.dp))
                } else {
                    SkyText("Tap an option to read about it.", 13.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2)
                }
            }
        }
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ModAction("CANCEL", true) { ModManager.cancelFomod() }
            ModAction("BACK", stepIndex > 0) { stepIndex-- }
            val valid = steps.isEmpty() || steps[stepIndex].groups.withIndex().all { (gi, g) ->
                val n = choices[groupOffset(stepIndex) + gi].size
                when (g.type) {
                    FomodGroupType.SELECT_EXACTLY_ONE -> n == 1
                    FomodGroupType.SELECT_AT_LEAST_ONE -> n >= 1
                    FomodGroupType.SELECT_AT_MOST_ONE -> n <= 1
                    FomodGroupType.SELECT_ANY -> true
                }
            }
            if (steps.isEmpty() || stepIndex == steps.lastIndex) ModAction("INSTALL", valid) { ModManager.finishFomod(selectedPlugins) }
            else ModAction("NEXT", valid) { stepIndex++ }
        }
    }
}

private fun defaultChoice(type: FomodGroupType, plugins: List<FomodPlugin>): Set<Int> {
    val required = plugins.indices.filter { plugins[it].type == FomodPluginType.REQUIRED }
    val recommended = plugins.indices.filter { plugins[it].type == FomodPluginType.RECOMMENDED }
    val usable = plugins.indices.filter { plugins[it].type != FomodPluginType.NOT_USABLE }
    return when (type) {
        FomodGroupType.SELECT_EXACTLY_ONE, FomodGroupType.SELECT_AT_LEAST_ONE ->
            (required + recommended).take(if (type == FomodGroupType.SELECT_EXACTLY_ONE) 1 else Int.MAX_VALUE).ifEmpty { usable.take(1) }.toSet()
        FomodGroupType.SELECT_AT_MOST_ONE -> (required + recommended).take(1).toSet()
        FomodGroupType.SELECT_ANY -> (required + recommended).toSet()
    }
}

private fun toggle(type: FomodGroupType, cur: Set<Int>, i: Int): Set<Int> = when (type) {
    FomodGroupType.SELECT_EXACTLY_ONE -> setOf(i)
    FomodGroupType.SELECT_AT_MOST_ONE -> if (i in cur) emptySet() else setOf(i)
    else -> if (i in cur) cur - i else cur + i
}

private fun decodeScaled(f: File): android.graphics.Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (o.outWidth / sample > 900) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun BusyPanel(b: ModManager.Busy) {
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            SkyText(b.title, 20.sp, Sky.Text, 5.sp)
            Spacer(Modifier.height(6.dp))
            SkyText(b.detail, 16.sp, Sky.Dim, 1.sp, light = true, align = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            val mod = Modifier.width(360.dp).height(3.dp)
            if (b.progress >= 0f) LinearProgressIndicator(progress = { b.progress }, color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
            else LinearProgressIndicator(color = Sky.Text, trackColor = Sky.Faint, modifier = mod)
        }
    }
}

@Composable
internal fun ModAction(label: String, enabled: Boolean, danger: Boolean = false, onTap: () -> Unit) {
    SkyText(label, 15.sp, when { !enabled -> Sky.Faint; danger -> Sky.Health; else -> Sky.Text }, 2.sp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap).padding(horizontal = 10.dp, vertical = 10.dp))
}

private fun size(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}
