package com.skyrimayn

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.service.SteamService
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Skyrim's Steam achievements: the list (from Steam, cached so it also shows offline) and the unlock
 * notices. Unlock popups themselves come from the engine (its Steam-host poller online, the stand-in
 * Steam's watcher offline); [onUnlocked] mirrors them to the bottom screen and refreshes the list.
 */
object Achievements {
    data class Item(
        val id: String, val title: String, val description: String, val unlocked: Boolean,
        /** Seconds since 1970, 0 when locked. */
        val unlockedAt: Long, val hidden: Boolean, val icon: String, val iconGray: String?,
    )

    /** null: never loaded (no Steam yet and nothing cached). */
    private val _items = MutableStateFlow<List<Item>?>(null)
    val items: StateFlow<List<Item>?> = _items
    /** True when [items] is the cached copy rather than fresh from Steam. */
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ctx: Context? = null
    private const val PREFS = "achievements"
    private const val KEY = "list"

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        if (_items.value == null) readCache()?.let { _items.value = it; _stale.value = true }
    }

    /** Fetches the list from Steam (needs Steam signed in); keeps the cached copy when it can't. */
    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        scope.launch {
            try {
                val fresh = runCatching { SteamService.fetchAchievementsForDisplay(SkyrimEngine.STEAM_APP_ID) }
                    .onFailure { Timber.w(it, "SkyrimAYN: achievements fetch failed") }.getOrNull()
                if (fresh != null && fresh.isNotEmpty()) {
                    val list = fresh.map {
                        Item(
                            id = it.name ?: it.displayName, title = it.displayName.ifBlank { it.name ?: "" },
                            description = it.description, unlocked = it.isUnlocked, unlockedAt = it.unlockTimestamp.toLong(),
                            hidden = it.hidden, icon = it.icon, iconGray = it.iconGray,
                        )
                    }
                    _items.value = list
                    _stale.value = false
                    writeCache(list)
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /** An achievement just unlocked in game (from the engine's popup): tell the bottom screen, update the list. */
    fun onUnlocked(name: String) {
        NotificationFeed.achievement(name)
        _items.value = _items.value?.map { if (it.title == name && !it.unlocked) it.copy(unlocked = true, unlockedAt = System.currentTimeMillis() / 1000) else it }
        scope.launch { delay(5_000); refresh() }
    }

    /** "23 / 75", or null before anything is known. */
    fun summary(list: List<Item>?): String? = list?.let { l -> "${l.count { it.unlocked }} / ${l.size}" }

    private fun writeCache(list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.description).put("u", it.unlocked)
                .put("at", it.unlockedAt).put("h", it.hidden).put("i", it.icon).put("g", it.iconGray ?: ""))
        }
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private fun readCache(): List<Item>? = runCatching {
        val s = ctx?.getSharedPreferences(PREFS, 0)?.getString(KEY, null) ?: return null
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("id"), o.getString("t"), o.optString("d"), o.optBoolean("u"), o.optLong("at"),
                o.optBoolean("h"), o.optString("i"), o.optString("g").ifEmpty { null })
        }
    }.getOrNull()

    /** For render tests. */
    internal fun injectForTest(list: List<Item>?) { _items.value = list; _stale.value = false }
}

/** The achievements list, styled like the game's menus: unlocked first (newest on top), then locked. */
@Composable
internal fun AchievementsPage(onBack: () -> Unit) {
    val list by Achievements.items.collectAsState()
    val stale by Achievements.stale.collectAsState()
    val loading by Achievements.loading.collectAsState()
    LaunchedEffect(Unit) { Achievements.refresh() }
    Column(Modifier.fillMaxSize().background(Color(0xFF070707)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkyText("‹", 26.sp, Sky.Text, 0.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            SkyText("ACHIEVEMENTS", 20.sp, Sky.Text, 4.sp, modifier = Modifier.weight(1f))
            Achievements.summary(list)?.let { SkyText(it, 18.sp, Sky.Text, 1.sp) }
        }
        Rule()
        val l = list
        if (l == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SkyText(if (loading) "Loading your achievements from Steam…" else "Connect to Steam to see your achievements.",
                    15.sp, Sky.Dim, 0.5.sp, light = true)
            }
            return@Column
        }
        val done = l.count { it.unlocked }
        // Progress, like the game's level bar.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(4.dp).background(Color(0xFF1C1C1C))) {
            Box(Modifier.fillMaxWidth(if (l.isEmpty()) 0f else done.toFloat() / l.size).height(4.dp).background(Sky.Body))
        }
        if (stale) SkyText("Offline: showing your achievements as of the last time Steam was connected.", 11.sp, Sky.Faint, 0.3.sp,
            light = true, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
        val sorted = l.sortedWith(compareByDescending<Achievements.Item> { it.unlocked }.thenByDescending { it.unlockedAt }.thenBy { it.hidden })
        LazyColumn(Modifier.weight(1f)) {
            items(sorted, key = { it.id }) { AchievementRow(it) }
        }
    }
}

@Composable
private fun AchievementRow(a: Achievements.Item) {
    val secret = a.hidden && !a.unlocked
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(if (a.unlocked) 1f else 0.55f), verticalAlignment = Alignment.CenterVertically) {
        val url = if (a.unlocked) a.icon else a.iconGray ?: a.icon
        Box(Modifier.size(44.dp).border(1.dp, Sky.Frame).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            if (secret || url.isBlank()) SkyText("?", 20.sp, Sky.Dim, 0.sp)
            else CoilImage(imageModel = { url }, imageOptions = ImageOptions(contentScale = ContentScale.Crop), modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            SkyText(if (secret) "Hidden achievement" else a.title, 15.sp, if (a.unlocked) Sky.Text else Sky.Body, 0.3.sp)
            SkyText(if (secret) "Keep playing to discover it." else a.description, 12.sp, Sky.Dim, 0.2.sp, light = true, maxLines = 2)
        }
        if (a.unlocked && a.unlockedAt > 0) {
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(a.unlockedAt * 1000))
            SkyText(date.uppercase(), 10.sp, Sky.Dim, 1.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
