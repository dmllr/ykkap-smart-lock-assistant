package com.ykkap.lockbridge.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck
import java.nio.charset.StandardCharsets
import java.util.UUID

class MqttManager(
  private val serverHost: String,
  private val serverPort: Int,
  private val username: String?,
  private val password: String?,
  private val onConnectionStatusChange: (isConnected: Boolean, reason: String?) -> Unit,
  private val onMessageArrived: (topic: String, message: String) -> Unit
) {
  private var mqttClient: Mqtt5AsyncClient? = null

  val isConnected: Boolean
    get() = mqttClient?.state?.isConnected == true

  companion object {
    private const val TAG = "MqttManager"
  }

  fun connect() {
    if (isConnected) {
      Log.i(TAG, "Already connected.")
      return
    }

    try {
      val clientBuilder = MqttClient.builder()
        .useMqttVersion5()
        .identifier(UUID.randomUUID().toString())
        .serverHost(serverHost)
        .serverPort(serverPort)
        .automaticReconnectWithDefaultConfig()
        .addConnectedListener {
          Log.i(TAG, "MQTT connection successful.")
          onConnectionStatusChange(true, null)
          subscribeToTopics()
        }
        .addDisconnectedListener { context ->
          val cause = context.cause
          Log.w(TAG, "MQTT connection lost. Will reconnect automatically.", cause)
          onConnectionStatusChange(false, "Reconnecting...")
        }

      if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
        clientBuilder.simpleAuth()
          .username(username)
          .password(password.toByteArray(StandardCharsets.UTF_8))
          .applySimpleAuth()
      }

      val client = clientBuilder.buildAsync()

      // Define the Last Will and Testament (LWT) message. This is a core MQTT feature where the broker
      // publishes a message on the client's behalf if the client disconnects ungracefully (e.g., network loss, app crash).
      // This ensures Home Assistant knows the lock is 'offline'.
      val willMessage = Mqtt5Publish.builder()
        .topic("home/doorlock/availability")
        .payload("offline".toByteArray(StandardCharsets.UTF_8))
        .retain(false) // Availability status should not be a retained message.
        .build()

      client.connectWith()
        .cleanStart(true)
        .keepAlive(60)
        .willPublish(willMessage) // Set the LWT for this connection.
        .send()
        .whenComplete { _: Mqtt5ConnAck?, throwable: Throwable? ->
          if (throwable != null) {
            Log.e(TAG, "MQTT connect onFailure.", throwable)
            onConnectionStatusChange(false, throwable.message ?: "Connection failed")
          }
        }

      mqttClient = client

    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize MqttManager", e)
      onConnectionStatusChange(false, e.message ?: "MQTT initialization failed.")
    }
  }

  private fun subscribeToTopics() {
    val client = mqttClient ?: return
    if (!client.state.isConnected) {
      Log.w(TAG, "Cannot subscribe, MQTT client is not connected.")
      return
    }
    Log.d(TAG, "Subscribing to topics, ignoring retained messages.")

    val topics = listOf("home/doorlock/set", "home/doorlock/check_status", "home/doorlock/debug")

    // The fluent API for subscribing requires chaining the addition of subscriptions,
    // then defining the callback for message handling, and finally sending the request.
    client.subscribeWith()
      .addSubscription()
      .topicFilter(topics[0])
      .retainHandling(Mqtt5RetainHandling.DO_NOT_SEND)
      .applySubscription()
      .addSubscription()
      .topicFilter(topics[1])
      .retainHandling(Mqtt5RetainHandling.DO_NOT_SEND)
      .applySubscription()
      .addSubscription()
      .topicFilter(topics[2])
      .retainHandling(Mqtt5RetainHandling.DO_NOT_SEND)
      .applySubscription()
      .callback { publish: Mqtt5Publish -> handleMessage(publish) }
      .send()
      .whenComplete { subAck: Mqtt5SubAck?, throwable: Throwable? ->
        if (throwable != null) {
          Log.e(TAG, "Failed to subscribe to topics.", throwable)
        } else subAck?.reasonCodes?.forEachIndexed { index, reasonCode ->
          val topic = topics.getOrNull(index) ?: "UNKNOWN_TOPIC"
          if (reasonCode.isError) {
            Log.w(TAG, "Subscription to topic '$topic' failed with code: $reasonCode")
          } else {
            Log.i(TAG, "Successfully subscribed to topic '$topic'.")
          }
        }
      }
  }

  private fun handleMessage(publish: Mqtt5Publish) {
    if (!publish.payload.isPresent) {
      Log.d(TAG, "Received message on topic '${publish.topic}' with an empty payload. Ignoring.")
      return
    }

    val topic = publish.topic.toString()
    val message = StandardCharsets.UTF_8.decode(publish.payload.get()).toString()
    onMessageArrived(topic, message)
  }

  fun publish(topic: String, message: String) {
    if (mqttClient?.state?.isConnected != true) {
      Log.w(TAG, "Cannot publish message, MQTT client is not connected.")
      return
    }
    mqttClient?.publishWith()
      ?.topic(topic)
      ?.payload(message.toByteArray(StandardCharsets.UTF_8))
      ?.send()
  }

  fun disconnect() {
    if (mqttClient?.state?.isConnected == true) {
      Log.d(TAG, "Disconnecting from MQTT broker.")
      mqttClient?.disconnect()
    }
  }
}
