"""Preserve activity labels and snapshot each completed sensor batch."""
import argparse
from pathlib import Path
import re
import shutil
import sys

RELATIVE = Path('wear/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer/WearCommunicationManager.kt')
PATTERN = re.compile(
    r'^([ \t]*)val updatedActivity = sharedPref\.getString\("userActivity", "Unlabeled activity"\) \?: "Unlabeled activity"\n'
    r'\1dataSet\.indices\.forEach \{ j ->\n'
    r'\1[ \t]+dataSet\[j\] = dataSet\[j\]\.copy\(label = updatedActivity\)\n'
    r'\1\}\n'
    r'\1transmit\.transmitData\(dataSet\)', re.MULTILINE)


def patch(source):
    if 'label = currentActivity' not in source:
        raise ValueError('The expected per-sample activity label was not found.')
    if 'transmit.transmitData(dataSet.toList())' in source and not PATTERN.search(source):
        return source
    updated, count = PATTERN.subn(lambda m: m[1] + '// Preserve the activity label assigned at acquisition time.\n' + m[1] + 'transmit.transmitData(dataSet.toList())', source)
    if count != 1:
        raise ValueError(f'Expected one original batch-labeling block; found {count}. No files changed.')
    return updated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate without modifying files.')
    parser.add_argument('--root', type=Path, default=Path.cwd(), help='Android Studio project directory.')
    args = parser.parse_args()
    path = args.root / RELATIVE
    try:
        raw = path.read_bytes()
        source = raw.decode('utf-8')
        newline = '\r\n' if '\r\n' in source else '\n'
        source = source.replace('\r\n', '\n')
        updated = patch(source)
        if updated == source:
            print('Correction already installed. No files changed.')
            return 0
        if args.check:
            print('Compatible source found. No files changed.')
            return 0
        backup = path.with_name(path.name + '.before_label_fix.bak')
        if backup.exists():
            raise ValueError(f'Backup already exists: {backup}. No files changed.')
        shutil.copy2(path, backup)
        path.write_bytes(updated.replace('\n', newline).encode('utf-8'))
        print(f'Updated: {path}\nBackup: {backup}')
        print('Rebuild and reinstall the wear module.')
        return 0
    except (OSError, ValueError) as exc:
        print(f'Error: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
