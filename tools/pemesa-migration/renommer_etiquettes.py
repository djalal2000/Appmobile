#!/usr/bin/env python3
"""PEMESA : ajoute un crayon aux sept boutons d'étiquetage, sans modifier wear/."""
from pathlib import Path
import re
import sys

REL = Path('app/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer/ValidationActivity.kt')


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError(f'Motif attendu une seule fois : {old!r}')
    return source.replace(old, new, 1)


def regex_replace(source, pattern, new, expected=1):
    result, count = re.subn(pattern, lambda _: new, source)
    if count != expected:
        raise ValueError(f'Motif {pattern!r} : {count} occurrences, {expected} attendues')
    return result


def patch(source):
    if 'private fun installLabelEditor(' in source:
        raise ValueError('Le correctif est déjà présent.')
    translated = 'localized("Current Activity:")' in source
    display = 'localized(defaultActivityLabels[index])' if translated else 'defaultActivityLabels[index]'
    ui = ('if (AppLanguage.get(this) == AppLanguage.FRENCH) french else english'
          if translated else 'english')
    status = ('localized("Current Activity:") + " " + selectedActivityDisplay()'
              if translated else '"Current Activity: ${selectedActivityDisplay()}"')
    s = replace_once(source, 'import android.widget.SeekBar', '''import android.widget.SeekBar
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
import android.util.TypedValue''')
    s = replace_once(s, '    private var isRecording: Boolean = false', '''    // User-defined labels are stored per profession, independently of the UI language.
    private val unlabeledIndex = 6
    private lateinit var defaultActivityLabels: List<String>
    private val customActivityLabels = MutableList<String?>(7) { null }
    private val activityLabelPreferences by lazy {
        getSharedPreferences("activity_labels", MODE_PRIVATE)
    }
    private var isRecording: Boolean = false''')
    s = replace_once(s, '        vraiActivity = intent.getStringExtra("Activity") ?: "lifting a patient"', '''        vraiActivity = intent.getStringExtra("Activity") ?: "lifting a patient"
        initializeActivityLabels()''')
    # Keep all existing recording/communication handlers; only substitute the chosen label.
    s = regex_replace(s, r'(?m)^(\s*)selectedActivity = "Unlabeled activity"$', '                selectedActivity = activityLabel(unlabeledIndex)', 2)
    # Restore original indentation in refreshPage (both original assignments were matched).
    s = replace_once(s, 'private fun refreshPage() {\n                selectedActivity', 'private fun refreshPage() {\n        selectedActivity')
    s = regex_replace(s, r'(?m)^[ \t]*binding\.toggleActivityButton\.text = (?:"\\uD83D\\uDED1 Unlabeled activity"|localized\("🛑 Unlabeled activity"\))$', '                updateUnlabeledButton("🛑")', 2)
    s = replace_once(s, 'private fun refreshPage() {\n        selectedActivity = activityLabel(unlabeledIndex)\n                updateUnlabeledButton', 'private fun refreshPage() {\n        selectedActivity = activityLabel(unlabeledIndex)\n        updateUnlabeledButton')
    # Replace the original list/setup block, keeping the exact existing click behavior.
    start = s.index('    private fun setupActivityButtons() {')
    end = s.index('    private fun setActivityButtonsEnabled(', start)
    original_setup = s[start:end]
    handler_start = original_setup.index('            button.setOnClickListener {')
    handler = original_setup[handler_start:]
    handler = replace_once(handler, 'selectedActivity = ActivitysFromIntent[index]', 'selectedActivity = activityLabel(index)')
    new_setup = '''    private fun setupActivityButtons() {
        activityButtons().forEachIndexed { index, button ->
            button.text = activityDisplay(index)
            installLabelEditor(button, index)

''' + handler
    # Append the seventh independent editor before the setup function's closing brace.
    tail = '        }\n    }\n\n'
    if not new_setup.endswith(tail):
        raise ValueError('Fin de setupActivityButtons inattendue.')
    new_setup = new_setup[:-len(tail)] + '''        }
        updateUnlabeledButton("🚫")
        installLabelEditor(binding.toggleActivityButton, unlabeledIndex)
    }

'''
    s = s[:start] + new_setup + s[end:]
    s = regex_replace(s, r'(?m)^        ActivityButtons\.forEach \{ button ->\n            button\.setBackgroundColor\(if \(button\.text == (?:selectedActivity|localized\(selectedActivity\))\) colorSelected else colorDefault\)\n        \}', '''        ActivityButtons.forEachIndexed { index, button ->
            button.setBackgroundColor(if (activityLabel(index) == selectedActivity) colorSelected else colorDefault)
        }''')
    s = regex_replace(s, r'(?m)^        binding\.ActivityStatusTextView\.text = (?:"Current Activity: \$selectedActivity"|localized\("Current Activity:"\) \+ " " \+ localized\(selectedActivity\))$', '        binding.ActivityStatusTextView.text = ' + status)
    helper = r'''    private fun activityButtons() = listOf(
        binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
        binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6
    )

    private fun labelKey(index: Int) = "$selectedprofession:$index"

    private fun initializeActivityLabels() {
        val fromIntent = intent.getStringArrayExtra("Activities")
        defaultActivityLabels = List(6) { index ->
            fromIntent?.getOrNull(index) ?: "Activity ${index + 1}"
        } + "Unlabeled activity"
        customActivityLabels.indices.forEach { index ->
            customActivityLabels[index] = activityLabelPreferences.getString(labelKey(index), null)
        }
        selectedActivity = activityLabel(unlabeledIndex)
    }

    private fun activityLabel(index: Int): String =
        customActivityLabels[index] ?: defaultActivityLabels[index]

    private fun activityDisplay(index: Int): String =
        customActivityLabels[index] ?: __DISPLAY__

    private fun selectedActivityDisplay(): String {
        val index = defaultActivityLabels.indices.firstOrNull { activityLabel(it) == selectedActivity }
        return if (index == null) selectedActivity else activityDisplay(index)
    }

    private fun updateUnlabeledButton(prefix: String) {
        binding.toggleActivityButton.text = "$prefix ${activityDisplay(unlabeledIndex)}"
    }

    private fun labelUiText(english: String, french: String): String = __UI__

    private fun labelDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // The original button keeps its ID, listeners and outer layout parameters.
    // A separate pencil button remains usable even when the activity button is disabled.
    private fun installLabelEditor(button: Button, index: Int) {
        val parent = button.parent as LinearLayout
        val position = parent.indexOfChild(button)
        val originalParams = button.layoutParams as LinearLayout.LayoutParams
        parent.removeViewAt(position)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        parent.addView(row, position, originalParams)
        row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pencil = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val padding = labelDp(12)
            setPadding(padding, padding, padding, padding)
            val backgroundValue = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)) {
                setBackgroundResource(backgroundValue.resourceId)
            }
            contentDescription = labelUiText("Rename label", "Renommer l’étiquette") + ": " + activityDisplay(index)
        }
        row.addView(pencil, LinearLayout.LayoutParams(labelDp(48), labelDp(48)))
        pencil.setOnClickListener { showLabelEditor(index, pencil) }
    }

    private fun showLabelEditor(index: Int, pencil: ImageButton) {
        val currentDisplay = activityDisplay(index)
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(currentDisplay)
            selectAll()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(labelDp(24), labelDp(8), labelDp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(labelUiText("Rename label", "Renommer l’étiquette"))
            .setView(container)
            .setNegativeButton(labelUiText("Cancel", "Annuler"), null)
            .setPositiveButton(labelUiText("Save", "Enregistrer"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newLabel = input.text.toString().trim()
                when {
                    newLabel == currentDisplay -> dialog.dismiss()
                    newLabel.isEmpty() -> input.error = labelUiText("Enter a label", "Saisissez une étiquette")
                    newLabel.any { it == ',' || it == ':' || it == '\n' || it == '\r' } ->
                        input.error = labelUiText("Do not use commas or colons", "N’utilisez ni virgule ni deux-points")
                    defaultActivityLabels.indices.any { it != index && (activityLabel(it) == newLabel || activityDisplay(it) == newLabel) } ->
                        input.error = labelUiText("This label already exists", "Cette étiquette existe déjà")
                    else -> {
                        saveActivityLabel(index, newLabel)
                        pencil.contentDescription = labelUiText("Rename label", "Renommer l’étiquette") + ": " + activityDisplay(index)
                        dialog.dismiss()
                    }
                }
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun saveActivityLabel(index: Int, newLabel: String) {
        val wasSelected = selectedActivity == activityLabel(index)
        customActivityLabels[index] = newLabel
        activityLabelPreferences.edit().putString(labelKey(index), newLabel).apply()
        if (index == unlabeledIndex) {
            val prefix = if (binding.toggleActivityButton.text.startsWith("🛑")) "🛑" else "🚫"
            updateUnlabeledButton(prefix)
        } else {
            activityButtons()[index].text = activityDisplay(index)
        }
        if (wasSelected) {
            selectedActivity = newLabel
            // A rename is not a new recording command; only the active label is updated.
            if (isRecording && wearableDeviceConnected) {
                communicator.sendMessage("Activity: $selectedActivity")
            }
        }
        updateActivityButtonColor()
        updateActivityStatusText()
    }

'''.replace('__DISPLAY__', display).replace('__UI__', ui)
    s = replace_once(s, '    private fun setActivityButtonsEnabled(', helper + '    private fun setActivityButtonsEnabled(')
    # Existing message syntax, controls and timers must survive unchanged.
    for line in source.splitlines():
        if ('communicator.sendMessage(' in line or 'val messageToSend =' in line or
                'handler.postDelayed(' in line or 'private fun sendFatigueLevel' in line):
            if line.strip() not in s:
                raise ValueError('Modification non autorisée du protocole : ' + line)
    return s


def main():
    args = [arg for arg in sys.argv[1:] if arg != '--check']
    if len(args) > 1:
        raise ValueError('Usage : py renommer_etiquettes.py [dossier du projet] [--check]')
    root = Path(args[0]) if args else Path.cwd()
    path = root / REL
    if not path.is_file():
        raise ValueError(f'Fichier introuvable : {path}\nLancez le script depuis le dossier qui contient app/ et wear/.')
    raw = path.read_bytes()
    encoding = 'utf-8-sig' if raw.startswith(b'\xef\xbb\xbf') else 'utf-8'
    text = raw.decode(encoding).replace('\r\n', '\n')
    result = patch(text)
    if '--check' in sys.argv:
        print('Vérification des motifs réussie. Aucun fichier modifié.')
        return
    if b'\r\n' in raw:
        result = result.replace('\n', '\r\n')
    backup = path.with_name(path.name + '.avant_renommage.bak')
    if backup.exists():
        raise ValueError(f'Sauvegarde déjà présente : {backup}. Vérifiez-la avant de relancer.')
    backup.write_bytes(raw)
    path.write_bytes(result.encode(encoding))
    print('Modifié :', path)
    print('Sauvegarde :', backup)
    print('Terminé. Seul ValidationActivity.kt a été modifié ; vérifiez la compilation dans Android Studio.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as exc:
        print('Erreur :', exc, file=sys.stderr)
        sys.exit(1)
