package com.skyrimayn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Live game state from SkyrimAYN.dll (docs/shared-memory.md, v3). */
data class PlayerState(
    val health: Float, val healthMax: Float,
    val magicka: Float, val magickaMax: Float,
    val stamina: Float, val staminaMax: Float,
    val x: Float, val y: Float, val z: Float, val heading: Float,
    val worldspaceId: Int, val cellId: Int,
    val level: Int, val inCombat: Boolean, val menuOpen: Boolean,
    val location: String, val worldspace: String,
    val gold: Int, val carryWeight: Float, val carryMax: Float, val xpProgress: Float,
    val rightHandId: Int, val leftHandId: Int, val shoutId: Int,
    val rightHand: String, val leftHand: String, val shout: String,
    val sneaking: Boolean, val weaponDrawn: Boolean,
    val gameHour: Float, val gameDays: Int,
    /** In an interior cell (position is local to that cell, not the world map). */
    val interior: Boolean = false,
    val gameDay: Int = 0, val gameMonth: Int = 0, val gameYear: Int = 0,
    val name: String = "", val race: String = "",
    /** 18 skills in the game's order (see [Skills]): base level and progress to the next level (0-1). */
    val skillLevels: List<Float> = emptyList(), val skillProgress: List<Float> = emptyList(),
    /** What the game's Active Effects menu lists: one row per source. */
    val effects: List<ActiveEffect> = emptyList(),
    /** User mods the game actually loaded (not base game, DLC or Creation Club). */
    val loadedMods: List<String> = emptyList(),
)

/** [secondsLeft] < 0 means it doesn't wear off. [harmful]: hostile, detrimental or a disease. */
data class ActiveEffect(val name: String, val secondsLeft: Float, val harmful: Boolean)

/** Skyrim's 18 skills, in actor-value order, grouped like the Skills menu constellations. */
object Skills {
    val names = listOf("One-Handed", "Two-Handed", "Archery", "Block", "Smithing", "Heavy Armor", "Light Armor", "Pickpocket",
        "Lockpicking", "Sneak", "Alchemy", "Speech", "Alteration", "Conjuration", "Destruction", "Illusion", "Restoration", "Enchanting")
    /** Warrior, thief, mage, as the game's three columns. */
    val warrior = listOf(0, 1, 2, 3, 4, 5)
    val thief = listOf(6, 7, 8, 9, 10, 11)
    val mage = listOf(12, 13, 14, 15, 16, 17)
}

enum class ItemCategory(val label: String) {
    WEAPON("Weapons"), APPAREL("Apparel"), POTION("Potions"), FOOD("Food"), INGREDIENT("Ingredients"),
    SCROLL("Scrolls"), BOOK("Books"), MISC("Misc"), AMMO("Ammo"), KEY("Keys"), SOULGEM("Soul gems"),
    LIGHT("Torches"), OTHER("Other");
    companion object { fun of(i: Int) = entries.getOrElse(i) { OTHER } }
}

data class InvItem(
    val formId: Int, val count: Int, val category: ItemCategory,
    val equippedRight: Boolean, val equippedLeft: Boolean, val favorite: Boolean, val stolen: Boolean,
    val weight: Float, val value: Int, val name: String,
    /** Weapon type (game's numbering) or armor weight class (0 light, 1 heavy, 2 clothing); -1 otherwise. */
    val subtype: Int = -1,
    /** Damage (weapons) or armor rating (armor). */
    val stat: Float = 0f,
    /** First effect (potions, poisons, scrolls, ingredients, enchantments). */
    val effect: String = "", val magnitude: Float = 0f, val duration: Int = 0,
) {
    val equipped get() = equippedRight || equippedLeft
    val consumable get() = category == ItemCategory.POTION || category == ItemCategory.FOOD || category == ItemCategory.INGREDIENT
}

enum class SpellKind(val label: String) { SPELL("Spells"), SHOUT("Shouts"), POWER("Powers"), LESSER_POWER("Lesser powers") }
enum class School(val label: String) { NONE("Other"), ALTERATION("Alteration"), CONJURATION("Conjuration"), DESTRUCTION("Destruction"), ILLUSION("Illusion"), RESTORATION("Restoration") }

data class Spell(
    val formId: Int, val kind: SpellKind, val school: School,
    val equippedRight: Boolean, val equippedLeft: Boolean, val favorite: Boolean, val equippedVoice: Boolean,
    val cost: Float, val name: String,
) {
    val equipped get() = equippedRight || equippedLeft || equippedVoice
}

data class GameLists(val items: List<InvItem>, val spells: List<Spell>)

enum class MarkerKind { LOCATION, QUEST, HOSTILE, ALLY }

/** A map/compass marker from the plugin (protocol v3). [type] is the game's map-marker icon type for locations. */
data class MapMarker(
    val x: Float, val y: Float, val z: Float, val kind: MarkerKind,
    val discovered: Boolean, val mainQuest: Boolean, val type: Int, val formId: Int, val name: String,
    /** Inside the player's interior cell: compass only, not on the world map. */
    val interior: Boolean = false,
    /** A place not yet on the map: shown on the compass only when close, like the game. */
    val hidden: Boolean = false,
)

data class Markers(val worldspaceId: Int, val list: List<MapMarker>)

enum class ObjectiveState { ACTIVE, COMPLETED, FAILED }
data class Objective(val index: Int, val state: ObjectiveState, val text: String)

/** A quest as the journal shows it. [type] is the game's quest type (1 main, 6 misc, 8 side...). */
data class Quest(
    val formId: Int, val type: Int, val active: Boolean, val completed: Boolean, val failed: Boolean,
    val stage: Int, val name: String, val objectives: List<Objective>,
)

object Proto {
    const val V2_TOTAL = 131072
    const val V3_TOTAL = 262144
    const val V4_TOTAL = 393216
    const val QUESTS_VALID = 1 shl 6
    const val QUESTS = 262144; const val QUEST_ENTRIES = 262208; const val QUEST_SIZE = 1024; const val MAX_QUESTS = 120
    /** v0.11: a copy of the game HUD compass (what it draws, as it draws it). */
    const val COMPASS = 386048; const val COMPASS_ENTRIES = 386112; const val COMPASS_SIZE = 16; const val MAX_COMPASS = 200
    const val MARKERS_VALID = 1 shl 5
    const val MARKERS = 131072; const val MARKER_ENTRIES = 131136; const val MARKER_SIZE = 64; const val MAX_MARKERS = 2040
    const val PLAYER_VALID = 1 shl 2
    const val LISTS_VALID = 1 shl 3
    const val IN_GAME = 1 shl 4
    /** Plugin 0.18: Skyrim is drawing something (main menu, loading screen or the world), not its black start-up. */
    const val SHOWING = 1 shl 7
    const val LIST_SEQ_COPY = 52
    const val LISTS = 8192
    const val ITEMS = 8256; const val ITEM_SIZE = 120; const val MAX_ITEMS = 800
    const val SPELLS = 104448; const val SPELL_SIZE = 80; const val MAX_SPELLS = 300

    const val CMD_EQUIP_ITEM = 2
    const val CMD_UNEQUIP_ITEM = 3
    const val CMD_USE_ITEM = 4
    const val CMD_SET_SPELL_HOTKEY = 5
    const val CMD_SET_HUD = 6
    const val CMD_FAST_TRAVEL = 7
    /** arg0 quest form id, arg1 1 = track (show its markers), 0 = untrack. Plugin 0.12+. */
    const val CMD_TRACK_QUEST = 8
    /** Plugin 0.14+: a normal save named "Overheat Save". */
    const val CMD_SAVE_GAME = 9

    fun str(b: ByteBuffer, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b.get(end).toInt() != 0) end++
        val bytes = ByteArray(end - off) { b.get(off + it) }
        return bytes.decodeToString()
    }

    fun player(b: ByteBuffer): PlayerState = PlayerState(
        b.getFloat(64), b.getFloat(68), b.getFloat(72), b.getFloat(76), b.getFloat(80), b.getFloat(84),
        b.getFloat(88), b.getFloat(92), b.getFloat(96), b.getFloat(100),
        b.getInt(104), b.getInt(108),
        b.getShort(112).toInt() and 0xFFFF, b.get(114).toInt() != 0, b.get(115).toInt() != 0,
        str(b, 116, 64), str(b, 364, 48),
        b.getInt(180), b.getFloat(184), b.getFloat(188), b.getFloat(192),
        b.getInt(196), b.getInt(200), b.getInt(204),
        str(b, 208, 48), str(b, 256, 48), str(b, 304, 48),
        b.get(352).toInt() != 0, b.get(353).toInt() != 0,
        b.getFloat(356), b.getInt(360),
        b.get(354).toInt() != 0,
        b.getShort(412).toInt() and 0xFFFF, b.get(414).toInt() and 0xFF, b.getShort(416).toInt() and 0xFFFF,
        str(b, 420, 48), str(b, 468, 32),
        List(18) { b.getFloat(500 + it * 8) }, List(18) { b.getFloat(504 + it * 8).coerceIn(0f, 1f) },
        List((b.get(700).toInt() and 0xFF).coerceAtMost(12)) { i -> val o = 704 + i * 48; ActiveEffect(str(b, o, 40), b.getFloat(o + 40), b.get(o + 44).toInt() != 0) },
        List((b.getShort(1284).toInt() and 0xFFFF).coerceAtMost(36)) { i -> str(b, 1288 + i * 48, 48) },
    )

    /** Parses the lists region; [b] is the whole file. Returns null if it's mid-write. */
    fun lists(b: ByteBuffer): GameLists? {
        val seq = b.getInt(LISTS)
        if (seq and 1 != 0 || seq != b.getInt(LISTS + 12)) return null
        val nItems = b.getInt(LISTS + 4).coerceIn(0, MAX_ITEMS)
        val nSpells = b.getInt(LISTS + 8).coerceIn(0, MAX_SPELLS)
        val items = (0 until nItems).map { i ->
            val o = ITEMS + i * ITEM_SIZE
            val flags = b.get(o + 9).toInt()
            InvItem(
                b.getInt(o), b.getInt(o + 4), ItemCategory.of(b.get(o + 8).toInt()),
                flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0,
                b.getFloat(o + 12), b.getInt(o + 16), str(b, o + 20, 52),
                (b.get(o + 10).toInt() and 0xFF).let { if (it == 0xFF) -1 else it }, b.getFloat(o + 72),
                str(b, o + 84, 36), b.getFloat(o + 76), b.getInt(o + 80),
            )
        }
        val spells = (0 until nSpells).map { i ->
            val o = SPELLS + i * SPELL_SIZE
            val flags = b.get(o + 5).toInt()
            Spell(
                b.getInt(o), SpellKind.entries.getOrElse(b.get(o + 4).toInt()) { SpellKind.SPELL },
                School.entries.getOrElse(b.get(o + 6).toInt()) { School.NONE },
                flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0,
                b.getFloat(o + 8), str(b, o + 12, 68),
            )
        }
        return GameLists(items, spells)
    }

    /** Parses the markers region; [b] holds bytes from [MARKERS] to the end of the file. Null if mid-write. */
    fun markers(b: ByteBuffer): Markers? {
        val seq = b.getInt(0)
        if (seq and 1 != 0 || seq != b.getInt(12)) return null
        val n = b.getInt(4).coerceIn(0, MAX_MARKERS)
        val list = (0 until n).map { i ->
            val o = MARKER_ENTRIES - MARKERS + i * MARKER_SIZE
            val flags = b.get(o + 13).toInt()
            MapMarker(
                b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8),
                MarkerKind.entries.getOrElse(b.get(o + 12).toInt()) { MarkerKind.LOCATION },
                flags and 1 != 0, flags and 2 != 0, b.getShort(o + 14).toInt() and 0xFFFF, b.getInt(o + 16), str(b, o + 20, 44),
                flags and 4 != 0, flags and 8 != 0,
            )
        }
        return Markers(b.getInt(8), list)
    }

    /** Parses the quest region; [b] holds bytes from [QUESTS] to the end of the file. Null if mid-write. */
    fun quests(b: ByteBuffer): List<Quest>? {
        val seq = b.getInt(0)
        if (seq and 1 != 0 || seq != b.getInt(12)) return null
        val n = b.getInt(4).coerceIn(0, MAX_QUESTS)
        return (0 until n).map { i ->
            val o = QUEST_ENTRIES - QUESTS + i * QUEST_SIZE
            val flags = b.get(o + 5).toInt()
            val nObj = (b.get(o + 6).toInt() and 0xFF).coerceAtMost(8)
            Quest(
                b.getInt(o), b.get(o + 4).toInt() and 0xFF, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0,
                b.getShort(o + 8).toInt() and 0xFFFF, str(b, o + 12, 60),
                (0 until nObj).map { k ->
                    val oo = o + 72 + k * 112
                    val st = when (b.get(oo).toInt()) { 3 -> ObjectiveState.COMPLETED; 5 -> ObjectiveState.FAILED; else -> ObjectiveState.ACTIVE }
                    Objective(b.getShort(oo + 2).toInt() and 0xFFFF, st, str(b, oo + 4, 108))
                },
            )
        }
    }

    fun wrap(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}


/** One marker on the game's compass: heading in degrees, alpha 0..100, the HUD's icon frame, scale %. */
data class CompassMark(val heading: Float, val alpha: Float, val frame: Int, val scale: Float)

/**
 * The game's compass as its HUD draws it: centre angle and visible half-arc (degrees), the markers,
 * and the HUD's frame numbers for each icon family (locations and undiscovered are bases; + map marker type).
 */
data class GameCompass(
    val angle: Float, val half: Float, val marks: List<CompassMark>,
    val quest: Int, val questDoor: Int, val playerSet: Int, val enemy: Int, val locations: Int, val undiscovered: Int,
    val at: Long,
) {
    fun isQuest(f: Int) = f == quest || f == questDoor
    /** Map marker type for a location icon frame, and whether it's discovered; null if not a location. */
    fun location(f: Int): Pair<Int, Boolean>? {
        val bases = listOf(locations to true, undiscovered to false).filter { it.first > 0 }.sortedByDescending { it.first }
        for ((b, disc) in bases) if (f >= b) return (f - b) to disc
        return null
    }
}
