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

package org.catrobat.catroid.test.mqtt

import org.catrobat.catroid.devices.mqtt.MqttClientFactory
import org.catrobat.catroid.devices.mqtt.MqttClientInterface
import org.catrobat.catroid.devices.mqtt.MqttConnectionConfig
import org.catrobat.catroid.devices.mqtt.MqttListener
import org.catrobat.catroid.devices.mqtt.MqttManager
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MqttManagerTest {

    private lateinit var fakeClient: FakeMqttClient
    private lateinit var fakeFactory: FakeMqttClientFactory
    private lateinit var manager: MqttManager

    @Before
    fun setUp() {
        fakeClient = FakeMqttClient()
        fakeFactory = FakeMqttClientFactory(fakeClient)
        manager = MqttManager(fakeFactory)
    }

    private val defaultConfig = MqttConnectionConfig("localhost", 1883, "client-1", "", "", false)

    // --- Initial state ---

    @Test
    fun testIsNotConnectedInitially() {
        assertFalse(manager.isConnected)
    }

    // --- connect() ---

    @Test
    fun testConnectReturnsTrueOnSuccess() {
        assertTrue(manager.connect(defaultConfig))
        assertTrue(fakeClient.connectCalled)
    }

    @Test
    fun testConnectSetsCallbackOnClient() {
        manager.connect(defaultConfig)
        assertTrue(fakeClient.callbackSet)
    }

    @Test
    fun testConnectReturnsFalseWhenClientThrows() {
        fakeClient.throwOnConnect = true
        assertFalse(manager.connect(defaultConfig))
    }

    @Test
    fun testIsNotConnectedAfterConnectFailure() {
        fakeClient.throwOnConnect = true
        manager.connect(defaultConfig)
        assertFalse(manager.isConnected)
    }

    @Test
    fun testCloseIsCalledOnClientWhenConnectThrows() {
        fakeClient.throwOnConnect = true
        manager.connect(defaultConfig)
        assertTrue(fakeClient.closeCalled)
    }

    @Test
    fun testConnectWhenAlreadyConnectedDoesNotReconnect() {
        manager.connect(defaultConfig)
        fakeClient.connectCalled = false
        manager.connect(defaultConfig)
        assertFalse(fakeClient.connectCalled)
    }

    @Test
    fun testConnectWhenAlreadyConnectedReturnsTrue() {
        manager.connect(defaultConfig)
        assertTrue(manager.connect(defaultConfig))
    }

    @Test
    fun testConnectWithBlankHostReturnsFalse() {
        assertFalse(manager.connect(MqttConnectionConfig("   ", 1883, "client-1", "", "", false)))
    }

    @Test
    fun testConnectWithBlankHostDoesNotCallClient() {
        manager.connect(MqttConnectionConfig("   ", 1883, "client-1", "", "", false))
        assertFalse(fakeClient.connectCalled)
    }

    @Test
    fun testConnectSucceedsWithEmptyClientId() {
        manager.connect(MqttConnectionConfig("localhost", 1883, "", "", "", false))
        assertTrue(fakeClient.connectCalled)
    }

    @Test
    fun testConnectClosesStaleClientBeforeReconnecting() {
        manager.connect(defaultConfig)
        fakeClient.connected = false
        fakeFactory.createCalled = false
        manager.connect(defaultConfig)
        assertTrue(fakeClient.closeCalled)
        assertTrue(fakeFactory.createCalled)
    }

    @Test
    fun testConnectWhenAlreadyConnectedDoesNotCloseClient() {
        manager.connect(defaultConfig)
        fakeClient.closeCalled = false
        manager.connect(defaultConfig)
        assertFalse(fakeClient.closeCalled)
    }

    // --- URI building ---

    @Test
    fun testBuildServerUriWithoutTlsUsesTcpScheme() {
        assertTrue(manager.buildServerUri("localhost", 1883, false).startsWith("tcp://"))
    }

    @Test
    fun testBuildServerUriWithTlsUsesSslScheme() {
        assertTrue(manager.buildServerUri("localhost", 8883, true).startsWith("ssl://"))
    }

    @Test
    fun testBuildServerUriTcpFullUri() {
        assertEquals("tcp://broker.test.com:1883", manager.buildServerUri("broker.test.com", 1883, false))
    }

    @Test
    fun testBuildServerUriSslFullUri() {
        assertEquals("ssl://broker.test.com:8883", manager.buildServerUri("broker.test.com", 8883, true))
    }

    // --- ConnectOptions building ---

    @Test
    fun testBuildConnectOptionsUsesCleanSession() {
        assertTrue(manager.buildConnectOptions("", "").isCleanSession)
    }

    @Test
    fun testBuildConnectOptionsSetsUsernameAndPasswordWhenProvided() {
        val options = manager.buildConnectOptions("user", "pass")
        assertEquals("user", options.userName)
        assertEquals("pass", String(options.password ?: charArrayOf()))
    }

    @Test
    fun testBuildConnectOptionsDoesNotSetUsernameWhenEmpty() {
        assertEquals(null, manager.buildConnectOptions("", "").userName)
    }

    @Test
    fun testBuildConnectOptionsUsernameOnlyWithEmptyPasswordStillSets() {
        val options = manager.buildConnectOptions("user", "")
        assertEquals("user", options.userName)
        assertEquals("", String(options.password ?: charArrayOf()))
    }

    @Test
    fun testBuildConnectOptionsDoesNotSetUsernameWhenBlank() {
        assertEquals(null, manager.buildConnectOptions("   ", "").userName)
    }

    // --- disconnect() ---

    @Test
    fun testDisconnectCallsClientDisconnect() {
        manager.connect(defaultConfig)
        manager.disconnect()
        assertTrue(fakeClient.disconnectCalled)
    }

    @Test
    fun testDisconnectCallsClientClose() {
        manager.connect(defaultConfig)
        manager.disconnect()
        assertTrue(fakeClient.closeCalled)
    }

    @Test
    fun testDisconnectWhenNoClientDoesNotCallClient() {
        manager.disconnect()
        assertFalse(fakeClient.disconnectCalled)
        assertFalse(fakeClient.closeCalled)
    }

    @Test
    fun testDisconnectCleansUpDroppedConnection() {
        manager.connect(defaultConfig)
        fakeClient.connected = false
        manager.disconnect()
        assertTrue(fakeClient.disconnectCalled)
        assertTrue(fakeClient.closeCalled)
    }

    @Test
    fun testIsNotConnectedAfterDisconnect() {
        manager.connect(defaultConfig)
        manager.disconnect()
        assertFalse(manager.isConnected)
    }

    @Test
    fun testDisconnectTwiceDoesNotCrash() {
        manager.connect(defaultConfig)
        manager.disconnect()
        manager.disconnect()
        // no exception = pass
    }

    // --- publish() ---

    private fun connected(): MqttManager = manager.also { it.connect(defaultConfig) }

    @Test
    fun testPublishReturnsTrueOnSuccess() {
        assertTrue(connected().publish(defaultConfig, "home/light", "ON"))
        assertTrue(fakeClient.publishCalled)
    }

    @Test
    fun testPublishSendsTopicAndPayload() {
        connected().publish(defaultConfig, "home/light", "ON")
        assertEquals("home/light", fakeClient.lastPublishTopic)
        assertEquals("ON", String(fakeClient.lastPublishMessage!!.payload, Charsets.UTF_8))
    }

    @Test
    fun testPublishEncodesPayloadAsUtf8() {
        connected().publish(defaultConfig, "home/text", "grüße✓")
        assertEquals("grüße✓", String(fakeClient.lastPublishMessage!!.payload, Charsets.UTF_8))
    }

    @Test
    fun testPublishAllowsEmptyPayload() {
        assertTrue(connected().publish(defaultConfig, "home/light", ""))
        assertEquals(0, fakeClient.lastPublishMessage!!.payload.size)
    }

    @Test
    fun testPublishAppliesQosAndRetainedFlag() {
        connected().publish(defaultConfig, "home/light", "ON", qos = 2, retained = true)
        assertEquals(2, fakeClient.lastPublishMessage!!.qos)
        assertTrue(fakeClient.lastPublishMessage!!.isRetained)
    }

    @Test
    fun testPublishDefaultsToQosZeroNotRetained() {
        connected().publish(defaultConfig, "home/light", "ON")
        assertEquals(0, fakeClient.lastPublishMessage!!.qos)
        assertFalse(fakeClient.lastPublishMessage!!.isRetained)
    }

    @Test
    fun testPublishRejectsBlankTopic() {
        assertFalse(connected().publish(defaultConfig, "   ", "ON"))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishRejectsMultiLevelWildcardTopic() {
        assertFalse(connected().publish(defaultConfig, "home/#", "ON"))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishRejectsSingleLevelWildcardTopic() {
        assertFalse(connected().publish(defaultConfig, "home/+/state", "ON"))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishRejectsQosAboveRange() {
        assertFalse(connected().publish(defaultConfig, "home/light", "ON", qos = 3))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishRejectsNegativeQos() {
        assertFalse(connected().publish(defaultConfig, "home/light", "ON", qos = -1))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishLazilyConnectsWhenDisconnected() {
        assertTrue(manager.publish(defaultConfig, "home/light", "ON"))
        assertTrue(fakeClient.connectCalled)
        assertTrue(fakeClient.publishCalled)
    }

    @Test
    fun testPublishDoesNotReconnectWhenAlreadyConnected() {
        connected()
        fakeClient.connectCalled = false
        manager.publish(defaultConfig, "home/light", "ON")
        assertFalse(fakeClient.connectCalled)
    }

    @Test
    fun testPublishReturnsFalseWhenLazyConnectFails() {
        fakeClient.throwOnConnect = true
        assertFalse(manager.publish(defaultConfig, "home/light", "ON"))
        assertFalse(fakeClient.publishCalled)
    }

    @Test
    fun testPublishReturnsFalseWhenClientThrows() {
        connected()
        fakeClient.throwOnPublish = true
        assertFalse(manager.publish(defaultConfig, "home/light", "ON"))
    }

    // --- subscribe() ---

    @Test
    fun testSubscribeReturnsTrueOnSuccess() {
        assertTrue(connected().subscribe(defaultConfig, "home/light"))
        assertTrue(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeSendsTopicAndQos() {
        connected().subscribe(defaultConfig, "home/light", qos = 2)
        assertEquals(listOf("home/light"), fakeClient.subscribedTopics)
        assertEquals(2, fakeClient.lastSubscribeQos)
    }

    @Test
    fun testSubscribeAcceptsMultiLevelWildcard() {
        assertTrue(connected().subscribe(defaultConfig, "home/#"))
        assertEquals(listOf("home/#"), fakeClient.subscribedTopics)
    }

    @Test
    fun testSubscribeAcceptsSingleLevelWildcard() {
        assertTrue(connected().subscribe(defaultConfig, "home/+/state"))
        assertEquals(listOf("home/+/state"), fakeClient.subscribedTopics)
    }

    @Test
    fun testSubscribeRejectsBlankTopic() {
        assertFalse(connected().subscribe(defaultConfig, "  "))
        assertFalse(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeRejectsInvalidQos() {
        assertFalse(connected().subscribe(defaultConfig, "home/light", qos = 3))
        assertFalse(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeTracksActiveSubscription() {
        connected().subscribe(defaultConfig, "home/light")
        assertEquals(setOf("home/light"), manager.activeSubscriptions)
    }

    @Test
    fun testDuplicateSubscribeDoesNotCallClientAgain() {
        connected().subscribe(defaultConfig, "home/light")
        fakeClient.subscribeCalled = false
        assertTrue(manager.subscribe(defaultConfig, "home/light"))
        assertFalse(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeLazilyConnectsWhenDisconnected() {
        assertTrue(manager.subscribe(defaultConfig, "home/light"))
        assertTrue(fakeClient.connectCalled)
        assertTrue(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeReturnsFalseWhenLazyConnectFails() {
        fakeClient.throwOnConnect = true
        assertFalse(manager.subscribe(defaultConfig, "home/light"))
        assertFalse(fakeClient.subscribeCalled)
    }

    @Test
    fun testSubscribeReturnsFalseWhenClientThrows() {
        connected()
        fakeClient.throwOnSubscribe = true
        assertFalse(manager.subscribe(defaultConfig, "home/light"))
        assertTrue(manager.activeSubscriptions.isEmpty())
    }

    // --- unsubscribe() ---

    @Test
    fun testUnsubscribeReturnsTrueOnSuccess() {
        connected().subscribe(defaultConfig, "home/light")
        assertTrue(manager.unsubscribe("home/light"))
        assertTrue(fakeClient.unsubscribeCalled)
    }

    @Test
    fun testUnsubscribeStopsTrackingTopic() {
        connected().subscribe(defaultConfig, "home/light")
        manager.unsubscribe("home/light")
        assertTrue(manager.activeSubscriptions.isEmpty())
    }

    @Test
    fun testUnsubscribeWhenNotSubscribedDoesNotCallClient() {
        connected()
        assertTrue(manager.unsubscribe("home/light"))
        assertFalse(fakeClient.unsubscribeCalled)
    }

    @Test
    fun testUnsubscribeRejectsBlankTopic() {
        connected()
        assertFalse(manager.unsubscribe("  "))
    }

    @Test
    fun testUnsubscribeReturnsFalseWhenClientThrows() {
        connected().subscribe(defaultConfig, "home/light")
        fakeClient.throwOnUnsubscribe = true
        assertFalse(manager.unsubscribe("home/light"))
        assertEquals(setOf("home/light"), manager.activeSubscriptions)
    }

    // --- subscription lifecycle ---

    @Test
    fun testDisconnectClearsSubscriptions() {
        connected().subscribe(defaultConfig, "home/light")
        manager.disconnect()
        assertTrue(manager.activeSubscriptions.isEmpty())
    }

    @Test
    fun testReconnectAfterDropClearsStaleSubscriptions() {
        connected().subscribe(defaultConfig, "home/light")
        fakeClient.connected = false
        manager.connect(defaultConfig)
        assertTrue(manager.activeSubscriptions.isEmpty())
    }

    @Test
    fun testSubscribeAgainAfterDropReachesClient() {
        connected().subscribe(defaultConfig, "home/light")
        fakeClient.connected = false
        manager.connect(defaultConfig)
        fakeClient.subscribeCalled = false
        assertTrue(manager.subscribe(defaultConfig, "home/light"))
        assertTrue(fakeClient.subscribeCalled)
    }

    // --- topic based listener routing ---

    @Test
    fun testDispatchDeliversTopicAndPayloadToRegisteredListener() {
        val listener = FakeListener()
        manager.register("home/temp", listener)
        manager.dispatchMessage("home/temp", "22.5")
        assertEquals(listOf("home/temp" to "22.5"), listener.received)
    }

    @Test
    fun testDispatchDeliversToAllListenersOfSameTopic() {
        val first = FakeListener()
        val second = FakeListener()
        manager.register("home/temp", first)
        manager.register("home/temp", second)
        manager.dispatchMessage("home/temp", "22.5")
        assertEquals(1, first.received.size)
        assertEquals(1, second.received.size)
    }

    @Test
    fun testDispatchDoesNotDeliverToListenerOfDifferentTopic() {
        val listener = FakeListener()
        manager.register("home/temp", listener)
        manager.dispatchMessage("home/light", "ON")
        assertTrue(listener.received.isEmpty())
    }

    @Test
    fun testDispatchIgnoresUnknownTopic() {
        manager.dispatchMessage("home/unknown", "payload")
    }

    @Test
    fun testUnregisteredListenerStopsReceivingMessages() {
        val listener = FakeListener()
        manager.register("home/temp", listener)
        manager.unregister("home/temp", listener)
        manager.dispatchMessage("home/temp", "22.5")
        assertTrue(listener.received.isEmpty())
    }

    @Test
    fun testUnregisteringOneListenerKeepsOthersOnSameTopic() {
        val removed = FakeListener()
        val kept = FakeListener()
        manager.register("home/temp", removed)
        manager.register("home/temp", kept)
        manager.unregister("home/temp", removed)
        manager.dispatchMessage("home/temp", "22.5")
        assertTrue(removed.received.isEmpty())
        assertEquals(1, kept.received.size)
    }

    @Test
    fun testUnregisteringLastListenerDropsTopic() {
        val listener = FakeListener()
        manager.register("home/temp", listener)
        manager.unregister("home/temp", listener)
        assertTrue(manager.registeredTopics.isEmpty())
    }

    @Test
    fun testListenerCanBeReRegisteredAfterUnregister() {
        val listener = FakeListener()
        manager.register("home/temp", listener)
        manager.unregister("home/temp", listener)
        manager.register("home/temp", listener)
        manager.dispatchMessage("home/temp", "22.5")
        assertEquals(1, listener.received.size)
    }

    @Test
    fun testRegisterRejectsBlankTopic() {
        manager.register("  ", FakeListener())
        assertTrue(manager.registeredTopics.isEmpty())
    }

    @Test
    fun testUnregisteringFromUnknownTopicDoesNotCrash() {
        manager.unregister("home/nothing", FakeListener())
    }

    @Test
    fun testThrowingListenerDoesNotPreventOtherListeners() {
        val throwing = MqttListener { _, _ -> throw IllegalStateException("boom") }
        val healthy = FakeListener()
        manager.register("home/temp", throwing)
        manager.register("home/temp", healthy)
        manager.dispatchMessage("home/temp", "22.5")
        assertEquals(1, healthy.received.size)
    }

    private class FakeListener : MqttListener {
        val received = mutableListOf<Pair<String, String>>()
        override fun onMessageReceived(topic: String, payload: String) {
            received.add(topic to payload)
        }
    }

    // --- FakeMqttClientFactory ---

    private class FakeMqttClientFactory(private val client: FakeMqttClient) : MqttClientFactory {
        var createCalled = false
        override fun create(brokerUrl: String, clientId: String): FakeMqttClient {
            createCalled = true
            return client
        }
    }

    // --- FakeMqttClient ---

    private class FakeMqttClient : MqttClientInterface {
        var connected = false
        var connectCalled = false
        var disconnectCalled = false
        var closeCalled = false
        var callbackSet = false
        var throwOnConnect = false
        var publishCalled = false
        var throwOnPublish = false
        var lastPublishTopic: String? = null
        var lastPublishMessage: MqttMessage? = null
        var subscribeCalled = false
        var throwOnSubscribe = false
        var lastSubscribeQos = -1
        var unsubscribeCalled = false
        var throwOnUnsubscribe = false
        val subscribedTopics = mutableListOf<String>()

        override val isConnected get() = connected

        override fun connect(options: MqttConnectOptions) {
            if (throwOnConnect) throw MqttException(0)
            connectCalled = true
            connected = true
        }

        override fun publish(topic: String, message: MqttMessage) {
            if (throwOnPublish) throw MqttException(0)
            publishCalled = true
            lastPublishTopic = topic
            lastPublishMessage = message
        }

        override fun subscribe(topic: String, qos: Int) {
            if (throwOnSubscribe) throw MqttException(0)
            subscribeCalled = true
            subscribedTopics.add(topic)
            lastSubscribeQos = qos
        }

        override fun unsubscribe(topic: String) {
            if (throwOnUnsubscribe) throw MqttException(0)
            unsubscribeCalled = true
            subscribedTopics.remove(topic)
        }

        override fun disconnect() {
            disconnectCalled = true
            connected = false
        }

        override fun close() {
            closeCalled = true
        }

        override fun setCallback(callback: MqttCallback) {
            callbackSet = true
        }
    }
}
