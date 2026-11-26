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

    private class BucketWrites {
        var pending = 0L
        var failures = 0L
        var last = "NONE"
    }

    private val writeLock = Any()
    private val activityWrites = BucketWrites()
    private val wearableWrites = BucketWrites()

    private fun beginWrite(stats: BucketWrites) = synchronized(writeLock) {
        stats.pending++
    }

    private fun finishWrite(stats: BucketWrites, accepted: Boolean?) {
        synchronized(writeLock) {
            stats.pending--
            if (accepted != null) {
                stats.last = if (accepted) "OK" else "ERROR"
                if (!accepted) stats.failures++
            }
        }
    }

    /** Report completed HTTP writes independently of the wearable control channel. */
    fun writeStatus(): org.json.JSONObject = synchronized(writeLock) {
        org.json.JSONObject()
            .put("activity", activityWrites.last)
            .put("wearable", wearableWrites.last)
            .put("pending", activityWrites.pending + wearableWrites.pending)
            .put("failures", activityWrites.failures + wearableWrites.failures)
    }

    fun transmitData(data: List<Data>) {
        if (data.isEmpty()) return
        // Register both scheduled writes before the asynchronous work starts.
        synchronized(writeLock) {
            activityWrites.pending++
            wearableWrites.pending++
        }
        CoroutineScope(Dispatchers.IO).launch {
            writeBucket(bucketActivity, activityWrites, "InfluxDB", "LineProtocol") {
                data.flatMap { sample ->
                    listOf(sample.toLineProtocol(), sample.toLineProtocolActivity())
                        .filter { it.isNotBlank() }
                }.joinToString("\n")
            }
            writeBucket(bucketWearable, wearableWrites, "InfluxDB_Wearable", "LineProtocolWearable") {
                wearableLines(data)
            }
        }
    }

    fun transmitWearableData(data: List<Data>) {
        if (data.isEmpty()) return
        beginWrite(wearableWrites)
        CoroutineScope(Dispatchers.IO).launch {
            writeBucket(bucketWearable, wearableWrites, "InfluxDB_Wearable", "LineProtocolWearable") {
                wearableLines(data)
            }
        }
    }

    private fun wearableLines(data: List<Data>): String =
        data.map { it.toLineProtocolWearable() }.filter { it.isNotBlank() }.joinToString("\n")

    private suspend fun writeBucket(
        bucket: String,
        stats: BucketWrites,
        logTag: String,
        lineTag: String,
        buildLines: () -> String
    ) {
        var accepted: Boolean? = null
        try {
            val lineProtocol = buildLines()
            if (lineProtocol.isBlank()) return

            val requestBody = lineProtocol.toRequestBody("text/plain".toMediaType())
            Log.d(lineTag, "➡️ $lineProtocol")
            val response = influxService.sendLineProtocol(
                org = org,
                bucket = bucket,
                precision = "ms",
                body = requestBody
            )
            accepted = response.isSuccessful
            Log.d(logTag, "Response code: ${response.code()}")
            Log.d(logTag, "Response message: ${response.message()}")
            Log.d(logTag, "Response isSuccessful: ${response.isSuccessful}")
            if (response.isSuccessful) {
                Log.d(logTag, "Write accepted by InfluxDB.")
            } else {
                Log.e(logTag, "InfluxDB write rejected: ${response.code()} - ${response.message()}")
            }
            response.body()?.close()
            response.errorBody()?.close()
        } catch (error: Exception) {
            accepted = false
            Log.e(logTag, "InfluxDB write failed", error)
        } finally {
            finishWrite(stats, accepted)
        }
    }
}
