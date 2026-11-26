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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class TransmissionService(private val debugLogs: Boolean = false, private val onStatusChanged: (JSONObject) -> Unit = {}) {

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
        var pendingRecords = 0L
        var last = "NONE"
    }

    private val writeLock = Any()
    private val statusStream = java.util.UUID.randomUUID().toString()
    private var statusSequence = 0L
    private val activityWrites = BucketWrites()
    private val wearableWrites = BucketWrites()
    // Bound memory during outages. Independent FIFO workers preserve bucket ordering.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activityQueue = Channel<List<Data>>(32)
    private val wearableQueue = Channel<List<Data>>(512)
    private val maxWearableRecords = 2048L

    init {
        scope.launch {
            for (samples in activityQueue) {
                writeBucket(destination.activity, activityWrites, "InfluxDB", samples.size.toLong(), 1) {
                    buildString {
                        for (sample in samples) {
                            if (sample.sessionFields != null) {
                                appendProtocolLine(sample.toSessionProtocol())
                            } else {
                                appendProtocolLine(sample.toLineProtocol())
                                appendProtocolLine(sample.toLineProtocolActivity())
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            for (first in wearableQueue) {
                val submissions = mutableListOf(first)
                // Coalesce ready batches without waiting again; acquisition already groups them.
                for (i in 1..15) {
                    val next = wearableQueue.tryReceive().getOrNull() ?: break
                    submissions.add(next)
                }
                writeBucket(destination.wearable, wearableWrites, "InfluxDB_Wearable",
                    submissions.sumOf { it.size.toLong() }, submissions.size) {
                    buildString {
                        for (batch in submissions) for (sample in batch) appendProtocolLine(sample.toLineProtocolWearable())
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendProtocolLine(line: String) {
        if (line.isBlank()) return
        if (isNotEmpty()) append('\n')
        append(line)
    }

    private fun enqueue(queue: Channel<List<Data>>, stats: BucketWrites,
                        data: List<Data>, logTag: String) {
        synchronized(writeLock) {
            val records = data.size.toLong()
            if ((stats === wearableWrites && stats.pendingRecords + records > maxWearableRecords) ||
                queue.trySend(data).isFailure) {
                stats.failures++
                val changed = stats.last != "ERROR"
                stats.last = "ERROR"
                if (changed) publishStatusLocked()
                HealthJournal.add(HealthCode.QUEUE_FULL, data.size.toLong())
                Log.e(logTag, "Write queue full or closed; submission not accepted.")
            } else {
                stats.pending++
                stats.pendingRecords += records
            }
        }
    }

    private fun finishWrite(stats: BucketWrites, accepted: Boolean?, submissions: Int, records: Long) {
        synchronized(writeLock) {
            stats.pending -= submissions.toLong()
            stats.pendingRecords -= records
            if (accepted != null) {
                if (accepted && stats.last == "ERROR") HealthJournal.add(HealthCode.WRITE_RECOVERED)
                val previous = stats.last
                stats.last = if (accepted) "OK" else "ERROR"
                if (!accepted) stats.failures += submissions.toLong()
                if (previous != stats.last) publishStatusLocked()
            }
        }
    }

    private fun statusLocked(): JSONObject = JSONObject()
        .put("activity", activityWrites.last)
        .put("wearable", wearableWrites.last)
        .put("pending", activityWrites.pending + wearableWrites.pending)
        .put("failures", activityWrites.failures + wearableWrites.failures)
        .put("pendingWearableRecords", wearableWrites.pendingRecords)
        .put("stream", statusStream).put("revision", ++statusSequence)

    // Only transitions trigger a spontaneous message. Periodic polling remains a fallback.
    private fun publishStatusLocked() {
        runCatching { onStatusChanged(statusLocked()) }
    }

    fun writeStatus(): JSONObject = synchronized(writeLock) { statusLocked() }


    /** Add optional study metadata through the same bounded activity queue, never on the UI thread. */
    private fun sessionField(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

    fun transmitSession(id: String, person: String, study: JSONObject, hz: Long, sensors: Set<String>, epoch: Long, watchId: String? = null) {
        // Metadata is additive; existing measurements retain their fields and names.
        val fields = listOf("project", "experiment", "site", "protocol").mapNotNull { key ->
            study.optString(key).take(120).takeIf { it.isNotBlank() }?.let { "$key=" + sessionField(it) }
        }
        if (fields.isEmpty()) return
        val metadata = Data(timestamp = "", person_id = person, sessionId = id, epochMillis = epoch, watchId = watchId,
            sessionFields = (fields + listOf("sampling_hz=${hz}i", "sensors=" + sessionField(sensors.sorted().joinToString(";")))).joinToString(","))
        enqueue(activityQueue, activityWrites, listOf(metadata), "InfluxDB")
    }

    fun transmitData(data: List<Data>) {
        if (data.isEmpty()) return
        // Inertial samples no longer carry any wearable measurements.
        enqueue(activityQueue, activityWrites, data, "InfluxDB")
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
        bucket: String, stats: BucketWrites, logTag: String, records: Long,
        submissions: Int, buildLines: () -> String
    ) {
        var accepted: Boolean? = null
        try {
            val lineProtocol = buildLines()
            if (lineProtocol.isBlank()) return
            // A retry must preserve the original payload and timestamps.
            val requestBody = lineProtocol.toRequestBody("text/plain".toMediaType())
            // Reuse the exact request and destination throughout recovery.
            val service = influxService
            val organization = destination.organization
            val deadline = android.os.SystemClock.elapsedRealtime() + WRITE_RETRY_BUDGET_MS
            var attempt = 0
            fun remaining(): Long = deadline - android.os.SystemClock.elapsedRealtime()
            fun markRetry(code: Long? = null) {
                synchronized(writeLock) {
                    if (stats.last != "ERROR") {
                        stats.last = "ERROR"
                        publishStatusLocked()
                    }
                }
                HealthJournal.add(HealthCode.WRITE_RETRY, code)
            }
            while (remaining() > 0) {
                try {
                    val response = withTimeoutOrNull(remaining().coerceAtLeast(1L)) {
                        service.sendLineProtocol(org = organization, bucket = bucket, precision = "ms", body = requestBody)
                    }
                    if (response == null) {
                        accepted = false
                        HealthJournal.add(HealthCode.WRITE_FAILED)
                        return
                    }
                    val code = response.code()
                    val successful = response.isSuccessful
                    val retryHeader = response.headers()["Retry-After"]
                    response.body()?.close()
                    response.errorBody()?.close()
                    if (debugLogs) Log.d(logTag, "Response code: $code")
                    if (successful) {
                        accepted = true
                        if (debugLogs) Log.d(logTag, "Write accepted by InfluxDB.")
                        return
                    }
                    val retryDelay = retryDelayMs(code, retryHeader, attempt, remaining())
                    if (retryDelay == null) {
                        accepted = false
                        HealthJournal.add(HealthCode.WRITE_HTTP, code.toLong())
                        Log.e(logTag, "InfluxDB write rejected or retry budget exhausted: $code")
                        return
                    }
                    markRetry(code.toLong())
                    delay(retryDelay)
                } catch (error: IOException) {
                    // Certificate failures are not transient network outages.
                    if (error is javax.net.ssl.SSLHandshakeException ||
                        error is javax.net.ssl.SSLPeerUnverifiedException) throw error
                    val retryDelay = recoveryDelayMs(attempt, remaining()) ?: throw error
                    markRetry()
                    delay(retryDelay)
                }
                attempt++
            }
            accepted = false
            HealthJournal.add(HealthCode.WRITE_FAILED)
        } catch (cancelled: CancellationException) {
            accepted = false
            throw cancelled
        } catch (error: Exception) {
            accepted = false
            HealthJournal.add(HealthCode.WRITE_FAILED)
            Log.e(logTag, "InfluxDB write failed: " + error.javaClass.simpleName)
        } finally {
            finishWrite(stats, accepted, submissions, records)
        }
    }
}
