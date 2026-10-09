package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.GlassSurface
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.NeonTeal
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextWhite
import kotlin.math.roundToInt

/** How `{screenshot}` images are obtained and encoded. */
@Composable
fun AnkiImageSettingsCard(
    screenshotBehavior: AnkiScreenshotBehavior,
    screenshotFormat: AnkiScreenshotFormat,
    screenshotQuality: Int,
    onScreenshotBehaviorChange: (AnkiScreenshotBehavior) -> Unit,
    onScreenshotFormatChange: (AnkiScreenshotFormat) -> Unit,
    onScreenshotQualityChange: (Int) -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.anki_images_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = stringResource(R.string.anki_images_requires_pro),
                    color = TextWhite.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alignByBaseline()
                )
            }

            SettingDropdown(
                label = stringResource(R.string.anki_screenshot_behavior_label),
                selected = screenshotBehavior,
                options = AnkiScreenshotBehavior.entries,
                optionLabel = { it.label() },
                onSelect = onScreenshotBehaviorChange
            )

            SettingDropdown(
                label = stringResource(R.string.anki_screenshot_format_label),
                selected = screenshotFormat,
                options = AnkiScreenshotFormat.entries,
                optionLabel = { it.name },
                onSelect = onScreenshotFormatChange
            )

            if (screenshotFormat == AnkiScreenshotFormat.JPEG) {
                // The slider moves freely; the setting is written once the drag ends.
                var sliderValue by remember(screenshotQuality) { mutableFloatStateOf(screenshotQuality.toFloat()) }
                Text(
                    text = stringResource(R.string.anki_screenshot_quality_label, sliderValue.roundToInt()),
                    color = TextWhite
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onScreenshotQualityChange(sliderValue.roundToInt()) },
                    valueRange = 0f..100f
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            value = optionLabel(selected),
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = NeonTeal,
                unfocusedBorderColor = Color(0x44FFFFFF)
            )
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AnkiScreenshotBehavior.label(): String = when (this) {
    AnkiScreenshotBehavior.FULL_SCREEN -> stringResource(R.string.anki_screenshot_behavior_full_screen)
    AnkiScreenshotBehavior.CROP -> stringResource(R.string.anki_screenshot_behavior_crop)
}
