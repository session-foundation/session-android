package org.thoughtcrime.securesms.audio.model

import android.os.Bundle
import androidx.media3.session.SessionCommand

object AudioCommands {
    private const val PREFIX = "org.thoughtcrime.securesms.audio."
    private const val SCRUB_START = PREFIX + "SCRUB_START"
    private const val SCRUB_STOP  = PREFIX + "SCRUB_STOP"

    val ScrubStart = SessionCommand(SCRUB_START, Bundle.EMPTY)
    val ScrubStop  = SessionCommand(SCRUB_STOP, Bundle.EMPTY)

    fun isScrubStart(cmd: SessionCommand) = cmd.customAction == SCRUB_START
    fun isScrubStop(cmd: SessionCommand) = cmd.customAction == SCRUB_STOP
}
