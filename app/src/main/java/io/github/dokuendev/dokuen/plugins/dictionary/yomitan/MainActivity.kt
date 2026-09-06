package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardIcon
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateScope
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldDefaults
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldOverwriteMode
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiStandardMarkers
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.TagsTextUtil
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
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.SoftTeal
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextLightGray
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.TextWhite
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme.YomitanTheme
import kotlinx.coroutines.launch

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
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE) }
    var showGuide by remember { mutableStateOf(prefs.getBoolean("show_guide", true)) }
    val activity = context as? MainActivity
    val launchedFromDokuen = activity?.isLaunchedFromDokuen() == true
    val isDokuenInstalled = activity?.isDokuenInstalled() == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(MidnightDark, MidnightLight)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            DashboardHeaderCard(
                installedCount = uiState.installedDictionaries.size,
                onToggleGuide = {
                    showGuide = !showGuide
                    prefs.edit { putBoolean("show_guide", showGuide) }
                },
                launchedFromDokuen = launchedFromDokuen,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
            )

            val pagerState = rememberPagerState(pageCount = { 2 })
            val tabTitles = listOf(
                stringResource(R.string.tab_dictionaries),
                stringResource(R.string.tab_anki_export)
            )
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = NeonTeal
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> DictionariesPage(
                        viewModel = viewModel,
                        showGuide = showGuide,
                        onDismissGuide = {
                            showGuide = false
                            prefs.edit { putBoolean("show_guide", false) }
                        },
                        launchedFromDokuen = launchedFromDokuen
                    )

                    else -> AnkiExportPage(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState
                    )
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}

@Composable
fun DashboardHeaderCard(
    installedCount: Int,
    onToggleGuide: () -> Unit,
    launchedFromDokuen: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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

            Box(
                modifier = Modifier
                    .background(Color(0x22FFFFFF), RoundedCornerShape(50.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = LocalResources.current.getQuantityString(
                        R.plurals.installed_dictionaries_count,
                        installedCount,
                        installedCount
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!launchedFromDokuen) {
            IconButton(
                onClick = onToggleGuide,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionariesPage(
    viewModel: MainViewModel,
    showGuide: Boolean,
    onDismissGuide: () -> Unit,
    launchedFromDokuen: Boolean
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var customUrl by remember { mutableStateOf("") }
    var deleteConfirmDict by remember { mutableStateOf<DictionaryEntity?>(null) }
    var deleteMode by remember { mutableStateOf(false) }

    val toastNoFileSelectedString = stringResource(R.string.toast_no_file_selected)
    val pickZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importLocalZip(uri)
        } else {
            Toast.makeText(context, toastNoFileSelectedString, Toast.LENGTH_SHORT).show()
        }
    }

    val isJitendexInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jitendex", ignoreCase = true)
    }
    val isJmnedictInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jmnedict", ignoreCase = true)
    }
    val isKanjidicInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("kanjidic", ignoreCase = true)
    }
    val isBccwjInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("bccwj", ignoreCase = true)
    }
    val isJpdbInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jpdb", ignoreCase = true)
    }
    val isJitenInstalled = uiState.installedDictionaries.any {
        it.meta.title.contains("jiten", ignoreCase = true) && !it.meta.title.contains("jitendex", ignoreCase = true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 140.dp + navigationBarBottomPadding)
        ) {
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
                                onClick = onDismissGuide,
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
                                            contentDescription = stringResource(R.string.delete_dictionary),
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

            // Frequency Sorting Dictionary Section
            item {
                SectionHeader(title = stringResource(R.string.section_frequency_sorting_dictionary))
            }
            item {
                FrequencySortingDictionaryCard(
                    options = uiState.frequencyDictionaryOptions,
                    selected = uiState.sortFrequencyDictionary,
                    order = uiState.sortFrequencyDictionaryOrder,
                    onSelect = viewModel::setSortFrequencyDictionary,
                    onOrderSelect = viewModel::setSortFrequencyDictionaryOrder,
                    onAutoDetect = {
                        if (uiState.sortFrequencyDictionary.isNotEmpty()) {
                            viewModel.detectAndApplySortOrder(uiState.sortFrequencyDictionary)
                        }
                    }
                )
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
                            modifier = Modifier.padding(bottom = 2.dp)
                        )

                        // Term Dictionaries
                        PresetCategoryHeader(title = stringResource(R.string.preset_category_term))

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

                        // Kanji Dictionaries
                        PresetCategoryHeader(title = stringResource(R.string.preset_category_kanji))

                        PresetRow(
                            title = stringResource(R.string.preset_kanjidic_title),
                            description = stringResource(R.string.preset_kanjidic_desc),
                            isInstalled = isKanjidicInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_KANJIDIC, "KANJIDIC")
                            }
                        )

                        // Frequency Dictionaries
                        PresetCategoryHeader(title = stringResource(R.string.preset_category_frequency))

                        PresetRow(
                            title = stringResource(R.string.preset_bccwj_title),
                            description = stringResource(R.string.preset_bccwj_desc),
                            isInstalled = isBccwjInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_BCCWJ, "BCCWJ")
                            }
                        )

                        PresetRow(
                            title = stringResource(R.string.preset_jpdb_title),
                            description = stringResource(R.string.preset_jpdb_desc),
                            isInstalled = isJpdbInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_JPDB, "JPDBv2㋕")
                            }
                        )

                        PresetRow(
                            title = stringResource(R.string.preset_jiten_title),
                            description = stringResource(R.string.preset_jiten_desc),
                            isInstalled = isJitenInstalled,
                            onDownload = {
                                viewModel.downloadAndInstallPreset(MainViewModel.PRESETS_JITEN, "Jiten")
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

        deleteConfirmDict?.let { meta ->
            AlertDialog(
                onDismissRequest = { deleteConfirmDict = null },
                title = { Text(stringResource(R.string.delete_dictionary)) },
                text = { Text(stringResource(R.string.delete_confirm_message, meta.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDictionary(meta)
                            deleteConfirmDict = null
                            deleteMode = false
                        }
                    ) {
                        Text(stringResource(R.string.delete), color = NeonRed, fontWeight = FontWeight.Bold)
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiExportPage(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ankiExportEnabledMessage = stringResource(R.string.anki_export_enabled_snackbar)

    // Bumped whenever previously-hidden content (the expanded Anki settings, or the Card
    // Formats card) becomes visible as a direct result of a user action, so the list
    // scrolls down far enough to actually show it.
    var revealScrollTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealScrollTrigger) {
        if (revealScrollTrigger > 0) {
            listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAnkiStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 140.dp + navigationBarBottomPadding)
    ) {
        item {
            AnkiExportGeneralSettingsCard(
                enabled = uiState.ankiSettings.enabled,
                ankiDroidInstalled = uiState.ankiDroidInstalled,
                ankiDroidPermissionGranted = uiState.ankiDroidPermissionGranted,
                tags = uiState.ankiSettings.tags,
                checkForDuplicates = uiState.ankiSettings.checkForDuplicates,
                duplicateBehavior = uiState.ankiSettings.duplicateBehavior,
                duplicateScope = uiState.ankiSettings.duplicateScope,
                duplicateScopeCheckAllModels = uiState.ankiSettings.duplicateScopeCheckAllModels,
                onEnabledChange = viewModel::setAnkiEnabled,
                onTagsChange = viewModel::setAnkiTags,
                onCheckForDuplicatesChange = viewModel::setAnkiCheckForDuplicates,
                onDuplicateBehaviorChange = viewModel::setAnkiDuplicateBehavior,
                onDuplicateScopeChange = viewModel::setAnkiDuplicateScope,
                onDuplicateScopeCheckAllModelsChange = viewModel::setAnkiDuplicateScopeCheckAllModels,
                onRefreshConnection = viewModel::refreshAnkiStatus,
                onConnected = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(ankiExportEnabledMessage)
                    }
                    revealScrollTrigger++
                }
            )
        }
        if (uiState.ankiSettings.enabled && uiState.ankiDroidInstalled && uiState.ankiDroidPermissionGranted) {
            item {
                val limitReachedMessage = stringResource(R.string.anki_card_format_limit_reached)
                AnkiCardFormatListCard(
                    cardFormats = uiState.ankiSettings.cardFormats,
                    deckOptions = uiState.ankiDeckOptions,
                    modelOptions = uiState.ankiModelOptions,
                    checkForDuplicates = uiState.ankiSettings.checkForDuplicates,
                    duplicateBehavior = uiState.ankiSettings.duplicateBehavior,
                    onSyncFields = viewModel::syncAnkiCardFormatFields,
                    onAddDefault = viewModel::addDefaultAnkiCardFormat,
                    onUpdate = viewModel::updateAnkiCardFormat,
                    onRemove = viewModel::removeAnkiCardFormat,
                    onLimitReached = {
                        coroutineScope.launch { snackbarHostState.showSnackbar(limitReachedMessage) }
                    }
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
                    contentDescription = stringResource(R.string.installed),
                    tint = GlowingGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = stringResource(if (isInstalled) R.string.installed else R.string.download).toUpperCase(Locale.current),
                color = if (isInstalled) GlowingGreen else NeonTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PresetCategoryHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(NeonTeal, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = SoftTeal,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * "Frequency sorting dictionary" picker, matching yomitan's own settings page
 * (settings.html #sort-frequency-dictionary / #sort-frequency-dictionary-order): a dropdown
 * of installed frequency dictionaries defaulting to "None" (no frequency-based sorting),
 * plus a sort-direction dropdown with an "Auto" detect button that only appears once a
 * dictionary is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencySortingDictionaryCard(
    options: List<String>,
    selected: String,
    order: String,
    onSelect: (String) -> Unit,
    onOrderSelect: (String) -> Unit,
    onAutoDetect: () -> Unit
) {
    var dictionaryMenuExpanded by remember { mutableStateOf(false) }
    var orderMenuExpanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.frequency_sorting_dictionary_none)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.frequency_sorting_dictionary_description),
                    color = TextGray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                ExposedDropdownMenuBox(
                    expanded = dictionaryMenuExpanded,
                    onExpandedChange = { dictionaryMenuExpanded = it },
                    modifier = Modifier.width(160.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        readOnly = true,
                        singleLine = true,
                        value = selected.ifEmpty { noneLabel },
                        onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dictionaryMenuExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = NeonTeal,
                            unfocusedBorderColor = Color(0x44FFFFFF)
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = dictionaryMenuExpanded,
                        onDismissRequest = { dictionaryMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(noneLabel) },
                            onClick = {
                                onSelect("")
                                dictionaryMenuExpanded = false
                            }
                        )
                        for (option in options) {
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onSelect(option)
                                    dictionaryMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (selected.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.frequency_sorting_mode_label),
                        color = TextGray,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(onClick = onAutoDetect) {
                        Text(stringResource(R.string.frequency_sorting_mode_auto), color = NeonTeal)
                    }

                    ExposedDropdownMenuBox(
                        expanded = orderMenuExpanded,
                        onExpandedChange = { orderMenuExpanded = it },
                        modifier = Modifier.width(160.dp)
                    ) {
                        val orderLabel = if (order == "ascending") {
                            stringResource(R.string.frequency_sorting_mode_rank_based)
                        } else {
                            stringResource(R.string.frequency_sorting_mode_occurrence_based)
                        }
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            readOnly = true,
                            singleLine = true,
                            value = orderLabel,
                            onValueChange = {},
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = orderMenuExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = NeonTeal,
                                unfocusedBorderColor = Color(0x44FFFFFF)
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = orderMenuExpanded,
                            onDismissRequest = { orderMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.frequency_sorting_mode_occurrence_based)) },
                                onClick = {
                                    onOrderSelect("descending")
                                    orderMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.frequency_sorting_mode_rank_based)) },
                                onClick = {
                                    onOrderSelect("ascending")
                                    orderMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiCardFormatListCard(
    cardFormats: List<AnkiCardFormat>,
    deckOptions: Map<Long, String>,
    modelOptions: Map<Long, String>,
    checkForDuplicates: Boolean,
    duplicateBehavior: AnkiDuplicateBehavior,
    onSyncFields: (Int, Long, () -> Unit) -> Unit,
    onAddDefault: () -> Boolean,
    onUpdate: (Int, (AnkiCardFormat) -> AnkiCardFormat) -> Unit,
    onRemove: (Int) -> Unit,
    onLimitReached: () -> Unit
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    // Bumped after a delete so the editor (and its locally buffered text) is rebuilt even though
    // the selected index is unchanged and now points at a different format.
    var editorGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(cardFormats.size) {
        if (selectedIndex >= cardFormats.size) {
            selectedIndex = maxOf(0, cardFormats.size - 1)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF))
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.anki_card_formats_title),
                color = TextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            if (cardFormats.isEmpty()) {
                Text(
                    text = stringResource(R.string.anki_card_formats_empty),
                    color = TextGray,
                    fontSize = 12.sp
                )
            }
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = Color.Transparent,
                contentColor = NeonTeal,
                edgePadding = 0.dp
            ) {
                for ((index, format) in cardFormats.withIndex()) {
                    // An unnamed format's tab reads "Format <n>", as in the original's tab label.
                    val fallbackName = stringResource(R.string.anki_card_format_default_name, index + 1)
                    Tab(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        text = { Text(format.name.ifEmpty { fallbackName }) }
                    )
                }
                Tab(
                    selected = false,
                    onClick = {
                        // The new format lands at the end of the list. Select it so its editor shows.
                        val newIndex = cardFormats.size
                        if (onAddDefault()) selectedIndex = newIndex else onLimitReached()
                    },
                    text = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.anki_card_format_add)
                        )
                    }
                )
            }

            val selectedFormat = cardFormats.getOrNull(selectedIndex)
            if (selectedFormat != null) {
                key(selectedIndex, editorGeneration) {
                    val editingIndex = selectedIndex
                    AnkiCardFormatEditor(
                        format = selectedFormat,
                        deckOptions = deckOptions,
                        modelOptions = modelOptions,
                        checkForDuplicates = checkForDuplicates,
                        duplicateBehavior = duplicateBehavior,
                        onSyncFields = { modelId, onDone -> onSyncFields(editingIndex, modelId, onDone) },
                        onChange = { transform -> onUpdate(editingIndex, transform) },
                        canDelete = cardFormats.size > 1,
                        onDeleteRequest = { pendingDeleteIndex = editingIndex }
                    )
                }
            }
        }
    }

    val deleteIndex = pendingDeleteIndex
    if (deleteIndex != null) {
        val formatName = cardFormats.getOrNull(deleteIndex)?.name ?: ""
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = null },
            title = { Text(stringResource(R.string.anki_card_format_delete_title)) },
            text = { Text(stringResource(R.string.anki_card_format_delete_body, formatName)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(deleteIndex)
                    pendingDeleteIndex = null
                    editorGeneration++
                }) {
                    Text(
                        stringResource(R.string.delete),
                        color = NeonRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIndex = null }) {
                    Text(stringResource(R.string.cancel_action), color = TextWhite)
                }
            },
            containerColor = GlassSurface,
            titleContentColor = TextWhite,
            textContentColor = TextWhite
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiCardFormatEditor(
    format: AnkiCardFormat,
    deckOptions: Map<Long, String>,
    modelOptions: Map<Long, String>,
    checkForDuplicates: Boolean,
    duplicateBehavior: AnkiDuplicateBehavior,
    onSyncFields: (Long, () -> Unit) -> Unit,
    // Takes a transform of the stored format rather than a whole format, so an edit never
    // writes back a copy captured before a concurrent change (see updateAnkiCardFormat).
    onChange: ((AnkiCardFormat) -> AnkiCardFormat) -> Unit,
    canDelete: Boolean,
    onDeleteRequest: () -> Unit
) {
    var deckMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var iconMenuExpanded by remember { mutableStateOf(false) }
    var loadingFields by remember { mutableStateOf(false) }
    // Text inputs edit a local buffer and push each change upstream. Binding them straight to the
    // state flow would feed every keystroke back one frame late and can drop or reorder typing.
    var nameText by remember { mutableStateOf(format.name) }

    // The ViewModel merges the fetched field names into this format's latest state (not a
    // snapshot captured here), so edits made while the fetch is in flight are kept.
    LaunchedEffect(format.modelId) {
        val id = format.modelId
        if (id != 0L) {
            loadingFields = true
            onSyncFields(id) { loadingFields = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = nameText,
                onValueChange = {
                    nameText = it
                    onChange { current -> current.copy(name = it) }
                },
                label = { Text(stringResource(R.string.anki_card_format_name_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (canDelete) {
                IconButton(onClick = onDeleteRequest) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.anki_card_format_delete_title),
                        tint = TextGray
                    )
                }
            }
        }

        Row {
            for (option in AnkiEntryType.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = format.type == option, onClick = { onChange { it.copy(type = option) } })
                    Text(option.label())
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = iconMenuExpanded,
            onExpandedChange = { iconMenuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                singleLine = true,
                value = format.icon.label(),
                onValueChange = {},
                label = { Text(stringResource(R.string.anki_card_format_icon_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = iconMenuExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = NeonTeal,
                    unfocusedBorderColor = Color(0x44FFFFFF)
                )
            )
            ExposedDropdownMenu(
                expanded = iconMenuExpanded,
                onDismissRequest = { iconMenuExpanded = false }
            ) {
                for (option in AnkiCardIcon.entries) {
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        onClick = { onChange { it.copy(icon = option) }; iconMenuExpanded = false }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = deckMenuExpanded,
            onExpandedChange = { deckMenuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                singleLine = true,
                value = deckOptions[format.deckId] ?: format.deckName,
                onValueChange = {},
                label = { Text(stringResource(R.string.deck)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deckMenuExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = NeonTeal,
                    unfocusedBorderColor = Color(0x44FFFFFF)
                )
            )
            ExposedDropdownMenu(
                expanded = deckMenuExpanded,
                onDismissRequest = { deckMenuExpanded = false }
            ) {
                if (deckOptions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.anki_card_format_no_decks)) },
                        onClick = {},
                        enabled = false
                    )
                }
                for ((id, deckName) in deckOptions) {
                    DropdownMenuItem(
                        text = { Text(deckName) },
                        onClick = {
                            onChange { it.copy(deckId = id, deckName = deckName) }
                            deckMenuExpanded = false
                        }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                singleLine = true,
                value = modelOptions[format.modelId] ?: format.modelName,
                onValueChange = {},
                label = { Text(stringResource(R.string.anki_card_format_model_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = NeonTeal,
                    unfocusedBorderColor = Color(0x44FFFFFF)
                )
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false }
            ) {
                if (modelOptions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.anki_card_format_no_models)) },
                        onClick = {},
                        enabled = false
                    )
                }
                for ((id, modelName) in modelOptions) {
                    DropdownMenuItem(
                        text = { Text(modelName) },
                        onClick = {
                            // Old fields stay in place until the new model's field list
                            // arrives, so same-named fields carry their values over.
                            onChange { it.copy(modelId = id, modelName = modelName) }
                            modelMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (loadingFields) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.anki_card_format_loading_fields),
                    color = TextGray,
                    fontSize = 12.sp
                )
            }
        }

        val fieldNames = if (loadingFields) emptyList() else format.fields.keys.toList()
        for ((fieldIndex, fieldName) in fieldNames.withIndex()) {
            key(fieldName) {
                val fieldConfig = format.fields[fieldName]
                    ?: AnkiField()
                var overwriteMenuExpanded by remember { mutableStateOf(false) }
                var markerMenuExpanded by remember { mutableStateOf(false) }
                var fieldText by remember { mutableStateOf(fieldConfig.value) }
                val missingRequiredMarker = fieldIndex == 0 &&
                        !AnkiFieldDefaults.containsAnyMarker(fieldText)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = fieldText,
                        onValueChange = { newValue ->
                            fieldText = newValue
                            onChange { it.withField(fieldName) { field -> field.copy(value = newValue) } }
                        },
                        label = { Text(fieldName) },
                        placeholder = { Text(stringResource(R.string.anki_card_format_field_marker_hint)) },
                        singleLine = true,
                        isError = missingRequiredMarker,
                        supportingText = if (missingRequiredMarker) {
                            { Text(stringResource(R.string.anki_card_format_first_field_needs_marker)) }
                        } else {
                            null
                        },
                        trailingIcon = {
                            Box {
                                IconButton(onClick = { markerMenuExpanded = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.anki_card_format_insert_marker),
                                        tint = TextGray
                                    )
                                }
                                // Mirrors the original's per-field marker menu: picking a marker
                                // replaces the field value with it, and the marker this field
                                // would get by default is shown in bold.
                                val defaultValue = AnkiFieldDefaults
                                    .defaultValue(fieldName, fieldIndex, format.type, null)
                                DropdownMenu(
                                    expanded = markerMenuExpanded,
                                    onDismissRequest = { markerMenuExpanded = false }
                                ) {
                                    for (marker in AnkiStandardMarkers.forType(format.type)) {
                                        val markerText = "{$marker}"
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    markerText,
                                                    fontWeight = if (markerText == defaultValue) FontWeight.Bold else null
                                                )
                                            },
                                            onClick = {
                                                fieldText = markerText
                                                onChange { it.withField(fieldName) { field -> field.copy(value = markerText) } }
                                                markerMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (checkForDuplicates && duplicateBehavior == AnkiDuplicateBehavior.OVERWRITE) {
                        ExposedDropdownMenuBox(
                            expanded = overwriteMenuExpanded,
                            onExpandedChange = { overwriteMenuExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                readOnly = true,
                                singleLine = true,
                                value = fieldConfig.overwriteMode.label(),
                                onValueChange = {},
                                label = { Text(stringResource(R.string.overwrite)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = overwriteMenuExpanded)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = NeonTeal,
                                    unfocusedBorderColor = Color(0x44FFFFFF)
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = overwriteMenuExpanded,
                                onDismissRequest = { overwriteMenuExpanded = false }
                            ) {
                                for (mode in AnkiFieldOverwriteMode.entries) {
                                    DropdownMenuItem(
                                        text = { Text(mode.label()) },
                                        onClick = {
                                            onChange { it.withField(fieldName) { field -> field.copy(overwriteMode = mode) } }
                                            overwriteMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** This format with the field named [fieldName] (or a new, empty one) replaced by [transform]'s result. */
private fun AnkiCardFormat.withField(fieldName: String, transform: (AnkiField) -> AnkiField): AnkiCardFormat =
    copy(fields = fields + (fieldName to transform(fields[fieldName] ?: AnkiField())))

@Composable
private fun AnkiDuplicateBehavior.label(): String =
    when (this) {
        AnkiDuplicateBehavior.PREVENT ->
            stringResource(R.string.anki_duplicate_behavior_prevent)

        AnkiDuplicateBehavior.OVERWRITE ->
            stringResource(R.string.anki_duplicate_behavior_overwrite)

        AnkiDuplicateBehavior.ALWAYS_ADD ->
            stringResource(R.string.anki_duplicate_behavior_always_add)
    }

@Composable
private fun AnkiDuplicateScope.label(): String =
    when (this) {
        AnkiDuplicateScope.COLLECTION ->
            stringResource(R.string.anki_duplicate_scope_collection)

        AnkiDuplicateScope.DECK ->
            stringResource(R.string.deck)

        AnkiDuplicateScope.DECK_ROOT ->
            stringResource(R.string.anki_duplicate_scope_deck_root)
    }

@Composable
private fun AnkiEntryType.label(): String =
    when (this) {
        AnkiEntryType.TERM ->
            stringResource(R.string.anki_entry_type_term)

        AnkiEntryType.KANJI ->
            stringResource(R.string.kanji)
    }

@Composable
private fun AnkiFieldOverwriteMode.label(): String =
    when (this) {
        AnkiFieldOverwriteMode.COALESCE ->
            stringResource(R.string.anki_overwrite_mode_coalesce)

        AnkiFieldOverwriteMode.COALESCE_NEW ->
            stringResource(R.string.anki_overwrite_mode_coalesce_new)

        AnkiFieldOverwriteMode.OVERWRITE ->
            stringResource(R.string.overwrite)

        AnkiFieldOverwriteMode.APPEND ->
            stringResource(R.string.anki_overwrite_mode_append)

        AnkiFieldOverwriteMode.PREPEND ->
            stringResource(R.string.anki_overwrite_mode_prepend)

        AnkiFieldOverwriteMode.SKIP ->
            stringResource(R.string.anki_overwrite_mode_skip)
    }

@Composable
private fun AnkiCardIcon.label(): String =
    when (this) {
        AnkiCardIcon.BIG_CIRCLE ->
            stringResource(R.string.anki_card_icon_big_circle)

        AnkiCardIcon.SMALL_CIRCLE ->
            stringResource(R.string.anki_card_icon_small_circle)

        AnkiCardIcon.SQUARE ->
            stringResource(R.string.anki_card_icon_square)

        AnkiCardIcon.DIAMOND ->
            stringResource(R.string.anki_card_icon_diamond)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiExportGeneralSettingsCard(
    enabled: Boolean,
    ankiDroidInstalled: Boolean,
    ankiDroidPermissionGranted: Boolean,
    tags: List<String>,
    checkForDuplicates: Boolean,
    duplicateBehavior: AnkiDuplicateBehavior,
    duplicateScope: AnkiDuplicateScope,
    duplicateScopeCheckAllModels: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTagsChange: (List<String>) -> Unit,
    onCheckForDuplicatesChange: (Boolean) -> Unit,
    onDuplicateBehaviorChange: (AnkiDuplicateBehavior) -> Unit,
    onDuplicateScopeChange: (AnkiDuplicateScope) -> Unit,
    onDuplicateScopeCheckAllModelsChange: (Boolean) -> Unit,
    onRefreshConnection: () -> Unit,
    onConnected: () -> Unit
) {
    var duplicateBehaviorMenuExpanded by remember { mutableStateOf(false) }
    var duplicateScopeMenuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var tagsText by remember { mutableStateOf(TagsTextUtil.formatTags(tags)) }
    LaunchedEffect(tags) {
        if (TagsTextUtil.shouldResyncFromUpstream(tagsText, tags)) {
            tagsText = TagsTextUtil.formatTags(tags)
        }
    }

    var showInstallDialog by rememberSaveable { mutableStateOf(false) }
    var showPermissionRationaleDialog by rememberSaveable { mutableStateOf(false) }
    var permissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    // Set once the user declines the permission request. The switch stays off in that case,
    // so the status row below must not depend on `enabled` or the user would have no visible
    // way to retry (or, after a permanent denial, to reach the app's settings page).
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onRefreshConnection()
        if (granted) {
            permissionDenied = false
            permissionPermanentlyDenied = false
            onEnabledChange(true)
            onConnected()
        } else {
            permissionDenied = true
            val activity = context as? android.app.Activity
            permissionPermanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        AnkiDroidClient.PERMISSION
                    )
        }
    }

    fun requestAnkiDroidPermission() {
        permissionLauncher.launch(AnkiDroidClient.PERMISSION)
    }

    fun openAnkiDroidPlayStore() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=com.ichi2.anki".toUri())
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://play.google.com/store/apps/details?id=com.ichi2.anki".toUri()
                    )
                )
            } catch (_: Exception) {
                // No app (not even a browser) can handle this, so there is nothing more we can do.
            }
        }
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF))
    ) {
        Column(
            // Top padding is reduced relative to the standard 20dp: this card's first row
            // holds a Text plus a Switch, and the switch's minimum touch target is taller
            // than a line of text, which otherwise reads as extra empty space above the
            // label compared to cards whose first row is text-only.
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.anki_export_enable),
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            when {
                                !ankiDroidInstalled -> showInstallDialog = true
                                !ankiDroidPermissionGranted -> showPermissionRationaleDialog = true
                                else -> {
                                    onEnabledChange(true)
                                    onConnected()
                                }
                            }
                        } else {
                            onEnabledChange(false)
                        }
                    }
                )
            }

            val showConnectionStatus = enabled || (ankiDroidInstalled && permissionDenied)
            if (showConnectionStatus) {
                when {
                    !ankiDroidInstalled -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.anki_export_not_installed),
                                color = NeonRed,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showInstallDialog = true }) {
                                Text(stringResource(R.string.anki_action_install))
                            }
                        }
                    }

                    !ankiDroidPermissionGranted -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    if (enabled) R.string.anki_export_permission_needed else R.string.anki_export_permission_denied
                                ),
                                color = NeonRed,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (permissionPermanentlyDenied) openAppSettings() else showPermissionRationaleDialog =
                                        true
                                }
                            ) {
                                Text(
                                    if (permissionPermanentlyDenied) {
                                        stringResource(R.string.anki_action_open_settings)
                                    } else {
                                        stringResource(R.string.anki_action_grant_permission)
                                    }
                                )
                            }
                        }
                    }

                    else -> {}
                }
            }

            if (enabled) {
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = {
                        tagsText = it
                        onTagsChange(TagsTextUtil.parseTags(it))
                    },
                    label = { Text(stringResource(R.string.anki_export_tags_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    // Like the original's settings page, the typed text is rewritten as the
                    // parsed tags, one space apart, once the field loses focus.
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (!focus.isFocused) {
                                tagsText = TagsTextUtil.formatTags(TagsTextUtil.parseTags(tagsText))
                            }
                        },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = NeonTeal,
                        unfocusedBorderColor = Color(0x44FFFFFF)
                    )
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.anki_export_check_duplicates),
                            color = TextWhite,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = checkForDuplicates, onCheckedChange = onCheckForDuplicatesChange)
                    }

                    if (checkForDuplicates) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.anki_export_check_all_models),
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = duplicateScopeCheckAllModels,
                                    onCheckedChange = onDuplicateScopeCheckAllModelsChange
                                )
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {

                                // The caption is the field's label, as in the card format editor's pickers,
                                // so the value gets the full width instead of being cut off beside it.
                                ExposedDropdownMenuBox(
                                    expanded = duplicateScopeMenuExpanded,
                                    onExpandedChange = { duplicateScopeMenuExpanded = it },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                        readOnly = true,
                                        singleLine = true,
                                        value = duplicateScope.label(),
                                        onValueChange = {},
                                        label = { Text(stringResource(R.string.anki_export_duplicate_scope_label)) },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = duplicateScopeMenuExpanded)
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextWhite,
                                            unfocusedTextColor = TextWhite,
                                            focusedBorderColor = NeonTeal,
                                            unfocusedBorderColor = Color(0x44FFFFFF)
                                        )
                                    )
                                    ExposedDropdownMenu(
                                        expanded = duplicateScopeMenuExpanded,
                                        onDismissRequest = { duplicateScopeMenuExpanded = false }
                                    ) {
                                        for (option in AnkiDuplicateScope.entries) {
                                            DropdownMenuItem(
                                                text = { Text(option.label()) },
                                                onClick = {
                                                    onDuplicateScopeChange(option); duplicateScopeMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                ExposedDropdownMenuBox(
                                    expanded = duplicateBehaviorMenuExpanded,
                                    onExpandedChange = { duplicateBehaviorMenuExpanded = it },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                        readOnly = true,
                                        singleLine = true,
                                        value = duplicateBehavior.label(),
                                        onValueChange = {},
                                        label = { Text(stringResource(R.string.anki_export_duplicate_behavior)) },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = duplicateBehaviorMenuExpanded)
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextWhite,
                                            unfocusedTextColor = TextWhite,
                                            focusedBorderColor = NeonTeal,
                                            unfocusedBorderColor = Color(0x44FFFFFF)
                                        )
                                    )
                                    ExposedDropdownMenu(
                                        expanded = duplicateBehaviorMenuExpanded,
                                        onDismissRequest = { duplicateBehaviorMenuExpanded = false }
                                    ) {
                                        for (option in AnkiDuplicateBehavior.entries) {
                                            DropdownMenuItem(
                                                text = { Text(option.label()) },
                                                onClick = {
                                                    onDuplicateBehaviorChange(option); duplicateBehaviorMenuExpanded =
                                                    false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text(stringResource(R.string.anki_install_dialog_title)) },
            text = { Text(stringResource(R.string.anki_install_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showInstallDialog = false; openAnkiDroidPlayStore() }) {
                    Text(stringResource(R.string.anki_install_dialog_confirm), color = NeonTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) {
                    Text(stringResource(R.string.cancel_action), color = TextWhite)
                }
            },
            containerColor = GlassSurface,
            titleContentColor = TextWhite,
            textContentColor = TextWhite
        )
    }

    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            title = { Text(stringResource(R.string.anki_permission_dialog_title)) },
            // The permission belongs to this plugin app, not to the Dokuen host.
            text = { Text(stringResource(R.string.anki_permission_dialog_body, stringResource(R.string.app_name))) },
            confirmButton = {
                // After a permanent denial Android no longer shows its permission prompt, so
                // the only way forward is this app's system settings page.
                TextButton(
                    onClick = {
                        showPermissionRationaleDialog = false
                        if (permissionPermanentlyDenied) openAppSettings() else requestAnkiDroidPermission()
                    }
                ) {
                    Text(
                        stringResource(
                            if (permissionPermanentlyDenied) {
                                R.string.anki_action_open_settings
                            } else {
                                R.string.anki_permission_dialog_confirm
                            }
                        ),
                        color = NeonTeal
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationaleDialog = false }) {
                    Text(stringResource(R.string.cancel_action), color = TextWhite)
                }
            },
            containerColor = GlassSurface,
            titleContentColor = TextWhite,
            textContentColor = TextWhite
        )
    }
}
