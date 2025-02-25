package com.encrypt.bwt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest

/**
 * Servicio de Accesibilidad que:
 *  1) Detecta texto seleccionado (TYPE_VIEW_TEXT_SELECTION_CHANGED).
 *  2) Detecta cuando el usuario copia texto (clipboard).
 *  3) Muestra notificación para abrir DialogFlowEncryptionActivity
 *     (donde se ve la pantalla "Choose Operation" -> "Encrypt"/"Decrypt").
 *
 *  Se controla un flag "accessibility_enabled" (SharedPreferences),
 *  para permitir encender/apagar la lógica sin deshabilitar en Ajustes.
 */
class AccessibilityEncryptionService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardText: String? = null
    private var lastEventTime = 0L

    // Listener del portapapeles (cuando algo se copia)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // (1) Miramos si la lógica está "desactivada" (false)
        val prefs = getSharedPreferences("AccessibilityPrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("accessibility_enabled", true)
        if (!isEnabled) {
            return@OnPrimaryClipChangedListener
        }

        // (2) Lógica normal de Clipboard
        val cm = clipboardManager ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotBlank() && text != lastClipboardText) {
                lastClipboardText = text
                // Muestra notificación que al pulsar abrirá la DialogFlowEncryptionActivity
                showEncryptionNotification(text)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configurar el tipo de eventos que escuchamos
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        // Podrías filtrar packageNames si quieres hacerlo sólo en ciertas apps
        info.packageNames = null
        serviceInfo = info

        // Inicializar portapapeles
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        createNotificationChannel()
        Toast.makeText(this, "AccessibilityEncryptionService conectado", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // (1) Chequeamos flag "accessibility_enabled"
        val prefs = getSharedPreferences("AccessibilityPrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("accessibility_enabled", true)
        if (!isEnabled) {
            return
        }

        // (2) Lógica normal para evento de texto seleccionado
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val now = SystemClock.uptimeMillis()
            // Evitamos spam si se selecciona texto muchas veces por segundo
            if (now - lastEventTime < 800) {
                return
            }
            lastEventTime = now

            val source = event.source ?: return
            val start = source.textSelectionStart
            val end = source.textSelectionEnd
            val text = source.text ?: return
            if (start >= 0 && end > start && end <= text.length) {
                val selectedText = text.substring(start, end)
                if (selectedText.isNotBlank()) {
                    showEncryptionNotification(selectedText)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Se llama si el servicio es interrumpido (p. ej. se desactiva)
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
    }

    /**
     * Muestra una notificación. Al pulsarla, abre la DialogFlowEncryptionActivity
     * que despliega "Choose Operation: Encrypt or Decrypt".
     */
    private fun showEncryptionNotification(detectedText: String) {
        // Activity que abrirá el "Choose Operation"
        val dialogIntent = Intent(this, DialogFlowEncryptionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("EXTRA_SELECTED_TEXT", detectedText)
        }

        val pending = PendingIntentHelper.getActivityPendingIntent(this, dialogIntent)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_overlay)
            .setContentTitle("Texto detectado")
            .setContentText("Pulsa para cifrar o descifrar")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)

        // En Android 13+, chequear permiso POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Si no está concedido, no lanzamos notificación
                return
            }
        }

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
        } catch (ex: SecurityException) {
            ex.printStackTrace()
        }
    }

    /**
     * Crear el canal de notificaciones en Android 8+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EncryptionService Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Notificaciones para cifrar/descifrar texto"
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "encrypt_service_channel"
        private const val NOTIF_ID = 2027
    }
}
