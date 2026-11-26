package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.bharathvishal.messagecommunicationusingwearabledatalayer.databinding.ActivityMainBinding
import com.google.android.gms.wearable.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.concurrent.fixedRateTimer

class WearCommunicationManager(
    private val context: Context,
    private val binding: ActivityMainBinding
) : DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener,
    CapabilityClient.OnCapabilityChangedListener {

    companion object {
        private const val TAG = "WearComm"
        private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
        private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
        private const val PREFS_NAME = "watch_prefs"
        private const val SAMPLING_PERIOD_MS = 20L
        private const val SEND_INTERVAL_MS = 10000L
    }

    private val sharedPref: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mobileDeviceConnected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateConnectionStatusUI(value)
            }
        }

    var mobileNodeUri: String? = null

    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var hrate: Sensor? = null
    private var lightSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var linac: Sensor? = null
    private var rotat: Sensor? = null
    private var stepCounter: Sensor? = null

    // Sensor data variables
    private var xaccel = 0f
    private var yaccel = 0f
    private var zaccel = 0f
    private var xgyro = 0f
    private var ygyro = 0f
    private var zgyro = 0f
    private var xGravity = 0f
    private var yGravity = 0f
    private var zGravity = 0f
    private var hvalue = "0"
    private var lightLux = 0f
    private var lastLightLux = -1f
    private var pressureHpa = 0f
    private var xLinAcc = 0f
    private var yLinAcc = 0f
    private var zLinAcc = 0f
    private var xRot = 0f
    private var yRot = 0f
    private var zRot = 0f
    private var wRot = 0f
    private var stepCount = 0
    private var lastStepCount = 0

    private var stop = false
    private var paused = false
    private var sensorTimer: Timer? = null

    private val transmit = TransmissionService()

    fun registerListeners() {
        Log.d(TAG, "🔌 Registering listeners")
        Wearable.getDataClient(context).addListener(this)
        Wearable.getMessageClient(context).addListener(this)
        Wearable.getCapabilityClient(context)
            .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)
    }

    fun unregisterListeners() {
        Log.d(TAG, "❌ Unregistering listeners")
        Wearable.getDataClient(context).removeListener(this)
        Wearable.getMessageClient(context).removeListener(this)
        Wearable.getCapabilityClient(context).removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {}
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {}

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            val messageStr = String(messageEvent.data, StandardCharsets.UTF_8)
            val path = messageEvent.path

            Log.d(TAG, "📩 Message reçu: $path | $messageStr")

            when (path) {
                APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
                    val nodeId = messageEvent.sourceNodeId
                    val payload = "AppOpenWearableACK".toByteArray()
                    Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
                        .addOnCompleteListener {
                            if (it.isSuccessful) {
                                mobileDeviceConnected = true
                                mobileNodeUri = nodeId
                            }
                        }
                }

                MESSAGE_ITEM_RECEIVED_PATH -> handleMessagePayload(messageStr)
                else -> Log.w(TAG, "⚠️ Path non reconnu: $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur réception message", e)
        }
    }

    private fun handleMessagePayload(payload: String) {
        val parts = payload.split(",").map { it.trim() }
        val messageMap = parts.mapNotNull {
            val kv = it.split(":").map { it.trim() }
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()

        val personID = messageMap["ID"]
        val userProfession = messageMap["Profession"]
        val userActivity = messageMap["Activity"]
        val action = messageMap["Action"]
        val fatigueLevel = messageMap["FatigueLevel"]
        Log.d(TAG, "🟡 FatigueLevel received: $fatigueLevel")

        when (action) {
            "Start" -> {
                stepCount = 0
                lastStepCount = 0
                sharedPref.edit()
                    .putString("personID", personID ?: "unknown")
                    .putString("userProfession", userProfession ?: "unknown")
                    .putString("userActivity", userActivity ?: "Unlabeled activity")
                    .putString("Fatigue Level", fatigueLevel ?: "0")
                    .apply()
                updateUI("📡 Start recording...")
                startRecording()
            }

            "Stop" -> {
                stopRecording()
                updateUI("⛔ Recording finished !")
            }

            "Pause" -> {
                paused = true
                updateUI("⏸️ En pause.")
            }

            "Resume" -> {
                paused = false
                updateUI("▶ Recording in progress...")
            }

            "Refresh" -> {
                stopRecording()
                sharedPref.edit()
                    .putString("personID", personID ?: "unknown")
                    .putString("userProfession", userProfession ?: "unknown")
                    .putString("userActivity", userActivity ?: "Unlabeled activity")
                    .putString("Fatigue Level", fatigueLevel ?: "0")
                    .apply()
                updateUI("🔄 Watch Rafraîchi")
            }

            else -> Log.w(TAG, "⚠️ Action non identifiée: $action")
        }

        fatigueLevel?.let {
            val currentFatigue = sharedPref.getString("Fatigue Level", "0") ?: "0"
            if (it != currentFatigue) {
                sharedPref.edit().putString("Fatigue Level", it).apply()
                transmitSensorData(fatigueLevel = it)
            }
        }

        if (userActivity != null && userActivity != sharedPref.getString("userActivity", "Unlabeled activity")) {
            sharedPref.edit().putString("userActivity", userActivity).apply()
        }
    }

    private fun updateConnectionStatusUI(connected: Boolean) {
        binding.deviceconnectionStatusTv.visibility = View.VISIBLE
        binding.deviceconnectionStatusTv.text =
            if (connected) "📶 Mobile connected" else "❌ Mobile disconnected"
    }

    private fun updateUI(prefix: String? = null) {
        val personID = sharedPref.getString("personID", "unknown")
        val userProfession = sharedPref.getString("userProfession", "unknown")
        val userActivity = sharedPref.getString("userActivity", "Unlabeled activity")
        val fatigueLevel = sharedPref.getString("Fatigue Level", "0")
        val heartRate = hvalue
        val steps = stepCount.toInt()

        val displayText = buildString {
            if (prefix != null) appendLine(prefix)
            appendLine("ID: $personID")
            appendLine("Profession: $userProfession")
            appendLine("Activity: $userActivity")
            appendLine("\uD83E\uDD71 Fatigue Level: $fatigueLevel")
            appendLine("❤️ BPM: $heartRate")
            appendLine("👣 Steps: $steps")
            appendLine("💡 Light: $lightLux")
        }

        binding.deviceconnectionStatusTv.post {
            binding.deviceconnectionStatusTv.text = displayText
            binding.deviceconnectionStatusTv.visibility = View.VISIBLE
        }
    }

    private fun setupSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linac = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotat = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hrate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        } else {
            hrate = null
        }

        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val samplingPeriodUs = (SAMPLING_PERIOD_MS * 1000).toInt()

        listOf(
            accel, gyro, gravitySensor, hrate,
            lightSensor, pressureSensor,
            linac, rotat, stepCounter
        ).forEach { sensor ->
            sensor?.let { sensorManager.registerListener(sensorListener, it, samplingPeriodUs) }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                val personID = sharedPref.getString("personID", "unknown") ?: "unknown"
                when (it.sensor.type) {

                    Sensor.TYPE_HEART_RATE -> {
                        val newBpm = it.values[0].toInt().toString()
                        Log.d(TAG, "❤️ Heart rate: $newBpm BPM")
                        if (newBpm != hvalue) {
                            hvalue = newBpm
                            transmitSensorData(bpm = hvalue)
                            updateUI()
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        xaccel = it.values[0]
                        yaccel = it.values[1]
                        zaccel = it.values[2]
                        Log.d(TAG, "📈 Accéléro: x=$xaccel y=$yaccel z=$zaccel")
                    }

                    Sensor.TYPE_GYROSCOPE -> {
                        xgyro = it.values[0]
                        ygyro = it.values[1]
                        zgyro = it.values[2]
                        Log.d(TAG, "🌀 Gyro: x=$xgyro y=$ygyro z=$zgyro")
                    }

                    Sensor.TYPE_STEP_COUNTER -> {
                        val totalSteps = it.values[0].toInt()
                        if (lastStepCount == 0 && stepCount == 0) {
                            lastStepCount = totalSteps
                            stepCount = 0
                        } else {
                            val newSteps = totalSteps - lastStepCount
                            if (newSteps > 0) {
                                stepCount += newSteps
                                lastStepCount = totalSteps
                                transmitSensorData(steps = stepCount)
                                updateUI()
                            }
                        }
                        Log.d(TAG, "👣 Step counter: total=$totalSteps, depuis début=$stepCount")
                    }

                    Sensor.TYPE_LIGHT -> {
                        val newLux = it.values[0]
                        Log.d(TAG, "💡 Light: $newLux lx")
                        if (newLux != lastLightLux) {
                            lastLightLux = newLux
                            lightLux = newLux
                            transmitSensorData(light = lightLux)
                            updateUI()
                        }
                    }

                    Sensor.TYPE_PRESSURE -> {
                        pressureHpa = it.values[0]
                        Log.d(TAG, "🌡️ Pressure: $pressureHpa hPa")
                        transmitSensorData(pressure = pressureHpa)
                    }

                    Sensor.TYPE_GRAVITY -> {
                        xGravity = it.values[0]
                        yGravity = it.values[1]
                        zGravity = it.values[2]
                        Log.d(TAG, "Gravity: x=$xGravity y=$yGravity z=$zGravity")
                    }

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        xLinAcc = it.values[0]
                        yLinAcc = it.values[1]
                        zLinAcc = it.values[2]
                        Log.d(TAG, "Acc Linear: x=$xLinAcc y=$yLinAcc z=$zLinAcc")
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        xRot = it.values[0]
                        yRot = it.values[1]
                        zRot = it.values[2]
                        wRot = if (it.values.size > 3) it.values[3] else 0f
                        Log.d(TAG, "Rotation Vector: x=$xRot y=$yRot z=$zRot w=$wRot")
                    }
                    else -> Log.w(TAG, "⚠️ Sensor not managed: ${it.sensor.type}")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun transmitSensorData(
        bpm: String? = null,
        light: Float? = null,
        pressure: Float? = null,
        steps: Int? = null,
        fatigueLevel: String? = null
    ) {
        val personID = sharedPref.getString("personID", "unknown") ?: "unknown"
        val data = Data(
            person_id = personID,
            bpm = bpm,
            light = light,
            pressure = pressure,
            steps = steps ?: 0,
            fatigueLevel = fatigueLevel
        )
        transmit.transmitWearableData(listOf(data))
    }

    private fun startRecording() {
        stop = false; paused = false
        setupSensors()
        val personID = sharedPref.getString("personID", "unknown") ?: "unknown"

        val dataSet = mutableListOf<Data>()
        var i = 0
        val bufferSize = (SEND_INTERVAL_MS / SAMPLING_PERIOD_MS).toInt()

        sensorTimer = fixedRateTimer("sensorTimer", true, 100, SAMPLING_PERIOD_MS) {
            val currentActivity = sharedPref.getString("userActivity", "Unlabeled activity") ?: "Unlabeled activity"
            if (!stop && !paused) {
                val data = Data(
                    person_id = personID,
                    acceX = xaccel,
                    acceY = yaccel,
                    acceZ = zaccel,
                    gyroX = xgyro,
                    gyroY = ygyro,
                    gyroZ = zgyro,
                    gravityX = xGravity,
                    gravityY = yGravity,
                    gravityZ = zGravity,
                    linAccX = xLinAcc,
                    linAccY = yLinAcc,
                    linAccZ = zLinAcc,
                    rotX = xRot,
                    rotY = yRot,
                    rotZ = zRot,
                    rotW = wRot,
                    label = currentActivity,
                    pressure = pressureHpa
                )

                if (dataSet.size < bufferSize) dataSet.add(data) else dataSet[i] = data

                if (i == bufferSize - 1) {
                    val updatedActivity = sharedPref.getString("userActivity", "Unlabeled activity") ?: "Unlabeled activity"
                    dataSet.indices.forEach { j ->
                        dataSet[j] = dataSet[j].copy(label = updatedActivity)
                    }
                    transmit.transmitData(dataSet)
                    i = 0
                } else i++
            }
        }
    }

    private fun stopRecording() {
        stop = true; paused = false
        sensorTimer?.cancel(); sensorTimer = null
        sensorManager.unregisterListener(sensorListener)
        lastStepCount = 0
        lastLightLux = -1f
    }
}
