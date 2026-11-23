package com.skyrimayn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GitHub issue a report opens: filled in from what the app detected, short enough for a URL. */
class BugReportTest {
    private val problem = Problems.Problem(
        "Skyrim closed unexpectedly",
        "Skyrim closed unexpectedly. If it keeps happening, try turning PLAY WITH MODS off, or tap SEND REPORT. (exit status 137)",
        1_763_900_000_000,
    )

    @Test fun detectedProblem_fillsTitleAndWhatHappened() {
        assertTrue(BugReport.title(problem).endsWith("Skyrim closed unexpectedly"))
        val body = BugReport.body(problem, "SkyrimSE-DualScreen-report-20261123-1200.zip")
        assertTrue(body.contains("exit status 137"))
        assertTrue(body.contains("Detected by the app"))
        assertTrue(body.contains("SkyrimSE-DualScreen-report-20261123-1200.zip"))
        assertFalse(body.contains("Describe what went wrong"))
        assertTrue("GitHub's new-issue URL must stay short", body.length < 2000)
    }

    @Test fun playersOwnWords_goIntoTheIssue() {
        val withProblem = BugReport.body(problem, "r.zip", "Was fast travelling to Whiterun")
        assertTrue(withProblem.contains("In the player's words:** Was fast travelling to Whiterun"))
        assertTrue(withProblem.contains("exit status 137"))
        assertTrue(BugReport.title(null, "Controller stops working after sleep").endsWith("Controller stops working after sleep"))
        assertTrue(BugReport.body(null, "r.zip", "Controller stops working after sleep").contains("Controller stops working after sleep"))
    }

    @Test fun noProblem_asksPlayerToDescribe() {
        assertTrue(BugReport.title(null).endsWith("Problem report"))
        assertTrue(BugReport.body(null, "r.zip").contains("Describe what went wrong"))
    }
}
