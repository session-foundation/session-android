package org.thoughtcrime.securesms.webrtc.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.session.libsession.utilities.ServiceUtil
import org.session.libsignal.utilities.Log

class IncomingRinger(private val context: Context) {
    companion object {
        const val TAG = "IncomingRinger"
        val PATTERN = longArrayOf(0L, 1000L, 1000L)
    }

    var mediaPlayer: MediaPlayer? = null

    val isRinging: Boolean
        get() = mediaPlayer?.isPlaying ?: false

    fun start() {
        val audioManager = ServiceUtil.getAudioManager(context)
        mediaPlayer?.release()
        mediaPlayer = createMediaPlayer()

        // Vibrate if policy/system allows
        if (shouldVibrate(audioManager)) vibrate()

        // Play ringtone only in NORMAL
        mediaPlayer?.let { player ->
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                try {
                    if (!player.isPlaying) {
                        player.prepare()
                        player.start()
                        Log.i(TAG, "Playing ringtone")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start mediaPlayer", e)
                }
            }
        } ?: run {
            Log.w(TAG,"Not ringing, mediaPlayer: ${mediaPlayer?.let{"available"}}")
        }
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator?.cancel()
        } else {
            context.getSystemService(Vibrator::class.java)?.cancel()
        }
    }

    private fun shouldVibrate(audioManager: AudioManager): Boolean {
        val v = ServiceUtil.getVibrator(context) ?: return false
        if (!v.hasVibrator()) return false

        // Respect 'Do Not Disturb'
        val nm = context.getSystemService(NotificationManager::class.java)
        when (nm?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> return false
        }

        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT   -> false
            AudioManager.RINGER_MODE_VIBRATE  -> true
            AudioManager.RINGER_MODE_NORMAL   -> true
            else                              -> false
        }
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(VibratorManager::class.java) ?: return
            val v = vm.defaultVibrator
            if (!v.hasVibrator()) return

            val effect = VibrationEffect.createWaveform(PATTERN, 1)
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_RINGTONE)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                v.vibrate(effect)
            }
        } else {
            val v = context.getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return

            val effect = VibrationEffect.createWaveform(PATTERN,  1)
            v.vibrate(effect)
        }
    }

    private fun createMediaPlayer(): MediaPlayer? {
        try {
            val defaultRingtone = try {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get default system ringtone", e)
                null
            } ?: return null

            try {
                return MediaPlayer().apply {
                    // Make volume follow the "Ring & notification" slider
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    setDataSource(context, defaultRingtone)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to create player with ringtone the normal way", e)
            }
        } catch (e: Exception) {
            Log.e(TAG,"Failed to create mediaPlayer")
        }

        return null
    }



}