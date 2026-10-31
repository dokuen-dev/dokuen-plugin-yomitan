package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.GlassSurface
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.NeonTeal
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextLightGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextWhite
import java.text.Collator

/**
 * Picks a wiktionary-to-yomitan dictionary by its source and target languages and lists the
 * dictionaries there are for the pair, each installable like a preset.
 *
 * The caller holds the selected [source] and [target] (an empty [target] means none is selected).
 * Inside a lazy list, an item's saved state is keyed by its position, and installing a dictionary
 * shifts that position, so state remembered here would be lost after every download.
 */
@Composable
fun WiktionaryDictionariesCard(
    catalogState: WiktionaryCatalogState,
    installedTitles: Set<String>,
    source: String,
    target: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onLoadCatalog: () -> Unit,
    onDownload: (WiktionaryDictionary) -> Unit
) {
    // After a failure, only the Retry button loads the catalog again.
    LaunchedEffect(Unit) {
        if (catalogState !is WiktionaryCatalogState.Failed) onLoadCatalog()
    }

    val names = WiktionaryDictionaries.LANGUAGES.associate { it.code to stringResource(it.nameRes) }
    val languages = remember(names) {
        val collator = Collator.getInstance()
        names.keys.sortedWith(compareBy(collator) { names.getValue(it) })
    }
    // Until the catalog loads, every language is offered as a target. A selected target is kept
    // when the source changes, even if the new source has no dictionary for it. This is to prevent
    // misclicks from accidentally clearing both dropdowns.
    val targets = (catalogState as? WiktionaryCatalogState.Loaded)?.catalog?.availableTargets(source)
        ?.let { available -> languages.filter { it in available } }
        ?: languages

    val context = LocalContext.current
    val toastCannotOpenLinkString = stringResource(R.string.toast_cannot_open_link)
    val downloadPageLink = LinkAnnotation.Url(
        url = WiktionaryDictionaries.DOWNLOAD_PAGE_URL,
        styles = TextLinkStyles(SpanStyle(color = NeonTeal, textDecoration = TextDecoration.Underline)),
        linkInteractionListener = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, WiktionaryDictionaries.DOWNLOAD_PAGE_URL.toUri()))
            } catch (_: Exception) {
                Toast.makeText(context, toastCannotOpenLinkString, Toast.LENGTH_SHORT).show()
            }
        }
    )

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
            Column {
                Text(
                    text = stringResource(R.string.wiktionary_title),
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.wiktionary_desc_prefix))
                        withLink(downloadPageLink) {
                            append(stringResource(R.string.wiktionary_desc_link))
                        }
                        append(stringResource(R.string.wiktionary_desc_suffix))
                    },
                    color = TextLightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingDropdown(
                    label = stringResource(R.string.wiktionary_source_language),
                    selected = source,
                    options = languages,
                    optionLabel = { names.getValue(it) },
                    onSelect = onSourceChange,
                    modifier = Modifier.weight(1f)
                )

                SettingDropdown(
                    label = stringResource(R.string.wiktionary_target_language),
                    selected = target,
                    options = targets,
                    optionLabel = { names[it].orEmpty() },
                    onSelect = onTargetChange,
                    modifier = Modifier.weight(1f)
                )
            }

            when (catalogState) {
                WiktionaryCatalogState.NotLoaded, WiktionaryCatalogState.Loading -> {
                    WiktionaryStatusText(stringResource(R.string.wiktionary_loading))
                }

                WiktionaryCatalogState.Failed -> {
                    WiktionaryStatusText(stringResource(R.string.wiktionary_load_failed))
                    TextButton(onClick = onLoadCatalog) {
                        Text(
                            text = stringResource(R.string.wiktionary_retry),
                            color = NeonTeal,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is WiktionaryCatalogState.Loaded -> if (target.isNotEmpty()) {
                    val dictionaries = catalogState.catalog.dictionariesFor(source, target)
                    if (dictionaries.isEmpty()) {
                        WiktionaryStatusText(stringResource(R.string.wiktionary_no_dictionaries))
                    }
                    for (dictionary in dictionaries) {
                        PresetRow(
                            title = stringResource(dictionary.type.labelRes),
                            description = stringResource(dictionary.type.descriptionRes, dictionary.size),
                            isInstalled = dictionary.title in installedTitles,
                            onDownload = { onDownload(dictionary) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WiktionaryStatusText(text: String) {
    Text(text = text, color = TextGray, fontSize = 12.sp)
}
