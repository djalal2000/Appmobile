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

    private data class Destination(val url: String, val organization: String, val activity: String,
        val wearable: String, val credential: String)
    private val original = Destination(influxUrl, org, bucketActivity, bucketWearable, token)
    @Volatile private var destination = original

    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val probeClient = client.newBuilder().callTimeout(7, TimeUnit.SECONDS).build()

    private fun service(target: Destination, http: OkHttpClient): InfluxService {
        val authenticated = http.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Token " + target.credential)
                .header("Content-Type", "text/plain").build())
        }.build()
        return Retrofit.Builder().baseUrl(target.url.trimEnd('/') + "/").client(authenticated).build()
            .create(InfluxService::class.java)
    }
    @Volatile private var influxService = service(original, client)

    /** No points are inserted by the empty-body write probe. */
    suspend fun prepare(server: JSONObject?): Boolean {
        if (pendingCount() != 0L) return false
        val target = if (server == null) original else try {
            Destination(server.getString("url"), server.getString("org"), server.getString("activity"),
                server.getString("wearable"), server.getString("token"))
        } catch (_: Exception) { return false }
        if (!validServerUrl(target.url) || target.organization.isBlank() || target.activity.isBlank() ||
            target.wearable.isBlank() || target.activity == target.wearable || target.credential.isBlank()) return false
        return try {
            val probe = service(target, probeClient)
            for (bucket in listOf(target.activity, target.wearable)) {
                val response = probe.sendLineProtocol(target.organization, bucket, "ms", "".toRequestBody("text/plain".toMediaType()))
                val ok = response.isSuccessful
                response.body()?.close()
                response.errorBody()?.close()
                if (!ok) return false
            }
            if (pendingCount() != 0L) return false
            influxService = service(target, client)
            destination = target
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    suspend fun diagnose(server: JSONObject?): JSONObject {
        val target = if (server == null) original else try {
            Destination(server.getString("url"), server.getString("org"), server.getString("activity"),
                server.getString("wearable"), server.getString("token"))
        } catch (_: Exception) { return JSONObject().put("error", "CONFIG") }
        if (!validServerUrl(target.url) || target.organization.isBlank() || target.activity.isBlank() ||
            target.wearable.isBlank() || target.credential.isBlank()) return JSONObject().put("error", "CONFIG")
        val result = JSONObject()
        try {
            val probe = service(target, probeClient)
            for ((key, bucket) in listOf("activity" to target.activity, "wearable" to target.wearable)) {
                val response = probe.sendLineProtocol(target.organization, bucket, "ms", "".toRequestBody("text/plain".toMediaType()))
                result.put(key, response.code())
                response.body()?.close(); response.errorBody()?.close()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { result.put("error", when (error) {
            is javax.net.ssl.SSLException -> "TLS"
            is java.net.UnknownHostException -> "DNS"
            is java.io.IOException -> "NETWORK"
            else -> "CONFIG"
        }) }
        return result
    }

    fun pendingCount(): Long = synchronized(writeLock) { activityWrites.pending + wearableWrites.pending }

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
                writeBucket(destination.activity, activityWrites, "InfluxDB", "LineProtocol", 1) {
                    samples.flatMap { sample ->
                        if (sample.sessionFields != null) listOf(sample.toSessionProtocol()) else listOf(sample.toLineProtocol(), sample.toLineProtocolActivity())
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
                writeBucket(destination.wearable, wearableWrites, "InfluxDB_Wearable",
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


    /** Add optional study metadata through the same bounded activity queue, never on the UI thread. */
    private fun sessionField(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

    fun transmitSession(id: String, person: String, study: JSONObject, hz: Long, sensors: Set<String>, epoch: Long) {
        // Metadata is additive; existing measurements retain their fields and names.
        val fields = listOf("project", "experiment", "site", "protocol").mapNotNull { key ->
            study.optString(key).take(120).takeIf { it.isNotBlank() }?.let { "$key=" + sessionField(it) }
        }
        if (fields.isEmpty()) return
        val metadata = Data(person_id = person, sessionId = id, epochMillis = epoch,
            sessionFields = (fields + listOf("sampling_hz=${hz}i", "sensors=" + sessionField(sensors.sorted().joinToString(";")))).joinToString(","))
        enqueue(activityQueue, activityWrites, listOf(metadata), "InfluxDB")
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
            // Do not log participant data or credentials.
            for (attempt in 0..1) {
                try {
                    val response = influxService.sendLineProtocol(
                        org = destination.organization, bucket = bucket, precision = "ms", body = requestBody
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
