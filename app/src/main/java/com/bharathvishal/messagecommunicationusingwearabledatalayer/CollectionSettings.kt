package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ConfigField(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
}

@Composable
internal fun ServerSettings(language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var url by remember { mutableStateOf(prefs.url) }
    var org by remember { mutableStateOf(prefs.organization) }
    var activity by remember { mutableStateOf(prefs.activity) }
    var wearable by remember { mutableStateOf(prefs.wearable) }
    var token by remember { mutableStateOf("") }
    var savedToken by remember { mutableStateOf(prefs.hasToken) }
    var custom by remember { mutableStateOf(prefs.custom) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Text(if (custom) t("Custom server", "Serveur personnalisé") else t("Original server configuration", "Configuration d’origine"),
        style = MaterialTheme.typography.titleMedium)
    Text(t("HTTPS only. Changes apply at the next Start. Token encrypted on the phone.",
        "HTTPS uniquement. Les changements s’appliquent au prochain Start. Jeton chiffré sur le téléphone."))
    ConfigField(url, { url = it }, t("Server HTTPS URL", "URL HTTPS du serveur"), !busy)
    ConfigField(org, { org = it }, t("Organization", "Organisation"), !busy)
    ConfigField(activity, { activity = it }, t("Activity bucket", "Bucket activité"), !busy)
    ConfigField(wearable, { wearable = it }, t("Wearable bucket", "Bucket montre"), !busy)
    OutlinedTextField(token, { token = it }, label = { Text("API Token") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), enabled = !busy,
        supportingText = { Text(if (savedToken) t("Leave blank to keep saved token", "Laisser vide pour conserver le jeton") else t("Write token required", "Jeton d’écriture requis")) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && validServerUrl(url.trim()) &&
        org.isNotBlank() && activity.isNotBlank() && wearable.isNotBlank() && activity.trim() != wearable.trim() && (savedToken || token.isNotBlank()),
        onClick = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { prefs.saveServer(url.trim(), org.trim(), activity.trim(), wearable.trim(), token.trim()) }
                if (ok) { custom = true; savedToken = true; token = "" }
                notice = if (ok) t("Saved. Verified from the watch at Start.", "Enregistré. Vérifié depuis la montre au Start.")
                    else t("Could not save. Re-enter the token.", "Enregistrement impossible. Ressaisissez le jeton.")
                busy = false
            }
        }) { Text(t("Save configuration", "Enregistrer la configuration")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && custom, onClick = {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { prefs.resetServer() }
            if (ok) { url = ""; org = ""; activity = "activity"; wearable = "wearable"; token = ""; savedToken = false; custom = false }
            notice = if (ok) t("Original configuration restored", "Configuration d’origine rétablie") else t("Reset failed", "Échec de la restauration")
            busy = false
        }
    }) { Text(t("Restore original configuration", "Rétablir la configuration d’origine")) }
    if (notice.isNotEmpty()) Text(notice)
}

@Composable
internal fun SensorSettings(language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var selected by remember { mutableStateOf(prefs.sensors) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val names = listOf(t("Accelerometer","Accéléromètre"), t("Gyroscope","Gyroscope"), t("Gravity","Gravité"),
        t("Linear acceleration","Accélération linéaire"), t("Rotation vector","Vecteur de rotation"),
        t("Heart rate","Fréquence cardiaque"), t("Steps","Nombre de pas"), t("Light","Luminosité"),
        t("Pressure","Pression"), t("Subjective fatigue","Fatigue subjective"))
    Text(t("Choose signals for the next session. Inertial sampling remains 50 Hz.",
        "Choisissez les signaux de la prochaine session. Les données inertielles restent à 50 Hz."))
    SENSOR_KEYS.forEachIndexed { index, key ->
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(names[index], style = MaterialTheme.typography.bodyLarge)
                    Text(if (index < 5) "50 Hz" else if (key == "fatigue") t("Manual","Manuel") else t("Sensor events","Événements du capteur"),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = key in selected, enabled = !busy, onCheckedChange = {
                    selected = if (it) selected + key else selected - key
                    notice = ""
                })
            }
        }
    }
    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && validSensorSelection(selected), onClick = {
        busy = true
        val snapshot = selected
        scope.launch {
            val ok = withContext(Dispatchers.IO) { prefs.saveSensors(snapshot) }
            notice = if (ok) t("Saved for next session","Enregistré pour la prochaine session") else t("Save failed","Échec de l’enregistrement")
            busy = false
        }
    }) { Text(t("Save sensors","Enregistrer les capteurs")) }
    TextButton(enabled = !busy, onClick = { selected = SENSOR_KEYS.toSet(); notice = "" }) { Text(t("Enable all","Tout activer")) }
    if (notice.isNotEmpty()) Text(notice)
}
