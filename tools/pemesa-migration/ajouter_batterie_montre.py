#!/usr/bin/env python3
"""PEMESA: afficher la batterie de la montre sur le mobile, sans toucher aux mesures."""
import argparse
from datetime import datetime
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

PKG = Path('src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer')
MOBILE = Path('app') / PKG
WATCH = Path('wear') / PKG
FILES = {
    'watch': WATCH / 'WearCommunicationManager.kt',
    'comm': MOBILE / 'WearableCommunicator.kt',
    'screen': MOBILE / 'ValidationActivity.kt',
    'xml': Path('app/src/main/res/layout/activity_validation.xml'),
}
MARK = 'PEMESA_WATCH_BATTERY_V1'
PATH = '/pemesa/watch-battery'


def once(s, old, new):
    n = s.count(old)
    if n != 1:
        raise ValueError(f'Motif attendu une fois ({n}): {old[:100]!r}')
    return s.replace(old, new, 1)


def patch_watch(s):
    anchor = '        private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"'
    s = once(s, anchor, anchor + '\n        // ' + MARK + '\n        private const val WATCH_BATTERY_PATH = "' + PATH + '"')
    anchor = '                MESSAGE_ITEM_RECEIVED_PATH -> handleMessagePayload(messageStr)'
    s = once(s, anchor, '''                WATCH_BATTERY_PATH -> {
                    // Separate request; never enter the recording/label command parser.
                    if (messageStr == "get") {
                        val battery = context.getSystemService(Context.BATTERY_SERVICE)
                            as? android.os.BatteryManager
                        val level = battery?.getIntProperty(
                            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
                        )?.takeIf { it in 0..100 } ?: -1
                        Wearable.getMessageClient(context)
                            .sendMessage(messageEvent.sourceNodeId, WATCH_BATTERY_PATH,
                                level.toString().toByteArray(StandardCharsets.UTF_8))
                            .addOnFailureListener { error ->
                                Log.w(TAG, "Watch battery reply failed", error)
                            }
                    }
                }

''' + anchor)
    return s


def patch_comm(s):
    anchor = '    private val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"'
    s = once(s, anchor, anchor + '''
    // ''' + MARK + '''
    private val WATCH_BATTERY_PATH = "''' + PATH + '''"
    var onWatchBatteryLevel: ((Int?) -> Unit)? = null

    fun requestWatchBattery() {
        val nodeId = lastNodeId ?: return
        try {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, WATCH_BATTERY_PATH,
                    "get".toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener { error ->
                    Log.w("WearBattery", "Battery request failed", error)
                }
        } catch (error: Exception) {
            Log.w("WearBattery", "Battery request failed", error)
        }
    }
''')
    anchor = '            MESSAGE_ITEM_RECEIVED_PATH -> {'
    s = once(s, anchor, '''            WATCH_BATTERY_PATH -> {
                // Only accept battery data from the watch selected by the existing connection logic.
                if (event.sourceNodeId == lastNodeId) {
                    onWatchBatteryLevel?.invoke(message.toIntOrNull()?.takeIf { it in 0..100 })
                }
            }
''' + anchor)
    return s


def patch_screen(s, translated):
    anchor = '    private lateinit var communicator: WearableCommunicator'
    s = once(s, anchor, anchor + '''
    // ''' + MARK + '''
    private val batteryUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val batteryExpiry = Runnable { showWatchBattery(null) }

    private fun showWatchBattery(level: Int?) {
        batteryUiHandler.removeCallbacks(batteryExpiry)
        val french = ''' + ('AppLanguage.get(this) == AppLanguage.FRENCH' if translated else 'false') + '''
        val title = if (french) "Batterie de la montre" else "Watch battery"
        val warning = if (level != null && level <= 20) " ⚠️" else ""
        binding.watchBatteryTv.text = "🔋 $title: " +
            (level?.let { "$it%" } ?: "—") + warning
        if (level != null) {
            // Never keep showing an old reading if the watch stops answering.
            batteryUiHandler.postDelayed(batteryExpiry, 25000L)
        }
    }
''')
    # Keep the existing setContentView call and its following lines intact.
    anchor = '        setContentView(binding.root)'
    s = once(s, anchor, anchor + '\n        showWatchBattery(null)')
    anchor = '                    updateConnectionStatus(isConnected, appOpen)'
    s = once(s, anchor, anchor + '''
                    if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        communicator.requestWatchBattery()
                    } else {
                        showWatchBattery(null)
                    }''')
    # Attach the extra callback after construction and before existing periodic check.
    anchor = '        lifecycleScope.launch {\n            while (isActive) {'
    s = once(s, anchor, '''        communicator.onWatchBatteryLevel = { level ->
            runOnUiThread {
                if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    showWatchBattery(level)
                }
            }
        }

''' + anchor)
    anchor = '        communicator.unregisterListeners()'
    s = once(s, anchor, anchor + '\n        showWatchBattery(null)')
    return s


def patch_xml(s):
    pattern = r'(?m)^([ \t]*)<TextView\b[^>]*android:id="@\+id/timerTextView"[^>]*>'
    match = re.search(pattern, s)
    if not match or len(re.findall(pattern, s)) != 1:
        raise ValueError('Impossible de retrouver le chronometre de facon unique.')
    indent = match.group(1)
    block = '''<!-- PEMESA_WATCH_BATTERY_V1 -->
<TextView
    android:id="@+id/watchBatteryTv"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center_horizontal"
    android:layout_marginTop="8dp"
    android:gravity="center"
    android:text="🔋 Watch battery: —"
    android:textColor="?attr/colorOnSurface"
    android:textSize="14sp" />
'''
    s = s[:match.start()] + '\n'.join(indent + line for line in block.rstrip().splitlines()) + '\n\n' + s[match.start():]
    ET.fromstring(s)
    return s


def transform(originals, translated=False):
    if any(MARK in value for value in originals.values()):
        raise ValueError('Correctif deja present (ou installation partielle). Aucun fichier modifie.')
    result = {
        'watch': patch_watch(originals['watch']),
        'comm': patch_comm(originals['comm']),
        'screen': patch_screen(originals['screen'], translated),
        'xml': patch_xml(originals['xml']),
    }
    # This patch is insertion-only: all existing source lines must remain in order.
    for key, old in originals.items():
        remaining = iter(result[key].splitlines())
        if not all(any(new_line == old_line for new_line in remaining) for old_line in old.splitlines()):
            raise ValueError(f'Ligne existante modifiee dans {key}. Abandon.')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', nargs='?', default='.', help='Racine du projet Android Studio')
    parser.add_argument('--check', action='store_true', help='Verifier sans modifier de fichier')
    args = parser.parse_args()
    root = Path(args.project).resolve()
    try:
        originals = {k: (root / p).read_bytes().decode('utf-8') for k,p in FILES.items()}
        # Normalize CRLF for matching; restore each original line-ending format when writing.
        normalized = {k: s.replace('\r\n','\n') for k,s in originals.items()}
        translated = (root / MOBILE / 'AppLanguage.kt').is_file() and 'localized(' in normalized['screen']
        result = transform(normalized, translated)
        if args.check:
            print('Verification OK : quatre fichiers compatibles. Aucun fichier modifie.')
            return 0
        backup = root / ('backup_batterie_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
        for key, path in FILES.items():
            dst = backup / path
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / path, dst)
        written = []
        try:
            for key,path in FILES.items():
                written.append(path)
                content = result[key]
                if '\r\n' in originals[key]:
                    content = content.replace('\n','\r\n')
                (root / path).write_bytes(content.encode('utf-8'))
        except Exception:
            for path in written:
                shutil.copy2(backup / path, root / path)
            raise
        print('Correctif installe. Sauvegarde :', backup)
        for path in FILES.values():
            print('  -', path)
        print('Recompiler et installer les modules app ET wear dans Android Studio.')
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print('Installation interrompue :', error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
