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
import org.catrobat.catroid.content.eventids.MqttEventId

/**
 * Turns messages routed by [MqttManager] into Catroid events for one subscription
 * filter.
 *
 * The dispatcher is deliberately unaware of how an event reaches the sprites: it
 * builds the [MqttEventId] and hands it to [onEvent], which the stage supplies.
 * That keeps the conversion testable without a running libGDX stage, and keeps
 * the graphics layer out of the device package.
 *
 * Because MqttManager drains its queue on the render thread, [onMessageReceived]
 * is already called there and may touch Catroid state directly.
 */
class MqttEventDispatcher(
    val topicFilter: String,
    private val onEvent: (MqttEventId, ReceivedMqttMessage) -> Unit
) : MqttListener {

    /**
     * The most recent message this filter matched. Scripts read the topic and
     * payload from here, so the concrete topic survives wildcard subscriptions
     * where the filter alone would not say which device sent the message.
     */
    @Volatile
    var lastMessage: ReceivedMqttMessage? = null
        private set

    override fun onMessageReceived(topic: String, payload: String) {
        val message = ReceivedMqttMessage(topic, payload)
        lastMessage = message
        Log.d(TAG, "Firing MQTT event for filter '$topicFilter' from topic '$topic'")
        onEvent(MqttEventId(topicFilter), message)
    }

    companion object {
        private val TAG = MqttEventDispatcher::class.java.simpleName
    }
}
