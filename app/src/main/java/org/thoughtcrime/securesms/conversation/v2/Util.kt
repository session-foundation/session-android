/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.conversation.v2

import org.session.libsignal.utilities.Log

object Util {
    private val TAG: String = Log.tag(Util::class.java)

    /**
     * Returns half of the difference between the given length, and the length when scaled by the
     * given scale.
     */
    fun halfOffsetFromScale(length: Int, scale: Float): Float {
        val scaledLength = length * scale
        return (length - scaledLength) / 2
    }

}