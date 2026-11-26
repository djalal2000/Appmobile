#!/usr/bin/env python3
"""PEMESA: improve connection responsiveness and standardize source comments."""
from pathlib import Path
from datetime import datetime
import argparse
import re
import shutil
import sys

PKG = Path('src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer')
MOBILE = Path('app') / PKG
WATCH = Path('wear') / PKG
COMM = MOBILE / 'WearableCommunicator.kt'
SCREEN = MOBILE / 'ValidationActivity.kt'
MANAGER = WATCH / 'WearCommunicationManager.kt'


def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError(f'Unexpected source version: {old[:100]!r}')
    return source.replace(old, new, 1)


def patch_communicator(source):
    if 'private val connectionCheckMutex' in source:
        return source
    s = once(source, 'import kotlinx.coroutines.Dispatchers', '''import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit''')
    s = once(s, '    private var currentAckFromWearForAppOpenCheck: String? = null', '''    private val connectionCheckMutex = Mutex()
    @Volatile private var pendingConnectionAck: CompletableDeferred<Unit>? = null
    @Volatile private var listenerRegistration: Task<Void>? = null
    private val connectionTimeoutMs = 7000L''')
    s = once(s, '    var lastNodeId: String? = null', '    @Volatile var lastNodeId: String? = null')
    start = s.index('    suspend fun checkWearableConnection()')
    end = s.index('    fun sendMessage(message: String)', start)
    s = s[:start] + '''    /**
     * Checks transport availability and waits for the watch application to acknowledge the ping.
     * Acknowledgements are delivered by the message listener rather than a polling loop.
     */
    suspend fun checkWearableConnection() = withContext(Dispatchers.IO) {
        connectionCheckMutex.withLock {
            val registration = listenerRegistration ?: return@withLock
            var ack: CompletableDeferred<Unit>? = null
            try {
                Tasks.await(registration, connectionTimeoutMs, TimeUnit.MILLISECONDS)
                if (listenerRegistration !== registration) return@withLock

                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes,
                    connectionTimeoutMs, TimeUnit.MILLISECONDS)
                if (listenerRegistration !== registration) return@withLock
                val node = nodes.firstOrNull()
                if (node == null) {
                    lastNodeId = null
                    withContext(Dispatchers.Main) {
                        if (listenerRegistration === registration) onWearableConnected(false, false)
                    }
                    return@withLock
                }

                lastNodeId = node.id
                // Install the waiter before sending: a fast reply must not be lost.
                val response = CompletableDeferred<Unit>()
                ack = response
                pendingConnectionAck = response
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, APP_OPEN_WEARABLE_PAYLOAD_PATH,
                        wearableAppCheckPayload.toByteArray(StandardCharsets.UTF_8))
                    .addOnFailureListener { error -> response.completeExceptionally(error) }

                val appOpen = withTimeoutOrNull(connectionTimeoutMs) {
                    response.await()
                    true
                } ?: false

                withContext(Dispatchers.Main) {
                    if (listenerRegistration === registration) onWearableConnected(true, appOpen)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("WearableComm", "Connection check failed", error)
                withContext(Dispatchers.Main) {
                    if (listenerRegistration === registration) {
                        lastNodeId = null
                        onWearableConnected(false, false)
                    }
                }
            } finally {
                if (pendingConnectionAck === ack) pendingConnectionAck = null
                ack?.cancel()
            }
        }
    }

''' + s[end:]
    s = once(s, '        Wearable.getMessageClient(context).addListener(this)', '''        listenerRegistration = Wearable.getMessageClient(context).addListener(this)''')
    s = once(s, '        Wearable.getMessageClient(context).removeListener(this)', '''        // Invalidate the registration before releasing the listener.
        listenerRegistration = null
        pendingConnectionAck?.cancel()
        Wearable.getMessageClient(context).removeListener(this)''')
    s = once(s, '                currentAckFromWearForAppOpenCheck = message', '''                if (listenerRegistration != null && event.sourceNodeId == lastNodeId &&
                    message == wearableAppCheckPayloadReturnACK) {
                    pendingConnectionAck?.complete(Unit)
                }''')
    # Reject incompatible source variants instead of silently removing extra behavior.
    for line in source.splitlines():
        if any(k in line for k in ['fun requestWatchBattery()', 'onWatchBatteryLevel', 'WATCH_BATTERY_PATH', 'fun sendMessage(message: String)', 'MESSAGE_ITEM_RECEIVED_PATH ->', 'onMessageReceivedCallback(message)']):
            if line.strip() not in s:
                raise ValueError('Existing message behavior would be lost: ' + line.strip())
    return s


def patch_screen(source):
    if 'private var connectionMonitorJob:' in source:
        return source
    s = once(source, '    private lateinit var communicator: WearableCommunicator', '''    private lateinit var communicator: WearableCommunicator
    private var connectionMonitorJob: kotlinx.coroutines.Job? = null''')
    pattern = r'(?m)^        lifecycleScope\.launch \{\n            while \(isActive\) \{\n                try \{\n                    communicator\.checkWearableConnection\(\)\n                \} catch \(e: Exception\) \{\n                    Log\.e\("ValidationActivity", "Error checking wearable connection", e\)\n                \}\n                kotlinx\.coroutines\.delay\(5000\)\n            \}\n        \}\n'
    matches = list(re.finditer(pattern, s))
    if len(matches) != 1:
        raise ValueError('The existing connection monitor could not be identified.')
    # Move monitoring to the resumed lifecycle; preserve the existing polling interval.
    s = s[:matches[0].start()] + s[matches[0].end():]
    s = once(s, '        communicator.registerListeners()', '''        communicator.registerListeners()
        connectionMonitorJob?.cancel()
        connectionMonitorJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    communicator.checkWearableConnection()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("ValidationActivity", "Connection check failed", error)
                }
                kotlinx.coroutines.delay(5000)
            }
        }''')
    s = once(s, '        communicator.unregisterListeners()', '''        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        communicator.unregisterListeners()''')
    # The UI, command payloads, fatigue and timer logic are deliberately preserved.
    protected = [line.strip() for line in source.splitlines() if any(k in line for k in (
        'communicator.sendMessage(', 'val messageToSend =', 'handler.postDelayed(',
        'showWatchBattery(', 'requestWatchBattery(', 'onWatchBatteryLevel',
        'selectedActivity =', 'isRecording =', 'isPaused =', '.setOnClickListener',
        'getSharedPreferences(', 'putExtra(', 'sendFatigueLevel()', 'binding.watchBatteryTv'))]
    for line in protected:
        if line not in s:
            raise ValueError('Existing UI or command behavior would be lost: ' + line)
    return s


def patch_watch(source):
    s = source
    if 'if (::sensorManager.isInitialized)' not in s:
        s = once(s, '        sensorManager.unregisterListener(sensorListener)', '''        // Refresh or stop may arrive before the first recording starts.
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(sensorListener)
        }''')
    for state in ('stop', 'paused'):
        old = f'    private var {state} = false'
        if old in s:
            s = once(s, old, f'    @Volatile private var {state} = false')
    return s


# Only exact, known comments are edited. String literals and protocol values are not translated.
COMMENTS = {
    '//For dynamic theming on Android 12 and above': '// Apply dynamic colors on Android 12 and later.',
    '// Vitesse d\'écriture': '// Delay between successive characters.',
    '// Appel pour afficher la liste des capteurs disponibles dans le Logcat': '// Log the sensors available on this device.',
    '//    // Envoi des données JSON via POST': '//    // Submit JSON data using HTTP POST.',
    '// 🔹 Accélération': '// Acceleration',
    '// 🔹 gravité': '// Gravity',
    '// 🔹 Linear Acceleration': '// Linear acceleration',
    '// 🔹 Rotation Vector': '// Rotation vector',
    '// Sensor data variables': '// Latest sensor readings.',
    '// UI language only; protocol and recorded data retain their existing values.': '// Localize interface text without changing protocol values or recorded data.',
    '// User-defined labels are stored per profession, independently of the UI language.': '// Persist custom activity labels by profession, independently of the display language.',
    '// The original button keeps its ID, listeners and outer layout parameters.': '// Retain the activity button ID, click listener, and layout parameters.',
    '// A separate pencil button remains usable even when the activity button is disabled.': '// Keep the label editor accessible when activity selection is disabled.',
    '// A rename is not a new recording command; only the active label is updated.': '// Update the active label without restarting the recording.',
    '// PEMESA_UI_REFRESH_V1 — visual styling only, no recording state or messages.': '// PEMESA_UI_REFRESH_V1: presentation and animation helpers.',
    '// Preserve the existing visual distinction between selected, idle and stop states.': '// Use distinct colors for selected, idle, and stop states.',
    '// The existing method assigns selected/idle colors, including the unlabeled button.': '// Apply the current selection colors to all activity buttons.',
    '// Short entrance animation. Controls stay present and clickable; no callbacks delayed.': '// Animate entry without delaying input handling.',
    '// PEMESA_PROFESSION_NAMES_V1: display names only, original options remain the keys.': '// PEMESA_PROFESSION_NAMES_V1: preserve profession identifiers when display names change.',
    '// Separate request; never enter the recording/label command parser.': '// Handle battery requests independently of recording commands.',
    '// Only accept battery data from the watch selected by the existing connection logic.': '// Accept battery readings only from the selected watch.',
    '// Never keep showing an old reading if the watch stops answering.': '// Expire stale battery readings.',
    '// PEMESA_BATTERY_COMPACT_V1: keep the full label for accessibility.': '// PEMESA_BATTERY_COMPACT_V1: retain the accessible battery description.',
    '<!-- MainActivity  -->': '<!-- Mobile home screen -->',
    '<!-- ValidationActivity -->': '<!-- Recording and activity labeling screen -->',
}


def normalize_comments(source):
    lines = []
    for line in source.splitlines(keepends=True):
        body = line.rstrip('\n')
        ending = line[len(body):]
        stripped = body.strip()
        if stripped in COMMENTS:
            body = body.replace(stripped, COMMENTS[stripped], 1)
        elif body.strip().startswith('delay(80) // Vitesse d\'écriture'):
            body = body.replace('// Vitesse d\'écriture', COMMENTS['// Vitesse d\'écriture'], 1)
        lines.append(body + ending)
    return ''.join(lines)


def transform(path, source):
    if path == COMM:
        source = patch_communicator(source)
    elif path == SCREEN:
        source = patch_screen(source)
    elif path == MANAGER:
        source = patch_watch(source)
    return normalize_comments(source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', nargs='?', default='.')
    parser.add_argument('--check', action='store_true', help='Check compatibility without writing files')
    args = parser.parse_args()
    root = Path(args.project).resolve()
    try:
        for p in (COMM, SCREEN, MANAGER):
            if not (root / p).is_file():
                raise ValueError(f'Missing project file: {p}')
        originals, updates = {}, {}
        for module in ('app', 'wear'):
            for file in sorted((root / module / 'src/main').rglob('*')):
                if not file.is_file() or file.suffix not in ('.kt', '.xml'):
                    continue
                path = file.relative_to(root)
                raw = file.read_bytes()
                source = raw.decode('utf-8')
                normalized = source.replace('\r\n', '\n')
                result = transform(path, normalized)
                if normalized != result:
                    originals[path] = raw
                    updates[path] = result.replace('\n', '\r\n').encode('utf-8') if '\r\n' in source else result.encode('utf-8')
        if not updates:
            print('No changes required. The correction is already present.')
            return 0
        print('Files to update:')
        for path in updates:
            print('  ', path)
        if args.check:
            print('Compatibility check passed; no files written.')
            return 0
        # Keep backups outside source sets so Gradle cannot compile duplicate classes.
        backup = root / ('backup_communication_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
        for path in originals:
            dst = backup / path
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / path, dst)
        written = []
        try:
            for path, content in updates.items():
                written.append(path)
                (root / path).write_bytes(content)
        except Exception:
            for path in written:
                shutil.copy2(backup / path, root / path)
            raise
        print('Correction applied. Backup:', backup)
        print('Rebuild and reinstall both app and wear modules.')
        return 0
    except (ValueError, OSError, UnicodeError) as error:
        print('No correction applied:', error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
