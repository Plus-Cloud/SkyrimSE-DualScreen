package com.skyrimayn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Nexus Mods for Skyrim Special Edition, natively (no website):
 *  - browsing, search, mod pages, files and requirements use the public v2 GraphQL API;
 *  - the player's account (personal API key) is checked with v1 users/validate;
 *  - Premium members get direct download links from v1; free members confirm each file once
 *    on Nexus's own download page (their rule), which hands back an nxm:// link we finish here.
 */
object NexusApi {
    const val GAME_DOMAIN = "skyrimspecialedition"
    const val GAME_ID = 1704
    private const val GRAPHQL = "https://api.nexusmods.com/v2/graphql"
    private const val V1 = "https://api.nexusmods.com/v1"

    data class Requirement(val modId: Int, val name: String, val notes: String, val gameId: Int = GAME_ID)
    data class Mod(
        val modId: Int, val name: String, val author: String, val summary: String,
        val endorsements: Int, val downloads: Int, val version: String,
        val thumbnail: String?, val picture: String?, val category: String,
        val requirements: List<Requirement>, val updatedAt: String,
    )
    data class ModFile(
        val fileId: Int, val name: String, val version: String, val category: String, val primary: Boolean,
        val sizeBytes: Long, val fileName: String, val date: Long, val description: String,
    ) {
        /** Files a player would want: not archived/removed/old. */
        val current get() = category in setOf("MAIN", "OPTIONAL", "UPDATE", "MISCELLANEOUS")
    }
    data class User(val name: String, val premium: Boolean, val avatar: String?)

    enum class Sort(val label: String, val field: String) {
        POPULAR("POPULAR", "endorsements"), DOWNLOADS("MOST DOWNLOADED", "uniqueDownloads"),
        NEWEST("NEWEST", "createdAt"), UPDATED("RECENTLY UPDATED", "updatedAt"),
    }

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private fun gql(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(json)
        val req = Request.Builder().url(GRAPHQL).post(body)
            .header("User-Agent", "SkyrimSE-DualScreen/1.0").header("Application-Name", "SkyrimSE-DualScreen").header("Application-Version", "1.0").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Nexus said ${r.code}")
            val j = JSONObject(text)
            j.optJSONArray("errors")?.let { if (it.length() > 0 && !j.has("data")) throw IOException(it.getJSONObject(0).optString("message")) }
            return j.getJSONObject("data")
        }
    }

    private const val MOD_FIELDS = """
        modId name author summary endorsements downloads version thumbnailUrl pictureUrl updatedAt
        modCategory { name }
        modRequirements { nexusRequirements { nodes { modId modName notes gameId } } }
    """

    private fun parseMod(o: JSONObject): Mod {
        val reqs = o.optJSONObject("modRequirements")?.optJSONObject("nexusRequirements")?.optJSONArray("nodes")
        return Mod(
            o.optInt("modId"), o.optString("name"), o.optString("author"), o.optString("summary").trim(),
            o.optInt("endorsements"), o.optInt("downloads"), o.optString("version"),
            o.optString("thumbnailUrl").ifBlank { null }, o.optString("pictureUrl").ifBlank { null },
            o.optJSONObject("modCategory")?.optString("name").orEmpty(),
            (0 until (reqs?.length() ?: 0)).mapNotNull { i ->
                val r = reqs!!.getJSONObject(i)
                val id = r.optString("modId").toIntOrNull() ?: return@mapNotNull null
                Requirement(id, r.optString("modName"), r.optString("notes"), r.optString("gameId").toIntOrNull() ?: GAME_ID)
            },
            o.optString("updatedAt"),
        )
    }

    /** One page of mods: by search text (relevance) or a sort. Returns the page and the total count. */
    suspend fun browse(query: String?, sort: Sort, offset: Int, count: Int = 24): Pair<List<Mod>, Int> = withContext(Dispatchers.IO) {
        val filter = JSONObject()
            .put("gameDomainName", JSONArray().put(JSONObject().put("value", GAME_DOMAIN).put("op", "EQUALS")))
            .put("adultContent", JSONArray().put(JSONObject().put("value", false).put("op", "EQUALS")))
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) filter.put("nameStemmed", JSONArray().put(JSONObject().put("value", q).put("op", "MATCHES")))
        val sortKey = if (q.isNotEmpty()) "relevance" else sort.field
        val vars = JSONObject().put("f", filter).put("s", JSONArray().put(JSONObject().put(sortKey, JSONObject().put("direction", "DESC"))))
            .put("o", offset).put("c", count)
        val d = gql("query(\$f: ModsFilter, \$s: [ModsSort!], \$o: Int, \$c: Int) { mods(filter: \$f, sort: \$s, offset: \$o, count: \$c) { totalCount nodes { $MOD_FIELDS } } }", vars)
        val mods = d.getJSONObject("mods")
        val nodes = mods.getJSONArray("nodes")
        List(nodes.length()) { parseMod(nodes.getJSONObject(it)) } to mods.optInt("totalCount")
    }

    /** Mod page: details plus the description as readable text. */
    suspend fun mod(modId: Int): Pair<Mod, String> = withContext(Dispatchers.IO) {
        val d = gql("query(\$m: ID!, \$g: ID!) { mod(modId: \$m, gameId: \$g) { $MOD_FIELDS description } }",
            JSONObject().put("m", "$modId").put("g", "$GAME_ID"))
        val o = d.getJSONObject("mod")
        parseMod(o) to bbcodeToText(o.optString("description"))
    }

    suspend fun files(modId: Int): List<ModFile> = withContext(Dispatchers.IO) {
        val d = gql("query(\$m: ID!, \$g: ID!) { modFiles(modId: \$m, gameId: \$g) { fileId name version category primary sizeInBytes uri date description } }",
            JSONObject().put("m", "$modId").put("g", "$GAME_ID"))
        val a = d.getJSONArray("modFiles")
        List(a.length()) { i ->
            val f = a.getJSONObject(i)
            ModFile(
                f.optInt("fileId"), f.optString("name"), f.optString("version"), f.optString("category"), f.optInt("primary") == 1,
                f.optString("sizeInBytes").toLongOrNull() ?: 0L, f.optString("uri"), f.optLong("date"),
                bbcodeToText(f.optString("description")),
            )
        }.sortedWith(compareBy({ categoryRank(it.category) }, { !it.primary }, { -it.date }))
    }

    private fun categoryRank(c: String) = when (c) { "MAIN" -> 0; "UPDATE" -> 1; "OPTIONAL" -> 2; "MISCELLANEOUS" -> 3; else -> 4 }

    private fun v1(path: String, key: String): String {
        val req = Request.Builder().url("$V1/$path").header("apikey", key)
            .header("Application-Name", "SkyrimSE-DualScreen").header("Application-Version", "1.0").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("That API key wasn't accepted")
            if (r.code == 403) throw IOException("PREMIUM_ONLY")
            if (!r.isSuccessful) throw IOException("Nexus said ${r.code}")
            return r.body!!.string()
        }
    }

    suspend fun validate(key: String): User = withContext(Dispatchers.IO) {
        val j = JSONObject(v1("users/validate.json", key))
        User(j.optString("name"), j.optBoolean("is_premium"), j.optString("profile_url").ifBlank { null })
    }

    /** Direct link (Premium accounts). Throws "PREMIUM_ONLY" for free accounts. */
    suspend fun premiumLink(key: String, modId: Int, fileId: Int): String = withContext(Dispatchers.IO) {
        JSONArray(v1("games/$GAME_DOMAIN/mods/$modId/files/$fileId/download_link.json", key)).getJSONObject(0).getString("URI")
    }

    /** The page where a free member confirms a file; it answers with an nxm:// link. */
    fun freeDownloadPage(modId: Int, fileId: Int) =
        "https://www.nexusmods.com/$GAME_DOMAIN/mods/$modId?tab=files&file_id=$fileId&nmm=1"

    /** Nexus descriptions are BBCode with some HTML; turn them into tidy plain text. */
    fun bbcodeToText(src: String): String {
        var s = src.replace("﻿", "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)\\[img[^\\]]*].*?\\[/img]"), "")
        s = s.replace(Regex("(?is)\\[url=[^\\]]*](.*?)\\[/url]"), "$1")
        s = s.replace(Regex("(?i)\\[\\*]"), "• ")
        s = s.replace(Regex("(?is)\\[/?[a-z*]+(=[^\\]]*)?]"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }
}
