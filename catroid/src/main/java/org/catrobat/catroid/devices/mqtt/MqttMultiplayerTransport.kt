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

import android.util.Log
import org.catrobat.catroid.formulaeditor.UserVariable
import org.koin.core.context.KoinContextHandler

/**
 * Carries Catroid's multiplayer variables over MQTT, as an alternative to the
 * existing Bluetooth transport.
 *
 * Bluetooth multiplayer is limited to devices that paired with each other. Over
 * MQTT any number of players on the same network, or on different networks via a
 * shared broker, observe the same variables.
 *
 * Topics are laid out as `catrobat/multiplayer/<room>/<sender>/<variable>`. The
 * sender is part of the topic rather than the payload so a device can recognise
 * and ignore the echo of its own publish without parsing anything: every client
 * subscribes to the whole room, and would otherwise immediately overwrite the
 * variable it just set.
 */
class MqttMultiplayerTransport(private val mqttManager: MqttManager) {

    private var config: MqttConnectionConfig? = null
    private var roomId: String? = null
    private var senderId: String? = null
    private var listener: MqttListener? = null

    val isStarted: Boolean get() = listener != null

    /**
     * @param onVariableReceived applies an incoming value. Supplied by the caller so
     * this class stays independent of ProjectManager and remains unit testable.
     */
    fun start(
        config: MqttConnectionConfig,
        roomId: String,
        senderId: String,
        onVariableReceived: (String, String) -> Unit
    ): Boolean {
        if (roomId.isBlank() || senderId.isBlank()) {
            Log.e(TAG, "Cannot start multiplayer transport: room or sender id is blank")
            return false
        }
        stop()
        this.config = config
        this.roomId = roomId
        this.senderId = senderId

        val filter = roomFilter(roomId)
        val roomListener = MqttListener { topic, payload ->
            val message = parse(topic) ?: return@MqttListener
            if (message.sender == senderId) {
                return@MqttListener
            }
            onVariableReceived(message.variable, payload)
        }
        mqttManager.register(filter, roomListener)
        listener = roomListener
        return mqttManager.subscribe(config, filter)
    }

    fun stop() {
        val filter = roomId?.let { roomFilter(it) }
        listener?.let { current ->
            filter?.let { mqttManager.unregister(it, current) }
        }
        listener = null
        roomId = null
        senderId = null
        config = null
    }

    fun sendVariable(variable: UserVariable?) {
        val name = variable?.name
        if (name.isNullOrBlank()) {
            return
        }
        val currentConfig = config
        val currentRoom = roomId
        val currentSender = senderId
        if (currentConfig == null || currentRoom == null || currentSender == null) {
            return
        }
        mqttManager.publish(
            currentConfig,
            "$TOPIC_ROOT/$currentRoom/$currentSender/$name",
            variable.value?.toString().orEmpty()
        )
    }

    private data class RoomMessage(val sender: String, val variable: String)

    private fun parse(topic: String): RoomMessage? {
        val segments = topic.split('/')
        if (segments.size != EXPECTED_SEGMENTS) {
            Log.d(TAG, "Ignoring multiplayer topic with unexpected shape: '$topic'")
            return null
        }
        return RoomMessage(segments[SENDER_INDEX], segments[VARIABLE_INDEX])
    }

    companion object {
        private val TAG = MqttMultiplayerTransport::class.java.simpleName

        /**
         * Resolves the transport only when a Koin context exists, returning null
         * otherwise. Script actions run in plain JVM unit tests where no dependency
         * graph is started, and requiring one there would make an unrelated test fail
         * simply because a variable was assigned.
         */
        @JvmStatic
        fun activeOrNull(): MqttMultiplayerTransport? =
            KoinContextHandler.getOrNull()?.getOrNull(MqttMultiplayerTransport::class)

        const val TOPIC_ROOT = "catrobat/multiplayer"
        private const val EXPECTED_SEGMENTS = 5
        private const val SENDER_INDEX = 3
        private const val VARIABLE_INDEX = 4

        fun roomFilter(roomId: String) = "$TOPIC_ROOT/$roomId/#"

        /**
         * Derives a room id from a project name.
         *
         * Project names are free text and are only sanitised for use as directory
         * names, which leaves the MQTT wildcards + and # untouched. Either of them
         * inside a topic filter makes it invalid and the broker rejects the
         * subscription, and a slash would add topic levels and break the segment
         * layout the sender is read from. All three are replaced so any project
         * name yields a usable room.
         */
        @JvmStatic
        fun roomIdFor(projectName: String): String =
            projectName.trim()
                .replace(Regex("[/+#]"), "_")
                .ifEmpty { DEFAULT_ROOM }

        private const val DEFAULT_ROOM = "room"
    }
}
