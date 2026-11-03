// SPDX-License-Identifier: GPL-3.0-only
// Preview and tile proportions, icon tints and spacing are the page's design values.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings > Language and appearance: a live preview, background tiles and grouped rows. */
@Composable
internal fun AppearancePage(data: AppData, model: AppViewModel) {
    val context = LocalContext.current
    val client = data.client
    val pick = rememberBackgroundPicker(model)
    val editing by model.backgroundEditing.collectAsState()
    val staged by model.backgroundStaged.collectAsState()
    val revision = CustomBackground.revision.intValue
    val hasPicture = remember(revision) { CustomBackground.exists(context) }
    val scenery = { change: ScenePreferenceChange ->
        model.appearance(client.language, client.theme, scenery = change)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppearancePreview(client)
        GroupTitle(tr(R.string.ui_section_background))
        SceneTiles(client, hasPicture) { scene ->
            if (scene == "image" && !hasPicture) pick()
            else scenery(ScenePreferenceChange(scene = scene))
        }
        if (client.scene == "image" && hasPicture) PictureRows(model, pick)
        if (client.scene == "none")
            GroupCard {
                SolidColorPicker(client.solidColor, client.solidSwatches) { color, swatches ->
                    scenery(ScenePreferenceChange(solidColor = color, solidSwatches = swatches))
                }
            }
        GroupTitle(tr(R.string.ui_section_display))
        DisplayRows(client, model)
    }
    if (editing && (hasPicture || staged != null))
        BackgroundEditor(staged, model::saveBackground, model::cancelBackground)
}

@Composable
private fun PictureRows(model: AppViewModel, pick: () -> Unit) {
    val frame = rememberFramedPicture()?.second
    GroupCard {
        NavRow(
            tr(R.string.ui_background_edit),
            "background-edit",
            value =
                frame?.let {
                    tr(if (it.fit) R.string.ui_background_fit else R.string.ui_background_fill)
                },
        ) {
            model.editBackground(true)
        }
        RowDivider(underIcons = false)
        ActionRow(tr(R.string.ui_background_change), "background-change", onClick = pick)
        RowDivider(underIcons = false)
        ActionRow(
            tr(R.string.ui_background_remove),
            "background-remove",
            color = MaterialTheme.colorScheme.error,
            onClick = model::removeBackground,
        )
    }
}

/** A small phone showing the current background, the badge and the navigation bar. */
@Composable
private fun AppearancePreview(client: ClientSettings) {
    val configuration = LocalConfiguration.current
    val aspect =
        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1)
    Surface(
        Modifier.fillMaxWidth().testTag("appearance-preview"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f),
    ) {
        Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.height(168.dp)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(16.dp))
                    .border(3.dp, Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
            ) {
                SceneArt(client, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val card = Size(size.width * .36f, size.height * .3f)
                    drawRoundRect(
                        Color.White.copy(alpha = .9f),
                        Offset((size.width - card.width) / 2, size.height * .2f),
                        card,
                        CornerRadius(card.width * .16f),
                    )
                    val bar = Size(size.width * .82f, size.height * .07f)
                    drawRoundRect(
                        Color.White.copy(alpha = .85f),
                        Offset((size.width - bar.width) / 2, size.height * .88f),
                        bar,
                        CornerRadius(bar.height / 2),
                    )
                }
            }
        }
    }
}

/** What a scene looks like, drawn into [modifier]: photo thumbnails, the colour or the picture. */
@Composable
private fun SceneArt(client: ClientSettings, modifier: Modifier, scene: String = client.scene) {
    when (scene) {
        "none" -> Box(modifier.background(solidColor(client.solidColor) ?: Color.White))
        "image" -> {
            val picture = rememberFramedPicture()
            if (picture == null)
                Box(
                    modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AppGlyph(PcIcon.Add, Modifier.size(22.dp), Muted)
                }
            else Canvas(modifier) { drawFramedBackground(picture.first, picture.second) }
        }
        else -> {
            val image =
                rememberSceneThumbnail(
                    if (scene == "clouds") R.drawable.clouds_v3 else R.drawable.ocean_surface_v3
                )
            if (image != null) Image(image, null, modifier, contentScale = ContentScale.Crop)
            else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

/** Decodes a scene photo at a fraction of its size, off the main thread. */
@Composable
private fun rememberSceneThumbnail(resource: Int): ImageBitmap? {
    val resources = LocalContext.current.resources
    val image by
        produceState<ImageBitmap?>(null, resource) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            BitmapFactory.decodeResource(
                                    resources,
                                    resource,
                                    BitmapFactory.Options().apply { inSampleSize = 8 },
                                )
                                ?.asImageBitmap()
                        }
                        .getOrNull()
                }
        }
    return image
}

@Composable
private fun SceneTiles(client: ClientSettings, hasPicture: Boolean, onPick: (String) -> Unit) {
    val scenes =
        listOf(
            "ocean" to R.string.ui_scene_ocean,
            "clouds" to R.string.ui_scene_clouds,
            "none" to R.string.ui_scene_none,
            "image" to R.string.ui_scene_image,
        )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        scenes.forEach { (scene, label) ->
            val chosen = client.scene == scene && (scene != "image" || hasPicture)
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.weight(1f)
                    .fluidClickable(role = Role.RadioButton) { onPick(scene) }
                    .testTag("scene-$scene")
                    .semantics { selected = chosen },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SceneArt(
                    client,
                    Modifier.fillMaxWidth()
                        .aspectRatio(.75f)
                        .clip(shape)
                        .border(
                            if (chosen) 2.5.dp else .5.dp,
                            if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape,
                        ),
                    scene,
                )
                Text(
                    tr(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) MaterialTheme.colorScheme.primary else Muted,
                    // Longer names ("Ocean and waves") wrap rather than being cut off.
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DisplayRows(client: ClientSettings, model: AppViewModel) {
    val scenery = { change: ScenePreferenceChange ->
        model.appearance(client.language, client.theme, scenery = change)
    }
    GroupCard {
        IconRow(PcIcon.Globe, Color(0xFF0068D9)) {
            PopUpChoice(
                tr(R.string.language),
                client.language,
                listOf(
                    "system" to tr(R.string.system_default),
                    "zh" to tr(R.string.text),
                    "en" to tr(R.string.english),
                ),
                true,
            ) {
                model.appearance(it, client.theme)
            }
        }
        // The solid scene is designed for the light theme, so dark mode is not offered there.
        if (client.scene != "none") {
            RowDivider()
            IconRow(PcIcon.Moon, Color(0xFF5E5CE6)) {
                ThemeToggle(client) { model.appearance(client.language, it) }
            }
        }
        RowDivider()
        IconRow(PcIcon.Sliders, Color(0xFF8E8E93)) {
            SettingSwitch(tr(R.string.ui_reduce_effects), client.reduceEffects, true) {
                model.appearance(client.language, client.theme, reduceEffects = it)
            }
        }
        RowDivider()
        IconRow(PcIcon.Layers, Color(0xFF8E8E93)) {
            SettingSwitch(tr(R.string.ui_reduce_transparency), client.reduceTransparency, true) {
                scenery(ScenePreferenceChange(reduceTransparency = it))
            }
        }
    }
}
