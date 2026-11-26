#!/usr/bin/env python3
"""Add execution acknowledgements to PEMESA's existing wearable commands."""
import argparse
from pathlib import Path
from datetime import datetime
import re
import shutil
import sys

PKG = Path('src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer')
FILES = {'mobile': Path('app')/PKG/'WearableCommunicator.kt',
         'screen': Path('app')/PKG/'ValidationActivity.kt',
         'watch': Path('wear')/PKG/'WearCommunicationManager.kt'}
MARK = 'PEMESA_COMMAND_ACK_V1'


def once(s, old, new):
    if s.count(old) != 1:
        raise ValueError(f'Expected one occurrence of {old[:90]!r}, found {s.count(old)}')
    return s.replace(old, new, 1)


def regex_once(s, pattern, replacement):
    s, count = re.subn(pattern, replacement, s, flags=re.M)
    if count != 1:
        raise ValueError(f'Expected one match for {pattern[:90]!r}, found {count}')
    return s


MOBILE = r'''    // PEMESA_COMMAND_ACK_V1
    private val commandPath = "/pemesa/command-v1"
    private val commandAckPath = "/pemesa/command-ack-v1"
    private data class PendingCommand(
        val nodeId: String,
        val result: kotlinx.coroutines.CompletableDeferred<String>
    )
    private val pendingCommands = java.util.concurrent.ConcurrentHashMap<String, PendingCommand>()

    /** Wait for execution on the watch, not merely successful transport submission. */
    suspend fun sendConfirmedCommand(message: String): String {
        val node = lastNodeId ?: return "NO_CONNECTION"
        val id = java.util.UUID.randomUUID().toString()
        val pending = PendingCommand(node, kotlinx.coroutines.CompletableDeferred())
        pendingCommands[id] = pending
        try {
            val envelope = org.json.JSONObject().put("id", id).put("payload", message)
            Wearable.getMessageClient(context)
                .sendMessage(node, commandPath, envelope.toString().toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener { error ->
                    Log.w("WearableComm", "Command transport failed", error)
                    pending.result.complete("UNKNOWN")
                }
            return kotlinx.coroutines.withTimeoutOrNull(3000L) { pending.result.await() } ?: "UNKNOWN"
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("WearableComm", "Command could not be confirmed", error)
            return "UNKNOWN"
        } finally {
            pendingCommands.remove(id)
            pending.result.cancel()
        }
    }

    private fun receiveCommandAck(event: MessageEvent, message: String) {
        try {
            val ack = org.json.JSONObject(message)
            val pending = pendingCommands[ack.getString("id")] ?: return
            if (pending.nodeId != event.sourceNodeId) return
            val result = ack.getString("result")
            if (result in setOf("OK", "REJECTED", "UNKNOWN")) pending.result.complete(result)
        } catch (error: Exception) {
            Log.w("WearableComm", "Invalid command acknowledgement", error)
        }
    }

'''


def patch_mobile(s):
    s = once(s, '    fun sendMessage(message: String) {', MOBILE + '    fun sendMessage(message: String) {')
    s = once(s, '            MESSAGE_ITEM_RECEIVED_PATH -> {', '            commandAckPath -> receiveCommandAck(event, message)\n            MESSAGE_ITEM_RECEIVED_PATH -> {')
    s = once(s, '    fun unregisterListeners() {', '''    fun unregisterListeners() {
        // A command may have executed even if the listener is removed before its reply.
        pendingCommands.values.forEach { it.result.complete("UNKNOWN") }''')
    return s


WATCH = r'''    // PEMESA_COMMAND_ACK_V1
    private val commandPath = "/pemesa/command-v1"
    private val commandAckPath = "/pemesa/command-ack-v1"
    private val commandHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Keep only recent command results in memory; never persist them to disk.
    private val completedCommands = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }

    private fun receiveConfirmedCommand(node: String, message: String) {
        // Execute control commands serially on the main thread, as with the existing listener.
        commandHandler.post {
            val envelope = try { org.json.JSONObject(message) } catch (error: Exception) {
                Log.w(TAG, "Invalid command envelope", error)
                return@post
            }
            val id = envelope.optString("id")
            if (id.length != 36 || runCatching { java.util.UUID.fromString(id) }.isFailure) return@post
            val key = "$node:$id"
            val result = completedCommands[key] ?: executeConfirmedCommand(envelope.optString("payload")).also {
                completedCommands[key] = it
            }
            val reply = org.json.JSONObject().put("id", id).put("result", result)
            Wearable.getMessageClient(context)
                .sendMessage(node, commandAckPath, reply.toString().toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener { error -> Log.w(TAG, "Command acknowledgement failed", error) }
        }
    }

    private fun executeConfirmedCommand(payload: String): String {
        val pairs = payload.split(',').map { it.split(':', limit = 2).map(String::trim) }
        if (pairs.any { it.size != 2 || it[0].isEmpty() || it[1].isEmpty() }) return "REJECTED"
        val fields = pairs.associate { it[0] to it[1] }
        if (fields.size != pairs.size || fields.keys.any { it !in setOf("ID", "Profession", "Activity", "FatigueLevel", "Action") }) return "REJECTED"
        if (fields.values.any { ':' in it }) return "REJECTED"
        val action = fields["Action"]
        val fatigue = fields["FatigueLevel"]
        if (fatigue != null && fatigue.toIntOrNull()?.let { it in 0..5 } != true) return "REJECTED"
        val running = sensorTimer != null
        when (action) {
            "Start" -> if (running || listOf("ID", "Profession", "Activity", "FatigueLevel").any { it !in fields }) return "REJECTED"
            "Pause", "Resume" -> if (!running) return "REJECTED"
            "Stop", "Refresh" -> Unit
            null -> if (fields.keys.any { it !in setOf("Activity", "FatigueLevel") } ||
                ("Activity" in fields && !running)) return "REJECTED"
            else -> return "REJECTED"
        }
        return try {
            handleMessagePayload(payload)
            // Confirm the resulting recording state and the applied metadata.
            val stateMatches = when (action) {
                "Start", "Resume" -> sensorTimer != null && !stop && !paused
                "Pause" -> sensorTimer != null && paused
                "Stop", "Refresh" -> sensorTimer == null && stop
                else -> true
            }
            val labelMatches = fields["Activity"]?.let { sharedPref.getString("userActivity", null) == it } ?: true
            val fatigueMatches = fatigue?.let { sharedPref.getString("Fatigue Level", null) == it } ?: true
            if (stateMatches && labelMatches && fatigueMatches) "OK" else "UNKNOWN"
        } catch (error: Exception) {
            Log.e(TAG, "Command execution failed", error)
            "UNKNOWN"
        }
    }

'''


def patch_watch(s):
    s = once(s, '    private fun handleMessagePayload(payload: String) {', WATCH + '    private fun handleMessagePayload(payload: String) {')
    s = once(s, '                MESSAGE_ITEM_RECEIVED_PATH -> handleMessagePayload(messageStr)', '''                commandPath -> receiveConfirmedCommand(messageEvent.sourceNodeId, messageStr)
                MESSAGE_ITEM_RECEIVED_PATH -> handleMessagePayload(messageStr)''')
    if 'if (::sensorManager.isInitialized)' not in s:
        s = once(s, '        sensorManager.unregisterListener(sensorListener)', '''        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(sensorListener)
        }''')
    for state in ('stop', 'paused'):
        old = f'    private var {state} = false'
        if old in s:
            s = once(s, old, f'    @Volatile private var {state} = false')
    return s


SCREEN = r'''    // PEMESA_COMMAND_ACK_V1
    private var commandPending = false
    private var commandUncertain = false
    private var confirmedFatigue = 0

    private fun commandText(english: String, french: String): String = __LANG__

    private fun commandNotice(english: String, french: String) {
        Toast.makeText(this, commandText(english, french), Toast.LENGTH_LONG).show()
    }

    private fun markCommandUncertain() {
        commandUncertain = true
        stopTimer()
        binding.startRecordingButton.isEnabled = false
        binding.pauseRecordingButton.isEnabled = false
        binding.stopRecordingButton.isEnabled = true
        setActivityButtonsEnabled(false)
        binding.TirednessSeekBar.isEnabled = false
    }

    /** Apply the mobile state transition only after the watch confirms execution. */
    private fun confirmedCommand(message: String, onFailure: () -> Unit = {}, onConfirmed: () -> Unit) {
        if (commandPending) {
            commandNotice("Waiting for the watch", "Confirmation de la montre en cours")
            onFailure()
            return
        }
        if (commandUncertain && message != "Action: Stop" && message != "Action: Refresh") {
            commandNotice("Watch state unknown. Confirm Stop or Refresh first.", "État de la montre inconnu. Confirmez d’abord Stop ou Actualiser.")
            onFailure()
            return
        }
        if (!wearableDeviceConnected) {
            commandNotice("Watch not connected", "Montre non connectée")
            onFailure()
            return
        }
        commandPending = true
        lifecycleScope.launch {
            try {
                when (communicator.sendConfirmedCommand(message)) {
                    "OK" -> {
                        if (!wearableDeviceConnected) {
                            onFailure()
                            markCommandUncertain()
                            commandNotice("Watch disconnected during confirmation", "Montre déconnectée pendant la confirmation")
                            return@launch
                        }
                        if (message == "Action: Stop" || message == "Action: Refresh") {
                            commandUncertain = false
                            binding.TirednessSeekBar.isEnabled = true
                        }
                        onConfirmed()
                    }
                    "REJECTED", "NO_CONNECTION" -> {
                        onFailure()
                        if (message.contains("Action: Start") || message == "Action: Pause" || message == "Action: Resume") {
                            markCommandUncertain()
                        }
                        commandNotice("Command rejected or watch unavailable. Check the watch.", "Commande refusée ou montre indisponible. Vérifiez la montre.")
                    }
                    else -> {
                        onFailure()
                        markCommandUncertain()
                        commandNotice("No execution confirmation. Check the watch and use Stop or Refresh.", "Exécution non confirmée. Vérifiez la montre puis utilisez Stop ou Actualiser.")
                    }
                }
            } finally {
                commandPending = false
            }
        }
    }

'''


def patch_screen(s):
    lang = 'if (AppLanguage.get(this) == AppLanguage.FRENCH) french else english' if 'localized(' in s or 'AppLanguage.' in s else 'english'
    s = once(s, '    private fun setupSeekBar() {', SCREEN.replace('__LANG__', lang) + '    private fun setupSeekBar() {')
    # Disconnection is not evidence that the remote recording has stopped.
    s = once(s, '                        stopRecording()\n', '                        markCommandUncertain()\n')
    s = s.replace('localized("Watch connection lost. Stopping recording.")', 'commandText("Watch connection lost. Recording state unknown.", "Connexion perdue. État de l’enregistrement inconnu.")')
    s = s.replace('"Watch connection lost. Stopping recording."', 'commandText("Watch connection lost. Recording state unknown.", "Connexion perdue. État de l’enregistrement inconnu.")')
    # Stop and home navigation must wait for actual execution.
    start = s.index('    private fun stopRecording() {')
    end = s.index('    private fun setupButtonListeners()', start)
    old = s[start:end]
    rest = once(old, '''    private fun stopRecording() {
        if (wearableDeviceConnected) {
            communicator.sendMessage("Action: Stop")
        }
''', '''    private fun stopRecording(after: () -> Unit = {}) {
        confirmedCommand("Action: Stop") {
            applyStoppedState()
            after()
        }
    }

    private fun applyStoppedState() {
''')
    s = s[:start] + rest + s[end:]
    s = once(s, '                binding.toggleActivityButton.isEnabled = true', '                setActivityButtonsEnabled(true)')
    # Start: all original UI updates and timer start belong inside the confirmation callback.
    s = once(s, '                communicator.sendMessage(messageToSend)\n                sendFatigueLevel()\n', '                confirmedCommand(messageToSend) {\n                confirmedFatigue = fatigue\n')
    s = regex_once(s, r'^(                Toast\.makeText\(this, (?:localized\()?"Recording started"\)?[^\n]+\n)(            \} else \{)', lambda m: m[1]+'                }\n'+m[2])
    # Label buttons: retain the original label source, display and styling, but delay mutation until ACK.
    pattern = r'^(?P<indent>[ \t]+)selectedActivity = (?P<expr>[^\n]+)\n(?P<body>(?:(?P=indent)(?:binding\.toggleActivityButton\.text = |updateUnlabeledButton\(|updateActivityButtonColor\(|updateActivityStatusText\()[^\n]*\n)+)[ \t]*\n(?P=indent)if \(wearableDeviceConnected\) \{\n(?P=indent)    communicator\.sendMessage\("Activity: \$selectedActivity"\)\n(?P=indent)\} else \{\n(?P=indent)    Toast\.makeText\([^\n]+\n(?P=indent)\}'
    def label(m):
        i=m['indent']; body=m['body']; expr=m['expr']
        return i+'val requestedActivity = '+expr+'\n'+i+'confirmedCommand("Activity: $requestedActivity") {\n'+i+'    selectedActivity = requestedActivity\n'+'\n'.join('    '+line if line.strip() else line for line in body.rstrip('\n').split('\n'))+'\n'+i+'}'
    s, count = re.subn(pattern, label, s, flags=re.M|re.S)
    if count != 2: raise ValueError(f'Expected two activity click handlers, found {count}')
    # Pause and resume: compute the desired state without changing the current one.
    s = once(s, '            isPaused = !isPaused\n\n            if (isPaused) {', '''            val requestedPause = !isPaused
            confirmedCommand(if (requestedPause) "Action: Pause" else "Action: Resume") {
            isPaused = requestedPause

            if (isPaused) {''')
    s = once(s, '                communicator.sendMessage("Action: Pause")\n', '')
    s = once(s, '                communicator.sendMessage("Action: Resume")\n', '')
    s = regex_once(s, r'^(                Toast\.makeText\(this, (?:localized\()?"Recording resumed"\)?[^\n]+\n            \}\n)(        \}\n    \})', lambda m:m[1]+'            }\n'+m[2])
    s = s.replace('handler.removeCallbacks(updateTimerRunnable!!)', 'updateTimerRunnable?.let { handler.removeCallbacks(it) }')
    home = '''                    if (isRecording) {
                        binding.stopRecordingButton.performClick()
                    }
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)'''
    s = once(s, home, '''                    val goHome = {
                        val intent = Intent(this, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }
                    if (commandPending) {
                        commandNotice("Waiting for the watch", "Confirmation de la montre en cours")
                    } else if (isRecording || commandUncertain) {
                        stopRecording(goHome)
                    } else {
                        goHome()
                    }''')
    # Refresh itself stops and resets the watch. Do not send a second unconfirmed Stop.
    start=s.index('    private fun refreshPage() {'); end=s.index('    private fun getCurrentFatigueLevel()',start)
    block=s[start:end]
    block=once(block,'    private fun refreshPage() {\n','    private fun refreshPage() {\n        if (!wearableDeviceConnected) {\n            lifecycleScope.launch { communicator.checkWearableConnection() }\n            commandNotice("Reconnect, then refresh the watch", "Reconnectez puis actualisez la montre")\n            return\n        }\n        confirmedCommand("Action: Refresh") {\n')
    block=once(block,'''        if (isRecording) {
            binding.stopRecordingButton.performClick()
        }''','''        applyStoppedState()
        confirmedFatigue = 0
        binding.TirednessSeekBar.progress = 0''')
    block=once(block,'''        if (wearableDeviceConnected) {
            communicator.sendMessage("Action: Refresh")
        }

''','')
    block=regex_once(block,r'^(        Toast\.makeText\(this, (?:localized\()?"Page refreshed"\)?[^\n]+\n)(    \})',lambda m:m[1]+'        }\n'+m[2])
    s=s[:start]+block+s[end:]
    # Restore the last confirmed slider value if the request cannot be confirmed.
    s=once(s,'''        if (wearableDeviceConnected) {
            val fatigue = getCurrentFatigueLevel()
            communicator.sendMessage("FatigueLevel: $fatigue")
        }''','''        if (!wearableDeviceConnected && !isRecording && !commandUncertain) return
        val fatigue = getCurrentFatigueLevel()
        confirmedCommand("FatigueLevel: $fatigue", onFailure = {
            binding.TirednessSeekBar.progress = confirmedFatigue
        }) {
            confirmedFatigue = fatigue
        }''')
    # The optional activity-label editor must also confirm an active label change before saving it.
    if 'private fun saveActivityLabel(index: Int, newLabel: String)' in s:
        s=once(s,'    private fun saveActivityLabel(index: Int, newLabel: String) {\n        val wasSelected = selectedActivity == activityLabel(index)', '''    private fun saveActivityLabel(index: Int, newLabel: String, confirmed: Boolean = false) {
        val wasSelected = selectedActivity == activityLabel(index)
        if (wasSelected && (isRecording || commandUncertain) && !confirmed) {
            confirmedCommand("Activity: $newLabel") { saveActivityLabel(index, newLabel, true) }
            return
        }''')
        s=regex_once(s,r'(?m)^            (?:\/\/[^\n]+\n            )*if \(isRecording && wearableDeviceConnected\) \{\n                communicator\.sendMessage\("Activity: \$selectedActivity"\)\n            \}\n','')
    # No recording commands may bypass the acknowledged path on this screen.
    if 'communicator.sendMessage(' in s: raise ValueError('Unrecognized legacy command sender remains in ValidationActivity.kt')
    return s


def transform(sources):
    present=[MARK in text for text in sources.values()]
    if all(present): return sources
    if any(present): raise ValueError('Partial ACK installation detected; restore backups before retrying.')
    return {'mobile':patch_mobile(sources['mobile']), 'watch':patch_watch(sources['watch']), 'screen':patch_screen(sources['screen'])}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true',help='Validate without changing files.')
    parser.add_argument('--root',type=Path,default=Path.cwd(),help='Android Studio project root.')
    args=parser.parse_args()
    try:
        originals={k:(args.root/p).read_bytes() for k,p in FILES.items()}
        sources={k:v.decode('utf-8').replace('\r\n','\n') for k,v in originals.items()}
        updated=transform(sources)
        changed=[k for k in FILES if sources[k]!=updated[k]]
        if not changed: print('ACK correction already installed. No files changed.'); return 0
        for k in changed: print('Compatible:', FILES[k])
        if args.check: print('Check passed. No files changed.'); return 0
        backup=args.root/('pemesa_ack_backup_'+datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
        for k in changed:
            dest=backup/FILES[k]; dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(args.root/FILES[k],dest)
        try:
            for k in changed:
                newline='\r\n' if b'\r\n' in originals[k] else '\n'
                (args.root/FILES[k]).write_bytes(updated[k].replace('\n',newline).encode('utf-8'))
        except OSError:
            for k in changed: (args.root/FILES[k]).write_bytes(originals[k])
            raise
        print('ACK installed. Backup:',backup)
        print('Rebuild and reinstall both app and wear modules.')
        return 0
    except (OSError,ValueError) as error:
        print('Error:',error,file=sys.stderr); return 1

if __name__=='__main__': sys.exit(main())
