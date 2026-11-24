package com.skyrimayn

import java.util.Locale

/**
 * Which of a mod's Nexus files fit the player's game (Skyrim SE 1.7.104, "AE"). Many mods (Address Library, SKSE,
 * Engine Fixes, most SKSE plugins) ship separate files for the old 1.5.97 runtime, VR and GOG, and Nexus's
 * "primary" file is often the old one. Read from the file's name and description, so every automatic pick
 * (requirements, mod lists, SKSE) and the mod page agree on what to download.
 */
object GameVersionFit {
    enum class Fit { FITS, OTHER_GAME, UNKNOWN }

    /**
     * Skyrim 1.7.99+ changed the Address Library format, so a file named for 1.7 is the one to take over a
     * "1.6.1170 and later" file, even when it's posted as a beta or optional file (SSE Engine Fixes: its main file
     * covers 1.5.97 and 1.6.1170 only, and 1.7 needs the separate 7.0.21 beta).
     */
    private val newest = Regex("\\b1\\.7(\\.\\d+|\\.x)?\\b")
    fun namesNewest(f: NexusApi.ModFile) = newest.containsMatchIn("${f.name} ${f.fileName}".lowercase(Locale.US).replace('_', ' '))
    private val older = Regex("\\b1\\.[56]\\.\\d+")
    /** "Part 2"-style files are a mod's other half (Engine Fixes' preloader works on every version), not an older build. */
    private val otherPart = Regex("\\bpart ?\\d\\b")

    /**
     * [of], among the mod's other files: when one is named for Skyrim 1.7, a file that only names 1.5/1.6 versions
     * ("Supports SE 1.5.97 and AE 1.6.1170+") is for an older Skyrim too, even though it says "AE".
     */
    fun of(f: NexusApi.ModFile, all: List<NexusApi.ModFile>): Fit {
        val own = of(f)
        if (namesNewest(f) || all.none { it !== f && namesNewest(it) && of(it) == Fit.FITS }) return own
        val text = "${f.name} ${f.fileName} ${f.description}".lowercase(Locale.US).replace('_', ' ')
        if (otherPart.containsMatchIn("${f.name} ${f.fileName}".lowercase(Locale.US).replace('_', ' '))) return own
        return if (older.containsMatchIn(text) && !newest.containsMatchIn(text)) Fit.OTHER_GAME else own
    }

    private val other = listOf(
        Regex("1\\.5\\.\\d+"), Regex("\\b1\\.5\\b"), Regex("\\bvr\\b"), Regex("skyrim ?vr"), Regex("\\bgog\\b"),
        Regex("\\(se\\)"), Regex("\\(special edition\\)"), Regex("\\bse only\\b"), Regex("special edition only"),
        Regex("pre[- ]?ae\\b"), Regex("non[- ]?ae\\b"), Regex("\\bse build\\b"), Regex("\\boldrim\\b"), Regex("\\ble\\b"),
    )
    private val fits = listOf(
        Regex("anniversary"), Regex("\\(ae\\)"), Regex("\\bae\\b"), Regex("1\\.6\\.\\d+"), Regex("1\\.7\\.\\d+"),
        Regex("\\bng\\b"), Regex("all versions"),
    )

    fun of(f: NexusApi.ModFile): Fit {
        // The name says what the file is for; descriptions often list other versions too ("not for 1.5.97").
        val name = "${f.name} ${f.fileName}".lowercase(Locale.US).replace('_', ' ')
        val nameFits = fits.any { it.containsMatchIn(name) }
        val nameOther = other.any { it.containsMatchIn(name) }
        if (nameFits && !nameOther) return Fit.FITS
        if (nameOther && !nameFits) return Fit.OTHER_GAME
        if (nameFits) return Fit.FITS   // "AE and SE" style names: usable on ours
        val desc = f.description.lowercase(Locale.US)
        val descFits = fits.any { it.containsMatchIn(desc) }
        val descOther = other.any { it.containsMatchIn(desc) }
        return when {
            descFits && !descOther -> Fit.FITS
            descOther && !descFits -> Fit.OTHER_GAME
            else -> Fit.UNKNOWN
        }
    }

    /**
     * The file to download when the player didn't pick one (a requirement, a mod list's missing file):
     * a current main file for our game, else Nexus's primary among those not for another game.
     */
    fun bestFile(all: List<NexusApi.ModFile>): NexusApi.ModFile? {
        val current = all.filter { it.current }.ifEmpty { all }
        current.filter { namesNewest(it) && of(it) == Fit.FITS }.let { n ->
            if (n.isNotEmpty()) return n.firstOrNull { it.category == "MAIN" } ?: n.maxByOrNull { it.date }
        }
        val usable = current.filter { of(it) != Fit.OTHER_GAME }.ifEmpty { current }
        val main = usable.filter { it.category == "MAIN" }.ifEmpty { usable }
        return main.firstOrNull { of(it) == Fit.FITS && it.primary }
            ?: main.firstOrNull { of(it) == Fit.FITS }
            ?: main.firstOrNull { it.primary }
            ?: main.maxByOrNull { it.date }
    }

    /** The mod page's order: the recommended file first, files for other games last. */
    fun sorted(all: List<NexusApi.ModFile>): List<NexusApi.ModFile> {
        val best = bestFile(all)
        return all.sortedWith(compareBy({ it != best }, { of(it, all) == Fit.OTHER_GAME }, { it.category != "MAIN" }))
    }
}
