package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.json.JSONArray

/** Only settings are persisted; no acquired measurements. */
internal class CollectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pemesa_collection_v2", Context.MODE_PRIVATE)
    val custom: Boolean get() = prefs.getBoolean("custom", false)
    val url: String get() = prefs.getString("url", "") ?: ""
    val organization: String get() = prefs.getString("org", "") ?: ""
    val activity: String get() = prefs.getString("activity", "activity") ?: "activity"
    val wearable: String get() = prefs.getString("wearable", "wearable") ?: "wearable"
    val sensors: Set<String> get() = prefs.getStringSet("sensors", SENSOR_KEYS.toSet())?.toSet() ?: SENSOR_KEYS.toSet()
    val hasToken: Boolean get() = prefs.contains("token")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("pemesa_influx_v2", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pemesa_influx_v2", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(): String {
        val parts = requireNotNull(prefs.getString("token", null)).split(":")
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun saveServer(url: String, org: String, activity: String, wearable: String, token: String): Boolean = runCatching {
        require(validServerUrl(url) && org.isNotBlank() && activity.isNotBlank() && wearable.isNotBlank() && activity != wearable)
        val encrypted = if (token.isNotBlank()) encrypt(token) else {
            decrypt()
            requireNotNull(prefs.getString("token", null))
        }
        prefs.edit().putBoolean("custom", true).putString("url", url).putString("org", org)
            .putString("activity", activity).putString("wearable", wearable).putString("token", encrypted).commit()
    }.getOrDefault(false)
    fun resetServer(): Boolean = prefs.edit().remove("custom").remove("url").remove("org")
        .remove("activity").remove("wearable").remove("token").commit()
    fun saveSensors(keys: Set<String>): Boolean = validSensorSelection(keys) &&
        prefs.edit().putStringSet("sensors", keys.toSet()).commit()
    fun envelope(session: String): JSONObject {
        require(validSensorSelection(sensors))
        val result = JSONObject().put("session", session).put("sensors", JSONArray(sensors.toList()))
        if (custom) result.put("server", JSONObject().put("url", url).put("org", organization)
            .put("activity", activity).put("wearable", wearable).put("token", decrypt()))
        return result
    }
}
