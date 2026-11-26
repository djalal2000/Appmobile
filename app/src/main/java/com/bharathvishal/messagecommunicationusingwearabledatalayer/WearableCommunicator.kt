package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class WearableCommunicator(
    private val context: Context,
    private val onMessageReceivedCallback: (String) -> Unit,
    private val onWearableConnected: (Boolean, Boolean) -> Unit
) : MessageClient.OnMessageReceivedListener {

    private val wearableAppCheckPayload = "AppOpenWearable"
    private val wearableAppCheckPayloadReturnACK = "AppOpenWearableACK"
    private var currentAckFromWearForAppOpenCheck: String? = null

    private val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"

    var lastNodeId: String? = null

    suspend fun checkWearableConnection() = withContext(Dispatchers.IO) {
        Log.d("WearableConnectionCheck", "Démarrage de la vérification de la connexion...")
        val nodeListTask = Wearable.getNodeClient(context).connectedNodes
        try {
            val nodes = Tasks.await(nodeListTask)
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                lastNodeId = node.id
                Log.d("WearableConnectionCheck", "Noeud trouvé : ${node.displayName} (${node.id})")
                val payload = wearableAppCheckPayload.toByteArray()

                Log.d("WearableSend", "📤 Envoi du ping : $wearableAppCheckPayload à ${node.id}")
                val sendMessageTask = Wearable.getMessageClient(context)
                    .sendMessage(node.id, APP_OPEN_WEARABLE_PAYLOAD_PATH, payload)

                Tasks.await(sendMessageTask)

                currentAckFromWearForAppOpenCheck = null

                val ackTimeout = 7000L
                val ackInterval = 200L
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < ackTimeout) {
                    if (currentAckFromWearForAppOpenCheck == wearableAppCheckPayloadReturnACK) {
                        Log.d("WearablePing", "✅ Réponse ACK reçue depuis la montre")
                        withContext(Dispatchers.Main) {
                            onWearableConnected(true, true)
                        }
                        return@withContext
                    }
                    kotlinx.coroutines.delay(ackInterval)
                }

                Log.d("WearablePing", "⚠️ Timeout - Réponse ACK non reçue, mais montre connectée")
                withContext(Dispatchers.Main) {
                    onWearableConnected(true, false)
                }

            } else {
                Log.w("WearableConnectionCheck", "❌ Aucun noeud connecté trouvé")
                withContext(Dispatchers.Main) {
                    onWearableConnected(false, false)
                }
            }
        } catch (e: Exception) {
            Log.e("WearableComm", "❌ Erreur lors de la vérification de la connexion", e)
            withContext(Dispatchers.Main) {
                onWearableConnected(false, false)
            }
        }
    }

    fun sendMessage(message: String) {
        val nodeId = lastNodeId
        if (nodeId == null) {
            Log.w("WearableSend", "❌ Aucun noeud disponible pour l'envoi : $message")
            Toast.makeText(context, "Aucune montre connectée", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = message.toByteArray()
        Log.d("WearableSend", "📤 Envoi du message : $message à $nodeId")

        Wearable.getMessageClient(context)
            .sendMessage(nodeId, MESSAGE_ITEM_RECEIVED_PATH, payload)
            .addOnCompleteListener {
                if (it.isSuccessful) {
                    Log.d("WearableSend", "✅ Message envoyé avec succès à $nodeId : $message")
                } else {
                    Log.e("WearableSend", "❌ Échec de l'envoi du message à $nodeId : $message", it.exception)
                    Toast.makeText(context, "Échec de l'envoi du message", Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun registerListeners() {
        Wearable.getMessageClient(context).addListener(this)
        Log.d("WearableListener", "📡 Listener enregistré pour les messages")
    }

    fun unregisterListeners() {
        Wearable.getMessageClient(context).removeListener(this)
        Log.d("WearableListener", "📴 Listener supprimé pour les messages")
    }

    override fun onMessageReceived(event: MessageEvent) {
        val message = String(event.data, StandardCharsets.UTF_8)
        Log.d("WearableReceive", "📩 Message reçu depuis ${event.sourceNodeId} (path=${event.path}) : $message")

        when (event.path) {
            APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
                currentAckFromWearForAppOpenCheck = message
                Log.d("WearableACK", "📥 ACK reçu pour vérification d'ouverture : $message")
            }
            MESSAGE_ITEM_RECEIVED_PATH -> {
                onMessageReceivedCallback(message)
            }
            else -> {
                Log.w("WearableReceive", "⚠️ Message reçu avec un chemin inconnu : ${event.path}")
            }
        }
    }
}
