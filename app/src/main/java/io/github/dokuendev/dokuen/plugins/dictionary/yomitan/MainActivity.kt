package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.CyanAccent
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.DarkPresetBg
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.ElectricIndigo
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.GlassSurface
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.GlowingGreen
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.HeaderEnd
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.HeaderStart
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.HintText
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.MidnightDark
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.MidnightLight
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.NeonRed
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.NeonTeal
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextLightGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextWhite
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.YomitanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            YomitanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    YomitanDashboardScreen()
                }
            }
        }
    }

    fun isLaunchedFromDokuen(): Boolean {
        return referrer?.host?.equals("io.github.dokuendev.dokuenreader") == true
    }

    fun isDokuenInstalled(): Boolean {
        val dokuenPackage = "io.github.dokuendev.dokuenreader"
        return packageManager.getLaunchIntentForPackage(dokuenPackage) != null
    }

    fun launchOrInstallDokuen() {
        val dokuenPackage = "io.github.dokuendev.dokuenreader"
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(dokuenPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$dokuenPackage".toUri()))
            } catch (_: Exception) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://play.google.com/store/apps/details?id=$dokuenPackage".toUri()
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YomitanDashboardScreen(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE) }
    var showGuide by remember { mutableStateOf(prefs.getBoolean("show_guide", true)) }
    val activity = context as? MainActivity
    val launchedFromDokuen = activity?.isLaunchedFromDokuen() == true
    val isDokuenInstalled = activity?.isDokuenInstalled() == true

    var customUrl by remember { mutableStateOf("") }
    var deleteConfirmDict by remember { mutableStateOf<DictionaryEntity?>(null) }
    var deleteMode by remember { mutableStateOf(false) }

    val toastNoFileSelectedString = stringResource(R.string.toast_no_file_selected)

    // Launcher for selecting a local ZIP file
    val pickZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importLocalZip(uri)
        } else {
            Toast.makeText(context, toastNoFileSelectedString, Toast.LENGTH_SHORT).show()
        }
    }

    // Determine if preset Jitendex, JMnedict, and KANJIDIC are already installed based on title
    val isJitendexInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jitendex", ignoreCase = true)
    }
    val isJmnedictInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jmnedict", ignoreCase = true)
    }
    val isKanjidicInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("kanjidic", ignoreCase = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(MidnightDark, MidnightLight)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 140.dp)
        ) {
            // Header Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(HeaderStart, HeaderEnd)
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.header_description),
                            color = HintText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Stats Badge
                        Box(
                            modifier = Modifier
                                .background(Color(0x22FFFFFF), RoundedCornerShape(50.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = LocalResources.current.getQuantityString(
                                    R.plurals.installed_dictionaries_count,
                                    uiState.installedDictionaries.size,
                                    uiState.installedDictionaries.size
                                ),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!launchedFromDokuen) {
                        // Restore guide info/help icon button
                        IconButton(
                            onClick = {
                                showGuide = !showGuide
                                prefs.edit { putBoolean("show_guide", showGuide) }
                            },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.content_description_show_guide),
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Guide Card
            if (showGuide && !launchedFromDokuen) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassSurface),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF))
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.guide_title),
                                    color = NeonTeal,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(
                                    modifier = Modifier.padding(top = 8.dp, end = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        "1." to stringResource(R.string.guide_step_1),
                                        "2." to stringResource(R.string.guide_step_2)
                                    ).forEach { (number, step) ->
                                        Row {
                                            Text(
                                                text = number,
                                                color = TextLightGray,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                modifier = Modifier.width(20.dp)
                                            )
                                            Text(
                                                text = step,
                                                color = TextLightGray,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    Row {
                                        Text(
                                            text = "3.",
                                            color = TextLightGray,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            modifier = Modifier.width(20.dp)
                                        )
                                        val step3Prefix = stringResource(R.string.guide_step_3_prefix)
                                        val step3Bold = stringResource(R.string.guide_step_3_bold)
                                        val step3Suffix = stringResource(R.string.guide_step_3_suffix)
                                        Text(
                                            text = buildAnnotatedString {
                                                append(step3Prefix)
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(step3Bold)
                                                }
                                                append(step3Suffix)
                                            },
                                            color = TextLightGray,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    showGuide = false
                                    prefs.edit { putBoolean("show_guide", false) }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.content_description_dismiss_guide),
                                    tint = TextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Installed Dictionaries Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeader(title = stringResource(R.string.section_installed_dictionaries))

                    if (uiState.installedDictionaries.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(
                                    if (deleteMode) Color(0x2200F2FE) else Color(0x11FFFFFF)
                                )
                        ) {
                            IconButton(
                                onClick = { deleteMode = !deleteMode }
                            ) {
                                Icon(
                                    imageVector = if (deleteMode) Icons.Default.Check else Icons.Default.Delete,
                                    contentDescription = stringResource(
                                        if (deleteMode) R.string.content_description_exit_delete_mode
                                        else R.string.content_description_enter_delete_mode
                                    ),
                                    tint = if (deleteMode) NeonTeal else TextGray
                                )
                            }
                        }
                    }
                }
            }

            // Installed Dictionaries List
            if (uiState.installedDictionaries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassSurface),
                        border = BorderStroke(1.dp, Color(0x1FFFFFFF))
                    ) {
                        Text(
                            text = stringResource(R.string.no_dictionaries_installed),
                            color = TextGray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = uiState.installedDictionaries,
                    key = { _, info -> info.meta.title }
                ) { index, info ->
                    val meta = info.meta
                    val isActive = uiState.activeDictionaries.contains(meta.title)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clickable(enabled = !deleteMode) {
                                viewModel.toggleDictionaryActive(meta.title)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassSurface),
                        border = BorderStroke(
                            width = if (deleteMode) 1.5.dp else if (isActive) 1.5.dp else 1.dp,
                            brush = if (deleteMode) {
                                Brush.horizontalGradient(listOf(NeonRed, Color(0xFFFF6B6B)))
                            } else if (isActive) {
                                Brush.horizontalGradient(listOf(NeonTeal, ElectricIndigo))
                            } else {
                                Brush.horizontalGradient(colors = listOf(Color(0xFF2C3255), Color(0xFF1B1F37)))
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 72.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = meta.title,
                                        color = TextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.dictionary_author_version,
                                        meta.author ?: stringResource(R.string.author_unknown),
                                        meta.revision
                                    ),
                                    color = TextGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    text = info.countText,
                                    color = GlowingGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            if (deleteMode) {
                                // Show trash icon in place of the switch
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50.dp))
                                        .background(Color(0x22EF5350))
                                ) {
                                    IconButton(
                                        onClick = { deleteConfirmDict = meta }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.content_description_delete_dictionary),
                                            tint = NeonRed
                                        )
                                    }
                                }
                            } else {
                                // Group the arrows and switch in a fillMaxHeight Box to align switch at center and arrows at top
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(56.dp)
                                ) {
                                    // Arrows nestled at the top
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .offset(y = (-8).dp),
                                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.moveDictionary(index, index - 1) },
                                            enabled = index > 0,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = stringResource(R.string.content_description_move_up),
                                                tint = if (index > 0) NeonTeal else TextGray.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveDictionary(index, index + 1) },
                                            enabled = index < uiState.installedDictionaries.size - 1,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = stringResource(R.string.content_description_move_down),
                                                tint = if (index < uiState.installedDictionaries.size - 1) NeonTeal else TextGray.copy(
                                                    alpha = 0.3f
                                                ),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // Switch centered vertically
                                    Switch(
                                        modifier = Modifier.align(Alignment.Center),
                                        checked = isActive,
                                        onCheckedChange = { viewModel.toggleDictionaryActive(meta.title) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MidnightDark,
                                            checkedTrackColor = NeonTeal,
                                            uncheckedThumbColor = TextGray,
                                            uncheckedTrackColor = Color(0x22FFFFFF),
                                            checkedBorderColor = Color.Transparent,
                                            uncheckedBorderColor = Color(0x44FFFFFF)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Install Section Header
            item {
                SectionHeader(title = stringResource(R.string.section_install_dictionary))
            }

            // Preset Downloads Card
            item {
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
                        Text(
                            text = stringResource(R.string.download_presets_title),
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        PresetRow(
                            title = stringResource(R.string.preset_jitendex_title),
                            description = stringResource(R.string.preset_jitendex_desc),
                            isInstalled = isJitendexInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_JITENDEX, "Jitendex")
                            }
                        )

                        PresetRow(
                            title = stringResource(R.string.preset_jmnedict_title),
                            description = stringResource(R.string.preset_jmnedict_desc),
                            isInstalled = isJmnedictInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_JMNEDICT, "JMnedict")
                            }
                        )

                        PresetRow(
                            title = stringResource(R.string.preset_kanjidic_title),
                            description = stringResource(R.string.preset_kanjidic_desc),
                            isInstalled = isKanjidicInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_KANJIDIC, "KANJIDIC English")
                            }
                        )
                    }
                }
            }

            // Local ZIP Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickZipLauncher.launch("application/zip") },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = GlassSurface),
                    border = BorderStroke(1.dp, Color(0x1FFFFFFF))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(50.dp))
                                .background(Color(0x1A00F2FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.content_description_import),
                                tint = NeonTeal,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.import_local_zip_title),
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.import_local_zip_desc),
                                color = TextLightGray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // Custom URL Download Card
            item {
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
                        Text(
                            text = stringResource(R.string.custom_url_title),
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text(stringResource(R.string.custom_url_label)) },
                            placeholder = { Text(stringResource(R.string.custom_url_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = NeonTeal,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedLabelColor = NeonTeal,
                                unfocusedLabelColor = TextGray
                            )
                        )

                        val errorInvalidUrlString = stringResource(R.string.error_invalid_url)
                        val customDictionaryNameString = stringResource(R.string.custom_dictionary_name)

                        Button(
                            onClick = {
                                val url = customUrl.trim()
                                if (url.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        errorInvalidUrlString,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    viewModel.downloadAndInstallPreset(
                                        url,
                                        customDictionaryNameString
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(CyanAccent, NeonTeal)
                                        )
                                    )
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.download_and_install_button),
                                    color = MidnightDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Full Screen Progress Overlay - Glowing glassmorphic border
        uiState.progressState?.let { progressState ->
            Dialog(onDismissRequest = {}) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = GlassSurface),
                    border = BorderStroke(
                        1.5.dp,
                        Brush.horizontalGradient(listOf(NeonTeal, ElectricIndigo, NeonTeal))
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 220.dp)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (progressState.total > 0) {
                            val progressFloat = progressState.progress.toFloat() / progressState.total.toFloat()
                            LinearProgressIndicator(
                                progress = { progressFloat },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = NeonTeal,
                                trackColor = Color(0x15FFFFFF)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = NeonTeal,
                                trackColor = Color(0x15FFFFFF)
                            )
                        }

                        Text(
                            text = progressState.stage,
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        if (progressState.details.isNotEmpty()) {
                            Text(
                                text = progressState.details,
                                color = TextGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (progressState.isImporting) {
                            Text(
                                text = stringResource(R.string.import_warning_details),
                                color = HintText,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        deleteConfirmDict?.let { meta ->
            AlertDialog(
                onDismissRequest = { deleteConfirmDict = null },
                title = { Text(stringResource(R.string.delete_dictionary_title)) },
                text = { Text(stringResource(R.string.delete_confirm_message, meta.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDictionary(meta)
                            deleteConfirmDict = null
                            deleteMode = false
                        }
                    ) {
                        Text(stringResource(R.string.delete_action), color = NeonRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmDict = null }) {
                        Text(stringResource(R.string.cancel_action), color = TextWhite)
                    }
                },
                containerColor = GlassSurface,
                titleContentColor = TextWhite,
                textContentColor = TextWhite
            )
        }

        // Error Dialog
        uiState.errorDialogState?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                title = { Text(error.title) },
                text = { Text(error.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text(stringResource(R.string.ok_action), color = NeonTeal)
                    }
                },
                containerColor = GlassSurface,
                titleContentColor = TextWhite,
                textContentColor = TextWhite
            )
        }

        // Sticky Bottom Action Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(GlassSurface.copy(alpha = 0.96f))
                .navigationBarsPadding()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(NeonTeal.copy(alpha = 0.3f))
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (launchedFromDokuen) {
                            activity.finish()
                        } else {
                            activity?.launchOrInstallDokuen()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(CyanAccent, NeonTeal)
                                )
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (launchedFromDokuen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MidnightDark,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = when {
                                    launchedFromDokuen -> stringResource(R.string.button_return_to_dokuen)
                                    isDokuenInstalled -> stringResource(R.string.button_launch_dokuen)
                                    else -> stringResource(R.string.button_get_dokuen)
                                },
                                color = MidnightDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                val githubUrl = "https://github.com/dokuen-dev/dokuen-plugin-yomitan"
                val toastCannotOpenLinkString = stringResource(R.string.toast_cannot_open_link)

                Text(
                    text = githubUrl,
                    color = TextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable {
                            try {
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    githubUrl.toUri()
                                )
                                context.startActivity(browserIntent)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    toastCannotOpenLinkString,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(NeonTeal, ElectricIndigo)
                    ),
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            color = TextWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PresetRow(
    title: String,
    description: String,
    isInstalled: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isInstalled) {
                    DarkPresetBg.copy(alpha = 0.3f)
                } else {
                    DarkPresetBg
                }
            )
            .clickable(enabled = !isInstalled) { onDownload() }
            .padding(12.dp)
            .alpha(if (isInstalled) 0.6f else 1.0f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                color = TextGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isInstalled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.content_description_installed),
                    tint = GlowingGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = if (isInstalled) stringResource(R.string.status_installed) else stringResource(R.string.status_download),
                color = if (isInstalled) GlowingGreen else NeonTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
