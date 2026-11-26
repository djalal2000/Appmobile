package com.bharathvishal.messagecommunicationusingwearabledatalayer

import kotlinx.coroutines.isActive
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import android.widget.SeekBar
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
    private var wearableDeviceConnected: Boolean = false
    private var selectedId: String = ""
    private var selectedprofession: String = ""
    private var selectedActivity: String = "Unlabeled activity"
    private var vraiActivity: String = ""
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false

    private var startTime: Long = 0
    private val handler = Handler()
    private var updateTimerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityValidationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        selectedId = intent.getStringExtra("id") ?: "Unknown"
        selectedprofession = intent.getStringExtra("profession") ?: "Unknown"
        vraiActivity = intent.getStringExtra("Activity") ?: "lifting a patient"

        communicator = WearableCommunicator(
            context = this,
            onMessageReceivedCallback = { message ->
                runOnUiThread {
                    Toast.makeText(this, "Message received: $message", Toast.LENGTH_SHORT).show()
                }
            },
            onWearableConnected = { isConnected, appOpen ->
                runOnUiThread {
                    val wasConnected = wearableDeviceConnected
                    wearableDeviceConnected = isConnected && appOpen
                    updateConnectionStatus(isConnected, appOpen)
                    Log.d("WearableConnection", "Watch connection: $isConnected, App open: $appOpen")

                    if (!wearableDeviceConnected && wasConnected && isRecording) {
                        Toast.makeText(
                            this@ValidationActivity,
                            "Watch connection lost. Stopping recording.",
                            Toast.LENGTH_LONG
                        ).show()
                        stopRecording()
                    }
                }
            }
        )

        lifecycleScope.launch {
            while (isActive) {
                try {
                    communicator.checkWearableConnection()
                } catch (e: Exception) {
                    Log.e("ValidationActivity", "Error checking wearable connection", e)
                }
                kotlinx.coroutines.delay(5000)
            }
        }

        binding.stopRecordingButton.isEnabled = false
        updateActivityButtonColor()
        updateActivityStatusText()

        setupSeekBar()
        setupButtonListeners()
        setupActivityButtons()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        communicator.registerListeners()
    }

    override fun onPause() {
        communicator.unregisterListeners()
        super.onPause()
        stopTimer()
    }

    private fun setupSeekBar() {
        binding.TirednessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.TirednessValueTextView.text = "Fatigue Level: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sendFatigueLevel()
            }
        })
    }

    private fun stopRecording() {
        if (wearableDeviceConnected) {
            communicator.sendMessage("Action: Stop")
        }
        binding.startRecordingButton.isEnabled = true
        binding.stopRecordingButton.isEnabled = false
        binding.stopRecordingButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.pauseRecordingButton.isEnabled = false
        binding.pauseRecordingButton.text = "⏸ Pause"

        isRecording = false
        isPaused = false
        binding.toggleActivityButton.isEnabled = false

        stopTimer()

        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun setupButtonListeners() {
        binding.startRecordingButton.setOnClickListener {
            if (wearableDeviceConnected) {
                val fatigue = getCurrentFatigueLevel()
                val messageToSend = "ID: $selectedId, Profession: $selectedprofession, Activity: $selectedActivity, FatigueLevel: $fatigue, Action: Start"
                communicator.sendMessage(messageToSend)
                sendFatigueLevel()

                binding.startRecordingButton.isEnabled = false
                binding.stopRecordingButton.isEnabled = true
                binding.stopRecordingButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.toggleActivityButton.isEnabled = true
                binding.pauseRecordingButton.isEnabled = true

                isRecording = true
                isPaused = false
                binding.pauseRecordingButton.text = "⏸ Pause"
                startTime = System.currentTimeMillis()
                startTimer()

                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Watch not connected.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.toggleActivityButton.setOnClickListener {
            if (isRecording) {
                selectedActivity = "Unlabeled activity"
                binding.toggleActivityButton.text = "\uD83D\uDED1 Unlabeled activity"
                updateActivityButtonColor()
                updateActivityStatusText()

                if (wearableDeviceConnected) {
                    communicator.sendMessage("Activity: $selectedActivity")
                } else {
                    Toast.makeText(this, "Watch not connected.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please start recording before setting the Activity.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.stopRecordingButton.setOnClickListener {
            stopRecording()
        }

        binding.pauseRecordingButton.setOnClickListener {
            if (!isRecording) {
                Toast.makeText(this, "No recording in progress.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isPaused = !isPaused

            if (isPaused) {
                handler.removeCallbacks(updateTimerRunnable!!)
                setActivityButtonsEnabled(false)
                binding.pauseRecordingButton.text = "▶ Resume"
                communicator.sendMessage("Action: Pause")
                Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show()
            } else {
                startTime = System.currentTimeMillis() - getElapsedTimeFromText()
                startTimer()
                setActivityButtonsEnabled(true)
                binding.pauseRecordingButton.text = "⏸ Pause"
                communicator.sendMessage("Action: Resume")
                Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show()
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
                                .setTitle("Connected Watches")
                                .setMessage(deviceNames)
                                .setPositiveButton("OK", null)
                                .create()
                                .show()
                        } else {
                            Toast.makeText(this@ValidationActivity, "No connected watches", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                R.id.action_home -> {
                    if (isRecording) {
                        binding.stopRecordingButton.performClick()
                    }
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActivityButtons() {
        val ActivitysFromIntent = intent.getStringArrayExtra("Activities")?.toList() ?: listOf(
            "Activity 1", "Activity 2", "Activity 3", "Activity 4", "Activity 5", "Activity 6"
        )

        val ActivityButtons = listOf(
            binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
            binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6
        )


        ActivityButtons.forEachIndexed { index, button ->
            if (index < ActivitysFromIntent.size) {
                button.text = ActivitysFromIntent[index]
            }

            button.setOnClickListener {
                if (isRecording) {
                    selectedActivity = ActivitysFromIntent[index]
                    updateActivityButtonColor()
                    updateActivityStatusText()

                    if (wearableDeviceConnected) {
                        communicator.sendMessage("Activity: $selectedActivity")
                    } else {
                        Toast.makeText(this, "Watch not connected.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Start recording before choosing a Activity.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        binding.deviceconnectionStatusTv.text = when {
            !isConnected -> "❌ Watch not connected."
            appOpen -> "✅ Watch connected and app open."
            else -> "⚠️ Watch connected but app closed."
        }
    }

    private fun updateActivityButtonColor() {
        val colorSelected = ContextCompat.getColor(this, android.R.color.holo_green_light)
        val colorDefault = ContextCompat.getColor(this, android.R.color.darker_gray)

        val ActivityButtons = listOf(
            binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
            binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6
        )

        ActivityButtons.forEach { button ->
            button.setBackgroundColor(if (button.text == selectedActivity) colorSelected else colorDefault)
        }

        val toggleColor = if (selectedActivity == vraiActivity)
            ContextCompat.getColor(this, android.R.color.darker_gray)
        else
            ContextCompat.getColor(this, android.R.color.holo_orange_light)

        binding.toggleActivityButton.setBackgroundColor(toggleColor)
    }

    private fun updateActivityStatusText() {
        binding.ActivityStatusTextView.text = "Current Activity: $selectedActivity"
    }

    private fun refreshPage() {
        selectedActivity = "Unlabeled activity"
        binding.toggleActivityButton.text = "\uD83D\uDED1 Unlabeled activity"
        updateActivityButtonColor()
        updateActivityStatusText()

        if (isRecording) {
            binding.stopRecordingButton.performClick()
        }
        binding.timerTextView.text = "00:00:00:000"

        lifecycleScope.launch {
            communicator.checkWearableConnection()
        }

        if (wearableDeviceConnected) {
            communicator.sendMessage("Action: Refresh")
        }

        Toast.makeText(this, "Page refreshed", Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentFatigueLevel(): Int = binding.TirednessSeekBar.progress

    private fun sendFatigueLevel() {
        if (wearableDeviceConnected) {
            val fatigue = getCurrentFatigueLevel()
            communicator.sendMessage("FatigueLevel: $fatigue")
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
