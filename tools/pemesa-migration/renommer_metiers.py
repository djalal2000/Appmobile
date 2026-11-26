#!/usr/bin/env python3
"""PEMESA: rename the displayed profession names; keep RN/AS/ASH and all activity mappings."""
from pathlib import Path
import re
import shutil
import sys

REL = Path('app/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer/MainActivity.kt')
MARK = '// PEMESA_PROFESSION_NAMES_V1'


def once(s, old, new):
    if s.count(old) != 1:
        raise ValueError(f'Expected exactly one occurrence of {old!r}; found {s.count(old)}.')
    return s.replace(old, new, 1)


def patch(source):
    if MARK in source:
        raise ValueError('The profession rename feature is already installed.')
    start = source.index('fun DropdownField(')
    end = source.index('\n@OptIn(ExperimentalMaterial3Api::class)', start)
    block = source[start:end]
    translated = 'displayOption: (String) -> String' in block
    original_display = 'displayOption(option)' if translated else 'option'
    original_selected = 'displayOption(selectedOption)' if translated else 'selectedOption'
    language = ('AppLanguage.get(context) == AppLanguage.FRENCH' if translated else 'false')
    if translated and 'AppLanguage' not in source:
        raise ValueError('Unknown localization implementation; no changes made.')
    state = '''    // PEMESA_PROFESSION_NAMES_V1: display names only, original options remain the keys.
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("profession_display_names", android.content.Context.MODE_PRIVATE)
    }
    fun code(option: String): String = option.substringBefore(" - ")
    val customNames = remember(preferences, options) {
        mutableStateMapOf<String, String>().apply {
            options.forEach { option ->
                preferences.getString(code(option), null)?.let { put(option, it) }
            }
        }
    }
    fun displayProfession(option: String): String = customNames[option] ?: ORIGINAL_DISPLAY
    val french = IS_FRENCH
    fun ui(en: String, fr: String): String = if (french) fr else en
    var editingOption by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
'''.replace('ORIGINAL_DISPLAY', original_display).replace('IS_FRENCH', language)
    block = once(block, '    var expanded by remember { mutableStateOf(false) }\n',
                 '    var expanded by remember { mutableStateOf(false) }\n' + state)
    block = once(block, f'value = {original_selected},', 'value = displayProfession(selectedOption),')
    block = once(block, f'text = {{ Text({original_display}) }},', '''text = { Text(displayProfession(option)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            expanded = false
                            draftName = displayProfession(option)
                            editingOption = option
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = ui("Rename profession", "Renommer le métier") +
                                    ": " + displayProfession(option)
                            )
                        }
                    },''')
    # Insert the dialog outside the dropdown popup, but inside DropdownField.
    close = block.rfind('\n}')
    if close == -1 or block[close+2:].strip():
        raise ValueError('Unexpected end of DropdownField.')
    dialog = '''

    editingOption?.let { option ->
        AlertDialog(
            onDismissRequest = { editingOption = null },
            title = { Text(ui("Rename profession", "Renommer le métier")) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text(ui("Profession name", "Nom du métier")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftName.isNotBlank(),
                    onClick = {
                        val newName = draftName.trim()
                        customNames[option] = newName
                        preferences.edit().putString(code(option), newName).apply()
                        editingOption = null
                    }
                ) { Text(ui("Save", "Enregistrer")) }
            },
            dismissButton = {
                TextButton(onClick = { editingOption = null }) {
                    Text(ui("Cancel", "Annuler"))
                }
            }
        )
    }
'''
    # MainActivity imports android.app.AlertDialog, so qualify the Compose dialog.
    dialog = dialog.replace('        AlertDialog(', '        androidx.compose.material3.AlertDialog(')
    block = block[:close] + dialog + block[close:]
    result = source[:start] + block + source[end:]
    # Assert that code outside this single composable has not been touched.
    assert result[:start] == source[:start] and result.endswith(source[end:])
    for anchor in ['onOptionSelected(option)', 'selectedOption: String']:
        assert anchor in block
    return result


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    path = root / REL
    if not path.is_file():
        raise ValueError('MainActivity.kt not found. Run from the Android Studio project root, or pass its path.')
    source = path.read_text(encoding='utf-8-sig')
    result = patch(source)
    backup = path.with_name(path.name + '.before_profession_rename.bak')
    if backup.exists():
        raise ValueError(f'Backup already exists: {backup}. No changes made.')
    shutil.copy2(path, backup)
    temporary = path.with_name(path.name + '.profession_rename.tmp')
    try:
        temporary.write_text(result, encoding='utf-8')
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()
    print('OK: MainActivity.kt updated. Original profession codes and activities unchanged.')
    print('Backup:', backup)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
