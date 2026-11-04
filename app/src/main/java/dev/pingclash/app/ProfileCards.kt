// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.UUID
import kotlinx.coroutines.delay

internal enum class ProfileCommand {
    REFRESH,
    DETAILS,
    RENAME,
    EXPORT,
    DELETE,
    UA,
    REPARSE,
    COMPATIBILITY,
    PROVIDERS,
    PROXIES,
}

internal data class ProfileActions(
    val select: (LocalProfile) -> Unit,
    val command: (ProfileCommand, LocalProfile) -> Unit,
    val add: () -> Unit,
    val import: ((ImportMethod) -> Unit)? = null,
    val refreshAll: (() -> Unit)? = null,
    val reorder: ((List<String>) -> Unit)? = null,
)

@Composable
internal fun ProfilesOverview(
    data: AppData,
    locked: Boolean,
    ui: AppUiState,
    actions: ProfileActions,
) {
    val scroll = rememberLazyListState()
    val compact by remember {
        derivedStateOf {
            scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 32
        }
    }
    val bottom = LocalNavigationClearance.current
    var sorting by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val reorder = remember(scroll) { ProfileReorder(scroll, haptics, edge) }
    LaunchedEffect(data.profiles.size, locked) {
        if (data.profiles.size < 2 || locked) sorting = false
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = readableSidePadding(maxWidth)
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag("profiles-list"),
            contentPadding =
                PaddingValues(start = side, end = side, top = 12.dp, bottom = bottom + 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PageHeading(tr(R.string.profiles), Modifier.weight(1f), "page-title-profiles")
                    ProfileHeaderActions(
                        canRefresh = !fetchLocked(locked) && data.profiles.any { it.autoUpdates },
                        canSort = !locked && data.profiles.size > 1,
                        sorting,
                        actions,
                    ) {
                        sorting = !sorting
                    }
                }
            }
            ui.importIssue?.let { issue -> item(key = "issue") { InputIssue(issue) } }
            if (data.profiles.isEmpty()) item(key = "empty") { ProfilesEmpty() }
            profileItems(data, locked, actions, if (sorting) reorder else null)
        }
        ProfileImportMenu(
            compact,
            fetchLocked(locked),
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottom + 12.dp),
        ) { method ->
            actions.import?.invoke(method) ?: actions.add()
        }
    }
}

private fun LazyListScope.profileItems(
    data: AppData,
    locked: Boolean,
    actions: ProfileActions,
    reorder: ProfileReorder?,
) {
    val shown = reorder?.display(data.profiles) ?: data.profiles
    items(shown, key = { it.id }) { profile ->
        val lifted = reorder?.dragged == profile.id
        val sort = if (reorder != null && actions.reorder != null) reorder else null
        Box(
            // Other cards glide aside as the lifted one passes; the lifted card
            // follows the finger directly instead of animating to its slot.
            Modifier.animateItem(placementSpec = if (lifted) null else spring(stiffness = 600f))
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    translationY = reorder?.translation(profile.id) ?: 0f
                    val lift = if (lifted) 1.03f else 1f
                    scaleX = lift
                    scaleY = lift
                    shadowElevation = if (lifted) 16.dp.toPx() else 0f
                    shape = UiCorners.Sheet
                }
                .then(
                    if (sort == null) Modifier
                    else
                        Modifier.reorderable(sort, profile.id, actions.reorder!!).semantics {
                            customActions = sortActions(sort, profile, actions.reorder)
                        }
                )
        ) {
            ProfileSummary(profile, data.activeProfile?.id == profile.id, locked, actions, sort)
        }
    }
}

@Composable
private fun ProfileSummary(
    profile: LocalProfile,
    selected: Boolean,
    locked: Boolean,
    actions: ProfileActions,
    // Set while reordering: the card shows a drag grip instead of its menu.
    reorder: ProfileReorder? = null,
) {
    val press = remember { MutableInteractionSource() }
    Surface(
        shape = UiCorners.Sheet,
        color = Panel.copy(alpha = .86f),
        border =
            BorderStroke(
                if (selected) 1.4.dp else .7.dp,
                if (selected) Mint else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier.fillMaxWidth()
                .testTag("profile-${profile.id}")
                .fluidPress(press, depth = .97f)
                .selectable(
                    selected,
                    interactionSource = press,
                    indication = null,
                    enabled = !selectionLocked(locked) && reorder == null,
                    role = Role.RadioButton,
                ) {
                    actions.select(profile)
                },
    ) {
        ProfileQuotaFill(profile, selected) {
            // The 48dp menu target sets the title row height; less top and more bottom
            // padding keeps the text block optically centred in the card.
            Column(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // Imported from a link and not yet selected: selecting it activates it.
                    if (profile.activationRequired) NewProfileChip()
                    val commit = actions.reorder
                    if (reorder != null && commit != null)
                        ProfileDragHandle(profile, reorder, commit)
                    // While updating, the menu button itself becomes the spinner.
                    else if (profile.id in LocalRefreshingProfiles.current)
                        ProfileUpdatingSpinner(profile)
                    else ProfileMenu(profile, locked, actions)
                }
                ProfileQuota(profile)
            }
        }
    }
}

/** A small badge in the title row (no extra height), gone once the profile is selected. */
@Composable
private fun NewProfileChip() {
    Text(
        tr(R.string.profile_new),
        Modifier.padding(start = 8.dp)
            .background(Mint.copy(alpha = .14f), androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("profile-new"),
        color = Mint,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun ProfileMenu(profile: LocalProfile, locked: Boolean, actions: ProfileActions) {
    var menu by remember(profile.id) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val fetch = fetchLocked(locked)
    val context = LocalContext.current
    LaunchedEffect(copied) {
        if (copied) {
            delay(1300)
            copied = false
            menu = false
        }
    }
    Box {
        IconButton(
            { menu = true },
            Modifier.testTag("profile-menu-${profile.id}").semantics {
                contentDescription = tr(R.string.ui_more_actions)
            },
        ) {
            AppGlyph(PcIcon.More)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr(R.string.open_proxies)) },
                onClick = {
                    menu = false
                    actions.command(ProfileCommand.PROXIES, profile)
                },
                enabled = !selectionLocked(locked),
            )
            DropdownMenuItem(
                text = {
                    Text(tr(if (copied) R.string.ui_copied else R.string.ui_subscription_copy))
                },
                leadingIcon = {
                    AnimatedContent(copied, label = "copy-feedback") {
                        AppGlyph(if (it) PcIcon.Check else PcIcon.Copy)
                    }
                },
                enabled = profile.source != null,
                onClick = { profile.source?.let { copied = copySubscription(context, it) } },
            )
            listOf(
                    ProfileCommand.REFRESH to R.string.update,
                    ProfileCommand.DETAILS to R.string.ui_profile_details,
                    ProfileCommand.RENAME to R.string.rename,
                    ProfileCommand.EXPORT to R.string.export_profile,
                    ProfileCommand.DELETE to R.string.delete,
                )
                .forEach { (command, title) ->
                    if (command != ProfileCommand.REFRESH || profile.source != null)
                        DropdownMenuItem(
                            text = { Text(tr(title)) },
                            enabled =
                                when (command) {
                                    ProfileCommand.DETAILS -> true
                                    ProfileCommand.REFRESH -> !fetch
                                    else -> !locked
                                },
                            onClick = {
                                menu = false
                                actions.command(command, profile)
                            },
                        )
                }
            if (compatibilityWarning(profile, null).isNotBlank())
                DropdownMenuItem(
                    text = { Text(tr(R.string.show_import_details)) },
                    onClick = {
                        menu = false
                        actions.command(ProfileCommand.COMPATIBILITY, profile)
                    },
                    modifier = Modifier.testTag("profile-compatibility-${profile.id}"),
                )
            if (profile.definition.isNotBlank())
                DropdownMenuItem(
                    text = { Text(tr(R.string.reparse_local_profile)) },
                    enabled = !locked,
                    onClick = {
                        menu = false
                        actions.command(ProfileCommand.REPARSE, profile)
                    },
                    modifier = Modifier.testTag("profile-reparse-${profile.id}"),
                )
        }
    }
}

@Composable
private fun ProfileQuota(profile: LocalProfile) {
    Row(
        // The card column ends at 4dp for the menu target; inset the date to 16dp.
        Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile.quota != null) ProfileQuotaLabel(profile)
        else
            Text(
                trPlural(
                    R.plurals.count_proxy_proxies,
                    profile.nodes.size,
                    profile.nodes.size,
                    tr(if (profile.source == null) R.string.local_import else R.string.subscription),
                ),
                // Unweighted: with no expiry date on this row, a weight here split the width
                // with the spacer below and wrapped the text at half the card.
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        Spacer(Modifier.weight(1f))
        profile.quota?.expire?.let {
            Text(
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(
                        java.time.ZoneId.systemDefault()
                    )
                    .format(java.time.Instant.ofEpochSecond(it)),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/** Explicit user copy only. Never logs, previews, persists or exports the URL. */
internal fun copySubscription(context: Context, url: String): Boolean =
    runCatching {
            val clipboard =
                context.applicationContext.getSystemService(ClipboardManager::class.java)
            val id = UUID.randomUUID().toString()
            val clip = ClipData.newPlainText("PingClash subscription", url)
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                    putString("pingclash.copy", id)
                }
            clipboard.setPrimaryClip(clip)
            Handler(Looper.getMainLooper())
                .postDelayed(
                    {
                        runCatching {
                            if (
                                clipboard.primaryClipDescription
                                    ?.extras
                                    ?.getString("pingclash.copy") == id
                            )
                                clipboard.clearPrimaryClip()
                        }
                    },
                    60_000,
                )
            true
        }
        .getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileDetailsSheet(
    profile: LocalProfile,
    locked: Boolean,
    onDismiss: () -> Unit,
    onCommand: (ProfileCommand, LocalProfile) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleLarge)
            ProfileInventory(profile)
            SubscriptionDetails(profile)
            if (profile.source != null)
                TextButton(
                    {
                        onDismiss()
                        onCommand(ProfileCommand.UA, profile)
                    },
                    enabled = !locked,
                ) {
                    Text(tr(R.string.subscription_ua))
                }
            if (profile.remote != null)
                TextButton(
                    { onCommand(ProfileCommand.PROVIDERS, profile) },
                    enabled = !fetchLocked(locked),
                ) {
                    Text(tr(R.string.provider_refresh))
                }
        }
    }
}

/** No profiles yet: a card saying so and where to add one, readable over any scene. */
@Composable
private fun ProfilesEmpty() {
    GroupCard {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp).testTag("profiles-empty"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconSquare(PcIcon.Profile, MaterialTheme.colorScheme.primary)
            Text(
                tr(R.string.ui_profiles_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                tr(R.string.ui_profiles_empty_hint),
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
