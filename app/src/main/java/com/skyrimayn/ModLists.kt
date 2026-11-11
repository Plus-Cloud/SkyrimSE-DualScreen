package com.skyrimayn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Mod lists: shareable checklists of Nexus mods. A list never owns anything: deleting one uninstalls nothing,
 * and every list looks at the same installed mods, so people can take a few mods from several lists.
 * They live in the game folder (SkyrimAYN/modlists), so they survive reinstalling the app.
 */
object ModLists {
    private const val FORMAT = "skyrimse-dualscreen-modlist"
    private const val RECOMMENDED_ASSET = "skyrimayn/recommended-modlist.json"

    data class Entry(val name: String, val nexusId: Int, val fileId: Int, val version: String)

    /** [file] is null for the built-in Recommended list, which can't be deleted. */
    data class ModList(val file: File?, val name: String, val entries: List<Entry>, val plugins: List<Pair<String, Boolean>>) {
        val builtIn get() = file == null
    }

    private val _lists = MutableStateFlow<List<ModList>>(emptyList())
    val lists: StateFlow<List<ModList>> = _lists

    private fun dir(): File? = GameFolder.dir.value?.let { File(it, "SkyrimAYN/modlists").apply { mkdirs() } }

    private fun parse(text: String, fallbackName: String, file: File?): ModList {
        val j = JSONObject(text)
        if (j.optString("format") != FORMAT) throw IOException("That isn't a mod list file")
        val mods = j.optJSONArray("mods") ?: JSONArray()
        val order = j.optJSONArray("plugins") ?: JSONArray()
        return ModList(
            file, j.optString("name").ifBlank { fallbackName },
            List(mods.length()) { i ->
                mods.getJSONObject(i).let { Entry(it.optString("name"), it.optInt("nexusId"), it.optInt("fileId"), it.optString("version")) }
            }.sortedBy { it.name.lowercase() },
            List(order.length()) { i -> order.getJSONObject(i).let { it.getString("name") to it.optBoolean("active", true) } },
        )
    }

    private fun render(name: String, entries: List<Entry>, plugins: List<Pair<String, Boolean>>): String =
        JSONObject().put("format", FORMAT).put("version", 1).put("name", name)
            .put("mods", JSONArray(entries.map { JSONObject().put("name", it.name).put("version", it.version).put("nexusId", it.nexusId).put("fileId", it.fileId) }))
            .put("plugins", JSONArray(plugins.map { JSONObject().put("name", it.first).put("active", it.second) }))
            .toString(2)

    private fun safeFileName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().ifEmpty { "mod list" }

    private fun uniqueFile(name: String): File {
        val folder = dir() ?: throw IOException("Choose your Skyrim folder first")
        var f = File(folder, safeFileName(name) + ".json")
        var n = 2
        while (f.exists()) f = File(folder, "${safeFileName(name)} $n.json").also { n++ }
        return f
    }

    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        val builtIn = runCatching { parse(ctx.assets.open(RECOMMENDED_ASSET).bufferedReader().readText(), "Recommended", null) }.getOrNull()
        val own = dir()?.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }.orEmpty()
            .mapNotNull { f -> runCatching { parse(f.readText(), f.nameWithoutExtension, f) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
        _lists.value = listOfNotNull(builtIn) + own
    }

    /** Copies a list someone shared into the player's lists. */
    suspend fun import(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val shown = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.substringBeforeLast('.') ?: "Imported list"
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            val list = parse(text, shown, null)
            uniqueFile(list.name).writeText(render(list.name, list.entries, list.plugins))
            load(ctx)
            "${list.name} added"
        } catch (e: Exception) {
            "Couldn't read that file: ${e.message}"
        }
    }

    /** Saves what's installed now as a new list. Mods that didn't come from Nexus can't be fetched by others, so they stay out. */
    suspend fun createFromInstalled(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            val mods = ModManager.mods.value
            val fromNexus = mods.filter { it.nexusId > 0 }
            if (fromNexus.isEmpty()) return@withContext "No Nexus mods installed yet"
            val name = "My mods " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date())
            val plugins = LoadOrder.plugins.value.filter { !it.locked }.map { it.name to it.active }
            val entries = fromNexus.map { Entry(it.name, it.nexusId, it.fileId, it.version) }
            uniqueFile(name).writeText(render(name, entries, plugins))
            load(ctx)
            val left = mods.size - fromNexus.size
            "$name saved" + if (left > 0) ". $left not from Nexus left out" else ""
        } catch (e: Exception) {
            "Couldn't save the list: ${e.message}"
        }
    }

    suspend fun delete(ctx: Context, list: ModList) = withContext(Dispatchers.IO) {
        list.file?.delete()
        load(ctx)
    }

    /** Sends the list through Android's share sheet. Returns false if it couldn't be prepared. */
    suspend fun share(ctx: Context, list: ModList): Boolean {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                File(ctx.cacheDir, safeFileName(list.name) + ".json").apply { writeText(render(list.name, list.entries, list.plugins)) }
            }.getOrNull()
        } ?: return false
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share mod list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
