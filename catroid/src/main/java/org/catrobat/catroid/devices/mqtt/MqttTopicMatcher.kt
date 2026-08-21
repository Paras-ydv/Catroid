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

/**
 * Matches published topic names against subscription filters following the topic
 * matching rules in the MQTT 3.1.1 specification, section 4.7.
 *
 * `+` matches exactly one topic level. `#` matches the remaining levels including
 * none at all, so `sport/#` also matches `sport` itself. Neither wildcard matches
 * a topic beginning with `$`, which brokers reserve for their own statistics.
 */
object MqttTopicMatcher {

    private const val LEVEL_SEPARATOR = '/'
    private const val SINGLE_LEVEL = "+"
    private const val MULTI_LEVEL = "#"
    private const val RESERVED_PREFIX = '$'

    fun containsWildcard(filter: String): Boolean =
        filter.contains(SINGLE_LEVEL) || filter.contains(MULTI_LEVEL)

    @Suppress("ReturnCount")
    fun matches(filter: String, topic: String): Boolean {
        if (filter == topic) {
            return true
        }
        if (filter.isEmpty() || topic.isEmpty()) {
            return false
        }
        val filterLevels = filter.split(LEVEL_SEPARATOR)
        val topicLevels = topic.split(LEVEL_SEPARATOR)

        // A wildcard at the first level must not reach broker-reserved topics.
        if (topic[0] == RESERVED_PREFIX &&
            (filterLevels[0] == SINGLE_LEVEL || filterLevels[0] == MULTI_LEVEL)
        ) {
            return false
        }

        filterLevels.forEachIndexed { index, level ->
            if (level == MULTI_LEVEL) {
                // Valid only as the final level, where it absorbs whatever remains.
                return index == filterLevels.lastIndex
            }
            if (index >= topicLevels.size) {
                return false
            }
            if (level != SINGLE_LEVEL && level != topicLevels[index]) {
                return false
            }
        }
        return filterLevels.size == topicLevels.size
    }
}
