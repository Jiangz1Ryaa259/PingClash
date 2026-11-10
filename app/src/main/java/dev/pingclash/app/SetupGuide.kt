// SPDX-License-Identifier: GPL-3.0-only
// Layout spacing is a deliberate design value.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal const val SETUP_LANGUAGE = 1
internal const val SETUP_AGREEMENT = 2
internal const val SETUP_HOW = 3
private const val SETUP_PROFILE = 4
private const val SETUP_ROUTING = 5
private const val SETUP_BLOCKING = 6
internal const val SETUP_READY = 7

/** Languages the app speaks, each named in itself; more join this list as they are added. */
internal val SETUP_LANGUAGES = UI_LANGUAGES.map { it.code to it.name }

/** What the guide reports back to the app shell. */
internal class SetupActions(
    val agree: () -> Unit,
    val exit: () -> Unit,
    /** The guide ended: [skipped] by Skip setup, and whether to [connect] now. */
    val finish: (skipped: Boolean, connect: Boolean) -> Unit,
)

/**
 * The first-run guide, one step per screen. [first] is [SETUP_LANGUAGE] for a new install, or
 * [SETUP_HOW] when Settings runs the guide again; [agreementOnly] shows just the agreement. No step
 * writes anything until it is confirmed with Next, except the language, which applies at once.
 */
@Composable
internal fun SetupGuide(
    data: AppData,
    model: AppViewModel,
    first: Int,
    agreementOnly: Boolean,
    actions: SetupActions,
) {
    var step by rememberSaveable { mutableIntStateOf(first) }
    val last = if (agreementOnly) SETUP_AGREEMENT else SETUP_READY
    fun next() {
        if (step >= last) actions.finish(false, false) else step++
    }
    BackHandler {
        when {
            // Before the agreement there is no app to go to.
            step == first && step <= SETUP_AGREEMENT -> actions.exit()
            // Leaving the guide early is a skip: the hint says where to find it again.
            step == first -> actions.finish(true, false)
            else -> step--
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .readableWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("setup-guide"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupTopBar(step, agreementOnly) { actions.finish(true, false) }
            StepContent(step, data, model, actions, ::next)
        }
    }
}

@Composable
private fun ColumnScope.StepContent(
    step: Int,
    data: AppData,
    model: AppViewModel,
    actions: SetupActions,
    next: () -> Unit,
) {
    when (step) {
        SETUP_LANGUAGE -> LanguageStep(data, model, next)
        SETUP_AGREEMENT ->
            AgreementStep(
                {
                    actions.agree()
                    next()
                },
                actions.exit,
            )
        SETUP_HOW -> HowStep(next)
        SETUP_PROFILE -> ProfileStep(data, model, next)
        SETUP_ROUTING -> RoutingStep(data, model, next)
        SETUP_BLOCKING -> BlockingStep(data, model, next)
        else -> ReadyStep(data) { connect -> actions.finish(false, connect) }
    }
}

/**
 * The language comes first and is chosen, not guessed from the system. It applies at once, so the
 * rest of the guide speaks it.
 */
@Composable
private fun ColumnScope.LanguageStep(data: AppData, model: AppViewModel, onNext: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf("") }
    StepFrame(
        tr(R.string.setup_language_title),
        {
            Button(onNext, Modifier.testTag("setup-next"), enabled = chosen.isNotEmpty()) {
                Text(tr(R.string.setup_next), fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        SettingsSection {
            CheckList(SETUP_LANGUAGES, chosen, true, { "setup-language-$it" }) {
                chosen = it
                model.appearance(it, data.client.theme)
            }
        }
    }
}

@Composable
private fun ColumnScope.AgreementStep(onAgree: () -> Unit, onExit: () -> Unit) {
    StepFrame(
        tr(R.string.setup_welcome_title),
        {
            SecondaryButton(tr(R.string.setup_exit), "setup-exit", onExit)
            PrimaryButton(tr(R.string.setup_agree), "setup-agree", onAgree)
        },
    ) {
        SetupBody(tr(R.string.setup_welcome_body))
        SettingsSection(tr(R.string.user_agreement)) {
            AgreementText(Modifier.padding(vertical = 12.dp).testTag("setup-agreement-text"))
        }
    }
}

@Composable
private fun ColumnScope.HowStep(onNext: () -> Unit) {
    var guide by rememberSaveable { mutableStateOf(false) }
    StepFrame(
        tr(R.string.setup_how_title),
        {
            SecondaryButton(tr(R.string.setup_skip), "setup-skip", onNext)
            PrimaryButton(tr(R.string.setup_next), "setup-next", onNext)
        },
    ) {
        SettingsSection {
            HowRow(
                PcIcon.Add,
                Color(0xFF34C759),
                R.string.setup_how_import_title,
                R.string.setup_how_import_body,
            )
            RowDivider()
            HowRow(
                PcIcon.Power,
                Color(0xFF0A84FF),
                R.string.setup_how_connect_title,
                R.string.setup_how_connect_body,
            )
            RowDivider()
            HowRow(
                PcIcon.Layers,
                Color(0xFFFF9500),
                R.string.setup_how_nodes_title,
                R.string.setup_how_nodes_body,
            )
        }
        SettingsSection {
            NavRow(tr(R.string.setup_full_guide), "setup-full-guide") { guide = true }
        }
    }
    if (guide) UserGuideDialog { guide = false }
}

@Composable
private fun HowRow(icon: PcIcon, tint: Color, title: Int, body: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquare(icon, tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                tr(title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(tr(body), color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColumnScope.ProfileStep(data: AppData, model: AppViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val id = result.data?.getStringExtra(ProfileImportActivity.RESULT_PROFILE)
            if (result.resultCode == Activity.RESULT_OK && id != null) model.importCompleted(id)
        }
    val restorer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::restoreBackup)
        }
    val profile = data.activeProfile ?: data.profiles.firstOrNull()
    StepFrame(
        tr(R.string.setup_profile_title),
        {
            if (profile == null) SecondaryButton(tr(R.string.setup_later), "setup-skip", onNext)
            PrimaryButton(tr(R.string.setup_next), "setup-next", onNext)
        },
    ) {
        SetupBody(tr(R.string.setup_profile_body))
        SettingsSection {
            ActionRow(tr(R.string.setup_import), "setup-import") {
                importer.launch(ProfileImportActivity.intent(context, ImportMethod.URL))
            }
            RowDivider(underIcons = false)
            ActionRow(tr(R.string.setup_restore), "setup-restore") {
                restorer.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
            }
        }
        if (profile != null)
            Text(
                tr(R.string.setup_profile_ready, profile.name),
                Modifier.testTag("setup-profile-ready"),
                color = Success,
                style = MaterialTheme.typography.bodyLarge,
            )
    }
}

private val routeSaver =
    androidx.compose.runtime.saveable.Saver<Map<String, String>, String>(
        save = { JSONObject(it as Map<*, *>).toString() },
        restore = { text ->
            JSONObject(text).let { o -> o.keys().asSequence().associateWith(o::getString) }
        },
    )

@Composable
private fun ColumnScope.RoutingStep(data: AppData, model: AppViewModel, onNext: () -> Unit) {
    val rows = guideRows(rememberHomeRegion())
    var draft by
        rememberSaveable(stateSaver = routeSaver) { mutableStateOf(data.client.serviceRoutes) }
    StepFrame(
        tr(R.string.setup_routing_title),
        {
            SecondaryButton(tr(R.string.setup_skip), "setup-skip", onNext)
            PrimaryButton(tr(R.string.setup_next), "setup-next") {
                model.serviceRouting(draft, data.client.blocking)
                onNext()
            }
        },
    ) {
        SettingsSection(footer = tr(R.string.setup_routing_more)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider(underIcons = false)
                RouteRow(
                    RouteLabel(row.label(LocalUiLanguage.current), row.id),
                    draft.routeOf(row.members),
                    true,
                ) {
                    draft = draft.withRoute(row.members, it)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.BlockingStep(data: AppData, model: AppViewModel, onNext: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(data.client.blocking) }
    StepFrame(
        tr(R.string.setup_blocking_title),
        {
            SecondaryButton(tr(R.string.setup_skip), "setup-skip", onNext)
            PrimaryButton(tr(R.string.setup_next), "setup-next") {
                model.serviceRouting(data.client.serviceRoutes, draft)
                onNext()
            }
        },
    ) {
        SettingsSection {
            CheckList(blockingOptions(), draft, true, { "blocking-$it" }) { draft = it }
        }
    }
}

@Composable
private fun ColumnScope.ReadyStep(data: AppData, onFinish: (connect: Boolean) -> Unit) {
    val profile = data.activeProfile ?: data.profiles.firstOrNull()
    StepFrame(
        tr(R.string.setup_ready_title),
        {
            if (data.selected != null)
                SecondaryButton(tr(R.string.setup_connect), "setup-connect") { onFinish(true) }
            PrimaryButton(tr(R.string.setup_home), "setup-home") { onFinish(false) }
        },
    ) {
        SettingsSection {
            InfoRow(
                tr(R.string.setup_ready_profile),
                profile?.name ?: tr(R.string.setup_ready_no_profile),
            )
            RowDivider(underIcons = false)
            InfoRow(tr(R.string.setup_ready_services), data.client.serviceRoutes.size.toString())
            RowDivider(underIcons = false)
            InfoRow(
                tr(R.string.ad_blocking),
                blockingOptions().first { it.first == data.client.blocking }.second,
            )
        }
    }
}
