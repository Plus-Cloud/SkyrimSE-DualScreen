package com.skyrimayn

/**
 * Sample character for previewing the companion without the game (debug builds only).
 * Enable: shared_prefs/link.xml boolean "demo" = true.
 */
object DemoData {
    val player = PlayerState(
        health = 186f, healthMax = 250f, magicka = 92f, magickaMax = 160f, stamina = 210f, staminaMax = 210f,
        x = 22500f, y = -8900f, z = 1450f, heading = 0.6f,
        worldspaceId = 0x3C, cellId = 0x1A26F, level = 14, inCombat = false, menuOpen = false,
        location = "Whiterun", worldspace = "Skyrim",
        gold = 1240, carryWeight = 212f, carryMax = 300f, xpProgress = 0.42f,
        rightHandId = 1, leftHandId = 2, shoutId = 3,
        rightHand = "Steel Sword", leftHand = "Flames", shout = "Unrelenting Force",
        sneaking = false, weaponDrawn = true, gameHour = 14.5f, gameDays = 11,
        gameDay = 28, gameMonth = 7, gameYear = 201, name = "Aela", race = "Nord",
        skillLevels = listOf(42f, 25f, 38f, 30f, 31f, 22f, 35f, 15f, 24f, 33f, 20f, 26f, 18f, 21f, 34f, 17f, 29f, 16f),
        skillProgress = listOf(.3f, .7f, .1f, .5f, .2f, .9f, .4f, .6f, .1f, .8f, .3f, .2f, .5f, .7f, .45f, .1f, .6f, .25f),
    )

    private fun item(id: Int, name: String, cat: ItemCategory, count: Int = 1, eq: Boolean = false, fav: Boolean = false, w: Float = 1f, v: Int = 10,
                     sub: Int = -1, stat: Float = 0f, effect: String = "", mag: Float = 0f, dur: Int = 0) =
        InvItem(id, count, cat, eq, false, fav, false, w, v, name, sub, stat, effect, mag, dur)

    private fun spell(id: Int, name: String, school: School, kind: SpellKind = SpellKind.SPELL, eqR: Boolean = false, eqL: Boolean = false, fav: Boolean = false, cost: Float = 20f) =
        Spell(id, kind, school, eqR, eqL, fav, kind == SpellKind.SHOUT && fav, cost, name)

    val lists = GameLists(
        items = listOf(
            item(1, "Steel Sword", ItemCategory.WEAPON, eq = true, fav = true, w = 10f, v = 45, sub = 1, stat = 8f),
            item(10, "Hunting Bow", ItemCategory.WEAPON, fav = true, w = 7f, v = 50, sub = 7, stat = 7f),
            item(11, "Steel Arrow", ItemCategory.AMMO, count = 34, w = 0f, v = 3),
            item(12, "Steel Armor", ItemCategory.APPAREL, eq = true, w = 35f, v = 275, sub = 1, stat = 31f),
            item(13, "Steel Helmet", ItemCategory.APPAREL, eq = true, w = 5f, v = 125),
            item(14, "Steel Shield", ItemCategory.APPAREL, w = 12f, v = 150),
            item(15, "Potion of Healing", ItemCategory.POTION, count = 6, fav = true, w = 0.5f, v = 36, effect = "Restore Health", mag = 50f),
            item(16, "Potion of Minor Magicka", ItemCategory.POTION, count = 3, w = 0.5f, v = 20),
            item(17, "Sweetroll", ItemCategory.FOOD, count = 2, w = 0.5f, v = 2),
            item(18, "Blue Mountain Flower", ItemCategory.INGREDIENT, count = 5, w = 0.1f, v = 2),
            item(19, "The Lusty Argonian Maid", ItemCategory.BOOK, w = 1f, v = 50),
            item(20, "Iron Ingot", ItemCategory.MISC, count = 4, w = 1f, v = 7),
            item(21, "Petty Soul Gem", ItemCategory.SOULGEM, count = 2, w = 0.1f, v = 10),
        ),
        spells = listOf(
            spell(2, "Flames", School.DESTRUCTION, eqL = true, fav = true, cost = 14f),
            spell(30, "Sparks", School.DESTRUCTION, cost = 18f),
            spell(31, "Healing", School.RESTORATION, fav = true, cost = 35f),
            spell(32, "Oakflesh", School.ALTERATION, cost = 98f),
            spell(33, "Conjure Familiar", School.CONJURATION, cost = 94f),
            spell(34, "Clairvoyance", School.ILLUSION, cost = 27f),
            spell(3, "Unrelenting Force", School.NONE, SpellKind.SHOUT, fav = true, cost = 0f),
            spell(35, "Battle Cry", School.NONE, SpellKind.POWER, cost = 0f),
        ),
    )

    /** Approximate spots around the player, for previewing the map and compass. */
    val markers = Markers(0x3C, listOf(
        MapMarker(22500f, -6200f, 0f, MarkerKind.LOCATION, true, false, 1, 1, "Whiterun"),
        MapMarker(27000f, -12500f, 0f, MarkerKind.LOCATION, true, false, 2, 2, "Riverwood"),
        MapMarker(15000f, -4000f, 0f, MarkerKind.LOCATION, true, false, 4, 3, "Bleak Falls Barrow"),
        MapMarker(32000f, -3000f, 0f, MarkerKind.LOCATION, false, false, 6, 4, "Fort Greymoor"),
        MapMarker(19000f, -15000f, 0f, MarkerKind.LOCATION, true, false, 17, 5, "The Guardian Stones"),
        MapMarker(26000f, -7600f, 0f, MarkerKind.QUEST, false, true, 1, 6, "Dragon Rising"),
        MapMarker(24200f, -8100f, 0f, MarkerKind.HOSTILE, false, false, 0, 7, "Bandit"),
        MapMarker(23900f, -9800f, 0f, MarkerKind.HOSTILE, false, false, 0, 8, "Bandit Archer"),
        MapMarker(22100f, -9300f, 0f, MarkerKind.ALLY, false, false, 0, 9, "Lydia"),
    ))

    val quests = listOf(
        Quest(6, 1, true, false, false, 10, "Dragon Rising", listOf(
            Objective(20, ObjectiveState.ACTIVE, "Go to the Western Watchtower"),
            Objective(10, ObjectiveState.COMPLETED, "Meet Irileth near the Western Watchtower"),
        )),
        Quest(10, 8, false, false, false, 10, "The Golden Claw", listOf(
            Objective(10, ObjectiveState.ACTIVE, "Retrieve the Golden Claw from Bleak Falls Barrow"),
        )),
        Quest(11, 6, false, false, false, 0, "", listOf(
            Objective(1, ObjectiveState.ACTIVE, "Speak to Lucan Valerius about the stolen claw"),
            Objective(2, ObjectiveState.ACTIVE, "Deliver the sword to Proventus Avenicci"),
        )),
        Quest(12, 1, false, true, false, 1000, "Unbound", listOf(
            Objective(100, ObjectiveState.COMPLETED, "Escape Helgen"),
        )),
    )
}
