@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.fotoaufraeumer.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

// ---------- Berechtigungen ----------

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun hasMediaPermission(ctx: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> granted(ctx, Manifest.permission.READ_MEDIA_IMAGES)
    else -> granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun isPartialAccess(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 &&
        !granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) &&
        granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

// ---------- Hauptbildschirm ----------

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var hasPerm by remember { mutableStateOf(hasMediaPermission(ctx)) }
    var partial by remember { mutableStateOf(isPartialAccess(ctx)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Photo?>(null) }
    var useTrash by rememberSaveable { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPerm = hasMediaPermission(ctx)
        partial = isPartialAccess(ctx)
        if (hasPerm) vm.loadAndScan()
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.onDeleteConfirmed()
    }

    LaunchedEffect(hasPerm) {
        if (hasPerm && !vm.scannedOnce && !vm.scanning) vm.loadAndScan()
    }
    LaunchedEffect(vm.lastMessage) {
        vm.lastMessage?.let {
            snackbar.showSnackbar(it)
            vm.lastMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foto-Aufräumer") },
                actions = {
                    if (hasPerm) {
                        IconButton(onClick = { vm.loadAndScan() }, enabled = !vm.scanning) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Neu scannen")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (vm.selected.isNotEmpty()) {
                    SelectionBar(
                        count = vm.selected.size,
                        bytes = vm.selectedBytes,
                        onClear = { vm.clearSelection() },
                        onDelete = { showConfirm = true }
                    )
                }
                if (hasPerm) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.CheckCircle, null) },
                            label = { Text("Duplikate (${vm.exactGroups.size})") }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Search, null) },
                            label = { Text("Ähnlich (${vm.similarGroups.size})") }
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.DateRange, null) },
                            label = { Text("Sortiert") }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !hasPerm -> PermissionScreen { permLauncher.launch(requiredPermissions()) }
                vm.scanning -> ScanningScreen(vm.progress, vm.status)
                else -> Column(Modifier.fillMaxSize()) {
                    if (partial) {
                        PartialAccessHint { permLauncher.launch(requiredPermissions()) }
                    }
                    when (tab) {
                        0 -> GroupList(
                            groups = vm.exactGroups,
                            selected = vm.selected,
                            title = "Exakte Duplikate",
                            hint = "Byte für Byte identische Kopien. Das älteste Original bleibt erhalten.",
                            emptyText = "Keine doppelten Fotos gefunden 🎉\n${vm.photos.size} Fotos geprüft.",
                            onToggle = vm::toggle,
                            onPreview = { preview = it },
                            onAutoSelect = { vm.autoSelect(vm.exactGroups) },
                        )
                        1 -> GroupList(
                            groups = vm.similarGroups,
                            selected = vm.selected,
                            title = "Ähnliche Fotos",
                            hint = "Serienbilder, Bearbeitungen und Kopien in anderer Größe. Das schärfste/größte Foto steht vorne.",
                            emptyText = "Keine ähnlichen Fotos gefunden.",
                            onToggle = vm::toggle,
                            onPreview = { preview = it },
                            onAutoSelect = { vm.autoSelect(vm.similarGroups) },
                            header = {
                                SensitivityRow(vm.threshold) { vm.changeThreshold(it) }
                            }
                        )
                        else -> AllPhotos(vm) { preview = it }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        val n = vm.selected.size
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("$n Fotos löschen?") },
            text = {
                Column {
                    Text("Damit werden ${formatSize(vm.selectedBytes)} frei.")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("In den Papierkorb", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "30 Tage lang wiederherstellbar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = useTrash, onCheckedChange = { useTrash = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        vm.buildDeleteRequest(useTrash)?.let {
                            deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    preview?.let { p ->
        PreviewDialog(
            photo = p,
            selected = p.id in vm.selected,
            onToggle = { vm.toggle(p.id) },
            onClose = { preview = null }
        )
    }
}

// ---------- Bausteine ----------

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock, null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("Zugriff auf deine Fotos", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Damit die App doppelte und ähnliche Fotos finden kann, braucht sie Zugriff auf deine Galerie. " +
                "Alles passiert offline auf deinem Handy – kein Foto verlässt das Gerät.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Zugriff erlauben") }
    }
}

@Composable
fun PartialAccessHint(onRequest: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Du hast nur einzelne Fotos freigegeben. Für eine vollständige Suche alle Fotos erlauben.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRequest) { Text("Ändern") }
        }
    }
}

@Composable
fun ScanningScreen(progress: Float, status: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Fotos werden analysiert", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Beim ersten Mal kann das bei vielen Fotos ein paar Minuten dauern.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SelectionBar(count: Int, bytes: Long, onClear: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("$count ausgewählt", style = MaterialTheme.typography.titleSmall)
                Text(
                    formatSize(bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClear) { Text("Abwählen") }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Löschen")
            }
        }
    }
}

@Composable
fun SensitivityRow(current: Int, onChange: (Int) -> Unit) {
    Column {
        Text("Empfindlichkeit", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Streng" to 3, "Normal" to 6, "Locker" to 10).forEach { (label, value) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
fun GroupList(
    groups: List<List<Photo>>,
    selected: Set<Long>,
    title: String,
    hint: String,
    emptyText: String,
    onToggle: (Long) -> Unit,
    onPreview: (Photo) -> Unit,
    onAutoSelect: () -> Unit,
    header: @Composable () -> Unit = {},
) {
    val wasted = remember(groups) { groups.sumOf { g -> g.drop(1).sumOf { it.size } } }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(hint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    header()
                    if (groups.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${groups.size} Gruppen · bis zu ${formatSize(wasted)} freigeben",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = onAutoSelect) {
                            Text("Alle Kopien auswählen (Beste behalten)")
                        }
                    }
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    emptyText,
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        listItems(groups, key = { it.first().id }) { g ->
            GroupCard(g, selected, onToggle, onPreview)
        }
        item {
            Text(
                "Tippen = auswählen · Lange drücken = groß ansehen",
                Modifier.fillMaxWidth().padding(8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GroupCard(
    group: List<Photo>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onPreview: (Photo) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${group.size} Fotos · ${formatSize(group.sumOf { it.size })}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "${formatDate(group.first().dateTaken)} · ${group.first().album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.forEachIndexed { i, p ->
                    Thumb(
                        photo = p,
                        isSelected = p.id in selected,
                        isBest = i == 0,
                        showInfo = true,
                        modifier = Modifier.width(112.dp),
                        onClick = { onToggle(p.id) },
                        onLongClick = { onPreview(p) }
                    )
                }
            }
        }
    }
}

@Composable
fun Thumb(
    photo: Photo,
    isSelected: Boolean,
    isBest: Boolean,
    showInfo: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(
                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.error, shape)
                    else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isSelected) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .padding(4.dp)
                ) {
                    Icon(Icons.Filled.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            if (isBest) {
                Text(
                    "Beste",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (showInfo) {
            Text(
                "${formatSize(photo.size)} · ${photo.width}×${photo.height}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun AllPhotos(vm: MainViewModel, onPreview: (Photo) -> Unit) {
    val sections = remember(vm.photos, vm.sortMode) { vm.sections() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortMode.entries.forEach { m ->
                FilterChip(
                    selected = vm.sortMode == m,
                    onClick = { vm.sortMode = m },
                    label = { Text(m.label) }
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            sections.forEach { (title, list) ->
                item(key = "h_$title", span = { GridItemSpan(maxLineSpan) }) {
                    val ids = list.map { it.id }
                    val allSelected = list.all { it.id in vm.selected }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${list.size} Fotos · ${formatSize(list.sumOf { it.size })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            if (allSelected) vm.deselectAll(ids) else vm.selectAll(ids)
                        }) {
                            Text(if (allSelected) "Abwählen" else "Alle auswählen")
                        }
                    }
                }
                gridItems(list, key = { it.id }) { p ->
                    Thumb(
                        photo = p,
                        isSelected = p.id in vm.selected,
                        isBest = false,
                        showInfo = false,
                        onClick = { vm.toggle(p.id) },
                        onLongClick = { onPreview(p) }
                    )
                }
            }
        }
    }
}

@Composable
fun PreviewDialog(photo: Photo, selected: Boolean, onToggle: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, "Schließen", tint = Color.White)
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp)
            ) {
                Text(photo.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatSize(photo.size)} · ${photo.width}×${photo.height} · ${formatDate(photo.dateTaken)}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Album: ${photo.album}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onToggle,
                    colors = if (selected) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (selected) "Nicht löschen" else "Zum Löschen markieren")
                }
            }
        }
    }
}
