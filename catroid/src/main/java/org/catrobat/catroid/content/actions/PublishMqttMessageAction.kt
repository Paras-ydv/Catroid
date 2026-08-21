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

package org.catrobat.catroid.content.actions

import android.util.Log
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.devices.mqtt.MqttConnectionConfig
import org.catrobat.catroid.devices.mqtt.MqttManager
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.InterpretationException
import org.koin.java.KoinJavaComponent.inject

/**
 * Publishes one message when the brick is reached.
 *
 * Extends TemporalAction with the default zero duration so the publish happens
 * once and the script continues in the same frame, matching how the other
 * fire-and-forget device bricks behave rather than blocking on the broker.
 */
class PublishMqttMessageAction : TemporalAction() {

    var scope: Scope? = null
    var topicFormula: Formula? = null
    var messageFormula: Formula? = null
    var qos: Int = MqttManager.DEFAULT_QOS
    var retained: Boolean = false

    private val mqttManager: MqttManager by inject(MqttManager::class.java)

    override fun begin() {
        val currentScope = scope ?: run {
            Log.e(TAG, "Cannot publish: action has no scope")
            return
        }
        val topic = interpret(topicFormula, currentScope)
        if (topic.isBlank()) {
            Log.e(TAG, "Cannot publish: topic evaluated to an empty value")
            return
        }
        val message = interpret(messageFormula, currentScope)
        val config = MqttConnectionConfig.fromContext(CatroidApplication.getAppContext())
        mqttManager.publish(config, topic, message, qos, retained)
    }

    override fun update(percent: Float) = Unit

    /**
     * A formula that cannot be evaluated yields an empty value rather than
     * aborting the script, consistent with the other formula-driven bricks.
     */
    private fun interpret(formula: Formula?, scope: Scope): String =
        try {
            formula?.interpretString(scope).orEmpty()
        } catch (exception: InterpretationException) {
            Log.d(TAG, "Formula interpretation for this specific Brick failed.", exception)
            ""
        }

    companion object {
        private val TAG = PublishMqttMessageAction::class.java.simpleName
    }
}
