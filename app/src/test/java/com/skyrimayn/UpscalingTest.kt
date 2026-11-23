package com.skyrimayn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 360p / 480p render small and FSR enlarges them; 720p and up keep their old look unless FSR is chosen. */
class UpscalingTest {
    @Test fun renderSizes_are16by9() {
        assertEquals(640 to 360, LauncherSettings.renderSize("360p"))
        assertEquals(854 to 480, LauncherSettings.renderSize("480p"))
        assertEquals(1280 to 720, LauncherSettings.renderSize("720p"))
        assertEquals(1920 to 1080, LauncherSettings.renderSize("1080p"))
        assertEquals(1280 to 720, LauncherSettings.renderSize("something old"))
    }

    @Test fun auto_usesFsrOnlyBelow720p() {
        assertTrue(LauncherSettings.useFsr("360p", "Auto"))
        assertTrue(LauncherSettings.useFsr("480p", "Auto"))
        assertFalse(LauncherSettings.useFsr("720p", "Auto"))
        assertTrue(LauncherSettings.useFsr("720p", "FSR"))
        assertFalse(LauncherSettings.useFsr("360p", "Off"))
    }
}
