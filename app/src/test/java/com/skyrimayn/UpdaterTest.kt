package com.skyrimayn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionOrdering() {
        assertTrue(Updater.newer("1.0.1", "1.0.0"))
        assertTrue(Updater.newer("1.10.0", "1.9.2"))
        assertTrue(Updater.newer("2.0", "1.99.99"))
        assertTrue(Updater.newer("1.0.0.1", "1.0.0"))
        assertFalse(Updater.newer("1.0.0", "1.0.0"))
        assertFalse(Updater.newer("1.0", "1.0.0"))          // same version, different length
        assertFalse(Updater.newer("0.9.9", "1.0.0"))
    }

    @Test fun releaseNotesBecomePlainText() {
        val md = "## 1.0.1\n\n**Fixes**\n- Compass `jitter` fixed\n* See [the README](https://example.com)\n\n\n\nThanks!"
        assertEquals("1.0.1\n\nFixes\n• Compass jitter fixed\n• See the README\n\nThanks!", Updater.plainNotes(md))
    }
}
