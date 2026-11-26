package com.bharathvishal.messagecommunicationusingwearabledatalayer

import kotlinx.coroutines.isActive
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
import android.util.TypedValue
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import com.bharathvishal.messagecommunicationusingwearabledatalayer.databinding.ActivityValidationBinding
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ValidationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityValidationBinding
    private lateinit var communicator: WearableCommunicator
    private var connectionMonitorJob: kotlinx.coroutines.Job? = null
    // PEMESA_WATCH_BATTERY_V1
    private val batteryUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val batteryExpiry = Runnable { showWatchBattery(null) }

    private fun showWatchBattery(level: Int?) {
        batteryUiHandler.removeCallbacks(batteryExpiry)
        val french = AppLanguage.get(this) == AppLanguage.FRENCH
        val title = if (french) "Batterie de la montre" else "Watch battery"
        val warning = if (level != null && level <= 20) " ⚠️" else ""
        // PEMESA_BATTERY_COMPACT_V1: retain the accessible battery description.
        val batteryValue = (level?.let { "$it%" } ?: "—") + warning
        binding.watchBatteryTv.text = "🔋 $batteryValue"
        binding.watchBatteryTv.contentDescription = "$title: $batteryValue"
        if (level != null) {
            // Expire stale battery readings.
            batteryUiHandler.postDelayed(batteryExpiry, 25000L)
        }
    }

    private var wearableDeviceConnected: Boolean = false
    private var selectedId: String = ""
    private var selectedprofession: String = ""
    private var selectedActivity: String = "Unlabeled activity"
    private var vraiActivity: String = ""
    // Persist custom activity labels by profession, independently of the display language.
    private val unlabeledIndex = 6
    private lateinit var defaultActivityLabels: List<String>
    private val customActivityLabels = MutableList<String?>(7) { null }
    private val activityLabelPreferences by lazy {
        getSharedPreferences("activity_labels", MODE_PRIVATE)
    }
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false

    private var startTime: Long = 0
    private val handler = Handler()
    private var updateTimerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityValidationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showWatchBattery(null)

        binding.deviceconnectionStatusTv.text = localized("Connection status")
        binding.startRecordingButton.text = localized("Start Recording")
        binding.stopRecordingButton.text = localized("Stop Recording")
        binding.pauseRecordingButton.text = localized("⏸ Pause")
        binding.toggleActivityButton.text = localized("🚫 Unlabeled activity")
        binding.TirednessValueTextView.text = localized("Fatigue Level:") + " " + binding.TirednessSeekBar.progress

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        selectedId = intent.getStringExtra("id") ?: "Unknown"
        selectedprofession = intent.getStringExtra("profession") ?: "Unknown"
        vraiActivity = intent.getStringExtra("Activity") ?: "lifting a patient"
        initializeActivityLabels()

        communicator = WearableCommunicator(
            context = this,
            onMessageReceivedCallback = { message ->
                runOnUiThread {
                    Toast.makeText(this, localized("Message received:") + " " + message, Toast.LENGTH_SHORT).show()
                }
            },
            onWearableConnected = { isConnected, appOpen ->
                runOnUiThread {
                    val wasConnected = wearableDeviceConnected
                    wearableDeviceConnected = isConnected && appOpen
                    updateConnectionStatus(isConnected, appOpen)
                    if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        communicator.requestWatchBattery()
                    } else {
                        showWatchBattery(null)
                    }
                    Log.d("WearableConnection", "Watch connection: $isConnected, App open: $appOpen")

                    if (!wearableDeviceConnected && wasConnected && isRecording) {
                        Toast.makeText(
                            this@ValidationActivity,
                            commandText("Watch connection lost. Recording state unknown.", "Connexion perdue. État de l’enregistrement inconnu."),
                            Toast.LENGTH_LONG
                        ).show()
                        markCommandUncertain()
                    }
                }
            }
        )

        communicator.onWatchBatteryLevel = { level ->
            runOnUiThread {
                if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    showWatchBattery(level)
                }
            }
        }


        binding.stopRecordingButton.isEnabled = false
        updateActivityButtonColor()
        updateActivityStatusText()

        setupSeekBar()
        setupButtonListeners()
        setupActivityButtons()
        setupBottomNavigation()
        applyUiDesign()
    }

    override fun onResume() {
        super.onResume()
        communicator.registerListeners()
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
        }
    }

    override fun onPause() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        communicator.unregisterListeners()
        showWatchBattery(null)
        super.onPause()
        stopTimer()
    }

    // PEMESA_COMMAND_ACK_V1
    private var commandPending = false
    private var commandUncertain = false
    private var confirmedFatigue = 0

    private fun commandText(english: String, french: String): String = if (AppLanguage.get(this) == AppLanguage.FRENCH) french else english

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

    private fun setupSeekBar() {
        binding.TirednessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.TirednessValueTextView.text = localized("Fatigue Level:") + " " + progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sendFatigueLevel()
            }
        })
    }

    private fun stopRecording(after: () -> Unit = {}) {
        confirmedCommand("Action: Stop") {
            applyStoppedState()
            after()
        }
    }

    private fun applyStoppedState() {
        binding.startRecordingButton.isEnabled = true
        binding.stopRecordingButton.isEnabled = false
        uiStyleButton(binding.stopRecordingButton, ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.pauseRecordingButton.isEnabled = false
        binding.pauseRecordingButton.text = localized("⏸ Pause")

        isRecording = false
        isPaused = false
        binding.toggleActivityButton.isEnabled = false

        stopTimer()

        Toast.makeText(this, localized("Recording stopped"), Toast.LENGTH_SHORT).show()
    }

    private fun setupButtonListeners() {
        binding.startRecordingButton.setOnClickListener {
            if (wearableDeviceConnected) {
                val fatigue = getCurrentFatigueLevel()
                val messageToSend = "ID: $selectedId, Profession: $selectedprofession, Activity: $selectedActivity, FatigueLevel: $fatigue, Action: Start"
                confirmedCommand(messageToSend) {
                confirmedFatigue = fatigue

                binding.startRecordingButton.isEnabled = false
                binding.stopRecordingButton.isEnabled = true
                uiStyleButton(binding.stopRecordingButton, ContextCompat.getColor(this, android.R.color.holo_red_dark))
                setActivityButtonsEnabled(true)
                binding.pauseRecordingButton.isEnabled = true

                isRecording = true
                isPaused = false
                binding.pauseRecordingButton.text = localized("⏸ Pause")
                startTime = System.currentTimeMillis()
                startTimer()

                Toast.makeText(this, localized("Recording started"), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, localized("Watch not connected."), Toast.LENGTH_SHORT).show()
            }
        }

        binding.toggleActivityButton.setOnClickListener {
            if (isRecording) {
                val requestedActivity = activityLabel(unlabeledIndex)
                confirmedCommand("Activity: $requestedActivity") {
                    selectedActivity = requestedActivity
                    updateUnlabeledButton("🛑")
                    updateActivityButtonColor()
                    updateActivityStatusText()
                }
            } else {
                Toast.makeText(this, localized("Please start recording before setting the Activity."), Toast.LENGTH_SHORT).show()
            }
        }

        binding.stopRecordingButton.setOnClickListener {
            stopRecording()
        }

        binding.pauseRecordingButton.setOnClickListener {
            if (!isRecording) {
                Toast.makeText(this, localized("No recording in progress."), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val requestedPause = !isPaused
            confirmedCommand(if (requestedPause) "Action: Pause" else "Action: Resume") {
            isPaused = requestedPause

            if (isPaused) {
                updateTimerRunnable?.let { handler.removeCallbacks(it) }
                setActivityButtonsEnabled(false)
                binding.pauseRecordingButton.text = localized("▶ Resume")
                Toast.makeText(this, localized("Recording paused"), Toast.LENGTH_SHORT).show()
            } else {
                startTime = System.currentTimeMillis() - getElapsedTimeFromText()
                startTimer()
                setActivityButtonsEnabled(true)
                binding.pauseRecordingButton.text = localized("⏸ Pause")
                Toast.makeText(this, localized("Recording resumed"), Toast.LENGTH_SHORT).show()
            }
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_refresh_connection -> {
                    refreshPage()
                    true
                }
                R.id.action_show_connected_devices -> {
                    lifecycleScope.launch {
                        val nodeList = withContext(Dispatchers.IO) {
                            try {
                                Tasks.await(Wearable.getNodeClient(this@ValidationActivity).connectedNodes)
                            } catch (e: Exception) {
                                Log.e("Wearable", "Error fetching nodes", e)
                                null
                            }
                        }

                        if (!nodeList.isNullOrEmpty()) {
                            val deviceNames = nodeList.joinToString("\n") { "• ${it.displayName} (${it.id})" }
                            AlertDialog.Builder(this@ValidationActivity)
                                .setTitle(localized("Connected Watches"))
                                .setMessage(deviceNames)
                                .setPositiveButton("OK", null)
                                .create()
                                .show()
                        } else {
                            Toast.makeText(this@ValidationActivity, localized("No connected watches"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                R.id.action_home -> {
                    val goHome = {
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
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActivityButtons() {
        activityButtons().forEachIndexed { index, button ->
            button.text = activityDisplay(index)
            installLabelEditor(button, index)

            button.setOnClickListener {
                if (isRecording) {
                    val requestedActivity = activityLabel(index)
                    confirmedCommand("Activity: $requestedActivity") {
                        selectedActivity = requestedActivity
                        updateActivityButtonColor()
                        updateActivityStatusText()
                    }
                } else {
                    Toast.makeText(this, localized("Start recording before choosing a Activity."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateUnlabeledButton("🚫")
        installLabelEditor(binding.toggleActivityButton, unlabeledIndex)
    }

    private fun activityButtons() = listOf(
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
        customActivityLabels[index] ?: localized(defaultActivityLabels[index])

    private fun selectedActivityDisplay(): String {
        val index = defaultActivityLabels.indices.firstOrNull { activityLabel(it) == selectedActivity }
        return if (index == null) selectedActivity else activityDisplay(index)
    }

    private fun updateUnlabeledButton(prefix: String) {
        binding.toggleActivityButton.text = "$prefix ${activityDisplay(unlabeledIndex)}"
    }

    private fun labelUiText(english: String, french: String): String = if (AppLanguage.get(this) == AppLanguage.FRENCH) french else english

    private fun labelDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // Retain the activity button ID, click listener, and layout parameters.
    // Keep the label editor accessible when activity selection is disabled.
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

    private fun saveActivityLabel(index: Int, newLabel: String, confirmed: Boolean = false) {
        val wasSelected = selectedActivity == activityLabel(index)
        if (wasSelected && (isRecording || commandUncertain) && !confirmed) {
            confirmedCommand("Activity: $newLabel") { saveActivityLabel(index, newLabel, true) }
            return
        }
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
        }
        updateActivityButtonColor()
        updateActivityStatusText()
    }

    private fun setActivityButtonsEnabled(enabled: Boolean) {
        binding.toggleActivityButton.isEnabled = enabled
        binding.ActivityButton1.isEnabled = enabled
        binding.ActivityButton2.isEnabled = enabled
        binding.ActivityButton3.isEnabled = enabled
        binding.ActivityButton4.isEnabled = enabled
        binding.ActivityButton5.isEnabled = enabled
        binding.ActivityButton6.isEnabled = enabled
    }

    private fun startTimer() {
        updateTimerRunnable = object : Runnable {
            override fun run() {
                if (isRecording && !isPaused) {
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val seconds = (elapsedTime / 1000) % 60
                    val minutes = (elapsedTime / 60000) % 60
                    val hours = (elapsedTime / 3600000)
                    val milliseconds = elapsedTime % 1000

                    binding.timerTextView.text = String.format("%02d:%02d:%02d:%03d", hours, minutes, seconds, milliseconds)
                    handler.postDelayed(this, 10)
                }
            }
        }
        handler.post(updateTimerRunnable!!)
    }

    private fun stopTimer() {
        updateTimerRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun updateConnectionStatus(isConnected: Boolean, appOpen: Boolean) {
        when {
            !isConnected -> uiStatusStyle("#FCE7E9", "#922B37")
            appOpen -> uiStatusStyle("#D7F2EE", "#064C4B")
            else -> uiStatusStyle("#FBEBD5", "#744917")
        }
        binding.deviceconnectionStatusTv.text = when {
            !isConnected -> localized("❌ Watch not connected.")
            appOpen -> localized("✅ Watch connected and app open.")
            else -> localized("⚠️ Watch connected but app closed.")
        }
    }

    // PEMESA_UI_REFRESH_V1: presentation and animation helpers.
    private fun uiColor(hex: String): Int = android.graphics.Color.parseColor(hex)
    private fun uiDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun uiRounded(fill: Int, radius: Int = 16, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = uiDp(radius).toFloat()
            if (stroke != null) setStroke(uiDp(1), stroke)
        }

    private fun uiStyleButton(button: android.widget.Button, originalColor: Int) {
        // Use distinct colors for selected, idle, and stop states.
        val fill = when (originalColor) {
            ContextCompat.getColor(this, android.R.color.holo_green_light) -> uiColor("#096B69")
            ContextCompat.getColor(this, android.R.color.darker_gray) -> uiColor("#405B6B")
            ContextCompat.getColor(this, android.R.color.holo_orange_light) -> uiColor("#965018")
            ContextCompat.getColor(this, android.R.color.holo_red_dark) -> uiColor("#B32636")
            else -> originalColor
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), uiRounded(uiColor("#DCE5E8"), 14))
            addState(intArrayOf(), uiRounded(fill, 14))
        }
        button.backgroundTintList = null
        button.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), states,
            uiRounded(android.graphics.Color.WHITE, 14)
        )
        button.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(uiColor("#526671"), android.graphics.Color.WHITE)
        ))
        button.setAllCaps(false)
        button.minHeight = uiDp(52)
        button.minimumHeight = uiDp(52)
        button.setPadding(uiDp(8), uiDp(10), uiDp(8), uiDp(10))
        button.elevation = 0f
        button.stateListAnimator = null
    }

    private fun uiStatusStyle(fill: String, ink: String) {
        binding.deviceconnectionStatusTv.apply {
            background = uiRounded(uiColor(fill), 18)
            setTextColor(uiColor(ink))
            setPadding(uiDp(12), uiDp(9), uiDp(12), uiDp(9))
        }
    }

    private fun applyUiDesign() {
        binding.root.setBackgroundColor(uiColor("#F1F6F7"))
        uiStatusStyle("#E4EDF0", "#294958")
        binding.timerTextView.apply {
            background = uiRounded(uiColor("#FFFFFF"), 20, uiColor("#D6E3E6"))
            setTextColor(uiColor("#142D3A"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 28f
            setPadding(uiDp(16), uiDp(16), uiDp(16), uiDp(16))
        }
        binding.ActivityStatusTextView.apply {
            background = uiRounded(uiColor("#D7F2EE"), 14)
            setTextColor(uiColor("#064C4B"))
            setPadding(uiDp(10), uiDp(12), uiDp(10), uiDp(12))
        }
        binding.TirednessValueTextView.setTextColor(uiColor("#142D3A"))
        binding.TirednessSeekBar.progressTintList = android.content.res.ColorStateList.valueOf(uiColor("#096B69"))
        binding.TirednessSeekBar.thumbTintList = android.content.res.ColorStateList.valueOf(uiColor("#096B69"))
        binding.bottomNavigationView.apply {
            setBackgroundColor(uiColor("#FFFFFF"))
            itemIconTintList = android.content.res.ColorStateList.valueOf(uiColor("#36566B"))
            elevation = uiDp(4).toFloat()
        }
        uiStyleButton(binding.startRecordingButton, uiColor("#096B69"))
        uiStyleButton(binding.pauseRecordingButton, uiColor("#76521E"))
        uiStyleButton(binding.stopRecordingButton, uiColor("#B32636"))
        listOf(binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
            binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6).forEach {
            it.textSize = 13f
        }
        // Apply the current selection colors to all activity buttons.
        updateActivityButtonColor()
        // Animate entry without delaying input handling.
        binding.recordingButtonsContainer.apply {
            alpha = 0.86f
            translationY = uiDp(6).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    private fun updateActivityButtonColor() {
        val colorSelected = ContextCompat.getColor(this, android.R.color.holo_green_light)
        val colorDefault = ContextCompat.getColor(this, android.R.color.darker_gray)

        val ActivityButtons = listOf(
            binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
            binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6
        )

        ActivityButtons.forEachIndexed { index, button ->
            uiStyleButton(button, if (activityLabel(index) == selectedActivity) colorSelected else colorDefault)
        }

        val toggleColor = if (selectedActivity == vraiActivity)
            ContextCompat.getColor(this, android.R.color.darker_gray)
        else
            ContextCompat.getColor(this, android.R.color.holo_orange_light)

        uiStyleButton(binding.toggleActivityButton, toggleColor)
    }

    private fun updateActivityStatusText() {
        binding.ActivityStatusTextView.text = localized("Current Activity:") + " " + selectedActivityDisplay()
    }

    private fun refreshPage() {
        if (!wearableDeviceConnected) {
            lifecycleScope.launch { communicator.checkWearableConnection() }
            commandNotice("Reconnect, then refresh the watch", "Reconnectez puis actualisez la montre")
            return
        }
        confirmedCommand("Action: Refresh") {
        selectedActivity = activityLabel(unlabeledIndex)
        updateUnlabeledButton("🛑")
        updateActivityButtonColor()
        updateActivityStatusText()

        applyStoppedState()
        confirmedFatigue = 0
        binding.TirednessSeekBar.progress = 0
        binding.timerTextView.text = "00:00:00:000"

        lifecycleScope.launch {
            communicator.checkWearableConnection()
        }

        Toast.makeText(this, localized("Page refreshed"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentFatigueLevel(): Int = binding.TirednessSeekBar.progress

    private fun sendFatigueLevel() {
        if (!wearableDeviceConnected && !isRecording && !commandUncertain) return
        val fatigue = getCurrentFatigueLevel()
        confirmedCommand("FatigueLevel: $fatigue", onFailure = {
            binding.TirednessSeekBar.progress = confirmedFatigue
        }) {
            confirmedFatigue = fatigue
        }
    }

    private fun getElapsedTimeFromText(): Long {
        val parts = binding.timerTextView.text.split(":")
        if (parts.size != 4) return 0
        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val seconds = parts[2].toLongOrNull() ?: 0
        val millis = parts[3].toLongOrNull() ?: 0
        return (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + millis
    }
}
