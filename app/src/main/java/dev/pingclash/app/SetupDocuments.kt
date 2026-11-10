// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Each language's guide; a language without its own shows the English one. */
internal fun guideAsset(language: String) =
    when (language) {
        "zh" -> "guide/user-guide.md"
        "ja" -> "guide/user-guide.ja.md"
        else -> "guide/user-guide.en.md"
    }

/** Settings > About: the bundled user guide and the agreement, readable without a network. */
@Composable
internal fun AboutDocuments() {
    var open by remember { mutableStateOf("") }
    SettingsSection {
        NavRow(tr(R.string.user_guide), "about-user-guide") { open = "guide" }
        RowDivider(underIcons = false)
        NavRow(tr(R.string.user_agreement), "about-agreement") { open = "agreement" }
    }
    when (open) {
        "guide" -> UserGuideDialog { open = "" }
        "agreement" -> AgreementDialog { open = "" }
    }
}

@Composable
internal fun AgreementDialog(onClose: () -> Unit) {
    DocumentDialog(tr(R.string.user_agreement), onClose) { AgreementText() }
}

/** The agreement, one section per blank-line block; each block's first line is its title. */
@Composable
internal fun AgreementText(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tr(R.string.agreement_text).split("\n\n").forEach { block ->
            val lines = block.lines()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    lines.first(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                lines.drop(1).forEach {
                    Text(
                        it,
                        Modifier.padding(start = if (it.startsWith("•")) 8.dp else 0.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** The user guide as shipped in the APK, from the same Markdown file as the published one. */
@Composable
internal fun UserGuideDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val asset = guideAsset(LocalUiLanguage.current)
    val text =
        remember(context, asset) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                .getOrNull()
        }
    DocumentDialog(tr(R.string.user_guide), onClose) {
        if (text == null) Text(tr(R.string.guide_unavailable)) else MarkdownText(text)
    }
}

@Composable
private fun DocumentDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("document-text")
            ) {
                content()
            }
        },
        confirmButton = { TextButton(onClose) { Text(tr(R.string.close)) } },
    )
}

/**
 * Just enough Markdown for the guide: headings, paragraphs, lists, tables shown as plain rows, code
 * blocks, and bold, code and link text inline. Links are shown as their text only.
 */
@Composable
private fun MarkdownText(source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var code = false
        for (raw in source.lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                code = !code
                continue
            }
            when {
                code ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                line.isBlank() || line.matches(TABLE_RULE) -> Unit
                line.startsWith("#") -> Heading(line)
                line.startsWith("|") ->
                    Text(
                        inline(line.trim('|').split('|').joinToString(" · ") { it.trim() }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                line.trimStart().startsWith("- ") ->
                    Text(
                        inline("•  " + line.trimStart().removePrefix("- ")),
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val TABLE_RULE = Regex("""^\|?[\s:|-]+\|?$""")

@Composable
private fun Heading(line: String) {
    val level = line.takeWhile { it == '#' }.length
    val style =
        when (level) {
            1 -> MaterialTheme.typography.titleLarge
            2 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
    Text(
        inline(line.drop(level).trim()),
        Modifier.padding(top = if (level <= 2) 10.dp else 4.dp),
        style = style,
        fontWeight = FontWeight.SemiBold,
    )
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\([^)]*\)""")

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(start, match.range.first))
        val (bold, code, link) = match.destructured
        when {
            bold.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> append(link)
        }
        start = match.range.last + 1
    }
    append(text.substring(start))
}
