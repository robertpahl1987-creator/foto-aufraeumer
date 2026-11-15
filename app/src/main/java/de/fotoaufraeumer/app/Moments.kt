@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.fotoaufraeumer.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems

// ---------- Logik ----------

private const val GAP_MS = 6L * 60 * 60 * 1000   // 6 Stunden Pause = neuer Moment
private const val MIN_MOMENT = 3

data class Moment(val photos: List<Photo>) {
    val id: Long = photos.first().id
    val start: Long = photos.first().dateTaken
    val end: Long = photos.last().dateTaken
    val favorites: Int = photos.count { it.isFavorite }
    val special: Boolean = photos.size >= 12 || favorites >= 2 ||
        (favorites >= 1 && photos.size >= 5) ||
        (end - start > 20L * 3600_000 && photos.size >= 8)
    val bytes: Long = photos.sumOf { it.size }

    /** Bis zu 4 Vorschaubilder: Favoriten zuerst, sonst gleichmäßig verteilt */
    val cover: List<Photo> = run {
        val n = photos.size
        val spread = listOf(0, n / 3, 2 * n / 3, n - 1).distinct().map { photos[it] }
        (photos.filter { it.isFavorite } + spread).distinctBy { it.id }.take(4)
    }
}

fun buildMoments(all: List<Photo>): List<Moment> {
    val list = all.filter { !isScreenshot(it) && !isMessenger(it) }.sortedBy { it.dateTaken }
    val out = ArrayList<Moment>()
    var cur = ArrayList<Photo>()
    for (p in list) {
        if (cur.isNotEmpty() && p.dateTaken - cur.last().dateTaken > GAP_MS) {
            if (cur.size >= MIN_MOMENT) out += Moment(cur)
            cur = ArrayList()
        }
        cur += p
    }
    if (cur.size >= MIN_MOMENT) out += Moment(cur)
    return out.reversed()
}

/** Fotos von genau diesem Tag in früheren Jahren: (vor X Jahren) → Fotos */
fun onThisDay(all: List<Photo>): List<Pair<Int, List<Photo>>> {
    val now = Calendar.getInstance()
    val m = now.get(Calendar.MONTH)
    val d = now.get(Calendar.DAY_OF_MONTH)
    val y = now.get(Calendar.YEAR)
    val cal = Calendar.getInstance()
    return all.filter { !isScreenshot(it) && !isMessenger(it) }
        .mapNotNull { p ->
            cal.timeInMillis = p.dateTaken
            if (cal.get(Calendar.MONTH) == m && cal.get(Calendar.DAY_OF_MONTH) == d && cal.get(Calendar.YEAR) < y)
                (y - cal.get(Calendar.YEAR)) to p else null
        }
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedBy { it.first }
        .map { (k, v) -> k to v.sortedBy { it.dateTaken } }
}

/**
 * Wählt die besten Fotos für ein Kurzvideo: keine unscharfen/dunklen,
 * keine fast gleichen hintereinander, Favoriten bevorzugt, über die Zeit verteilt.
 */
fun pickBest(photos: List<Photo>, stats: Map<Long, Stats>, max: Int): List<Photo> {
    fun score(p: Photo) = (if (p.isFavorite) 10_000f else 0f) +
        (stats[p.id]?.sharpness ?: 100f).coerceAtMost(2000f)

    val sorted = photos.sortedBy { it.dateTaken }
    var c = sorted.filter { p ->
        val s = stats[p.id]
        p.isFavorite || s == null || (s.sharpness >= 50f && s.brightness >= DARK_LIMIT)
    }
    if (c.isEmpty()) c = sorted

    val dedup = ArrayList<Photo>()
    for (p in c) {
        val last = dedup.lastOrNull()
        val h1 = stats[p.id]?.dHash
        val h2 = last?.let { stats[it.id]?.dHash }
        if (last != null && h1 != null && h2 != null && java.lang.Long.bitCount(h1 xor h2) <= 8) {
            if (score(p) > score(last)) dedup[dedup.lastIndex] = p
        } else {
            dedup += p
        }
    }
    if (dedup.size <= max) return dedup

    val n = dedup.size
    val res = ArrayList<Photo>()
    for (b in 0 until max) {
        val from = b * n / max
        val to = maxOf((b + 1) * n / max, from + 1)
        res += dedup.subList(from, to).maxBy { score(it) }
    }
    return res.distinctBy { it.id }
}

private val dayLong = SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMANY)
private val dayShort = SimpleDateFormat("d. MMM", Locale.GERMANY)
private val dayShortYear = SimpleDateFormat("d. MMM yyyy", Locale.GERMANY)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)

private fun sameDay(a: Long, b: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = a }
    val c2 = Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

fun Moment.title(): String =
    if (sameDay(start, end)) dayLong.format(start)
    else "${dayShort.format(start)} – ${dayShortYear.format(end)}"

fun Moment.subtitle(): String {
    val time = if (sameDay(start, end)) "${timeFmt.format(start)} – ${timeFmt.format(end)} Uhr · " else ""
    val album = photos.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key
    val albumText = album?.takeUnless { it.equals("Camera", true) || it.equals("Kamera", true) }
        ?.let { " · $it" } ?: ""
    return "$time${photos.size} Fotos$albumText"
}

private fun yearOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

enum class MomentFilter(val label: String) { ALL("Alle"), SPECIAL("Besondere"), FAVORITES("Favoriten") }

private const val ID_ON_THIS_DAY = -1L
private const val ID_FAVORITES = -2L

// ---------- Oberfläche ----------

@Composable
fun MomentsTab(vm: MainViewModel, onPreview: (Photo) -> Unit) {
    val moments = remember(vm.photos) { buildMoments(vm.photos) }
    val memories = remember(vm.photos) { onThisDay(vm.photos) }
    val favorites = remember(vm.photos) { vm.photos.filter { it.isFavorite }.sortedBy { it.dateTaken } }
    var filter by rememberSaveable { mutableStateOf(MomentFilter.ALL) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var videoFor by remember { mutableStateOf<List<Photo>?>(null) }

    BackHandler(enabled = openId != null) { openId = null }

    val id = openId
    if (id != null) {
        val (title, sub, photos) = when (id) {
            ID_ON_THIS_DAY -> Triple("An diesem Tag", "Erinnerungen aus früheren Jahren", memories.flatMap { it.second })
            ID_FAVORITES -> Triple("Favoriten", "${favorites.size} Lieblingsfotos", favorites)
            else -> moments.firstOrNull { it.id == id }?.let { Triple(it.title(), it.subtitle(), it.photos) }
                ?: Triple("", "", emptyList())
        }
        if (photos.isEmpty()) {
            openId = null
        } else {
            MomentDetail(title, sub, photos, vm, onBack = { openId = null }, onPreview = onPreview) {
                videoFor = it
            }
        }
    } else {
        val shown = when (filter) {
            MomentFilter.SPECIAL -> moments.filter { it.special }
            else -> moments
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Deine Momente", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${moments.size} Ereignisse · ${moments.count { it.special }} besondere · " +
                                "${favorites.size} Favoriten",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MomentFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = {
                                        if (f == MomentFilter.FAVORITES) {
                                            if (favorites.isNotEmpty()) openId = ID_FAVORITES
                                            else vm.lastMessage = "Noch keine Favoriten – lange auf ein Foto drücken und ♥ antippen"
                                        } else filter = f
                                    },
                                    label = { Text(f.label) }
                                )
                            }
                        }
                    }
                }
            }
            if (memories.isNotEmpty()) {
                item(key = "memories") {
                    MemoriesCard(
                        memories,
                        onOpen = { openId = ID_ON_THIS_DAY },
                        onVideo = { videoFor = pickBest(memories.flatMap { it.second }, vm.stats, MAX_VIDEO_PHOTOS) }
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (filter == MomentFilter.SPECIAL) "Noch keine besonderen Momente erkannt."
                        else "Noch keine Momente – ab 3 Fotos innerhalb weniger Stunden entsteht ein Moment.",
                        Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            var lastYear = -1
            shown.forEach { m ->
                val y = yearOf(m.end)
                if (y != lastYear) {
                    lastYear = y
                    val count = shown.count { yearOf(it.end) == y }
                    item(key = "y_$y") {
                        Text(
                            "$y · $count Momente",
                            Modifier.padding(top = 8.dp, start = 4.dp),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                item(key = m.id) {
                    MomentCard(
                        m,
                        onOpen = { openId = m.id },
                        onVideo = { videoFor = pickBest(m.photos, vm.stats, MAX_VIDEO_PHOTOS) }
                    )
                }
            }
        }
    }

    videoFor?.let { list ->
        VideoOptionsDialog(
            count = list.size,
            onDismiss = { videoFor = null },
            onCreate = { opts ->
                videoFor = null
                vm.makeVideo(list, opts)
            }
        )
    }
    VideoProgressAndResult(vm)
}

@Composable
private fun Collage(m: Moment) {
    val c = m.cover
    Row(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        AsyncImage(
            model = c[0].uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.weight(2f).fillMaxSize()
        )
        if (c.size > 1) {
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                c.drop(1).forEach { p ->
                    AsyncImage(
                        model = p.uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun MomentCard(m: Moment, onOpen: () -> Unit, onVideo: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Box {
                Collage(m)
                if (m.special) {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFFF2B84B), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("Besonderer Moment", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(m.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.subtitle() + " · " + formatSize(m.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Ansehen") }
                FilledTonalButton(onClick = onVideo) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kurzvideo")
                }
            }
        }
    }
}

@Composable
private fun MemoriesCard(memories: List<Pair<Int, List<Photo>>>, onOpen: () -> Unit, onVideo: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("An diesem Tag", style = MaterialTheme.typography.titleMedium)
            Text(
                memories.joinToString(" · ") { (years, list) ->
                    "vor $years ${if (years == 1) "Jahr" else "Jahren"} (${list.size})"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                memories.flatMap { it.second }.take(12).forEach { p ->
                    AsyncImage(
                        model = p.uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Ansehen") }
                FilledTonalButton(onClick = onVideo) { Text("Erinnerungs-Video") }
            }
        }
    }
}

@Composable
private fun MomentDetail(
    title: String,
    subtitle: String,
    photos: List<Photo>,
    vm: MainViewModel,
    onBack: () -> Unit,
    onPreview: (Photo) -> Unit,
    onVideo: (List<Photo>) -> Unit,
) {
    val best = remember(photos, vm.stats) { pickBest(photos, vm.stats, MAX_VIDEO_PHOTOS).map { it.id }.toSet() }
    var chosen by remember(photos) { mutableStateOf(best) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Häkchen = kommt ins Kurzvideo. Die besten Fotos sind schon ausgewählt. Lange drücken = groß ansehen.",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { chosen = best }) { Text("Beste") }
                    TextButton(onClick = { chosen = photos.take(MAX_VIDEO_PHOTOS).map { it.id }.toSet() }) { Text("Alle") }
                    TextButton(onClick = { chosen = emptySet() }) { Text("Keine") }
                }
                Button(
                    onClick = { onVideo(photos.filter { it.id in chosen }) },
                    enabled = chosen.size >= 2,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Kurzvideo erstellen (${chosen.size} Fotos)")
                }
            }
        }
        gridItems(photos, key = { it.id }) { p ->
            val on = p.id in chosen
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(shape)
                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .combinedClickable(
                        onClick = {
                            chosen = when {
                                on -> chosen - p.id
                                chosen.size >= MAX_VIDEO_PHOTOS -> {
                                    vm.lastMessage = "Maximal $MAX_VIDEO_PHOTOS Fotos pro Video"
                                    chosen
                                }
                                else -> chosen + p.id
                            }
                        },
                        onLongClick = { onPreview(p) }
                    )
            ) {
                AsyncImage(
                    model = p.uri, contentDescription = p.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (!on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                if (on) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp)
                    ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                }
                if (p.isFavorite) {
                    Icon(
                        Icons.Filled.Favorite, null, tint = Color(0xFFFF5A7A),
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoOptionsDialog(count: Int, onDismiss: () -> Unit, onCreate: (VideoOptions) -> Unit) {
    var opts by remember { mutableStateOf(VideoOptions()) }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) opts = opts.copy(music = uri)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kurzvideo erstellen") },
        text = {
            Column {
                Text(
                    "$count Fotos · ca. ${(count * opts.secondsPerPhoto).toInt()} Sekunden",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(12.dp))
                Text("Sekunden pro Foto", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Schnell" to 1.5f, "Normal" to 2.5f, "Ruhig" to 4f).forEach { (l, v) ->
                        FilterChip(
                            selected = opts.secondsPerPhoto == v,
                            onClick = { opts = opts.copy(secondsPerPhoto = v) },
                            label = { Text(l) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoFormat.entries.forEach { f ->
                        FilterChip(
                            selected = opts.format == f,
                            onClick = { opts = opts.copy(format = f) },
                            label = { Text(f.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom-Effekt", Modifier.weight(1f))
                    Switch(checked = opts.zoom, onCheckedChange = { opts = opts.copy(zoom = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (opts.music != null) "Musik ausgewählt ✓" else "Musik (optional)",
                        Modifier.weight(1f)
                    )
                    if (opts.music != null) {
                        TextButton(onClick = { opts = opts.copy(music = null) }) { Text("Entfernen") }
                    } else {
                        TextButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }) { Text("Wählen") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(opts) }) { Text("Erstellen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
fun VideoProgressAndResult(vm: MainViewModel) {
    val ctx = LocalContext.current
    vm.videoProgress?.let { p ->
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kurzvideo wird erstellt…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("$p %")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.cancelVideo() }) { Text("Abbrechen") }
                }
            }
        }
    }
    vm.videoResult?.let { uri ->
        AlertDialog(
            onDismissRequest = { vm.videoResult = null },
            title = { Text("Kurzvideo fertig 🎬") },
            text = { Text("Gespeichert in deiner Galerie unter Filme › Foto-Aufräumer.") },
            confirmButton = {
                Button(onClick = {
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(i) }
                }) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Ansehen")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Video teilen")) }
                    }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Teilen")
                    }
                    TextButton(onClick = { vm.videoResult = null }) { Text("Schließen") }
                }
            }
        )
    }
}
