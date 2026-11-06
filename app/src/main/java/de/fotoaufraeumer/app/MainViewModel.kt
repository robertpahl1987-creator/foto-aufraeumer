package de.fotoaufraeumer.app

import android.app.Application
import android.content.ContentResolver
import android.content.IntentSender
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SortMode(val label: String) {
    NEWEST("Neueste"),
    OLDEST("Älteste"),
    LARGEST("Größte"),
    ALBUM("Alben"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cr: ContentResolver = app.contentResolver

    var photos by mutableStateOf<List<Photo>>(emptyList()); private set
    var exactGroups by mutableStateOf<List<List<Photo>>>(emptyList()); private set
    var similarGroups by mutableStateOf<List<List<Photo>>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var scanning by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    var scannedOnce by mutableStateOf(false); private set
    var threshold by mutableIntStateOf(6); private set
    var sortMode by mutableStateOf(SortMode.NEWEST)
    var lastMessage by mutableStateOf<String?>(null)

    private val dHashes = HashMap<Long, Long>()
    private var pendingTrash = true
    private var pendingCount = 0
    private var pendingBytes = 0L

    val selectedBytes: Long get() = photos.filter { it.id in selected }.sumOf { it.size }

    // ---------- Scannen ----------

    fun loadAndScan() {
        if (scanning) return
        scanning = true
        progress = 0f
        status = "Galerie wird gelesen…"
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { MediaRepo.loadPhotos(cr) }
                photos = list
                val ids = list.mapTo(HashSet()) { it.id }
                selected = selected.filterTo(HashSet()) { it in ids }

                val exact = withContext(Dispatchers.IO) { findExact(list) }
                exactGroups = exact

                withContext(Dispatchers.IO) { computeHashes(list) }
                status = "Gruppiere ähnliche Fotos…"
                val t = threshold
                similarGroups = withContext(Dispatchers.Default) { groupSimilar(list, exact, t) }
                scannedOnce = true
            } catch (e: Exception) {
                lastMessage = "Fehler beim Scannen: ${e.message}"
            } finally {
                status = ""
                scanning = false
            }
        }
    }

    private suspend fun report(p: Float, s: String) = withContext(Dispatchers.Main) {
        progress = p
        status = s
    }

    private suspend fun findExact(list: List<Photo>): List<List<Photo>> {
        // Nur Dateien mit gleicher Größe können identisch sein → spart viel Zeit
        val candidates = list.groupBy { it.size }
            .filter { it.key > 0 && it.value.size > 1 }
            .values.flatten()
        val keyed = ArrayList<Pair<String, Photo>>()
        val total = candidates.size.coerceAtLeast(1)
        candidates.forEachIndexed { i, p ->
            Scanner.md5(cr, p.uri)?.let { keyed += "${p.size}_$it" to p }
            if (i % 5 == 0) report(0.3f * i / total, "Suche exakte Duplikate… ${i + 1} / ${candidates.size}")
        }
        return keyed.groupBy({ it.first }, { it.second }).values
            .filter { it.size > 1 }
            .map { g -> g.sortedWith(compareBy<Photo>({ it.dateTaken }, { it.name.length })) }
            .sortedByDescending { g -> g.drop(1).sumOf { it.size } }
    }

    private suspend fun computeHashes(list: List<Photo>) {
        val todo = list.filter { it.id !in dHashes }
        val total = todo.size.coerceAtLeast(1)
        todo.forEachIndexed { i, p ->
            Scanner.dHash(cr, p.uri)?.let { dHashes[p.id] = it }
            if (i % 10 == 0) report(0.3f + 0.7f * i / total, "Vergleiche Bildinhalte… ${i + 1} / ${todo.size}")
        }
    }

    private fun groupSimilar(list: List<Photo>, exact: List<List<Photo>>, t: Int): List<List<Photo>> {
        val exactKey = HashMap<Long, Int>()
        exact.forEachIndexed { gi, g -> g.forEach { exactKey[it.id] = gi } }

        // Einfarbige Bilder (z. B. schwarze Screenshots) ausschließen – die sähen sonst alle "gleich" aus
        val items = list.mapNotNull { p ->
            val h = dHashes[p.id] ?: return@mapNotNull null
            if (java.lang.Long.bitCount(h) in 4..60) p to h else null
        }.sortedBy { it.first.dateTaken }

        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        val window = if (n <= 8000) n else 500
        for (i in 0 until n) {
            val hi = minOf(n, i + window)
            val hash = items[i].second
            for (j in i + 1 until hi) {
                if (java.lang.Long.bitCount(hash xor items[j].second) <= t) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }

        val groups = HashMap<Int, MutableList<Photo>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(items[i].first)

        return groups.values
            .filter { it.size > 1 }
            // Gruppen, die nur aus exakten Duplikaten bestehen, stehen schon im ersten Tab
            .filterNot { g ->
                val k = exactKey[g[0].id]
                k != null && g.all { exactKey[it.id] == k }
            }
            // Bestes Foto (höchste Auflösung, dann größte Datei) zuerst
            .map { g ->
                g.sortedWith(
                    compareByDescending<Photo> { it.width.toLong() * it.height }.thenByDescending { it.size }
                )
            }
            .sortedByDescending { g -> g.maxOf { it.dateTaken } }
    }

    fun changeThreshold(t: Int) {
        if (t == threshold) return
        threshold = t
        val list = photos
        val exact = exactGroups
        viewModelScope.launch {
            similarGroups = withContext(Dispatchers.Default) { groupSimilar(list, exact, t) }
        }
    }

    // ---------- Auswahl ----------

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll(ids: Collection<Long>) {
        selected = selected + ids
    }

    fun deselectAll(ids: Collection<Long>) {
        selected = selected - ids.toSet()
    }

    /** Wählt in jeder Gruppe alles außer dem ersten (besten) Foto aus. */
    fun autoSelect(groups: List<List<Photo>>) {
        selected = selected + groups.flatMap { g -> g.drop(1).map { it.id } }
    }

    fun clearSelection() {
        selected = emptySet()
    }

    // ---------- Löschen ----------

    fun buildDeleteRequest(trash: Boolean): IntentSender? {
        val chosen = photos.filter { it.id in selected }
        if (chosen.isEmpty()) return null
        pendingTrash = trash
        pendingCount = chosen.size
        pendingBytes = chosen.sumOf { it.size }
        val uris = chosen.map { it.uri }
        return try {
            if (trash) MediaStore.createTrashRequest(cr, uris, true).intentSender
            else MediaStore.createDeleteRequest(cr, uris).intentSender
        } catch (e: Exception) {
            lastMessage = "Löschen nicht möglich: ${e.message}"
            null
        }
    }

    fun onDeleteConfirmed() {
        val gone = selected
        photos = photos.filterNot { it.id in gone }
        exactGroups = exactGroups.map { g -> g.filterNot { it.id in gone } }.filter { it.size > 1 }
        similarGroups = similarGroups.map { g -> g.filterNot { it.id in gone } }.filter { it.size > 1 }
        gone.forEach { dHashes.remove(it) }
        selected = emptySet()
        val what = if (pendingTrash) "in den Papierkorb verschoben" else "gelöscht"
        lastMessage = "$pendingCount Fotos $what · ${formatSize(pendingBytes)} frei"
    }

    // ---------- Sortierte Ansicht ----------

    fun sections(): List<Pair<String, List<Photo>>> = when (sortMode) {
        SortMode.NEWEST -> photos.sortedByDescending { it.dateTaken }.groupBy { monthTitle(it.dateTaken) }.toList()
        SortMode.OLDEST -> photos.sortedBy { it.dateTaken }.groupBy { monthTitle(it.dateTaken) }.toList()
        SortMode.LARGEST -> photos.sortedByDescending { it.size }.groupBy { sizeBucket(it.size) }.toList()
        SortMode.ALBUM -> photos.groupBy { it.album }.toList()
            .sortedByDescending { it.second.size }
            .map { (album, list) -> album to list.sortedByDescending { it.dateTaken } }
    }
}

// ---------- Hilfsfunktionen ----------

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMANY)
private val dayFormat = SimpleDateFormat("d. MMM yyyy, HH:mm", Locale.GERMANY)

fun monthTitle(ms: Long): String = monthFormat.format(Date(ms))
fun formatDate(ms: Long): String = dayFormat.format(Date(ms))

fun sizeBucket(b: Long): String = when {
    b >= 10L * 1024 * 1024 -> "Über 10 MB"
    b >= 5L * 1024 * 1024 -> "5 – 10 MB"
    b >= 1L * 1024 * 1024 -> "1 – 5 MB"
    else -> "Unter 1 MB"
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.GERMANY, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.GERMANY, "%d KB", b / 1024)
}
