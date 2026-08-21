/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2026 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.devices.mqtt

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MqttManager(private val clientFactory: MqttClientFactory = DefaultMqttClientFactory) {

    @Volatile
    private var mqttClient: MqttClientInterface? = null

    /** Subscriptions live on the current broker session and are lost when it ends. */
    private val subscriptions = ConcurrentHashMap<String, Int>()

    /**
     * What the app asked to be subscribed to, independent of any session. Survives
     * dropped connections and failed reconnect attempts, so recovery still knows
     * what to restore after the broker has been unreachable for a while.
     */
    private val desiredSubscriptions = ConcurrentHashMap<String, Int>()

    internal val activeSubscriptions: Set<String> get() = subscriptions.keys.toSet()

    private val incomingMessages = MqttMessageQueue()

    private val outgoingMessages = MqttPublishQueue()

    @Volatile
    private var lastConfig: MqttConnectionConfig? = null

    @Volatile
    private var reconnectAttempt = 0

    /** Disabled by disconnect() so an intentional teardown is not undone. */
    @Volatile
    private var autoReconnectEnabled = true

    private val reconnectExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MqttReconnect").apply { isDaemon = true }
        }

    internal val pendingMessageCount: Int get() = incomingMessages.size

    internal val pendingPublishCount: Int get() = outgoingMessages.size

    /**
     * Drains everything the network thread has buffered and routes it to the
     * registered listeners. Must be called from the thread that is allowed to
     * touch Catroid state, which is the render thread while a stage is running.
     */
    fun dispatchPendingMessages() {
        incomingMessages.drain().forEach { dispatchMessage(it.topic, it.payload) }
    }

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<MqttListener>>()

    private val wildcardFilters = CopyOnWriteArraySet<String>()

    internal val registeredTopics: Set<String> get() = listeners.keys.toSet()

    /**
     * Registers [listener] for messages arriving on [topic]. Registration is
     * independent of the broker subscription: callers subscribe so the broker
     * delivers the topic, and register so the delivered message reaches them.
     */
    fun register(topic: String, listener: MqttListener) {
        if (topic.isBlank()) {
            Log.e(TAG, "Cannot register listener: topic is blank")
            return
        }
        val added = listeners.computeIfAbsent(topic) { CopyOnWriteArraySet() }.add(listener)
        if (MqttTopicMatcher.containsWildcard(topic)) {
            wildcardFilters.add(topic)
        }
        if (added) {
            Log.d(TAG, "Listener registered for '$topic'")
        }
    }

    fun unregister(topic: String, listener: MqttListener) {
        val topicListeners = listeners[topic] ?: return
        if (topicListeners.remove(listener)) {
            Log.d(TAG, "Listener unregistered from '$topic'")
        }
        // Drop the topic entry once its last listener is gone so routing does not
        // accumulate empty sets over repeated stage restarts.
        if (topicListeners.isEmpty()) {
            listeners.remove(topic, topicListeners)
            wildcardFilters.remove(topic)
        }
    }

    /**
     * Routes a received message to every listener whose filter matches its topic.
     *
     * Exact filters resolve through a single map lookup. Wildcard filters require
     * comparing the topic against each one, so they are kept in a separate set and
     * only that smaller collection is scanned.
     *
     * A listener that throws must not prevent the remaining listeners from seeing
     * the message, so failures are contained per listener.
     */
    internal fun dispatchMessage(topic: String, payload: String) {
        var delivered = false
        listeners[topic]?.forEach { listener ->
            notifyListener(listener, topic, payload)
            delivered = true
        }
        wildcardFilters.forEach { filter ->
            if (MqttTopicMatcher.matches(filter, topic)) {
                listeners[filter]?.forEach { listener ->
                    notifyListener(listener, topic, payload)
                    delivered = true
                }
            }
        }
        if (!delivered) {
            Log.d(TAG, "No listener registered for '$topic', ignoring message")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun notifyListener(listener: MqttListener, topic: String, payload: String) {
        try {
            listener.onMessageReceived(topic, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Listener failed handling message on '$topic'", e)
        }
    }

    val isConnected: Boolean
        get() = mqttClient?.isConnected == true

    companion object {
        private val TAG = MqttManager::class.java.simpleName
        private const val TCP_SCHEME = "tcp"
        private const val SSL_SCHEME = "ssl"
        private const val CONNECTION_TIMEOUT = 5
        private const val MIN_QOS = 0
        private const val MAX_QOS = 2
        const val DEFAULT_QOS = 0
        private const val MULTI_LEVEL_WILDCARD = '#'
        private const val SINGLE_LEVEL_WILDCARD = '+'
        private const val INITIAL_BACKOFF_MILLIS = 1000L
        private const val MAX_BACKOFF_MILLIS = 60_000L
        private const val MAX_BACKOFF_EXPONENT = 6
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    fun connectFromContext(context: Context) = connect(MqttConnectionConfig.fromContext(context))

    fun connect(config: MqttConnectionConfig): Boolean = synchronized(this) {
        autoReconnectEnabled = true
        if (isConnected) return true
        if (config.host.isBlank()) {
            Log.e(TAG, "Cannot connect: host is blank")
            return false
        }
        val currentClient = mqttClient
        if (currentClient != null && !currentClient.isConnected) {
            try {
                currentClient.close()
            } catch (e: MqttException) {
                Log.e(TAG, "Failed to close stale client before reconnect", e)
            }
            mqttClient = null
            // The new client starts a fresh broker session with no subscriptions.
            // desiredSubscriptions still remembers them, and they are re-issued below.
            subscriptions.clear()
        }
        return try {
            val brokerUrl = buildServerUri(config.host, config.port, config.useTls)
            val resolvedClientId = config.clientId.ifEmpty { MqttClient.generateClientId() }
            val client = clientFactory.create(brokerUrl, resolvedClientId).also { mqttClient = it }
            client.setCallback(callback)
            client.connect(buildConnectOptions(config.username, config.password))
            val connected = client.isConnected
            Log.d(TAG, "Connect result for clientId=$resolvedClientId at $brokerUrl: connected=$connected")
            if (connected) {
                lastConfig = config
                reconnectAttempt = 0
                restoreSubscriptions(desiredSubscriptions.toMap())
                flushOutgoingMessages()
            }
            connected
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to connect to ${config.host}:${config.port}", e)
            try {
                mqttClient?.close()
            } catch (closeEx: MqttException) {
                Log.e(TAG, "Failed to close client after connect error", closeEx)
            }
            mqttClient = null
            false
        }
    }

    fun publishFromContext(
        context: Context,
        topic: String,
        payload: String,
        qos: Int = DEFAULT_QOS,
        retained: Boolean = false
    ) = publish(MqttConnectionConfig.fromContext(context), topic, payload, qos, retained)

    fun publish(
        config: MqttConnectionConfig,
        topic: String,
        payload: String,
        qos: Int = DEFAULT_QOS,
        retained: Boolean = false
    ): Boolean {
        if (topic.isBlank()) {
            Log.e(TAG, "Cannot publish: topic is blank")
            return false
        }
        if (topic.contains(MULTI_LEVEL_WILDCARD) || topic.contains(SINGLE_LEVEL_WILDCARD)) {
            Log.e(TAG, "Cannot publish: topic contains wildcard characters")
            return false
        }
        if (qos !in MIN_QOS..MAX_QOS) {
            Log.e(TAG, "Cannot publish: invalid QoS value $qos")
            return false
        }
        val pending = PendingPublish(topic, payload, qos, retained)
        if (!isConnected && !connect(config)) {
            Log.w(TAG, "Broker unreachable, queueing message for '$topic'")
            outgoingMessages.enqueue(pending)
            return true
        }
        val client = mqttClient ?: run {
            Log.w(TAG, "No client available, queueing message for '$topic'")
            outgoingMessages.enqueue(pending)
            return true
        }
        return try {
            client.publish(topic, buildMessage(payload, qos, retained))
            Log.d(TAG, "Published message to '$topic'")
            true
        } catch (e: MqttException) {
            // The connection most likely dropped mid-publish, so keep the message for
            // the next successful connect instead of losing it.
            Log.e(TAG, "Failed to publish to '$topic', queueing for retry", e)
            outgoingMessages.enqueue(pending)
            false
        }
    }

    /**
     * Re-issues the subscriptions that belonged to the previous session. Called
     * after a reconnect, since a clean session starts the broker with none.
     */
    private fun restoreSubscriptions(topics: Map<String, Int>) {
        if (topics.isEmpty()) {
            return
        }
        val client = mqttClient ?: return
        Log.d(TAG, "Restoring ${topics.size} subscription(s)")
        topics.forEach { (topic, qos) ->
            try {
                client.subscribe(topic, qos)
                subscriptions[topic] = qos
            } catch (e: MqttException) {
                Log.e(TAG, "Failed to restore subscription '$topic'", e)
            }
        }
    }

    /**
     * Sends everything buffered while offline, oldest first.
     *
     * A message is only considered delivered once the client accepted it. If the
     * connection drops again part way through, the remainder goes back on the
     * queue so the flush resumes rather than losing the tail.
     */
    private fun flushOutgoingMessages() {
        if (outgoingMessages.isEmpty()) {
            return
        }
        val client = mqttClient ?: return
        val pending = outgoingMessages.drain()
        Log.d(TAG, "Flushing ${pending.size} queued message(s)")
        pending.forEachIndexed { index, message ->
            try {
                client.publish(message.topic, buildMessage(message.payload, message.qos, message.retained))
            } catch (e: MqttException) {
                Log.e(TAG, "Flush interrupted at '${message.topic}', requeueing remainder", e)
                outgoingMessages.requeueAll(pending.drop(index))
                return
            }
        }
    }

    fun subscribeFromContext(context: Context, topic: String, qos: Int = DEFAULT_QOS) =
        subscribe(MqttConnectionConfig.fromContext(context), topic, qos)

    fun subscribe(config: MqttConnectionConfig, topic: String, qos: Int = DEFAULT_QOS): Boolean {
        if (topic.isBlank()) {
            Log.e(TAG, "Cannot subscribe: topic is blank")
            return false
        }
        if (qos !in MIN_QOS..MAX_QOS) {
            Log.e(TAG, "Cannot subscribe: invalid QoS value $qos")
            return false
        }
        subscriptions[topic]?.let { existingQos ->
            Log.d(TAG, "Already subscribed to '$topic' with QoS $existingQos, ignoring duplicate")
            return true
        }
        // Wildcards are valid here, unlike for publish.
        if (!isConnected && !connect(config)) {
            Log.e(TAG, "Cannot subscribe: connection failed")
            return false
        }
        val client = mqttClient ?: run {
            Log.e(TAG, "Cannot subscribe: client is null")
            return false
        }
        return try {
            client.subscribe(topic, qos)
            subscriptions[topic] = qos
            desiredSubscriptions[topic] = qos
            Log.d(TAG, "Subscribed to '$topic' with QoS $qos")
            true
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to subscribe to '$topic'", e)
            false
        }
    }

    fun unsubscribe(topic: String): Boolean {
        if (topic.isBlank()) {
            Log.e(TAG, "Cannot unsubscribe: topic is blank")
            return false
        }
        if (!subscriptions.containsKey(topic)) {
            Log.d(TAG, "Not subscribed to '$topic', ignoring")
            return true
        }
        val client = mqttClient ?: run {
            Log.e(TAG, "Cannot unsubscribe: client is null")
            return false
        }
        return try {
            client.unsubscribe(topic)
            desiredSubscriptions.remove(topic)
            val qos = subscriptions.remove(topic)
            Log.d(TAG, "Unsubscribed from '$topic' (was QoS $qos)")
            true
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to unsubscribe from '$topic'", e)
            false
        }
    }

    internal fun buildMessage(payload: String, qos: Int, retained: Boolean) =
        MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = qos
            isRetained = retained
        }

    fun disconnect() {
        // Stop retrying first: a reconnect scheduled by a drop must not resurrect a
        // connection the user just ended by leaving the stage.
        autoReconnectEnabled = false
        // Listeners belong to the run that registered them. The manager outlives any
        // single stage, so keeping them would mean a second run of the same project
        // delivers every message twice, a third run three times, and so on. Cleared
        // before the client guard because a run that failed to connect still
        // registered its listeners.
        listeners.clear()
        wildcardFilters.clear()
        synchronized(this) {
            if (mqttClient == null) return
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
                Log.d(TAG, "Disconnected and closed client")
            } catch (e: MqttException) {
                Log.e(TAG, "Error during disconnect", e)
            } finally {
                subscriptions.clear()
                desiredSubscriptions.clear()
                incomingMessages.clear()
                // An intentional disconnect ends the session, so queued messages are
                // dropped rather than replayed into an unrelated later run.
                outgoingMessages.clear()
                mqttClient = null
            }
        }
    }

    internal fun buildServerUri(host: String, port: Int, useTls: Boolean): String {
        val scheme = if (useTls) SSL_SCHEME else TCP_SCHEME
        return "$scheme://$host:$port"
    }

    internal fun buildConnectOptions(username: String, password: String) = MqttConnectOptions().apply {
        isCleanSession = true
        connectionTimeout = CONNECTION_TIMEOUT
        if (username.isNotBlank()) {
            this.userName = username
            this.password = password.toCharArray()
        }
    }

    /**
     * Waits before each reconnect attempt, doubling the delay so a broker that is
     * down does not get hammered and the phone's radio is not kept awake retrying
     * in a tight loop. The delay is capped so recovery still happens promptly once
     * the broker returns.
     */
    internal fun backoffDelayMillis(attempt: Int): Long {
        val exponent = attempt.coerceAtLeast(0).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val delay = INITIAL_BACKOFF_MILLIS shl exponent
        return delay.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * Reconnects using the configuration of the last successful connection,
     * restoring subscriptions and flushing queued publishes as a side effect of
     * connect(). Returns false when there is nothing to reconnect to.
     */
    internal fun reconnectNow(): Boolean {
        val config = lastConfig ?: run {
            Log.d(TAG, "No previous configuration, skipping reconnect")
            return false
        }
        return connect(config)
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled) {
            return
        }
        val attempt = reconnectAttempt++
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Giving up reconnecting after $attempt attempts")
            return
        }
        val delay = backoffDelayMillis(attempt)
        Log.d(TAG, "Scheduling reconnect attempt ${attempt + 1} in ${delay}ms")
        reconnectExecutor.schedule({
            if (!isConnected && reconnectNow().not()) {
                scheduleReconnect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private val callback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            Log.e(TAG, "Connection lost: ${cause?.message}")
            scheduleReconnect()
        }
        override fun messageArrived(topic: String, message: MqttMessage) {
            // Runs on the Paho network thread: buffer only, never route from here.
            incomingMessages.enqueue(
                ReceivedMqttMessage(topic, String(message.payload, Charsets.UTF_8))
            )
        }
        // Delivery tokens are not used until publish is implemented in a later ticket.
        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }
}
