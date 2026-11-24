package com.skyrimayn

import org.junit.Assert.assertEquals
import org.junit.Test

/** skse64.log lines for plugins that failed become reasons a player can act on. */
class SkseFailuresTest {
    @Test fun reasons() {
        val log = listOf(
            "SKSE64 runtime: initialize (version = 2.3.1 01070680 01DB...)",
            "plugin EngineFixes.dll (00000001 EngineFixes 00000007) couldn't load plugin 126 (handle 0)",
            "plugin zz_report_test.dll (00000000  00000000) couldn't load plugin 193 (handle 0)",
            "plugin OldMod.dll (00000001 OldMod 00000001) disabled, incompatible with current version of the game 0 (handle 0)",
            "plugin po3_Tweaks.dll (00000001 powerofthree's Tweaks 00010600) disabled, address library needs to be updated 0 (handle 0)",
            "plugin NoVer.dll (00000000 NoVer 00000000) no version data 0 (handle 0)",
            "plugin SkyrimAYN.dll (00000001 SkyrimAYN 00000014) loaded correctly (handle 2)",
            "plugin skee64.dll (00000001 skee 00000001) loaded correctly (handle 3)",
        )
        assertEquals(
            listOf(
                "SSE Engine Fixes is missing its Part 2: the files that go next to SkyrimSE.exe",
                "zz_report_test.dll is damaged or isn't a 64-bit Windows plugin",
                "OldMod.dll isn't made for Skyrim ${GameFolder.SUPPORTED_VERSION} yet",
                "po3_Tweaks.dll needs the newest Address Library for SKSE Plugins (All in one, Anniversary Edition)",
                "NoVer.dll is made for an older Skyrim: get the file marked RECOMMENDED FOR YOUR GAME",
            ),
            NotificationFeed.skseFailures(log),
        )
    }

    @Test fun cleanLog_reportsNothing() {
        assertEquals(emptyList<String>(), NotificationFeed.skseFailures(listOf(
            "plugin SkyrimAYN.dll (00000001 SkyrimAYN 00000014) loaded correctly (handle 2)",
        )))
    }
}
