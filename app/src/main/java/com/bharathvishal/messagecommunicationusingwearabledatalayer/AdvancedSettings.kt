package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun AdvancedSettings(page: String, language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun save(action: () -> Boolean) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching(action).getOrDefault(false) }
            notice = if (ok) t("Saved", "Enregistré") else t("Operation failed. Check the settings.", "Échec. Vérifiez les paramètres.")
            busy = false
        }
    }
    when (page) {
        "acquisition" -> {
            var hz by remember { mutableStateOf(prefs.hz) }
            var interval by remember { mutableStateOf(prefs.interval) }
            Text(t("Changes apply only at the next Start. The original settings are 50 Hz and 10 seconds.",
                "Les changements s’appliquent au prochain Start. Les valeurs d’origine sont 50 Hz et 10 secondes."))
            Text(t("Sampling frequency", "Fréquence d’échantillonnage"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 100).forEach { value ->
                    FilterChip(selected = hz == value, onClick = { hz = value }, label = { Text("$value Hz") }, enabled = !busy)
                }
            }
            Text(t("Transmission interval", "Intervalle de transmission"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20).forEach { value ->
                    FilterChip(selected = interval == value, onClick = { interval = value }, label = { Text("$value s") }, enabled = !busy)
                }
            }
            Text(t("Samples per batch: ", "Échantillons par lot : ") + (hz * interval))
            Text(t("Target frequency, subject to hardware and Android scheduling.", "Fréquence cible, selon le matériel et Android."))
            Button(enabled = !busy && validAcquisition(hz, interval), modifier = Modifier.fillMaxWidth(), onClick = {
                val f = hz; val i = interval; save { prefs.saveAcquisition(f, i) }
            }) { Text(t("Save", "Enregistrer")) }
            TextButton(enabled = !busy, onClick = { hz = 50; interval = 10 }) { Text(t("Restore defaults", "Valeurs par défaut")) }
        }
        "study" -> {
            var project by remember { mutableStateOf(prefs.project) }
            var experiment by remember { mutableStateOf(prefs.experiment) }
            var site by remember { mutableStateOf(prefs.site) }
            var protocol by remember { mutableStateOf(prefs.protocol) }
            Text(t("Optional study metadata. Avoid participant names. A unique session ID is generated automatically.",
                "Métadonnées facultatives. Évitez les noms des participants. Un identifiant de session unique est généré automatiquement."))
            StudyField(project, { project = it }, t("Project", "Projet"), !busy)
            StudyField(experiment, { experiment = it }, t("Experiment", "Expérimentation"), !busy)
            StudyField(site, { site = it }, t("Study site", "Lieu d’étude"), !busy)
            StudyField(protocol, { protocol = it }, t("Protocol", "Protocole"), !busy)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val values = listOf(project, experiment, site, protocol).map { it.trim() }
                save { prefs.saveStudy(values[0], values[1], values[2], values[3]) }
            }) { Text(t("Save", "Enregistrer")) }
        }
        "reliability" -> {
            Text(t("Essential safeguards stay enabled.", "Les protections essentielles restent actives."), style = MaterialTheme.typography.titleMedium)
            listOf(
                t("Watch acknowledgement required", "Confirmation de la montre obligatoire"),
                t("Server check at Start", "Vérification serveur au Start"),
                t("Automatic connection checks", "Contrôles automatiques de connexion"),
                t("Bounded RAM queues", "Files d’attente RAM bornées"),
                t("Temporary error retries", "Nouvelles tentatives sur erreurs temporaires"),
                t("No permanent measurement storage", "Aucun stockage permanent des mesures")
            ).forEach { Text("✓  $it", modifier = Modifier.padding(6.dp)) }
            Text(t("Diagnostics provides a manual check without changing these protections.", "Diagnostics permet une vérification manuelle sans modifier ces protections."))
        }
        "alerts" -> {
            var vibration by remember { mutableStateOf(prefs.vibration) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Vibration feedback", "Retour vibratoire"))
                    Text(t("Connection loss and write failures while the recording screen is open.", "Perte de connexion et échec d’écriture lorsque l’écran de collecte est ouvert."), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vibration, enabled = !busy, onCheckedChange = { vibration = it; save { prefs.saveVibration(it) } })
            }
            Text(t("Visual warnings always remain active. Vibration follows Android system settings.",
                "Les avertissements visuels restent toujours actifs. Les vibrations suivent les réglages Android."))
        }
        "backup" -> {
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) save {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(prefs.exportSettings().toString(2).toByteArray(Charsets.UTF_8))
                    } != null
                }
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) save {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(32769)
                        var size = 0
                        while (size < buffer.size) {
                            val count = stream.read(buffer, size, buffer.size - size)
                            if (count <= 0) break
                            size += count
                        }
                        require(size <= 32768)
                        buffer.copyOf(size)
                    } ?: return@save false
                    prefs.importSettings(JSONObject(String(bytes, Charsets.UTF_8)))
                }
            }
            var confirm by remember { mutableStateOf(false) }
            Text(t("Export or restore sensor selection, acquisition settings, study metadata and vibration. Server credentials and measurements are excluded.",
                "Exporter ou restaurer les capteurs, les réglages d’acquisition, les métadonnées et les vibrations. Identifiants serveur et mesures exclus."))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { export.launch("pemesa-settings.json") }) { Text(t("Export settings", "Exporter les réglages")) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { confirm = true }) { Text(t("Import settings", "Importer les réglages")) }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(t("Replace collection settings?", "Remplacer les réglages de collecte ?")) },
                text = { Text(t("Imported values apply to the next session. Existing measurements and server credentials are untouched.", "Les valeurs importées s’appliquent à la prochaine session. Mesures et identifiants serveur inchangés.")) },
                confirmButton = { TextButton(onClick = { confirm = false; import.launch(arrayOf("application/json", "text/plain")) }) { Text(t("Continue", "Continuer")) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Cancel", "Annuler")) } })
        }
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice)
}

@Composable
private fun StudyField(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { if (it.length <= 120 && it.none(Char::isISOControl)) change(it) },
        label = { Text(label) }, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
}
