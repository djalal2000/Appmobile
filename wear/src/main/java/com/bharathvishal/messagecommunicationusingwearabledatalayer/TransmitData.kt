package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class TransmissionService {

    private val token = "n43TH_16XPvYcoHhKqtt5-r-ZxsYwdCAE1dCnoRf3ssQimb6Pf7rFDotYiomKeHPOTolhMQ0hjhGCpCaB3wSJA=="
    private val org = "ISIS - Université Champollion"
    private val bucketActivity = "activity"
    private val bucketWearable = "wearable"
    private val influxUrl = "https://indb1.chl2.connected-health.fr"


    private val loggingInterceptor = HttpLoggingInterceptor { message -> Log.d("OkHttp", message) }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Token $token")
                .addHeader("Content-Type", "text/plain")
                .build()
            chain.proceed(request)
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(influxUrl)
        .client(client)
        .build()

    private val influxService = retrofit.create(InfluxService::class.java)

    fun transmitData(data: List<Data>) {
        CoroutineScope(Dispatchers.IO).launch {
            val lineProtocol = data.mapNotNull { original ->
                val inertialLine = original.toLineProtocol()
                val activityLine = original.toLineProtocolActivity()
                listOf(inertialLine, activityLine).filter { it.isNotBlank() }
            }.flatten().joinToString("\n")

            if (lineProtocol.isBlank()) return@launch

            val mediaType = "text/plain".toMediaType()
            val requestBody = lineProtocol.toRequestBody(mediaType)

            try {
                Log.d("LineProtocol", "➡️ $lineProtocol")

                val response = influxService.sendLineProtocol(
                    org = org,
                    bucket = bucketActivity,
                    precision = "ms",
                    body = requestBody
                )

                Log.d("InfluxDB", "Response code: ${response.code()}")
                Log.d("InfluxDB", "Response message: ${response.message()}")
                Log.d("InfluxDB", "Response isSuccessful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    Log.d("InfluxDB", "✅ Données envoyées avec succès à InfluxDB.")
                } else {
                    Log.e("InfluxDB", "❌ Erreur InfluxDB: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e("InfluxDB", "❌ Exception réseau: ${e.localizedMessage}", e)
                e.printStackTrace()
            }

            transmitWearableData(data)
        }
    }

    fun transmitWearableData(data: List<Data>) {
        Log.d("DEBUG", "🔁 transmitWearableData called with: ${data.size} elements")

        CoroutineScope(Dispatchers.IO).launch {
            val lineProtocol = data.mapNotNull { original ->
                val wearableLine = original.toLineProtocolWearable()
                if (wearableLine.isBlank()) null else wearableLine
            }.joinToString("\n")

            if (lineProtocol.isBlank()) return@launch

            val mediaType = "text/plain".toMediaType()
            val requestBody = lineProtocol.toRequestBody(mediaType)

            try {
                Log.d("LineProtocolWearable", "➡️ $lineProtocol")

                val response = influxService.sendLineProtocol(
                    org = org,
                    bucket = bucketWearable,
                    precision = "ms",
                    body = requestBody
                )

                Log.d("InfluxDB_Wearable", "Response code: ${response.code()}")
                Log.d("InfluxDB_Wearable", "Response message: ${response.message()}")
                Log.d("InfluxDB_Wearable", "Response isSuccessful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    Log.d("InfluxDB_Wearable", "✅ Données envoyées avec succès au bucket wearable.")
                } else {
                    Log.e("InfluxDB_Wearable", "❌ Erreur InfluxDB: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e("InfluxDB_Wearable", "❌ Exception réseau: ${e.localizedMessage}", e)
                e.printStackTrace()
            }
        }
    }

}