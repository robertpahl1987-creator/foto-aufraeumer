@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.fotoaufraeumer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

fun List<Photo>.olderThan(f: AgeFilter): List<Photo> {
    if (f.days == 0) return this
    val cut = System.currentTimeMillis() - f.days * 86_400_000L
    return filter { it.dateTaken < cut }
}

@Composable
fun CleanupTab(vm: MainViewModel, onPreview: (Photo) -> Unit, onDelete: () -> Unit) {
    var openCat by rememberSaveable { mutableStateOf<Category?>(null) }
    var swipeCat by rememberSaveable { mutableStateOf<Category?>(null) }
    var age by rememberSaveable { mutableStateOf(AgeFilter.ALL) }

    val categories = remember(vm.photos, vm.stats, vm.blurThreshold) {
        Category.entries.associateWith { vm.categoryPhotos(it) }
    }

    BackHandler(enabled = swipeCat != null || openCat != null) {
        if (swipeCat != null) swipeCat = null else openCat = null
    }

    val sc = swipeCat
    val oc = openCat
    when {
        sc != null -> {
            val list = remember(sc, age, categories) { (categories[sc] ?: emptyList()).olderThan(age) }
            SwipeScreen(
                title = sc.title,
                list = list,
                vm = vm,
                onClose = { swipeCat = null },
                onDelete = onDelete
            )
        }
        oc != null -> CategoryDetail(
            cat = oc,
            list = (categories[oc] ?: emptyList()).olderThan(age),
            vm = vm,
            age = age,
            onAge = { age = it },
            onBack = { openCat = null },
            onSwipe = { swipeCat = oc },
            onPreview = onPreview
        )
        else -> CategoryOverview(
            vm = vm,
            categories = categories,
            onOpen = { openCat = it },
            onSwipe = { age = AgeFilter.ALL; swipeCat = it }
        )
    }
}

@Composable
private fun CategoryOverview(
    vm: MainViewModel,
    categories: Map<Category, List<Photo>>,
    onOpen: (Category) -> Unit,
    onSwipe: (Category) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { StorageCard(vm, categories) }
        listItems(Category.entries.toList(), key = { it.name }) { cat ->
            val list = categories[cat] ?: emptyList()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(cat.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        cat.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${list.size} Fotos · ${formatSize(list.sumOf { it.size })}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (list.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!cat.swipeOnly) {
                                OutlinedButton(onClick = { onOpen(cat) }) { Text("Ansehen") }
                            }
                            FilledTonalButton(onClick = { onSwipe(cat) }) { Text("Wischen") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(vm: MainViewModel, categories: Map<Category, List<Photo>>) {
    val total = vm.photos.sumOf { it.size }.coerceAtLeast(1L)
    val rows = listOf(
        "Duplikate" to vm.exactGroups.sumOf { g -> g.drop(1).sumOf { it.size } },
        "Ähnliche" to vm.similarGroups.sumOf { g -> g.drop(1).sumOf { it.size } },
        "Screenshots" to (categories[Category.SCREENSHOTS] ?: emptyList()).sumOf { it.size },
        "Messenger" to (categories[Category.MESSENGER] ?: emptyList()).sumOf { it.size },
        "Unscharf" to (categories[Category.BLURRY] ?: emptyList()).sumOf { it.size },
        "Dunkel" to (categories[Category.DARK] ?: emptyList()).sumOf { it.size },
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Speicher-Übersicht", style = MaterialTheme.typography.titleMedium)
            Text(
                "${vm.photos.size} Fotos belegen ${formatSize(total)}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { (label, bytes) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Text(
                        formatSize(bytes),
                        Modifier.width(76.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CategoryDetail(
    cat: Category,
    list: List<Photo>,
    vm: MainViewModel,
    age: AgeFilter,
    onAge: (AgeFilter) -> Unit,
    onBack: () -> Unit,
    onSwipe: () -> Unit,
    onPreview: (Photo) -> Unit,
) {
    val ids = list.map { it.id }
    val allSelected = list.isNotEmpty() && list.all { it.id in vm.selected }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
                    Text(cat.title, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    cat.desc,
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cat == Category.BLURRY) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Nur sehr unscharf" to 40f, "Normal" to 80f, "Auch leicht unscharf" to 150f)
                            .forEach { (label, v) ->
                                FilterChip(
                                    selected = vm.blurThreshold == v,
                                    onClick = { vm.blurThreshold = v },
                                    label = { Text(label) }
                                )
                            }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgeFilter.entries.forEach { f ->
                        FilterChip(selected = age == f, onClick = { onAge(f) }, label = { Text(f.label) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${list.size} Fotos · ${formatSize(list.sumOf { it.size })}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (list.isNotEmpty()) {
                        TextButton(onClick = {
                            if (allSelected) vm.deselectAll(ids) else vm.selectAll(ids)
                        }) { Text(if (allSelected) "Abwählen" else "Alle auswählen") }
                        FilledTonalButton(onClick = onSwipe) { Text("Wischen") }
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        "Hier gibt es nichts aufzuräumen 🎉",
                        Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

@Composable
private fun SwipeScreen(
    title: String,
    list: List<Photo>,
    vm: MainViewModel,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<Pair<Long, Boolean>>() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val limit = with(LocalDensity.current) { 110.dp.toPx() }
    val marked = history.count { it.second }
    val markedBytes = remember(history.size) {
        val del = history.filter { it.second }.map { it.first }.toSet()
        list.filter { it.id in del }.sumOf { it.size }
    }

    fun decide(photo: Photo, delete: Boolean) {
        if (delete) vm.select(photo.id) else vm.deselect(photo.id)
        history.add(photo.id to delete)
        index++
        offsetX = 0f
    }

    fun undo() {
        if (history.isEmpty()) return
        val last = history.removeAt(history.lastIndex)
        vm.deselect(last.first)
        index = (index - 1).coerceAtLeast(0)
        offsetX = 0f
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Schließen") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${minOf(index + 1, list.size)} / ${list.size} · $marked zum Löschen markiert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { undo() }, enabled = history.isNotEmpty()) {
                Icon(Icons.Filled.ArrowBack, "Rückgängig")
            }
        }

        if (index >= list.size) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Durch! 🎉", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "$marked Fotos zum Löschen markiert · ${formatSize(markedBytes)}",
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                if (vm.selected.isNotEmpty()) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Jetzt löschen") }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onClose) { Text("Zurück zur Übersicht") }
            }
            return@Column
        }

        val photo = list[index]
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Card(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offsetX
                        rotationZ = offsetX / 40f
                    }
                    .pointerInput(photo.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    offsetX < -limit -> decide(photo, true)
                                    offsetX > limit -> decide(photo, false)
                                    else -> offsetX = 0f
                                }
                            },
                            onDragCancel = { offsetX = 0f }
                        ) { change, amount ->
                            change.consume()
                            offsetX += amount
                        }
                    }
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = photo.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (offsetX < -30f) {
                        SwipeLabel("LÖSCHEN", MaterialTheme.colorScheme.error, Modifier.align(Alignment.TopEnd))
                    }
                    if (offsetX > 30f) {
                        SwipeLabel("BEHALTEN", Color(0xFF2E7D32), Modifier.align(Alignment.TopStart))
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(12.dp)
                    ) {
                        Text(formatDate(photo.dateTaken), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatSize(photo.size)} · ${photo.album}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { decide(photo, true) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Löschen")
            }
            Button(
                onClick = { decide(photo, false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Behalten")
            }
        }
        Text(
            "← wischen = löschen · wischen → = behalten",
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwipeLabel(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
            .padding(16.dp)
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
