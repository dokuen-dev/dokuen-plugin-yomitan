package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiAudioSource
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiAudioSourceList
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiAudioSourceType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.GlassSurface
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.NeonTeal
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextWhite

/**
 * The settings for where `{audio}` downloads from: whether the language's default sources are
 * also tried, the ordered source list, and the download timeout. The first two follow the order
 * of desktop Yomitan's audio sources dialog. Every control saves its value as soon as it changes.
 */
@Composable
fun AnkiAudioSettingsCard(
    sources: List<AnkiAudioSource>,
    enableDefaultAudioSources: Boolean,
    downloadTimeoutMillis: Int,
    onSourcesChange: ((List<AnkiAudioSource>) -> List<AnkiAudioSource>) -> Unit,
    onEnableDefaultAudioSourcesChange: (Boolean) -> Unit,
    onDownloadTimeoutChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = stringResource(R.string.anki_audio_title), color = TextWhite, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.anki_audio_default_sources_label),
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enableDefaultAudioSources, onCheckedChange = onEnableDefaultAudioSourcesChange)
            }

            Text(
                text = stringResource(R.string.anki_audio_sources_description),
                color = TextWhite.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )

            sources.forEachIndexed { index, source ->
                AudioSourceRow(
                    index = index,
                    source = source,
                    isFirst = index == 0,
                    isLast = index == sources.lastIndex,
                    onSourcesChange = onSourcesChange
                )
            }
            TextButton(onClick = { onSourcesChange { AnkiAudioSourceList.add(it) } }) {
                Icon(Icons.Default.Add, contentDescription = null, tint = NeonTeal)
                Text(text = stringResource(R.string.anki_audio_source_add), color = NeonTeal)
            }

            // The text is kept as typed; only a valid value is saved.
            var timeoutText by remember(downloadTimeoutMillis) { mutableStateOf(downloadTimeoutMillis.toString()) }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = timeoutText,
                onValueChange = { text ->
                    timeoutText = text
                    AnkiAudioSourceList.parseTimeout(text)?.let(onDownloadTimeoutChange)
                },
                singleLine = true,
                label = { Text(stringResource(R.string.anki_audio_timeout_label)) },
                supportingText = { Text(stringResource(R.string.anki_audio_timeout_description)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors()
            )
        }
    }
}

@Composable
private fun AudioSourceRow(
    index: Int,
    source: AnkiAudioSource,
    isFirst: Boolean,
    isLast: Boolean,
    onSourcesChange: ((List<AnkiAudioSource>) -> List<AnkiAudioSource>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.anki_audio_source_label, index + 1),
                    selected = source.type,
                    options = AnkiAudioSourceType.entries,
                    optionLabel = { it.label() },
                    onSelect = { type ->
                        onSourcesChange { AnkiAudioSourceList.update(it, index) { s -> s.copy(type = type) } }
                    }
                )
            }
            IconButton(onClick = { onSourcesChange { AnkiAudioSourceList.move(it, index, -1) } }, enabled = !isFirst) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.move_up),
                    tint = if (isFirst) TextWhite.copy(alpha = 0.3f) else TextWhite
                )
            }
            IconButton(onClick = { onSourcesChange { AnkiAudioSourceList.move(it, index, 1) } }, enabled = !isLast) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.move_down),
                    tint = if (isLast) TextWhite.copy(alpha = 0.3f) else TextWhite
                )
            }
            IconButton(onClick = { onSourcesChange { AnkiAudioSourceList.remove(it, index) } }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.anki_audio_source_remove),
                    tint = TextWhite
                )
            }
        }
        if (AnkiAudioSourceList.usesUrl(source.type)) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = source.url,
                onValueChange = { url ->
                    onSourcesChange { AnkiAudioSourceList.update(it, index) { s -> s.copy(url = url) } }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.anki_audio_source_url_label)) },
                supportingText = { Text(stringResource(R.string.anki_audio_source_url_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors()
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = NeonTeal,
    unfocusedBorderColor = Color(0x44FFFFFF)
)

@Composable
private fun AnkiAudioSourceType.label(): String = when (this) {
    AnkiAudioSourceType.JPOD101 -> stringResource(R.string.anki_audio_source_jpod101)
    AnkiAudioSourceType.LANGUAGE_POD_101 -> stringResource(R.string.anki_audio_source_language_pod_101)
    AnkiAudioSourceType.JISHO -> stringResource(R.string.anki_audio_source_jisho)
    AnkiAudioSourceType.LINGUA_LIBRE -> stringResource(R.string.anki_audio_source_lingua_libre)
    AnkiAudioSourceType.WIKTIONARY -> stringResource(R.string.anki_audio_source_wiktionary)
    AnkiAudioSourceType.CUSTOM -> stringResource(R.string.anki_audio_source_custom)
    AnkiAudioSourceType.CUSTOM_JSON -> stringResource(R.string.anki_audio_source_custom_json)
}
