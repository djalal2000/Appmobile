package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class TransmissionService(private val debugLogs: Boolean = false) {

    private val token = "n43TH_16XPvYcoHhKqtt5-r-ZxsYwdCAE1dCnoRf3ssQimb6Pf7rFDotYiomKeHPOTolhMQ0hjhGCpCaB3wSJA=="
    private val org = "ISIS - Université Champollion"
    private val bucketActivity = "activity"
    private val bucketWearable = "wearable"
    private val influxUrl = "https://indb1.chl2.connected-health.fr"


    private val loggingInterceptor = HttpLoggingInterceptor { message -> Log.d("OkHttp", message) }.apply {
        redactHeader("Authorization")
        level = if (debugLogs) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
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
        .callTimeout(65, TimeUnit.SECONDS)
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
    // Bound memory during outages. Independent FIFO workers preserve bucket ordering.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activityQueue = Channel<List<Data>>(32)
    private val wearableQueue = Channel<List<Data>>(512)

    init {
        scope.launch {
            for (samples in activityQueue) {
                writeBucket(bucketActivity, activityWrites, "InfluxDB", "LineProtocol", 1) {
                    samples.flatMap { sample ->
                        listOf(sample.toLineProtocol(), sample.toLineProtocolActivity())
                            .filter { it.isNotBlank() }
                    }.joinToString("\n")
                }
            }
        }
        scope.launch {
            for (first in wearableQueue) {
                val submissions = mutableListOf(first)
                // Coalesce only already queued readings, with no extra acquisition delay.
                for (i in 1..15) {
                    val next = wearableQueue.tryReceive().getOrNull() ?: break
                    submissions.add(next)
                }
                writeBucket(bucketWearable, wearableWrites, "InfluxDB_Wearable",
                    "LineProtocolWearable", submissions.size) {
                    submissions.flatten().map { it.toLineProtocolWearable() }
                        .filter { it.isNotBlank() }.joinToString("\n")
                }
            }
        }
    }

    private fun enqueue(queue: Channel<List<Data>>, stats: BucketWrites,
                        data: List<Data>, logTag: String) {
        synchronized(writeLock) {
            stats.pending++
            if (queue.trySend(data).isFailure) {
                stats.pending--
                stats.failures++
                stats.last = "ERROR"
                Log.e(logTag, "Write queue full or closed; submission not accepted.")
            }
        }
    }

    private fun finishWrite(stats: BucketWrites, accepted: Boolean?, submissions: Int) {
        synchronized(writeLock) {
            stats.pending -= submissions.toLong()
            if (accepted != null) {
                stats.last = if (accepted) "OK" else "ERROR"
                if (!accepted) stats.failures += submissions.toLong()
            }
        }
    }

    fun writeStatus(): JSONObject = synchronized(writeLock) {
        JSONObject()
            .put("activity", activityWrites.last)
            .put("wearable", wearableWrites.last)
            .put("pending", activityWrites.pending + wearableWrites.pending)
            .put("failures", activityWrites.failures + wearableWrites.failures)
    }

    fun transmitData(data: List<Data>) {
        if (data.isEmpty()) return
        enqueue(activityQueue, activityWrites, data, "InfluxDB")
        enqueue(wearableQueue, wearableWrites, data, "InfluxDB_Wearable")
    }

    fun transmitWearableData(data: List<Data>) {
        if (data.isNotEmpty()) enqueue(wearableQueue, wearableWrites, data, "InfluxDB_Wearable")
    }

    /** Stop accepting work, then drain the existing RAM queues. */
    fun close() {
        activityQueue.close()
        wearableQueue.close()
    }

    private suspend fun writeBucket(
        bucket: String, stats: BucketWrites, logTag: String, lineTag: String,
        submissions: Int, buildLines: () -> String
    ) {
        var accepted: Boolean? = null
        try {
            val lineProtocol = buildLines()
            if (lineProtocol.isBlank()) return
            // A retry must preserve the original payload and timestamps.
            val requestBody = lineProtocol.toRequestBody("text/plain".toMediaType())
            if (debugLogs) Log.d(lineTag, "➡️ $lineProtocol")
            for (attempt in 0..1) {
                try {
                    val response = influxService.sendLineProtocol(
                        org = org, bucket = bucket, precision = "ms", body = requestBody
                    )
                    val code = response.code()
                    val successful = response.isSuccessful
                    val retryHeader = response.headers()["Retry-After"]
                    response.body()?.close()
                    response.errorBody()?.close()
                    Log.d(logTag, "Response code: $code")
                    Log.d(logTag, "Response isSuccessful: $successful")
                    if (successful) {
                        accepted = true
                        Log.d(logTag, "Write accepted by InfluxDB.")
                        return
                    }
                    val retryDelay = retryDelayMs(code, retryHeader, attempt)
                    if (retryDelay != null) {
                        delay(retryDelay)
                    } else {
                        accepted = false
                        Log.e(logTag, "InfluxDB write rejected: $code")
                        return
                    }
                } catch (error: IOException) {
                    if (attempt == 0) delay(1000L) else throw error
                }
            }
        } catch (cancelled: CancellationException) {
            accepted = false
            throw cancelled
        } catch (error: Exception) {
            accepted = false
            Log.e(logTag, "InfluxDB write failed", error)
        } finally {
            finishWrite(stats, accepted, submissions)
        }
    }
}
