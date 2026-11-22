@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.fotoaufraeumer.app

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

enum class Extra { MENU, SUCHE, SMART, DOKUMENTE, ORTE, TAGS, VIDEOS, VERKLEINERN, PAPIERKORB, TRESOR, STATISTIK }

// ---------- Aktionen für ausgewählte Fotos ----------

class ExtraAktionen(
    private val vm: MainViewModel,
    private val ev: ExtraViewModel,
    private val sender: (IntentSender) -> Unit,
    private val zipZiel: (String) -> Unit,
) {
    var tagFuer by mutableStateOf<List<Photo>?>(null)
    var ordnerFuer by mutableStateOf<Pair<List<Photo>, String>?>(null)

    fun starte(s: IntentSender?) { s?.let(sender) }

    fun zip(items: List<Photo>) {
        if (items.isEmpty()) return
        ev.zipVormerken(items)
        zipZiel("Fotos_" + SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()) + ".zip")
    }

    fun tresor(items: List<Photo>) = ev.tresorVorbereiten(items) { sender(it) }

    /** Menüpunkte für die Auswahl-Leiste unten */
    fun auswahlAktionen(items: List<Photo>): List<Pair<String, () -> Unit>> = listOf(
        "Tag vergeben" to { tagFuer = items },
        "In Galerie-Ordner verschieben" to { ordnerFuer = items to "" },
        "Verkleinern (Speicher sparen)" to { starte(ev.verkleinernAnfrage(items)); vm.clearSelection() },
        "Als ZIP sichern (SD-Karte/PC)" to { zip(items); vm.clearSelection() },
        "In den Tresor 🔒" to { tresor(items); vm.clearSelection() },
    )
}

@Composable
fun rememberExtraAktionen(vm: MainViewModel, ev: ExtraViewModel): ExtraAktionen {
    val senderStarter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        ev.ergebnis(res.resultCode == Activity.RESULT_OK, vm)
    }
    val zipStarter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) ev.zipSchreiben(uri) else ev.zipVormerken(emptyList())
    }
    return remember(vm, ev) {
        ExtraAktionen(
            vm, ev,
            sender = { senderStarter.launch(IntentSenderRequest.Builder(it).build()) },
            zipZiel = { zipStarter.launch(it) },
        )
    }
}

/** Dialoge, Fortschritt und Meldungen der Extra-Werkzeuge */
@Composable
fun ExtraDialoge(vm: MainViewModel, ev: ExtraViewModel, ea: ExtraAktionen) {
    LaunchedEffect(ev.meldung) {
        ev.meldung?.let { vm.lastMessage = it; ev.meldung = null }
    }

    ev.arbeit?.let { (text, f) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Einen Moment …") },
            text = {
                Column {
                    Text(text)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    ea.tagFuer?.let { items ->
        var neu by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ea.tagFuer = null },
            title = { Text("${items.size} Fotos taggen") },
            text = {
                Column {
                    Column(Modifier.heightIn(max = 220.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        run {
                            ev.tags.keys.toList().forEach { tag ->
                                TextButton(onClick = {
                                    ev.tagSetzen(tag, items.map { it.id }); ea.tagFuer = null; vm.clearSelection()
                                    vm.lastMessage = "${items.size} Fotos mit „$tag“ getaggt"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.LocalOffer, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(tag, Modifier.weight(1f))
                                    Text("${ev.tags[tag]?.size ?: 0}")
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = neu, onValueChange = { neu = it }, singleLine = true,
                        placeholder = { Text("Neuer Tag, z. B. John") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = neu.isNotBlank(), onClick = {
                    val t = neu.trim()
                    ev.tagAnlegen(t); ev.tagSetzen(t, items.map { it.id }); ea.tagFuer = null; vm.clearSelection()
                    vm.lastMessage = "${items.size} Fotos mit „$t“ getaggt"
                }) { Text("Anlegen & taggen") }
            },
            dismissButton = { TextButton(onClick = { ea.tagFuer = null }) { Text("Abbrechen") } },
        )
    }

    ea.ordnerFuer?.let { (items, vorschlag) ->
        var name by remember(vorschlag) { mutableStateOf(vorschlag) }
        AlertDialog(
            onDismissRequest = { ea.ordnerFuer = null },
            title = { Text("In Galerie-Ordner verschieben") },
            text = {
                Column {
                    Text(
                        "${items.size} Fotos werden in den Ordner „Bilder/${name.ifBlank { "…" }}“ verschoben. " +
                            "Jede Galerie-App zeigt ihn dann als eigenes Album.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        placeholder = { Text("Ordnername, z. B. Aquarium") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    ea.ordnerFuer = null
                    vm.clearSelection()
                    ea.starte(ev.ordnerAnfrage(items, name))
                }) { Text("Verschieben") }
            },
            dismissButton = { TextButton(onClick = { ea.ordnerFuer = null }) { Text("Abbrechen") } },
        )
    }
}

// ---------- Rahmen für alle Extra-Seiten ----------

@Composable
fun ExtraHost(
    seite: Extra,
    vm: MainViewModel,
    ev: ExtraViewModel,
    ea: ExtraAktionen,
    onPreview: (Photo) -> Unit,
    onGehe: (Extra?) -> Unit,
) {
    val titel = when (seite) {
        Extra.MENU -> "Werkzeuge"
        Extra.SUCHE -> "Suche"
        Extra.SMART -> "Smart-Alben"
        Extra.DOKUMENTE -> "Dokumente"
        Extra.ORTE -> "Alben nach Ort"
        Extra.TAGS -> "Eigene Tags"
        Extra.VIDEOS -> "Große Videos"
        Extra.VERKLEINERN -> "Speicher sparen"
        Extra.PAPIERKORB -> "Papierkorb"
        Extra.TRESOR -> "Privater Tresor"
        Extra.STATISTIK -> "Serie & Statistik"
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { onGehe(if (seite == Extra.MENU) null else Extra.MENU) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
            }
            Text(titel, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (seite) {
                Extra.MENU -> MenuSeite(vm, ev, onGehe)
                Extra.SUCHE -> SuchSeite(vm, ev, onPreview)
                Extra.SMART -> SmartSeite(vm, ev, onPreview)
                Extra.DOKUMENTE -> DokumenteSeite(vm, ev, onPreview)
                Extra.ORTE -> OrteSeite(vm, ev, onPreview)
                Extra.TAGS -> TagsSeite(vm, ev, ea, onPreview)
                Extra.VIDEOS -> VideoSeite(ev, ea)
                Extra.VERKLEINERN -> VerkleinernSeite(vm, ev, ea, onPreview)
                Extra.PAPIERKORB -> PapierkorbSeite(ev, ea)
                Extra.TRESOR -> TresorSeite(vm, ev)
                Extra.STATISTIK -> StatistikSeite(ev)
            }
        }
    }
}

// ---------- Bausteine ----------

@Composable
private fun Hinweis(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErkennungsStatus(ev: ExtraViewModel) {
    val f = ev.erkennung ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            "Bilder werden erkannt … noch ${ev.erkennungOffen} Fotos (läuft offline im Hintergrund, solange die App offen ist)",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Foto-Raster mit Abschnitten; Auswahl über die gemeinsame Auswahl-Leiste unten. */
@Composable
private fun FotoRaster(
    abschnitte: List<Pair<String, List<Photo>>>,
    vm: MainViewModel,
    onPreview: (Photo) -> Unit,
    kopf: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        kopf?.invoke(this)
        abschnitte.forEach { (titel, list) ->
            item(key = "h_$titel", span = { GridItemSpan(maxLineSpan) }) {
                val ids = list.map { it.id }
                val alle = list.all { it.id in vm.selected }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(titel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${list.size} Fotos · ${formatSize(list.sumOf { it.size })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { if (alle) vm.deselectAll(ids) else vm.selectAll(ids) }) {
                        Text(if (alle) "Abwählen" else "Alle auswählen")
                    }
                }
            }
            gridItems(list, key = { "${titel}_${it.id}" }) { p ->
                Thumb(
                    photo = p,
                    isSelected = p.id in vm.selected,
                    isBest = false,
                    showInfo = false,
                    onClick = { vm.toggle(p.id) },
                    onLongClick = { onPreview(p) },
                )
            }
        }
    }
}

private fun LazyGridScope.kopfZeile(inhalt: @Composable () -> Unit) {
    item(key = "kopf", span = { GridItemSpan(maxLineSpan) }) { inhalt() }
}

/** Kachel für Videos, Papierkorb und Tresor (eigene Auswahl). */
@Composable
private fun MedienKachel(
    modell: Any,
    istVideo: Boolean,
    ausgewaehlt: Boolean,
    label: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val form = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(form)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (ausgewaehlt) Modifier.border(3.dp, MaterialTheme.colorScheme.error, form) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val anfrage = remember(modell, istVideo) {
            ImageRequest.Builder(ctx).data(modell).apply {
                if (istVideo) decoderFactory(VideoFrameDecoder.Factory())
            }.crossfade(true).build()
        }
        AsyncImage(model = anfrage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (ausgewaehlt) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape).padding(4.dp)
            ) { Icon(Icons.Filled.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        if (istVideo) {
            Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape).size(20.dp)
            )
        }
        if (label != null) {
            Text(
                label, color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun AktionsLeiste(inhalt: @Composable () -> Unit) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { inhalt() }
    }
}

// ---------- Serie-Karte (auch im Aufräumen-Tab) ----------

@Composable
fun SerieKarte(onClick: () -> Unit) {
    val s = Fortschritt.stand
    val (stufe, _, _) = Fortschritt.stufe(s.punkte)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.serie > 0) "🔥 ${s.serie}" else "🔥 0", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.serie == 1) "1 Tag Aufräum-Serie" else "${s.serie} Tage Aufräum-Serie",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("$stufe · ${s.punkte} Punkte", style = MaterialTheme.typography.bodySmall)
                }
                Text("${s.heute}/${Fortschritt.TAGESZIEL}", style = MaterialTheme.typography.titleSmall)
            }
            LinearProgressIndicator(
                progress = { (s.heute.toFloat() / Fortschritt.TAGESZIEL).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Werkzeuge: Suche, Smart-Alben, Orte, Tags, Tresor, Speicher sparen … ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------- Menü ----------

@Composable
private fun MenuSeite(vm: MainViewModel, ev: ExtraViewModel, onGehe: (Extra?) -> Unit) {
    val kandidaten = remember(vm.photos) { vm.photos.filter { Verkleinerer.istKandidat(it) } }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SerieKarte { onGehe(Extra.STATISTIK) } }
        item { Ueberschrift("Finden & Sortieren") }
        item { Eintrag(Icons.Filled.Search, "Suche", "Text auf Fotos, Orte, Tags, Inhalte – z. B. „Rechnung“, „Hund“") { onGehe(Extra.SUCHE) } }
        item { Eintrag(Icons.Filled.AutoAwesome, "Smart-Alben", "Tiere, Essen, Natur, Aquarium … automatisch erkannt") { onGehe(Extra.SMART) } }
        item { Eintrag(Icons.Filled.Description, "Dokumente", "Briefe, Quittungen, Formulare") { onGehe(Extra.DOKUMENTE) } }
        item { Eintrag(Icons.Filled.Place, "Alben nach Ort", "Aus den Standortdaten der Fotos") { onGehe(Extra.ORTE) } }
        item { Eintrag(Icons.Filled.LocalOffer, "Eigene Tags", "${ev.tags.size} Tags · auch als echte Galerie-Ordner") { onGehe(Extra.TAGS) } }
        item { Ueberschrift("Speicher") }
        item { Eintrag(Icons.Filled.VideoLibrary, "Große Videos", "Die größten Speicherfresser zuerst") { onGehe(Extra.VIDEOS) } }
        item {
            Eintrag(
                Icons.Filled.Compress, "Speicher sparen",
                "${kandidaten.size} große Fotos verkleinern · ca. ${formatSize(kandidaten.sumOf { it.size } / 2)} frei"
            ) { onGehe(Extra.VERKLEINERN) }
        }
        item { Eintrag(Icons.Filled.DeleteSweep, "Papierkorb", "Gelöschtes 30 Tage wiederherstellen") { onGehe(Extra.PAPIERKORB) } }
        item { Ueberschrift("Schützen & Sichern") }
        item { Eintrag(Icons.Filled.Lock, "Privater Tresor", "${ev.tresor.size} Dateien · mit PIN geschützt") { onGehe(Extra.TRESOR) } }
        item {
            Eintrag(Icons.Filled.FolderZip, "Als ZIP sichern", "Fotos auswählen → ⋮ unten → „Als ZIP sichern“ (z. B. auf SD-Karte)") {
                vm.lastMessage = "Wähle Fotos aus (z. B. im Tab „Alle“) und tippe unten auf ⋮ → „Als ZIP sichern“"
            }
        }
        item { Eintrag(Icons.Filled.EmojiEvents, "Serie, Statistik & Widget", "Punkte, Erinnerung, Startbildschirm-Widget") { onGehe(Extra.STATISTIK) } }
    }
}

@Composable
private fun Ueberschrift(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Eintrag(icon: ImageVector, titel: String, text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(titel) },
        supportingContent = { Text(text) },
    )
}

// ---------- Suche ----------

@Composable
private fun SuchSeite(vm: MainViewModel, ev: ExtraViewModel, onPreview: (Photo) -> Unit) {
    LaunchedEffect(vm.photos) { ev.erkennungStarten(vm.photos) }
    var frage by rememberSaveable { mutableStateOf("") }
    val treffer = remember(frage, vm.photos, ev.inhalte, ev.orte, ev.tags) { ev.suche(frage, vm.photos) }
    val vorschlaege = remember(ev.tags, ev.orte) {
        SMART_ALBEN.map { it.first.substringBefore(" ") } + ev.tags.keys + listOf("Dokument", "Rechnung") +
            ev.orte.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = frage, onValueChange = { frage = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (frage.isNotEmpty()) IconButton(onClick = { frage = "" }) { Icon(Icons.Filled.Close, "Leeren") } },
            placeholder = { Text("z. B. Rechnung, Hund, Berlin, Mai 2025") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            vorschlaege.distinct().forEach { v -> FilterChip(selected = frage == v, onClick = { frage = v }, label = { Text(v) }) }
        }
        ErkennungsStatus(ev)
        when {
            frage.trim().length < 2 -> Hinweis(
                "Sucht im Text auf deinen Fotos (Briefe, Schilder, Screenshots), in erkannten Inhalten, Orten, Tags, Alben und Monaten. " +
                    "Alles passiert offline auf dem Handy."
            )
            treffer.isEmpty() -> Hinweis("Nichts gefunden für „$frage“.")
            else -> FotoRaster(listOf("${treffer.size} Treffer" to treffer), vm, onPreview)
        }
    }
}

// ---------- Smart-Alben ----------

@Composable
private fun SmartSeite(vm: MainViewModel, ev: ExtraViewModel, onPreview: (Photo) -> Unit) {
    LaunchedEffect(vm.photos) { ev.erkennungStarten(vm.photos) }
    val alben = remember(vm.photos, ev.inhalte) { ev.smartAlben(vm.photos) }
    Column(Modifier.fillMaxSize()) {
        ErkennungsStatus(ev)
        if (alben.isEmpty()) Hinweis(if (ev.erkennung != null) "Die ersten Alben erscheinen gleich …" else "Noch keine Inhalte erkannt.")
        else FotoRaster(alben, vm, onPreview) {
            kopfZeile { Hinweis("Die App erkennt Bildinhalte selbst – ohne Internet. Ein Foto kann in mehreren Alben sein.") }
        }
    }
}

// ---------- Dokumente ----------

@Composable
private fun DokumenteSeite(vm: MainViewModel, ev: ExtraViewModel, onPreview: (Photo) -> Unit) {
    LaunchedEffect(vm.photos) { ev.erkennungStarten(vm.photos) }
    val doks = remember(vm.photos, ev.inhalte) { vm.photos.filter { ev.inhalte[it.id]?.dokument == true } }
    Column(Modifier.fillMaxSize()) {
        ErkennungsStatus(ev)
        if (doks.isEmpty()) Hinweis(if (ev.erkennung != null) "Suche Dokumente …" else "Keine Fotos von Dokumenten gefunden.")
        else FotoRaster(listOf("Dokumente" to doks), vm, onPreview) {
            kopfZeile { Hinweis("Tipp: Alle auswählen → ⋮ → „In Galerie-Ordner verschieben“ → „Dokumente“. Dann hast du sie gesammelt an einem Ort.") }
        }
    }
}

// ---------- Orte ----------

@Composable
private fun OrteSeite(vm: MainViewModel, ev: ExtraViewModel, onPreview: (Photo) -> Unit) {
    val ctx = LocalContext.current
    var erlaubt by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val starter = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erlaubt = it }
    LaunchedEffect(erlaubt, vm.photos) { if (erlaubt) ev.orteStarten(vm.photos) }
    val abschnitte = remember(vm.photos, ev.orte) {
        vm.photos.filter { it.id in ev.orte }.groupBy { ev.orte.getValue(it.id) }.toList()
            .sortedByDescending { it.second.size }
            .map { (o, l) -> o to l.sortedByDescending { it.dateTaken } }
    }
    Column(Modifier.fillMaxSize()) {
        if (!erlaubt) {
            Hinweis("Damit die App sieht, wo deine Fotos aufgenommen wurden, braucht sie einmal die Erlaubnis „Standort in Medien“.")
            Button(onClick = { starter.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }, modifier = Modifier.padding(12.dp)) {
                Text("Erlauben")
            }
            return@Column
        }
        ev.orteLaden?.let { f ->
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("Orte werden ermittelt …", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (abschnitte.isEmpty()) {
            if (ev.orteLaden == null) Hinweis(
                "Keine Fotos mit Standort gefunden. Tipp: In der Kamera-App „Standort speichern“ einschalten. " +
                    "Für Ortsnamen braucht das Handy Internet."
            )
        } else FotoRaster(abschnitte, vm, onPreview)
    }
}

// ---------- Tags ----------

@Composable
private fun TagsSeite(vm: MainViewModel, ev: ExtraViewModel, ea: ExtraAktionen, onPreview: (Photo) -> Unit) {
    var offen by rememberSaveable { mutableStateOf<String?>(null) }
    var neu by remember { mutableStateOf("") }
    var loeschFrage by remember { mutableStateOf<String?>(null) }
    val nachId = remember(vm.photos) { vm.photos.associateBy { it.id } }

    val tag = offen
    if (tag != null) {
        val fotos = (ev.tags[tag] ?: emptySet()).mapNotNull { nachId[it] }.sortedByDescending { it.dateTaken }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { offen = null }) { Text("‹ Alle Tags") }
                Spacer(Modifier.weight(1f))
                if (fotos.isNotEmpty()) {
                    OutlinedButton(onClick = { ea.ordnerFuer = fotos to tag }) { Text("Als Galerie-Ordner") }
                }
            }
            if (fotos.isEmpty()) Hinweis("Noch keine Fotos mit „$tag“. Wähle irgendwo Fotos aus und tippe unten auf ⋮ → „Tag vergeben“.")
            else FotoRaster(listOf(tag to fotos), vm, onPreview) {
                kopfZeile {
                    TextButton(onClick = {
                        ev.tagSetzen(tag, fotos.filter { it.id in vm.selected }.map { it.id }, an = false)
                    }) { Text("Ausgewählte aus dem Tag entfernen") }
                }
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = neu, onValueChange = { neu = it }, singleLine = true,
                    placeholder = { Text("Neuer Tag, z. B. John") }, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ev.tagAnlegen(neu); neu = "" }, enabled = neu.isNotBlank()) { Text("Anlegen") }
            }
        }
        listItems(ev.tags.keys.toList(), key = { it }) { name ->
            val fotos = (ev.tags[name] ?: emptySet()).mapNotNull { nachId[it] }
            ListItem(
                modifier = Modifier.clickable { offen = name },
                leadingContent = { Icon(Icons.Filled.LocalOffer, null) },
                headlineContent = { Text(name) },
                supportingContent = {
                    if (fotos.isEmpty()) Text("Noch leer") else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        fotos.sortedByDescending { it.dateTaken }.take(5).forEach { f ->
                            AsyncImage(
                                model = f.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                            )
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fotos.size}")
                        IconButton(onClick = { loeschFrage = name }) { Icon(Icons.Filled.Delete, "Tag löschen") }
                    }
                },
            )
        }
        item { Hinweis("So taggst du: Fotos auswählen (Tippen) → unten ⋮ → „Tag vergeben“. Ein Tag kann mit einem Tipp zum echten Galerie-Ordner werden.") }
    }

    loeschFrage?.let { name ->
        AlertDialog(
            onDismissRequest = { loeschFrage = null },
            title = { Text("Tag „$name“ löschen?") },
            text = { Text("Die Fotos selbst bleiben erhalten, nur der Tag wird entfernt.") },
            confirmButton = { TextButton(onClick = { ev.tagLoeschen(name); loeschFrage = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { loeschFrage = null }) { Text("Abbrechen") } },
        )
    }
}

// ---------- Große Videos ----------

private fun videoBerechtigt(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else true

@Composable
private fun VideoSeite(ev: ExtraViewModel, ea: ExtraAktionen) {
    val ctx = LocalContext.current
    var erlaubt by remember { mutableStateOf(videoBerechtigt(ctx)) }
    val starter = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erlaubt = videoBerechtigt(ctx) }
    LaunchedEffect(erlaubt) { if (erlaubt) ev.videosLaden() }
    var alle by rememberSaveable { mutableStateOf(false) }
    var auswahl by remember { mutableStateOf(setOf<Long>()) }

    if (!erlaubt) {
        Column {
            Hinweis("Für die Videos braucht die App einmal Zugriff auf deine Videos.")
            Button(onClick = { starter.launch(Manifest.permission.READ_MEDIA_VIDEO) }, modifier = Modifier.padding(12.dp)) {
                Text("Zugriff erlauben")
            }
        }
        return
    }
    val geladen = ev.videos
    if (geladen == null) { Hinweis("Videos werden gelesen …"); return }
    val liste = remember(geladen, alle) {
        geladen.filter { alle || it.size >= 50L * 1024 * 1024 }.sortedByDescending { it.size }
    }
    val gewaehlt = liste.filter { it.id in auswahl }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !alle, onClick = { alle = false }, label = { Text("Ab 50 MB") })
            FilterChip(selected = alle, onClick = { alle = true }, label = { Text("Alle Videos") })
        }
        Hinweis("${liste.size} Videos · ${formatSize(liste.sumOf { it.size })} · Tippen = auswählen, lange drücken = abspielen")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            gridItems(liste, key = { it.id }) { v ->
                MedienKachel(
                    modell = v.uri, istVideo = true, ausgewaehlt = v.id in auswahl,
                    label = "${formatSize(v.size)} · ${formatDauer(v.durationMs)}",
                    onClick = { auswahl = if (v.id in auswahl) auswahl - v.id else auswahl + v.id },
                    onLongClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                    },
                )
            }
        }
        if (gewaehlt.isNotEmpty()) {
            AktionsLeiste {
                Text("${gewaehlt.size} · ${formatSize(gewaehlt.sumOf { it.size })}", Modifier.weight(1f))
                TextButton(onClick = { auswahl = emptySet() }) { Text("Abwählen") }
                Button(
                    onClick = { ea.starte(ev.videosLoeschenAnfrage(gewaehlt)); auswahl = emptySet() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            }
        }
    }
}

// ---------- Speicher sparen ----------

@Composable
private fun VerkleinernSeite(vm: MainViewModel, ev: ExtraViewModel, ea: ExtraAktionen, onPreview: (Photo) -> Unit) {
    val kandidaten = remember(vm.photos) {
        vm.photos.filter { Verkleinerer.istKandidat(it) }.sortedByDescending { it.size }
    }
    val gewaehlt = kandidaten.filter { it.id in vm.selected }
    Column(Modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("${kandidaten.size} große Fotos · ${formatSize(kandidaten.sumOf { it.size })}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fotos werden auf max. 4000 Pixel und gute JPG-Qualität gebracht – auf dem Handy siehst du keinen Unterschied, " +
                        "meist wird die Hälfte frei. Datum, Ort und Drehung bleiben erhalten. Achtung: Das Original wird ersetzt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.selectAll(kandidaten.map { it.id }) }, enabled = kandidaten.isNotEmpty()) {
                        Text("Alle auswählen")
                    }
                    FilledTonalButton(
                        onClick = { ea.starte(ev.verkleinernAnfrage(gewaehlt)); vm.deselectAll(gewaehlt.map { it.id }) },
                        enabled = gewaehlt.isNotEmpty(),
                    ) { Text("${gewaehlt.size} verkleinern") }
                }
            }
        }
        if (kandidaten.isEmpty()) Hinweis("Keine großen JPG-Fotos gefunden 🎉")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(kandidaten, key = { it.id }) { p ->
                Thumb(
                    photo = p, isSelected = p.id in vm.selected, isBest = false, showInfo = true,
                    onClick = { vm.toggle(p.id) }, onLongClick = { onPreview(p) },
                )
            }
        }
    }
}

// ---------- Papierkorb ----------

@Composable
private fun PapierkorbSeite(ev: ExtraViewModel, ea: ExtraAktionen) {
    LaunchedEffect(Unit) { ev.papierkorbLaden() }
    var auswahl by remember { mutableStateOf(setOf<Long>()) }
    var frage by remember { mutableStateOf(false) }
    val liste = ev.papierkorb
    if (liste == null) { Hinweis("Papierkorb wird gelesen …"); return }
    // IDs von Fotos und Videos können gleich sein → Schlüssel mit Typ
    fun schluessel(p: Photo) = if (p.isVideo) -p.id - 1 else p.id
    val gewaehlt = liste.filter { schluessel(it) in auswahl }
    val jetzt = System.currentTimeMillis()
    Column(Modifier.fillMaxSize()) {
        Hinweis(
            if (liste.isEmpty()) "Der Papierkorb ist leer."
            else "${liste.size} Dateien · ${formatSize(liste.sumOf { it.size })}. Android löscht sie nach 30 Tagen automatisch. " +
                "„Endgültig löschen“ gibt den Speicher sofort frei."
        )
        if (liste.isNotEmpty()) {
            TextButton(onClick = {
                auswahl = if (gewaehlt.size == liste.size) emptySet() else liste.map { schluessel(it) }.toSet()
            }, modifier = Modifier.padding(horizontal = 4.dp)) { Text(if (gewaehlt.size == liste.size) "Keine" else "Alle auswählen") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            gridItems(liste, key = { schluessel(it) }) { p ->
                val k = schluessel(p)
                val tage = ((p.dateTaken - jetzt) / 86_400_000L).coerceAtLeast(0)
                MedienKachel(
                    modell = p.uri, istVideo = p.isVideo, ausgewaehlt = k in auswahl, label = "noch $tage T.",
                    onClick = { auswahl = if (k in auswahl) auswahl - k else auswahl + k },
                    onLongClick = { auswahl = if (k in auswahl) auswahl - k else auswahl + k },
                )
            }
        }
        if (gewaehlt.isNotEmpty()) {
            AktionsLeiste {
                OutlinedButton(
                    onClick = { ea.starte(ev.papierkorbAnfrage(gewaehlt, wiederherstellen = true)); auswahl = emptySet() },
                    modifier = Modifier.weight(1f),
                ) { Text("Wiederherstellen") }
                Button(
                    onClick = { frage = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("Endgültig") }
            }
        }
    }
    if (frage) {
        AlertDialog(
            onDismissRequest = { frage = false },
            title = { Text("${gewaehlt.size} Dateien endgültig löschen?") },
            text = { Text("Das kann nicht rückgängig gemacht werden. ${formatSize(gewaehlt.sumOf { it.size })} werden sofort frei.") },
            confirmButton = {
                TextButton(onClick = {
                    frage = false
                    ea.starte(ev.papierkorbAnfrage(gewaehlt, wiederherstellen = false)); auswahl = emptySet()
                }) { Text("Endgültig löschen") }
            },
            dismissButton = { TextButton(onClick = { frage = false }) { Text("Abbrechen") } },
        )
    }
}

// ---------- Tresor ----------

@Composable
private fun TresorSeite(vm: MainViewModel, ev: ExtraViewModel) {
    val ctx = LocalContext.current
    var entsperrt by remember { mutableStateOf(false) }

    // Bildschirmfotos im Tresor verhindern
    DisposableEffect(Unit) {
        val fenster = (ctx as? Activity)?.window
        fenster?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { fenster?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Beim Verlassen der App wieder sperren
    val besitzer = LocalLifecycleOwner.current
    DisposableEffect(besitzer) {
        val beobachter = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) entsperrt = false }
        besitzer.lifecycle.addObserver(beobachter)
        onDispose { besitzer.lifecycle.removeObserver(beobachter) }
    }

    if (!entsperrt) {
        PinSperre(ev) { entsperrt = true }
        return
    }

    var auswahl by remember { mutableStateOf(setOf<String>()) }
    var gross by remember { mutableStateOf<TresorEintrag?>(null) }
    var loeschFrage by remember { mutableStateOf(false) }
    val liste = ev.tresor
    val gewaehlt = liste.filter { it.datei in auswahl }

    Column(Modifier.fillMaxSize()) {
        Hinweis(
            "Hinzufügen: Fotos auswählen → unten ⋮ → „In den Tresor“. Die Dateien sind dann nur noch hier zu sehen. " +
                "Wichtig: Wird die App deinstalliert, ist der Tresor-Inhalt weg – vorher zurück in die Galerie legen."
        )
        if (liste.isEmpty()) Hinweis("Der Tresor ist leer.")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            gridItems(liste, key = { it.datei }) { e ->
                MedienKachel(
                    modell = ev.tresorDatei(e), istVideo = e.istVideo, ausgewaehlt = e.datei in auswahl, label = null,
                    onClick = { auswahl = if (e.datei in auswahl) auswahl - e.datei else auswahl + e.datei },
                    onLongClick = { gross = e },
                )
            }
        }
        if (gewaehlt.isNotEmpty()) {
            AktionsLeiste {
                OutlinedButton(
                    onClick = { ev.tresorWiederherstellen(gewaehlt, vm); auswahl = emptySet() },
                    modifier = Modifier.weight(1f),
                ) { Text("In die Galerie") }
                Button(
                    onClick = { loeschFrage = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("Löschen") }
            }
        }
    }

    gross?.let { e ->
        Dialog(onDismissRequest = { gross = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val anfrage = remember(e) {
                    ImageRequest.Builder(ctx).data(ev.tresorDatei(e)).apply {
                        if (e.istVideo) decoderFactory(VideoFrameDecoder.Factory())
                    }.build()
                }
                AsyncImage(model = anfrage, contentDescription = e.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { gross = null }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Filled.Close, "Schließen", tint = Color.White)
                }
                Text(
                    "${e.name} · ${formatDate(e.datum)} · ${formatSize(e.groesse)}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (loeschFrage) {
        AlertDialog(
            onDismissRequest = { loeschFrage = false },
            title = { Text("${gewaehlt.size} Dateien endgültig löschen?") },
            text = { Text("Sie sind dann komplett weg und lassen sich nicht wiederherstellen.") },
            confirmButton = {
                TextButton(onClick = { loeschFrage = false; ev.tresorLoeschen(gewaehlt); auswahl = emptySet() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { loeschFrage = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun PinSperre(ev: ExtraViewModel, onOffen: () -> Unit) {
    val neu = !ev.pinGesetzt
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var fehler by remember { mutableStateOf<String?>(null) }
    var versuche by remember { mutableIntStateOf(0) }
    var gesperrt by remember { mutableStateOf(false) }
    LaunchedEffect(gesperrt) {
        if (gesperrt) { delay(30_000); gesperrt = false; versuche = 0; fehler = null }
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (neu) "PIN für den Tresor festlegen" else "Tresor-PIN eingeben", style = MaterialTheme.typography.titleMedium)
        val tastatur = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
            label = { Text("PIN (4–8 Ziffern)") }, singleLine = true, enabled = !gesperrt,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = tastatur,
        )
        if (neu) {
            OutlinedTextField(
                value = pin2, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin2 = it },
                label = { Text("PIN wiederholen") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = tastatur,
            )
            Text(
                "Merk dir die PIN gut – ohne sie kommst du nicht mehr an die Tresor-Fotos.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
        fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(enabled = pin.length >= 4 && !gesperrt, onClick = {
            if (neu) {
                if (pin != pin2) fehler = "Die PINs stimmen nicht überein."
                else { ev.pinSetzen(pin); onOffen() }
            } else if (ev.pinPruefen(pin)) {
                onOffen()
            } else {
                versuche++
                pin = ""
                if (versuche >= 5) { gesperrt = true; fehler = "Zu viele Versuche – 30 Sekunden warten." }
                else fehler = "Falsche PIN."
            }
        }) { Text(if (neu) "Festlegen" else "Öffnen") }
    }
}

// ---------- Serie, Statistik, Widget, Erinnerung ----------

@Composable
private fun StatistikSeite(ev: ExtraViewModel) {
    val ctx = LocalContext.current
    val s = Fortschritt.stand
    val (stufe, anteil, fehlen) = Fortschritt.stufe(s.punkte)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔥 ${s.serie}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(if (s.serie == 1) "Tag in Folge aufgeräumt" else "Tage in Folge aufgeräumt")
                    Text("Rekord: ${s.rekord} Tage", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Heute: ${s.heute} von ${Fortschritt.TAGESZIEL} Fotos", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { (s.heute.toFloat() / Fortschritt.TAGESZIEL).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    if (s.heute >= Fortschritt.TAGESZIEL) Text("Tagesziel geschafft 🎉", Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Stufe: $stufe", style = MaterialTheme.typography.titleMedium)
                    Text("${s.punkte} Punkte" + if (fehlen > 0) " · noch $fehlen bis zur nächsten Stufe" else " · höchste Stufe!")
                    LinearProgressIndicator(progress = { anteil }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Punkte: 1 pro Wisch-Entscheidung oder Tag, 2 pro gelöschtem Foto, 3 pro verkleinertem Foto.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Bisher aufgeräumt", style = MaterialTheme.typography.titleMedium)
                    Text(formatSize(s.frei), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("${s.geloescht} Fotos & Videos gelöscht, dazu verkleinerte Fotos", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wöchentliche Erinnerung", style = MaterialTheme.typography.titleSmall)
                            Text("Hinweis, wenn sich neue Fotos angesammelt haben", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = s.erinnerung, onCheckedChange = { ev.erinnerungSetzen(it) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Startbildschirm-Widget", style = MaterialTheme.typography.titleSmall)
                    Text("Zeigt, wie viel du aufräumen kannst, und deine Serie.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = {
                        val awm = AppWidgetManager.getInstance(ctx)
                        if (awm.isRequestPinAppWidgetSupported) {
                            awm.requestPinAppWidget(ComponentName(ctx, FotoWidget::class.java), null, null)
                        } else {
                            ev.meldung = "Lange auf den Startbildschirm drücken → Widgets → Foto-Aufräumer"
                        }
                    }) { Text("Widget hinzufügen") }
                }
            }
        }
    }
}
