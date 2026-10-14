package org.thoughtcrime.securesms.audio

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.audio.model.AudioCommands
import java.util.concurrent.TimeUnit

/**
 * A controller that connects but is granted no player commands fails silently: MediaController drops
 * each call with a logcat warning and playback never starts. Connecting through the real service is
 * the only way to see the commands the session actually grants.
 */
@RunWith(AndroidJUnit4::class)
class AudioMediaServiceConnectionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: MediaController

    @Before
    fun connect() {
        val context = instrumentation.targetContext
        val token = SessionToken(context, ComponentName(context, AudioMediaService::class.java))

        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, token).buildAsync()
        }
        controller = future.get(10, TimeUnit.SECONDS)
    }

    @After
    fun release() {
        instrumentation.runOnMainSync { controller.release() }
    }

    @Test
    fun inAppControllerIsGrantedPlaybackCommands() {
        for ((name, command) in listOf(
            "COMMAND_SET_MEDIA_ITEM" to Player.COMMAND_SET_MEDIA_ITEM,
            "COMMAND_PREPARE" to Player.COMMAND_PREPARE,
            "COMMAND_PLAY_PAUSE" to Player.COMMAND_PLAY_PAUSE,
        )) {
            var available = false
            instrumentation.runOnMainSync { available = controller.isCommandAvailable(command) }
            assertTrue("$name not available to the in-app controller", available)
        }
    }

    @Test
    fun inAppControllerIsGrantedScrubCommands() {
        for (command in listOf(AudioCommands.ScrubStart, AudioCommands.ScrubStop)) {
            var available = false
            instrumentation.runOnMainSync { available = controller.isSessionCommandAvailable(command) }
            assertTrue("${command.customAction} not available to the in-app controller", available)
        }
    }
}
