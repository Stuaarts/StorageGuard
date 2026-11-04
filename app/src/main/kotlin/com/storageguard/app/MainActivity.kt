package com.storageguard.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storageguard.app.data.CleanupAttempt
import com.storageguard.core.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StorageGuardTheme {
                val vm: StorageViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                    vm.consentResult(it.resultCode == Activity.RESULT_OK)
                }
                LaunchedEffect(vm) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        vm.consent.collect { request ->
                            try { consent.launch(request) }
                            catch (_: Exception) { vm.consentResult(false) }
                        }
                    }
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshCoverage() }
                StorageGuardApp(state, vm) { asset ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(asset.uri), asset.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { vm.message("No supported preview app is available, or access has changed.") }
                }
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F1E4), onPrimaryContainer = Color(0xFF073D35),
    secondary = Color(0xFF4C635C), surface = Color(0xFFF7FAF8), surfaceContainer = Color(0xFFECF2EE),
    background = Color(0xFFF7FAF8), onBackground = Color(0xFF17221F),
    secondaryContainer = Color(0xFFDDECE4), onSecondaryContainer = Color(0xFF163E32), surfaceContainerHighest = Color(0xFFE1EAE4),
    surfaceContainerLow = Color(0xFFF0F5F2), onSurface = Color(0xFF17221F), outlineVariant = Color(0xFFD2DFD8),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF85D5BD), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF164E41), onPrimaryContainer = Color(0xFFC7F1E4),
    surface = Color(0xFF101916), surfaceContainer = Color(0xFF1B2822), surfaceContainerLow = Color(0xFF17211C),
    background = Color(0xFF101916), onBackground = Color(0xFFE1EBE5),
    secondaryContainer = Color(0xFF304D40), onSecondaryContainer = Color(0xFFD0EBDD), surfaceContainerHighest = Color(0xFF2B3931),
    onSurface = Color(0xFFE1EBE5), outlineVariant = Color(0xFF3B4C43),
)
@Composable
fun StorageGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageGuardApp(state: AppState, vm: StorageViewModel, preview: (Asset) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var purging by rememberSaveable { mutableStateOf(false) }
    val group = state.groups.firstOrNull { it.id == groupId }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshCoverage() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addSource(uri, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { vm.addSource(it, false) } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> uris.forEach { vm.addSource(it, false, true) } }
    val requestAccess = {
        permissions.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long); vm.dismissMessage() }
    }
    LaunchedEffect(state.selected) { if (state.selected.isEmpty()) confirming = false }
    BackHandler(group != null || confirming || state.page == Page.HISTORY) {
        when { confirming -> confirming = false; group != null -> groupId = null; else -> vm.page(Page.HOME) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Text("StorageGuard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }, navigationIcon = {
                if (group != null || confirming || state.page == Page.HISTORY) IconButton(onClick = {
                    when { confirming -> confirming = false; group != null -> groupId = null; else -> vm.page(Page.HOME) }
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = { groupId = null; vm.page(Page.HISTORY) }) { Icon(Icons.Outlined.History, "Cleanup history") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        },
        bottomBar = {
            if (state.ready && state.preferences.welcomed && !confirming) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf(Triple(Page.HOME, "Home", Icons.Outlined.Home), Triple(Page.FINDINGS, "Findings", Icons.Outlined.FolderCopy),
                    Triple(Page.SCAN, "Scan", Icons.Outlined.Radar), Triple(Page.SETTINGS, "Settings", Icons.Outlined.Settings)).forEach { (page, title, icon) ->
                    NavigationBarItem(selected = state.page == page && group == null, onClick = { groupId = null; vm.page(page) },
                        icon = { Icon(icon, null) }, label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                when {
                    !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    !state.preferences.welcomed -> Welcome(onContinue = { vm.welcome() }, requestAccess = requestAccess,
                        choosePhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                    confirming -> Confirmation(state, onBack = { confirming = false }, onConfirm = { confirming = false; vm.cleanup() })
                    group != null -> Review(state, group, vm, preview, onConfirm = { confirming = true })
                    state.page == Page.HOME -> Home(state, onScan = { vm.scan() }, onAccess = { vm.page(Page.SETTINGS) }, onFindings = { vm.page(Page.FINDINGS) })
                    state.page == Page.SCAN -> ScanScreen(state, vm)
                    state.page == Page.FINDINGS -> Findings(state, onOpen = { groupId = it.id }, onScan = { vm.scan() })
                    state.page == Page.SETTINGS -> Settings(state, requestAccess, onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onFolder = { folder.launch(null) }, onDocuments = { documents.launch(arrayOf("*/*")) },
                        onRemove = { vm.removeSource(it) }, onPermanent = { vm.permanent(it) }, onPurge = { purging = true })
                    state.page == Page.HISTORY -> History(state)
                }
            }
        }
    }
    if (purging) AlertDialog(onDismissRequest = { purging = false }, title = { Text("Reset local app data?") },
        text = { Text("Erase the scan index, cleanup history and preferences, and release saved file access. Your files will stay where they are. Media permissions can be revoked in Android Settings.") },
        confirmButton = { TextButton(onClick = { purging = false; groupId = null; vm.purge() }) { Text("Reset app data") } },
        dismissButton = { TextButton(onClick = { purging = false }) { Text("Cancel") } })
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
private fun Eyebrow(text: String) { Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }

@Composable
private fun Title(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Welcome(onContinue: () -> Unit, requestAccess: () -> Unit, choosePhotos: () -> Unit) = PageColumn {
    Spacer(Modifier.height(12.dp))
    Eyebrow("Your files. Your control.")
    Title("Make room.\nKeep what matters.", "Find exact duplicate files, see where they live, and choose what stays.")
    InfoCard(Icons.Outlined.Lock, "Private by design", "File comparison happens on your phone. No account, uploads or analytics.")
    InfoCard(Icons.Outlined.FolderOpen, "Only the files you allow", "Scan granted photos and videos, or choose individual files and folders. Other apps’ private storage stays out of reach.")
    InfoCard(Icons.Outlined.VerifiedUser, "A copy stays protected", "Review every removal. Copies are checked again before cleanup, with Android approval for media trash.")
    Button(onClick = { onContinue(); requestAccess() }, modifier = Modifier.fillMaxWidth()) { Text("Set up media access") }
    OutlinedButton(onClick = { onContinue(); choosePhotos() }, modifier = Modifier.fillMaxWidth()) { Text("Choose photos & videos") }
    TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue without media access") }
}

@Composable
private fun Home(state: AppState, onScan: () -> Unit, onAccess: () -> Unit, onFindings: () -> Unit) = PageColumn {
    Eyebrow("A little clarity. More room.")
    Title("Know your storage.", "Find extra copies without the guesswork.")
    state.device?.let { device ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("DEVICE STORAGE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    val used = ((device.total - device.free).toFloat() / device.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val primary = MaterialTheme.colorScheme.primary
                    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(108.dp).semantics { contentDescription = "${(used * 100).toInt()} percent used" }) {
                        Canvas(Modifier.size(96.dp)) {
                            drawArc(track, 135f, 270f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                            drawArc(primary, 135f, 270f * used, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                        }
                        Text("${(used * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        Text(formatBytes(device.free), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("available of ${formatBytes(device.total)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Device volume estimate · scanned coverage is shown separately below.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Card(onClick = onFindings, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Possible duplicate savings")
            Text(formatBytes(state.possibleBytes), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(if (state.groups.isEmpty()) "Run a scan to look for extra copies." else "${state.groups.size} exact-match groups to review")
            Text("Estimated logical bytes · storage recovered is unmeasured", style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = onScan, enabled = !state.running && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Outlined.Radar, null); Spacer(Modifier.width(10.dp)); Text(if (state.running) "Scan in progress" else "Scan accessible files")
    }
    InfoCard(Icons.Outlined.Visibility, state.coverage.label,
        "${state.preferences.sources.size} selected locations · ${state.progress.discovered} indexed files. Private app data is excluded.")
    TextButton(onClick = onAccess) { Text("Manage scan coverage") }
    state.sessions.firstOrNull()?.let { Text("Last scan: ${date(it.startedAt)} · ${it.status.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ScanScreen(state: AppState, vm: StorageViewModel) = PageColumn {
    Eyebrow("On-device comparison")
    Title(if (state.paused) "Scan paused." else if (state.running) "Looking for extra copies." else "Ready when you are.",
        "Only accessible files are included. Keep the app open for reliable progress; interrupted scans can be restarted.")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Radar, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(state.progress.phase, style = MaterialTheme.typography.titleLarge)
            if (state.running && !state.paused) {
                if (state.progress.candidates > 0) LinearProgressIndicator(progress = { state.progress.hashed.toFloat() / state.progress.candidates }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Metric("Files discovered", state.progress.discovered.toString())
            Metric("Candidate files processed", "${state.progress.hashed} / ${state.progress.candidates}")
            Metric("Content read", formatBytes(state.progress.bytesRead))
            Metric("Unreadable or changed files", state.progress.issues.toString())
        }
    }
    if (state.running) {
        Button(onClick = { vm.pause() }, modifier = Modifier.fillMaxWidth()) { Text(if (state.paused) "Resume scan" else "Pause scan") }
        OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel scan") }
    } else Button(onClick = { vm.scan() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Start a new scan") }
    InfoCard(Icons.Outlined.Visibility, "Current coverage", "${state.coverage.label}. ${state.preferences.sources.size} selected locations. Unknown-size files are indexed but excluded from duplicate comparison.")
    state.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Findings(state: AppState, onOpen: (DuplicateGroup) -> Unit, onScan: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var search by rememberSaveable { mutableStateOf("") }
    var largest by rememberSaveable { mutableStateOf(true) }
    val groups = state.groups.filter { group -> group.files.any {
        (filter == "All" || it.asset.category.name == filter) &&
            (it.asset.name.contains(search, true) || it.asset.location.contains(search, true))
    } }.let { if (largest) it.sortedByDescending { g -> g.possibleBytes } else it.sortedBy { g -> g.files.first().asset.name.lowercase() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Eyebrow("Exact duplicates") }
        item { Title("A few copies too many?", "${state.groups.size} groups · ${formatBytes(state.possibleBytes)} possible savings. Nothing is selected automatically.") }
        if (state.restored) item { InfoCard(Icons.Outlined.Update, "Saved findings", "These results are from an earlier scan. All files must pass fresh verification before removal.") }
        if (state.issues.isNotEmpty() || state.progress.issues > 0) item { InfoCard(Icons.Outlined.Info, "Partial scan coverage", "Some locations or files could not be read. Results cover only successfully compared files.") }
        items(state.issues) { issue -> Text(issue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        item {
            OutlinedTextField(search, { search = it }, label = { Text("Search names or locations") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) })
        }
        item {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All" to "All", "PHOTOS" to "Photos", "VIDEOS" to "Videos", "OTHER" to "Other").forEach { (key, label) ->
                        FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                    }
                }
                TextButton(onClick = { largest = !largest }) { Icon(Icons.AutoMirrored.Outlined.Sort, null); Spacer(Modifier.width(8.dp)); Text(if (largest) "Largest savings first" else "File name A–Z") }
            }
        }
        if (groups.isEmpty()) item {
            InfoCard(Icons.Outlined.CheckCircle, if (state.groups.isNotEmpty()) "No matching groups" else if (state.running) "Scan in progress" else if (state.scanFinished) "No exact duplicates found" else "Your next scan starts here",
                if (state.scanFinished) "No extra copies were found in the successfully scanned scope. This does not cover the entire device." else "Grant access or choose files in Settings, then scan to compare their full contents.")
            if (!state.running) Button(onClick = onScan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Scan accessible files") }
        }
        items(groups, key = { it.id }) { group ->
            Card(onClick = { onOpen(group) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(categoryIcon(group.files.first().asset.category), null, tint = MaterialTheme.colorScheme.primary)
                        Text(group.files.first().asset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    Text("${group.files.size} matching files · ${formatBytes(group.size)} each", style = MaterialTheme.typography.bodyMedium)
                    Text(group.files.map { it.asset.location }.distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(if (group.possibleBytes > 0) "${formatBytes(group.possibleBytes)} possible savings · Review copies" else "Review copies · no removable savings established",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Review(state: AppState, group: DuplicateGroup, vm: StorageViewModel, preview: (Asset) -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Eyebrow("Review copies") }
        item { Title("You decide what stays.", "${group.files.size} files have matching full-content hashes. Protect a copy, then select extras to remove.") }
        item { InfoCard(Icons.Outlined.Shield, "Keep a verified copy", "Cleanup checks access, metadata and full file contents again. Deleting copies in app-maintained folders can affect that app’s references or workflows.") }
        items(group.files, key = { it.asset.uri }) { file ->
            val asset = file.asset
            val kept = state.protected[group.id] == asset.uri
            val selected = asset.uri in state.selected
            val eligible = asset.removable && asset.identity != null &&
                (asset.source == Source.MEDIA || state.preferences.permanentDelete)
            var details by rememberSaveable(asset.uri) { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = if (kept) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (kept) Icons.Outlined.Shield else categoryIcon(asset.category), null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (kept) "Keep · protected copy" else if (selected) "Selected for removal" else "Additional copy",
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Text(asset.name, style = MaterialTheme.typography.titleMedium)
                    Text("${formatBytes(asset.size ?: 0)} · ${asset.mime}", style = MaterialTheme.typography.bodySmall)
                    Text(asset.location, style = MaterialTheme.typography.bodyMedium)
                    Text(if (asset.source == Source.MEDIA) "Android media · move to trash" else if (eligible) "Document · permanent deletion" else "Document · review only",
                        style = MaterialTheme.typography.labelMedium)
                    if (!kept) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = { vm.select(group, asset) }, enabled = eligible && !state.busy,
                            modifier = Modifier.semantics { contentDescription = "Remove ${asset.name}" })
                        Text("Remove this copy", Modifier.weight(1f))
                    }
                    if (asset.identity == null) Text("Independent file identity is uncertain. This reference cannot be removed.", style = MaterialTheme.typography.bodySmall)
                    else if (asset.source == Source.DOCUMENT && !state.preferences.permanentDelete && asset.removable)
                        Text("Permanent document deletion is off. Enable it in Settings to select this copy.", style = MaterialTheme.typography.bodySmall)
                    else if (!asset.removable) Text("Provider access does not support removal.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!kept && asset.identity != null) TextButton(onClick = { vm.protect(group, asset.uri) }, enabled = !state.busy) { Text("Keep this") }
                        TextButton(onClick = { preview(asset) }) { Text("Preview") }
                        TextButton(onClick = { details = !details }) { Text(if (details) "Less" else "Details") }
                    }
                    if (details) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Source URI\n${asset.uri}", style = MaterialTheme.typography.bodySmall)
                            val modified = asset.modified?.let { if (asset.source == Source.MEDIA) it * 1000 else it }
                            Text("Last modified: ${modified?.let { date(it) } ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256\n${file.sha256}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
        Surface(shadowElevation = 2.dp) {
            Button(onClick = onConfirm, enabled = state.selected.isNotEmpty() && !state.busy,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 56.dp)) {
                if (state.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (state.busy) "Verifying copies…" else "Review ${state.selected.size} selected · ${formatBytes(state.selectedBytes)}")
            }
        }
    }
}

@Composable
private fun Confirmation(state: AppState, onBack: () -> Unit, onConfirm: () -> Unit) {
    val selected = state.groups.flatMap { it.files }.filter { it.asset.uri in state.selected }
    val keepers = state.groups.filter { g -> g.files.any { it.asset.uri in state.selected } }.mapNotNull { group ->
        group.files.firstOrNull { it.asset.uri == state.protected[group.id] }
    }
    val permanent = selected.any { it.asset.source == Source.DOCUMENT }
    PageColumn {
        Eyebrow("Final review")
        Title("Check before cleanup.", "${selected.size} selected copies · ${formatBytes(state.selectedBytes)} logical bytes. Actual storage recovered is unmeasured.")
        InfoCard(if (permanent) Icons.Outlined.WarningAmber else Icons.Outlined.Recycling,
            if (permanent) "Includes permanent document deletion" else "Media goes to Android trash",
            if (permanent) "Selected documents will be permanently deleted using their provider. There may be no undo. Media uses Android’s trash approval. Removing app-maintained copies may affect other apps."
            else "Android will ask you to approve moving media to trash. Retention and restoration depend on Android and the provider. Trashed files may continue to occupy storage.")
        Text("Protected copies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        keepers.forEach { Text("Keep: ${it.asset.name}\n${it.asset.location}", style = MaterialTheme.typography.bodyMedium) }
        Text("Exact removal list", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        selected.forEach { file ->
            Text("${if (file.asset.source == Source.MEDIA) "Trash" else "Permanently delete"}: ${file.asset.name}\n${file.asset.location}\n${file.asset.uri}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Every selected copy and a protected copy must pass fresh verification. Changed or unreadable files block the request.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onConfirm, enabled = !state.busy && selected.size <= KeepPolicy.MAX_BATCH, modifier = Modifier.fillMaxWidth()) {
            Text(if (permanent) "Verify & proceed with deletion" else "Verify & request Android approval")
        }
        if (selected.size > KeepPolicy.MAX_BATCH) Text("Review at most ${KeepPolicy.MAX_BATCH} copies at once.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
    }
}

@Composable
private fun Settings(state: AppState, onAccess: () -> Unit, onPhotos: () -> Unit, onFolder: () -> Unit, onDocuments: () -> Unit,
    onRemove: (String) -> Unit, onPermanent: (Boolean) -> Unit, onPurge: () -> Unit) = PageColumn {
    Eyebrow("Access & privacy")
    Title("Your scan, your scope.", "Grant only the access you want. Coverage can change when Android permissions or providers change.")
    InfoCard(Icons.Outlined.Visibility, state.coverage.label, "Photos: ${if (state.coverage.images) "granted" else "not fully granted"}. Videos: ${if (state.coverage.videos) "granted" else "not fully granted"}. Selected-media access: ${if (state.coverage.partial) "granted" else "not granted"}.")
    OutlinedButton(onClick = onAccess, enabled = !state.busy && !state.running, modifier = Modifier.fillMaxWidth()) { Text("Grant or reselect media access") }
    OutlinedButton(onClick = onPhotos, enabled = !state.busy && !state.running, modifier = Modifier.fillMaxWidth()) { Text("Choose photos & videos") }
    OutlinedButton(onClick = onFolder, enabled = !state.busy && !state.running, modifier = Modifier.fillMaxWidth()) { Text("Add a folder") }
    OutlinedButton(onClick = onDocuments, enabled = !state.busy && !state.running, modifier = Modifier.fillMaxWidth()) { Text("Choose documents") }
    Text("Selected locations (${state.preferences.sources.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (state.preferences.sources.isEmpty()) Text("No folders or documents selected yet.", style = MaterialTheme.typography.bodyMedium)
    state.preferences.sources.forEach { source ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (source.startsWith("T|")) "Folder" else "Selected file", fontWeight = FontWeight.Bold)
                SelectionContainer { Text(Uri.decode(source.substringAfter('|')), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onRemove(source) }, enabled = !state.busy && !state.running) { Text("Remove access") }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Advanced · permanent document deletion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Off by default. Only supported local documents with independent identities can be selected. Deletion may be irreversible; every operation still requires a final review.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow permanent document deletion", Modifier.weight(1f))
                Switch(state.preferences.permanentDelete, onCheckedChange = onPermanent, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "Allow permanent document deletion" })
            }
        }
    }
    InfoCard(Icons.Outlined.Lock, "Local processing, no account", "The app has no Internet permission, backend or analytics. File metadata, hashes and recent history stay in local app storage. A selected cloud provider may retrieve files through its own connection.")
    Text("Other apps’ private caches and databases are inaccessible. This app does not recover deleted files, scan hidden app data or guarantee forensic erasure. Unknown provider identities remain review-only.", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onPurge, enabled = !state.running && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Reset local app data") }
    Text("StorageGuard 0.1.0 · Android 11+", style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun History(state: AppState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Eyebrow("Results & history") }
        item { Title("Every outcome, explained.", "Requests are checked against the provider. Moved to trash does not mean space recovered.") }
        if (state.attempts.isEmpty()) item { InfoCard(Icons.Outlined.History, "No cleanup yet", "Once you review and approve copies for removal, their outcomes will appear here.") }
        items(state.attempts, key = { it.id }) { attempt -> AttemptCard(attempt, state.cleanupLabels[attempt.id]) }
        item { Text("Recent scans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.sessions.isEmpty()) item { Text("No scans yet.") }
        items(state.sessions, key = { it.id }) { scan ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scan.status.lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold)
                    Text(date(scan.startedAt), style = MaterialTheme.typography.bodySmall)
                    Text("${scan.discovered} files · ${scan.errors} issues", style = MaterialTheme.typography.bodyMedium)
                    Text(scan.scope, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AttemptCard(attempt: CleanupAttempt, asset: Asset?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${attempt.status.lowercase().replaceFirstChar(Char::uppercase)} · ${if (attempt.action == "TRASH") "media trash" else "document deletion"}", fontWeight = FontWeight.Bold)
            if (asset != null) Text("${asset.name}\n${asset.location}", style = MaterialTheme.typography.bodyMedium)
            Text("${formatBytes(attempt.bytes)} logical bytes · ${date(attempt.requestedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(attempt.detail, style = MaterialTheme.typography.bodyMedium)
            Text("Operation ${attempt.id.take(8)} · names and paths omitted from saved history", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun categoryIcon(category: Category): ImageVector = when (category) {
    Category.PHOTOS -> Icons.Outlined.Image
    Category.VIDEOS -> Icons.Outlined.Videocam
    Category.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
private fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
