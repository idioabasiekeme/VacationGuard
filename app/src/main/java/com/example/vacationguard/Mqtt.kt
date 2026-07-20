package com.example.vacationguard

import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Thin wrapper around the Eclipse Paho MQTT client. Both phones meet on
 * a free public broker; the shared Home ID keeps the topics private-ish.
 * Topics: vacationguard/<homeId>/{status,alert,snapshot,cmd}
 */
class Mqtt(private val homeId: String) {

    companion object {
        const val BROKER = "tcp://broker.hivemq.com:1883"
        const val EXTRA_HOME_ID = "home_id"
        const val T_STATUS = "status"
        const val T_ALERT = "alert"
        const val T_SNAPSHOT = "snapshot"
        const val T_CMD = "cmd"
        const val T_LIVE = "live"
        const val CMD_SNAPSHOT = "SNAPSHOT"
        const val CMD_SIREN = "SIREN"
        const val CMD_SIREN_OFF = "SIREN_OFF"
        const val CMD_LIVE_ON = "LIVE_ON"
        const val CMD_LIVE_OFF = "LIVE_OFF"

        const val EXTRA_EMAIL_FROM = "email_from"
        const val EXTRA_EMAIL_PASS = "email_pass"
        const val EXTRA_EMAIL_TO = "email_to"
    }

    private val client = MqttAsyncClient(
        BROKER, "vg-" + UUID.randomUUID().toString().take(16), MemoryPersistence()
    )

    fun topic(sub: String) = "vacationguard/$homeId/$sub"

    fun connect(
        lastWillOffline: Boolean,
        onMessage: (sub: String, payload: ByteArray) -> Unit,
        onConnected: (Boolean) -> Unit,
        subs: List<String>
    ) {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                subs.forEach { client.subscribe(topic(it), 1) }
                onConnected(true)
            }
            override fun connectionLost(cause: Throwable?) { onConnected(false) }
            override fun messageArrived(t: String, message: MqttMessage) {
                onMessage(t.substringAfterLast('/'), message.payload)
            }
            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            if (lastWillOffline) {
                setWill(topic(T_STATUS), "OFFLINE".toByteArray(), 1, true)
            }
        }
        client.connect(opts)
    }

    fun publish(sub: String, payload: ByteArray, retained: Boolean = false) {
        if (client.isConnected) {
            client.publish(topic(sub), payload, 1, retained)
        }
    }

    fun disconnect() {
        runCatching { if (client.isConnected) client.disconnect() }
    }
}
