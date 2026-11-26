#!/usr/bin/env python3
"""Ajoute uniquement la traduction FR/EN au module Android app de PEMESA."""
from pathlib import Path
import json
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
P = ROOT / 'app/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer'
NAMES = ['MainActivity.kt', 'ValidationActivity.kt', 'SplashActivity.kt', 'WearableCommunicator.kt']

TRANSLATIONS = {
    'Selected language:': 'Langue sélectionnée :', 'Select Language': 'Choisir la langue',
    'Confirm': 'Confirmer', 'Cancel': 'Annuler', 'Smartwatch': 'Montre connectée',
    'Settings': 'Paramètres', 'About': 'À propos', 'Exit': 'Quitter',
    'Connected Watches': 'Montres connectées', 'No connected watch': 'Aucune montre connectée',
    'No connected watches': 'Aucune montre connectée', 'Select a profession': 'Choisir un métier',
    'Refresh': 'Actualiser', 'RN - Registered Nurse': 'RN - Infirmier / Infirmière',
    'AS - Certified Nursing Assistant': 'AS - Aide-soignant / Aide-soignante',
    'ASH - Environmental Services worker': 'ASH - Agent des services hospitaliers',
    'Measure temperature': 'Prendre la température', 'Inject medication': 'Injecter un médicament',
    'Apply dressing': 'Poser un pansement', 'Take blood pressure': 'Prendre la tension artérielle',
    **{f'Activity {i}': f'Activité {i}' for i in range(1, 7)},
    'Bed-to-bed transfer': 'Transfert lit à lit', 'Bed-to-chair transfer': 'Transfert lit à fauteuil',
    'Toilet assistance': 'Aide à la toilette', 'Bed making': 'Réfection du lit',
    'Office work': 'Travail de bureau', 'Climbing the stairs': 'Monter les escaliers',
    'Room cleaning': 'Nettoyage de la chambre', 'Floor cleaning': 'Nettoyage du sol',
    'Meal distribution': 'Distribution des repas', 'Medication storage': 'Rangement des médicaments',
    'Connection status': 'Statut de la connexion', 'Start Recording': 'Démarrer l’enregistrement',
    'Stop Recording': 'Arrêter l’enregistrement', 'Unlabeled activity': 'Activité non étiquetée',
    '🚫 Unlabeled activity': '🚫 Activité non étiquetée', '🛑 Unlabeled activity': '🛑 Activité non étiquetée',
    'Fatigue Level:': 'Niveau de fatigue :', 'Message received:': 'Message reçu :',
    'Watch connection lost. Stopping recording.': 'Connexion à la montre perdue. Arrêt de l’enregistrement.',
    '⏸ Pause': '⏸ Pause', '▶ Resume': '▶ Reprendre', 'Recording stopped': 'Enregistrement arrêté',
    'Recording started': 'Enregistrement démarré', 'Watch not connected.': 'Montre non connectée.',
    'Please start recording before setting the Activity.': 'Démarrez l’enregistrement avant de définir l’activité.',
    'No recording in progress.': 'Aucun enregistrement en cours.', 'Recording paused': 'Enregistrement mis en pause',
    'Recording resumed': 'Enregistrement repris',
    'Start recording before choosing a Activity.': 'Démarrez l’enregistrement avant de choisir une activité.',
    '❌ Watch not connected.': '❌ Montre non connectée.',
    '✅ Watch connected and app open.': '✅ Montre connectée et application ouverte.',
    '⚠️ Watch connected but app closed.': '⚠️ Montre connectée mais application fermée.',
    'Current Activity:': 'Activité actuelle :', 'Page refreshed': 'Page actualisée',
    'Towards healthier workplaces': 'Pour des lieux de travail plus sains',
    'No watch connected': 'Aucune montre connectée', 'Failed to send message': 'Échec de l’envoi du message',
}

def replace(s, old, new):
    if old not in s:
        raise ValueError(f'Version du code différente : motif introuvable {old!r}')
    return s.replace(old, new)


def main():
    originals = {n: (P/n).read_text(encoding='utf-8-sig') for n in NAMES}
    if (P/'AppLanguage.kt').exists():
        raise ValueError('AppLanguage.kt existe déjà. Aucune modification effectuée.')
    m, v, s, w = (originals[n] for n in NAMES)
    m = replace(m, 'private var currentLanguage by mutableStateOf("English")',
                'private var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)\n\n    private fun tr(text: String) = AppLanguage.translate(currentLanguage, text)')
    m = replace(m, 'super.onCreate(savedInstanceState)\n        window',
                'super.onCreate(savedInstanceState)\n        currentLanguage = AppLanguage.get(this)\n        window')
    m = replace(m, 'currentLanguage = lang\n                                Toast.makeText(this, "Selected language: $lang", Toast.LENGTH_SHORT).show()',
                'AppLanguage.set(this, lang)\n                                currentLanguage = lang\n                                Toast.makeText(this, tr("Selected language:") + " " + AppLanguage.languageName(lang), Toast.LENGTH_SHORT).show()')
    m = replace(m, 'MainPage(\n                        onMenuItemClick', 'MainPage(\n                        language = currentLanguage,\n                        onMenuItemClick')
    m = replace(m, '.setTitle("About")', '.setTitle(tr("About"))')
    about = r'''if (currentLanguage == AppLanguage.FRENCH) {
                    "Développé par Djalal Eddine Bouchekif, doctorant au laboratoire IRIT (Institut de recherche en informatique de Toulouse).\n\n" +
                        "Sous la direction de M. Rémi Bastide et de Mme Imen Magdiche.\n\n" +
                        "Ce travail s’inscrit dans le cadre de ma thèse de doctorat en cours à l’école d’ingénieurs ISIS, réalisée dans le cadre du projet PEMESA, cofinancé par la Région Occitanie en France.\n\n" +
                        "Contexte du projet PEMESA :\n" +
                        "Le projet vise à étudier la pénibilité au travail, notamment dans les professions de santé, en exploitant les technologies de l’Internet des objets (IoT), l’intelligence artificielle et l’analyse de données.\n\n" +
                        "Il repose sur la collecte de données inertielles (accéléromètre et gyroscope) pour identifier les activités physiques et sur le suivi de la fréquence cardiaque à l’aide de montres connectées, complétés par les perceptions subjectives des travailleurs recueillies par questionnaire.\n\n" +
                        "L’objectif principal est d’identifier les facteurs clés contribuant à la pénibilité au travail afin d’améliorer les conditions de travail dans le secteur médical."
                } else '''
    m = replace(m, '                "Developed by', '                ' + about + '"Developed by')
    m = replace(m, '.setTitle("Connected Watches")', '.setTitle(tr("Connected Watches"))')
    m = replace(m, 'Toast.makeText(this@MainActivity, "No connected watch",', 'Toast.makeText(this@MainActivity, tr("No connected watch"),')
    m = replace(m, 'val languages = listOf("English", "French")', 'val languages = listOf(AppLanguage.ENGLISH, AppLanguage.FRENCH)\n    fun tr(text: String) = AppLanguage.translate(currentLanguage, text)')
    for text in ['Select Language', 'Confirm', 'Cancel']:
        m = replace(m, f'Text("{text}")', f'Text(tr("{text}"))')
    m = replace(m, 'Text(text = lang)', 'Text(text = AppLanguage.languageName(lang))')
    m = replace(m, 'fun MainPage(onMenuItemClick: (String) -> Unit) {', 'fun MainPage(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {\n    fun tr(text: String) = AppLanguage.translate(language, text)')
    m = replace(m, 'SimpleTopAppBar(onMenuItemClick)', 'SimpleTopAppBar(onMenuItemClick, language)')
    m = replace(m, 'label = "Select a profession",', 'label = tr("Select a profession"),')
    m = replace(m, 'onOptionSelected = { professionFull = it }', 'onOptionSelected = { professionFull = it },\n                displayOption = { tr(it) }')
    for text in ['Confirm', 'Refresh']:
        m = replace(m, f'Text("{text}", fontSize', f'Text(tr("{text}"), fontSize')
    m = replace(m, 'onOptionSelected: (String) -> Unit\n)', 'onOptionSelected: (String) -> Unit,\n    displayOption: (String) -> String = { it }\n)')
    m = replace(m, 'value = selectedOption,', 'value = displayOption(selectedOption),')
    m = replace(m, 'text = { Text(option) }', 'text = { Text(displayOption(option)) }')
    m = replace(m, 'fun SimpleTopAppBar(onMenuItemClick: (String) -> Unit) {', 'fun SimpleTopAppBar(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {\n    fun tr(text: String) = AppLanguage.translate(language, text)')
    for text in ['Smartwatch', 'Settings', 'About', 'Exit']:
        m = replace(m, f'Text("{text}")', f'Text(tr("{text}"))')
        m = replace(m, f'contentDescription = "{text}"', f'contentDescription = tr("{text}")')
    # Translate displayed labels only, leaving message payloads and canonical activity values untouched.
    v = replace(v, 'setContentView(binding.root)', '''setContentView(binding.root)

        binding.deviceconnectionStatusTv.text = localized("Connection status")
        binding.startRecordingButton.text = localized("Start Recording")
        binding.stopRecordingButton.text = localized("Stop Recording")
        binding.pauseRecordingButton.text = localized("⏸ Pause")
        binding.toggleActivityButton.text = localized("🚫 Unlabeled activity")
        binding.TirednessValueTextView.text = localized("Fatigue Level:") + " " + binding.TirednessSeekBar.progress''')
    for text in ['Watch connection lost. Stopping recording.', 'Recording stopped', 'Recording started', 'Watch not connected.', 'Please start recording before setting the Activity.', 'No recording in progress.', 'Recording paused', 'Recording resumed', 'Connected Watches', 'No connected watches', 'Start recording before choosing a Activity.', '❌ Watch not connected.', '✅ Watch connected and app open.', '⚠️ Watch connected but app closed.', 'Page refreshed', '⏸ Pause', '▶ Resume']:
        pattern = r'(?<!localized\()' + re.escape('"' + text + '"')
        v, count = re.subn(pattern, lambda _: f'localized("{text}")', v)
        if count == 0:
            raise ValueError(f'Motif introuvable : {text}')
    v = replace(v, '"Message received: $message"', 'localized("Message received:") + " " + message')
    v = replace(v, '"Fatigue Level: $progress"', 'localized("Fatigue Level:") + " " + progress')
    v = replace(v, r'"\uD83D\uDED1 Unlabeled activity"', 'localized("🛑 Unlabeled activity")')
    v = replace(v, 'button.text = ActivitysFromIntent[index]', 'button.text = localized(ActivitysFromIntent[index])')
    v = replace(v, 'button.text == selectedActivity', 'button.text == localized(selectedActivity)')
    v = replace(v, '"Current Activity: $selectedActivity"', 'localized("Current Activity:") + " " + localized(selectedActivity)')
    v = replace(v, 'button.text = localized(ActivitysFromIntent[index])\n            }', 'button.text = localized(ActivitysFromIntent[index])\n            } else {\n                button.text = localized("Activity ${index + 1}")\n            }')
    s = replace(s, 'import androidx.compose.ui.Modifier\n', 'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n')
    s = replace(s, 'val fullText = "Towards healthier workplaces"', 'val fullText = LocalContext.current.localized("Towards healthier workplaces")')
    w = replace(w, 'Toast.makeText(context, "Aucune montre connectée",', 'Toast.makeText(context, context.localized("No watch connected"),')
    w = replace(w, '''Toast.makeText(context, "Échec de l'envoi du message",''', 'Toast.makeText(context, context.localized("Failed to send message"),')
    helper = '''package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context

// UI language only; protocol and recorded data retain their existing values.
internal object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "language"
    const val ENGLISH = "English"
    const val FRENCH = "French"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ENGLISH) ?: ENGLISH

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).apply()
    }

    fun languageName(language: String): String =
        if (language == FRENCH) "Français" else "English"

    fun translate(language: String, english: String): String =
        if (language == FRENCH) french[english] ?: english else english

    private val french = mapOf(
'''
    helper += ',\n'.join('        ' + json.dumps(a, ensure_ascii=False) + ' to ' + json.dumps(b, ensure_ascii=False) for a, b in TRANSLATIONS.items())
    helper += '''
    )
}

internal fun Context.localized(english: String): String =
    AppLanguage.translate(AppLanguage.get(this), english)
'''
    modified = dict(zip(NAMES, [m, v, s, w]))
    for n, new in modified.items():
        for line in originals[n].splitlines():
            if re.search(r'communicator\.sendMessage|putExtra\(|val messageToSend|private val .*PAYLOAD|private val .*PATH|selectedActivity = "', line):
                if line.strip() not in new:
                    raise ValueError('Protection du protocole : ' + line)
    # No writes until every source anchor and protocol guard has passed.
    modified['AppLanguage.kt'] = helper
    for name, content in modified.items():
        path = P/name
        if path.exists():
            raw = path.read_bytes()
            if b'\r\n' in raw:
                content = content.replace('\n', '\r\n')
            encoding = 'utf-8-sig' if raw.startswith(b'\xef\xbb\xbf') else 'utf-8'
        else:
            encoding = 'utf-8'
        path.write_bytes(content.encode(encoding))
        print('Modifié : ' + name)
    print('Terminé. Seuls cinq fichiers Kotlin du module app ont été modifiés ou créés. Vérifier la compilation Android Studio.')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as exc:
        print('Erreur : ' + str(exc), file=sys.stderr)
        sys.exit(1)
