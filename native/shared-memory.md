# Skyrim AYN shared-memory link (v4)

`SkyrimAYN.dll`, the SKSE plugin, and the Skyrim AYN app share one 393216-byte file (v3: 262144, v2: 131072), `<game folder>\SkyrimAYN\state.bin`. All values are little-endian. Strings are UTF-8, NUL-padded.

Each region has exactly one writer:

| Bytes | Writer | Contents |
|---|---|---|
| 0–4095 | plugin | header, player state, command ack |
| 4096–8191 | app | commands |
| 8192–131071 | plugin | lists: inventory, spells |
| 131072–262143 | plugin | map markers (v3) |
| 262144–393215 | plugin | quest journal (v4) |

## Header (0–63)
| Off | Type | Field |
|---|---|---|
| 0 | u32 | magic `0x4E594153` ("SAYN") |
| 4 | u16 | version = 4 |
| 6 | u16 | headerSize = 64 |
| 8 | u32 | totalSize = 131072 |
| 12 | u32 | seq (seqlock for 0–4095: odd while the plugin is writing) |
| 16 | u64 | frame (heartbeat) |
| 24 | u64 | uptimeMs |
| 32 | u32 | pid |
| 36 | u32 | runtimeVersion |
| 40 | u32 | skseVersion |
| 44 | u32 | flags: bit0 mmap, bit1 file, bit2 player valid, bit3 lists valid, bit4 in game (a save is loaded), bit5 markers valid, bit6 quests valid |
| 48 | u32 | tickHz |
| 52 | u32 | listSeq, a copy of the lists seqlock, so the app knows when to re-read the lists |
| 60 | u32 | seqEnd |

Read rule: accept page 0 only if `seq` is even and `seq == seqEnd`.

## Player (64–511)
| Off | Type | Field |
|---|---|---|
| 64 | f32×6 | health, healthMax, magicka, magickaMax, stamina, staminaMax |
| 88 | f32×4 | posX, posY, posZ, heading (radians, 0 = north, clockwise) |
| 104 | u32 | worldspaceFormId |
| 108 | u32 | cellFormId |
| 112 | u16 | level |
| 114 | u8 | inCombat |
| 115 | u8 | menuOpen |
| 116 | char[64] | location name |
| 180 | i32 | gold |
| 184 | f32 | carryWeight |
| 188 | f32 | carryMax |
| 192 | f32 | xpProgress (0–1) |
| 196 | u32 | rightHand formId |
| 200 | u32 | leftHand formId |
| 204 | u32 | shout/power formId |
| 208 | char[48] | right-hand name |
| 256 | char[48] | left-hand name |
| 304 | char[48] | shout name |
| 352 | u8 | sneaking |
| 353 | u8 | weaponDrawn |
| 354 | u8 | interior (player is in an interior cell; position is local to it) |
| 356 | f32 | gameHour (0–24) |
| 360 | u32 | gameDaysPassed |
| 364 | char[48] | worldspace name |
| 412 | u16 | GameDay (1–31) |
| 414 | u8 | GameMonth (0 = Morning Star … 11 = Evening Star) |
| 416 | u16 | GameYear (4E) |
| 420 | char[48] | character name |
| 468 | char[32] | race |
| 500 | (f32 level, f32 progress)×18 | skills in actor-value order (One-Handed … Enchanting); progress 0–1 to the next level |

## Ack (3072–3087)
| Off | Type | Field |
|---|---|---|
| 3072 | u32 | ackSeq |
| 3076 | u32 | ackResult (0 = ok, otherwise an error code) |
| 3080 | u64 | ackUptimeMs |

## Commands (4096–4119), written by the app as one 24-byte write
| Off | Type | Field |
|---|---|---|
| 4096 | u32 | cmdSeq |
| 4100 | u32 | cmdId |
| 4104 | u32×4 | arg0–arg3 |

| Id | Name | Args | Notes |
|---|---|---|---|
| 1 | PING | arg0 = any | ackResult = arg0 |
| 2 | EQUIP_ITEM | formId, hand (0 either, 1 right, 2 left) | Weapons, armor, ammo |
| 3 | UNEQUIP_ITEM | formId | |
| 4 | USE_ITEM | formId | Potions, food, poisons (EquipItem consumes them) |
| 6 | SET_HUD | mask (1 compass, 2 health/magicka/stamina, 4 enemy health) | Hides those parts of the game's HUD; re-applied by the plugin twice a second. |
| 5 | SET_SPELL_HOTKEY | formId, slot (0–7) | Puts a spell or shout on a favorites hotkey. The app then presses key 1–8, so the game equips it its own way. |

## Lists (8192–131071)
List header at 8192:
| Off | Type | Field |
|---|---|---|
| 8192 | u32 | listSeq (odd while writing) |
| 8196 | u32 | itemCount (≤ 1000) |
| 8200 | u32 | spellCount (≤ 300) |
| 8204 | u32 | listSeqEnd |

**Items**, starting at 8256, 96 bytes each:
`u32 formId, i32 count, u8 category, u8 flags, u16 _, f32 weight, i32 value, char[76] name`
- flags: 1 equipped right/body, 2 equipped left, 4 favorite, 8 stolen
- category: 0 weapon, 1 apparel, 2 potion, 3 food, 4 ingredient, 5 scroll, 6 book, 7 misc, 8 ammo, 9 key, 10 soul gem, 11 light, 12 other

**Spells**, starting at 104448, 80 bytes each:
`u32 formId, u8 kind, u8 flags, u8 school, u8 _, f32 cost, char[68] name`
- kind: 0 spell, 1 shout, 2 power, 3 lesser power
- flags: 1 equipped right, 2 equipped left, 4 favorite, 8 equipped voice
- school: 0 none, 1 alteration, 2 conjuration, 3 destruction, 4 illusion, 5 restoration

## Where the file lives
`<game folder>\SkyrimAYN\state.bin`. You can set a different path in `Data\SKSE\Plugins\SkyrimAYN.ini` (`[Link] SharedFile=`).

## Map markers (131072–262143, v3), rewritten twice a second
Header at 131072:
| Off | Type | Field |
|---|---|---|
| 131072 | u32 | markerSeq (odd while writing) |
| 131076 | u32 | count (≤ 2040) |
| 131080 | u32 | map worldspace formId (the player's, or the last exterior one while indoors) |
| 131084 | u32 | markerSeqEnd |

**Markers**, starting at 131136, 64 bytes each:
`f32 x, f32 y, f32 z, u8 kind, u8 flags, u16 type, u32 formId, char[44] name`
- kind: 0 location (map marker), 1 quest target, 2 hostile actor in combat, 3 follower
- flags: 1 discovered (locations), 2 main quest, 4 inside the player's interior cell (compass only; position is cell-local), 8 not on the map yet (compass only, when close)
- Quest targets inside another interior are reported at the exterior door that leads there.
- type: the game's map-marker icon type (locations) or quest type (quest targets)

Quest targets come from the displayed objectives of active quests: each target's alias is resolved through the
quest's alias→ref-handle map, and targets whose conditions fail (GetStage / GetQuestRunning) are skipped, like the HUD does.

## Quest journal (262144–393215, v4), rewritten every 2 s
Header at 262144: `u32 questSeq (odd while writing), u32 count (≤ 120), u32 _, u32 questSeqEnd`.

**Quests**, starting at 262208, 1024 bytes each:
`u32 formId, u8 type, u8 flags, u8 objectiveCount (≤ 8), u8 _, u16 stage, u16 _, char[60] name`, then at +72 up to 8 objectives of
112 bytes: `u8 state (1 shown, 3 completed, 5 failed), u8 _, u16 index, char[108] text` (newest first).
- type: the game's quest type (1 main, 2 Mages Guild, 3 Thieves Guild, 4 Dark Brotherhood, 5 Companions, 6 misc, 7 Daedric, 8 side, 9 Civil War, 10 Dawnguard, 11 Dragonborn)
- flags: 1 active (tracked), 2 completed, 4 failed
- Objective text has `<Alias=…>` tokens replaced with the filled reference's name.

## Game compass mirror (v0.11), 386048–389311

Written on the UI thread every tick from the HUD's own `CompassTargetDataA`, so the companion draws exactly what the game's compass draws.

| Off | Type | Field |
|---|---|---|
| 386048 | u32 | seq (odd while writing) |
| +4 | u32 | count (max 200) |
| +8 | f32 | compass angle, degrees (from `CompassRect._x` between `CompassZeroX` and `CompassThreeSixtyX`) |
| +12 | f32 | visible half-arc, degrees (`CompassMask_mc._width * 180 / |ThreeSixtyX - ZeroX|`) |
| +16 | u16×6 | HUD frame numbers: quest, quest door, player-set, enemy, locations base, undiscovered base |
| +28 | u32 | 1 = valid |
| 386112 | 16 B × n | heading (f32, degrees), alpha (f32, 0–100), frame (u16), pad, scale (f32, %) |

Marker x on the bar = `(heading - angle + half) / (2 * half)`; quest markers outside the arc sit at the nearest end, others are hidden. Location icons: frame - base = map marker type.
