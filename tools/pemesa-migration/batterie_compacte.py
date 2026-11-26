#!/usr/bin/env python3
"""PEMESA : placer la batterie a droite du statut de connexion (mobile uniquement)."""
import argparse
from datetime import datetime
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

SCREEN = Path('app/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer/ValidationActivity.kt')
LAYOUT = Path('app/src/main/res/layout/activity_validation.xml')
MARK = 'PEMESA_BATTERY_COMPACT_V1'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Motif absent ou ambigu : ' + repr(old[:90]))
    return text.replace(old, new, 1)


def patch_screen(text):
    old = '''        binding.watchBatteryTv.text = "🔋 $title: " +
            (level?.let { "$it%" } ?: "—") + warning'''
    new = '''        // PEMESA_BATTERY_COMPACT_V1: keep the full label for accessibility.
        val batteryValue = (level?.let { "$it%" } ?: "—") + warning
        binding.watchBatteryTv.text = "🔋 $batteryValue"
        binding.watchBatteryTv.contentDescription = "$title: $batteryValue"'''
    return replace_once(text, old, new)


def patch_layout(text):
    pattern = r'(?m)^([ \t]*)(<TextView\s+android:id="@\+id/deviceconnectionStatusTv"[^<>]*/>)\s*<!-- PEMESA_WATCH_BATTERY_V1 -->\s*(<TextView\s+android:id="@\+id/watchBatteryTv"[^<>]*/>)'
    matches = list(re.finditer(pattern, text))
    if len(matches) != 1:
        raise ValueError('Les deux indicateurs d\'origine ne sont pas retrouves cote a cote.')
    m = matches[0]
    indent, status, battery = m.groups()
    status = replace_once(status, 'android:layout_gravity="center_horizontal"', 'android:layout_gravity="center_vertical"\n' + indent + '    android:layout_weight="1"')
    battery = replace_once(battery, 'android:layout_gravity="center_horizontal"', 'android:layout_gravity="center_vertical"')
    battery = replace_once(battery, 'android:layout_marginTop="8dp"', 'android:layout_marginStart="6dp"')
    battery = replace_once(battery, 'android:text="🔋 Watch battery: —"', 'android:text="🔋 —"')
    battery = replace_once(battery, 'android:textSize="14sp"', 'android:textSize="12sp"')
    battery = replace_once(battery, 'android:gravity="center"', 'android:gravity="center"\n' + indent + '    android:maxLines="1"')
    def nested(block):
        return '\n'.join(indent + '    ' + (line[len(indent):] if line.startswith(indent) else line) if i else indent + '    ' + line for i, line in enumerate(block.splitlines()))
    row = '\n'.join([
        indent + '<!-- ' + MARK + ' -->',
        indent + '<LinearLayout',
        indent + '    android:layout_width="wrap_content"',
        indent + '    android:layout_height="wrap_content"',
        indent + '    android:layout_gravity="center_horizontal"',
        indent + '    android:orientation="horizontal"',
        indent + '    android:gravity="center_vertical">',
        nested(status), '',
        indent + '    <!-- PEMESA_WATCH_BATTERY_V1 -->',
        nested(battery),
        indent + '</LinearLayout>',
    ])
    result = text[:m.start()] + row + text[m.end():]
    old_root, new_root = ET.fromstring(text), ET.fromstring(result)
    def ids(root):
        return sorted(e.attrib[ANDROID+'id'] for e in root.iter() if ANDROID+'id' in e.attrib)
    if ids(old_root) != ids(new_root):
        raise ValueError('Un identifiant de vue a change. Abandon.')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', nargs='?', default='.')
    parser.add_argument('--check', action='store_true', help='Verifier sans modifier')
    args = parser.parse_args()
    root = Path(args.project).resolve()
    files = [SCREEN, LAYOUT]
    try:
        raw = {p: (root/p).read_bytes() for p in files}
        src = {p: b.decode('utf-8').replace('\r\n', '\n') for p,b in raw.items()}
        if all(MARK in s for s in src.values()):
            print('Correctif deja installe. Aucun fichier modifie.')
            return 0
        if any(MARK in s for s in src.values()):
            raise ValueError('Installation partielle : aucun fichier modifie.')
        if not all('PEMESA_WATCH_BATTERY_V1' in s for s in src.values()):
            raise ValueError('Installez d\'abord ajouter_batterie_montre.py.')
        out = {SCREEN: patch_screen(src[SCREEN]), LAYOUT: patch_layout(src[LAYOUT])}
        if args.check:
            print('Verification OK. Aucun fichier modifie.')
            return 0
        backup = root / ('backup_batterie_compacte_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
        for p in files:
            dest = backup/p
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root/p, dest)
        try:
            for p in files:
                content = out[p]
                if b'\r\n' in raw[p]:
                    content = content.replace('\n', '\r\n')
                (root/p).write_bytes(content.encode('utf-8'))
        except Exception:
            for p in files:
                shutil.copy2(backup/p, root/p)
            raise
        print('Correctif installe : deux fichiers du module app modifies.')
        print('Sauvegarde :', backup)
        print('Recompiler et reinstaller uniquement le module app.')
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print('Installation interrompue :', error, file=sys.stderr)
        return 1

if __name__ == '__main__':
    sys.exit(main())
