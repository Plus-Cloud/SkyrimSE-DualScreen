package com.skyrimayn

import android.content.Context
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale
import java.util.regex.Pattern

/**
 * The app's languages. Screens keep their English text; it's looked up here at the moment it's drawn,
 * using the translation files in assets/skyrimayn/lang (id = text, same ids in every language).
 * Text with values in it ("{} mods") is matched by pattern. Anything not in the files stays English.
 */
object I18n {
    /** Shown name to file name; "System" follows the Thor's language. */
    val languages = listOf(
        "System" to "", "English" to "en", "Deutsch" to "de", "Español" to "es", "Français" to "fr", "Italiano" to "it",
        "Polski" to "pl", "Português (Brasil)" to "pt-BR", "Русский" to "ru", "日本語" to "ja", "한국어" to "ko",
        "简体中文" to "zh-Hans", "繁體中文" to "zh-Hant",
    )

    private class Template(val pattern: Pattern, val translated: String, val literalLength: Int)

    private var exact: Map<String, String> = emptyMap()
    /** Menus show some words in capitals that the files hold in normal case ("Hidden" as "HIDDEN"). */
    private var capitals: Map<String, String> = emptyMap()
    private var templates: List<Template> = emptyList()
    private val cache = HashMap<String, String>()
    private var revision by mutableIntStateOf(0)

    private val slot = Regex("\\{\\d*\\}")

    private fun parse(ctx: Context, code: String): Map<String, String> = runCatching {
        ctx.assets.open("skyrimayn/lang/$code.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("[") && " = " in it }
                .associate { it.substringBefore(" = ").trim() to it.substringAfter(" = ").trimEnd().replace("\\n", "\n") }
        }
    }.getOrDefault(emptyMap())

    private fun systemCode(): String {
        val l = Locale.getDefault()
        return when (l.language) {
            "zh" -> if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            "pt" -> "pt-BR"
            else -> l.language
        }
    }

    /** Loads the language chosen in settings. */
    fun apply(ctx: Context, shownName: String) {
        val chosen = languages.firstOrNull { it.first == shownName }?.second.orEmpty().ifEmpty { systemCode() }
        val english = parse(ctx, "en")
        val target = if (chosen == "en") emptyMap() else parse(ctx, chosen)
        val single = HashMap<String, String>()
        val patterned = ArrayList<Template>()
        for ((id, en) in english) {
            val text = target[id] ?: continue
            if (!slot.containsMatchIn(en)) { single[en] = text; continue }
            val literals = en.split(slot)
            val length = literals.sumOf { it.length }
            if (length == 0) continue
            patterned += Template(Pattern.compile(literals.joinToString("(.+?)", "^", "$") { Pattern.quote(it) }, Pattern.DOTALL), text, length)
        }
        synchronized(cache) {
            exact = single
            capitals = single.entries.associate { it.key.uppercase() to it.value.uppercase() }
            templates = patterned.sortedByDescending { it.literalLength }
            cache.clear()
        }
        revision++
    }

    /** [text] in the current language. Reading it inside a composable redraws that text when the language changes. */
    fun tr(text: String): String {
        @Suppress("UNUSED_EXPRESSION") revision
        if (exact.isEmpty() || text.isEmpty()) return text
        synchronized(cache) {
            cache[text]?.let { return it }
            val result = exact[text] ?: capitals[text] ?: templates.firstNotNullOfOrNull { fill(it, text) } ?: text
            if (cache.size > 4000) cache.clear()
            cache[text] = result
            return result
        }
    }

    private fun fill(t: Template, text: String): String? {
        val m = t.pattern.matcher(text)
        if (!m.matches()) return null
        var next = 1
        return slot.replace(t.translated) { found ->
            val number = found.value.trim('{', '}').toIntOrNull() ?: next++
            if (number in 1..m.groupCount()) m.group(number) else found.value
        }
    }
}

/** Material's Text, translated: used by the top-screen launcher. */
@Composable
internal fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
) = androidx.compose.material3.Text(
    I18n.tr(text), modifier = modifier, color = if (color == Color.Unspecified) LocalContentColor.current else color, fontSize = fontSize,
    fontFamily = fontFamily, letterSpacing = letterSpacing, textAlign = textAlign, lineHeight = lineHeight, maxLines = maxLines, overflow = overflow,
    style = LocalTextStyle.current,
)
