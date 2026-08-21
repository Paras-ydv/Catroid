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
import org.catrobat.catroid.content.EventWrapper
import org.catrobat.catroid.content.MqttScript
import org.catrobat.catroid.content.Project

/**
 * Subscribes every MQTT script in a project and keeps the resulting listeners so
 * they can be torn down when the stage ends.
 *
 * Subscribing happens once at stage start rather than when a script first runs,
 * because a receive script has to be listening before the first message arrives,
 * and MQTT delivers nothing that was published before the subscription existed.
 *
 * Scripts sharing a topic share one broker subscription: MqttManager treats the
 * second subscribe as a duplicate, while both listeners are registered and both
 * scripts are triggered.
 */
class MqttScriptRegistrar(
    private val mqttManager: MqttManager,
    private val config: MqttConnectionConfig
) {

    private val registered = mutableListOf<Pair<String, MqttListener>>()

    /**
     * Returns the dispatchers created, keyed by the script they belong to, so the
     * caller can bind incoming values to that script's variables.
     */
    fun registerScriptsOf(project: Project): Map<MqttScript, MqttEventDispatcher> {
        val dispatchers = mutableMapOf<MqttScript, MqttEventDispatcher>()
        project.mqttScripts().forEach { script ->
            val topic = script.topic
            if (topic.isNullOrBlank()) {
                Log.w(TAG, "Skipping MQTT script with no topic")
                return@forEach
            }
            val dispatcher = MqttEventDispatcher(topic) { eventId, _ ->
                project.fireToAllSprites(EventWrapper(eventId, false))
            }
            mqttManager.register(topic, dispatcher)
            registered.add(topic to dispatcher)
            if (!mqttManager.subscribe(config, topic)) {
                Log.e(TAG, "Failed to subscribe MQTT script topic '$topic'")
            }
            dispatchers[script] = dispatcher
        }
        Log.d(TAG, "Registered ${dispatchers.size} MQTT script(s)")
        return dispatchers
    }

    fun unregisterAll() {
        registered.forEach { (topic, listener) -> mqttManager.unregister(topic, listener) }
        registered.clear()
    }

    companion object {
        private val TAG = MqttScriptRegistrar::class.java.simpleName

        private fun Project.mqttScripts(): List<MqttScript> =
            sceneList.orEmpty()
                .flatMap { scene -> scene.spriteList.orEmpty() }
                .flatMap { sprite -> sprite.scriptList.orEmpty() }
                .filterIsInstance<MqttScript>()
    }
}
