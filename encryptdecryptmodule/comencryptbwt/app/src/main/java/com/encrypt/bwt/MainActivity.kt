package com.encrypt.bwt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.encrypt.bwt.databinding.ActivityMainBinding
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedCipher = "AES"

    // -----------------------------------------------------------------------------------------
    // VARIABLES / CONSTANTES PARA PIN
    // -----------------------------------------------------------------------------------------
    private val PIN_PREFS_NAME = "PinPrefs"            // Nombre de prefs encriptadas
    private val KEY_USER_PIN = "userPinEncrypted"      // Clave donde guardamos el PIN
    // (Puedes cambiarlo, pero mantengo algo genérico)

    // -----------------------------------------------------------------------------------------
    // PARA BIOMÉTRICOS (huella, face) -> Se usa BiometricPrompt
    // -----------------------------------------------------------------------------------------
    private val executor: Executor by lazy {
        ContextCompat.getMainExecutor(this)
    }

    // Para pedir permiso de POST_NOTIFICATIONS en Android 13+
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "Permiso de notificaciones denegado. Podría afectar notificaciones.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) Pedimos notificaciones en Android 13+ (opcional)
        checkNotificationPermission()

        // 2) Spinner ciphers
        val cipherTypes = resources.getStringArray(R.array.cipher_types)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cipherTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cipherTypeSpinner.adapter = adapter
        binding.cipherTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCipher = cipherTypes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Botón Encriptar
        binding.encryptButton.setOnClickListener {
            val plainText = binding.plainTextInput.text.toString()
            val secretKey = binding.secretKeyInput.text.toString()
            if (plainText.isBlank() || secretKey.isBlank()) {
                Toast.makeText(this, "Falta texto o clave", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val encryptedText = try {
                when (selectedCipher) {
                    "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAES(plainText, secretKey)
                    "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptDES(plainText, secretKey)
                    "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptCamellia(plainText, secretKey)
                    "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptChaCha20Poly1305(plainText, secretKey)
                    "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptXChaCha20Poly1305(plainText, secretKey)
                    "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAegis256(plainText, secretKey)
                    else -> "Cipher not implemented"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                getString(R.string.encrypt_error_message)
            }
            binding.encryptedTextOutput.setText(encryptedText)
        }

        // Botón Desencriptar
        binding.decryptButton.setOnClickListener {
            val cipherText = binding.encryptedTextInput.text.toString()
            val secretKey = binding.secretKeyInput.text.toString()
            if (cipherText.isBlank() || secretKey.isBlank()) {
                Toast.makeText(this, "Falta texto o clave", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val decryptedText = try {
                when (selectedCipher) {
                    "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAES(cipherText, secretKey)
                    "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptDES(cipherText, secretKey)
                    "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptCamellia(cipherText, secretKey)
                    "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptChaCha20Poly1305(cipherText, secretKey)
                    "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptXChaCha20Poly1305(cipherText, secretKey)
                    "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAegis256(cipherText, secretKey)
                    else -> "Cipher not implemented"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                getString(R.string.decrypt_error_message)
            }
            binding.decryptedTextOutput.setText(decryptedText)
        }

        // -------------------------------------------------------------------------------------
        // [MODIFICACIÓN] Botón Manage Keys -> pedimos PIN/biometría antes de abrir KeyManagerActivity
        // -------------------------------------------------------------------------------------
        binding.manageKeysButton.setOnClickListener {
            checkAndAuthenticateUser {
                // Si la autenticación es correcta, abrimos KeyManager
                startActivity(Intent(this, org.thoughtcrime.securesms.KeyManagerActivity::class.java))
            }
        }

        // Botón Select Key (copiar la clave seleccionada al EditText)
        binding.selectKeyButton.setOnClickListener {
            pickStoredKey()
        }

        // Botón FILE ENCRYPTION
        binding.buttonFileEncryption.setOnClickListener {
            startActivity(Intent(this, FileEncryptionActivity::class.java))
        }

        // Botón "BurbujaEncryption" para (des)activar la lógica del servicio
        binding.buttonBubbleEncryption.setOnClickListener {
            toggleAccessibilityLogic()
        }

        // Botón "Start DialogFlow"
        val btnStartDialogFlow = findViewById<Button>(R.id.buttonStartOverlay)
        btnStartDialogFlow.text = "Start DialogFlow"
        btnStartDialogFlow.setOnClickListener {
            startDialogFlowExample()
        }

        // Ocultamos el botón StopOverlay
        val btnStopOverlay = findViewById<Button>(R.id.buttonStopOverlay)
        btnStopOverlay.visibility = View.GONE
    }

    /**
     * Activa o desactiva la lógica interna del AccessibilityEncryptionService
     * (sin deshabilitarlo en Ajustes).
     */
    private fun toggleAccessibilityLogic() {
        val prefs = getSharedPreferences("AccessibilityPrefs", MODE_PRIVATE)
        val currentlyEnabled = prefs.getBoolean("accessibility_enabled", true)
        val newValue = !currentlyEnabled
        prefs.edit().putBoolean("accessibility_enabled", newValue).apply()

        if (newValue) {
            Toast.makeText(this, "Función de accesibilidad reactivada", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Función de accesibilidad desactivada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun pickStoredKey() {
        val keyItems = org.thoughtcrime.securesms.KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            Toast.makeText(this, "No hay claves guardadas", Toast.LENGTH_SHORT).show()
            return
        }
        val nicknameList = keyItems.map { it.nickname }.toMutableList()
        val addNew = getString(R.string.add_new_key_option)
        nicknameList.add(addNew)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_key_dialog_title))
            .setItems(nicknameList.toTypedArray()) { _, which ->
                val selectedNick = nicknameList[which]
                if (selectedNick == addNew) {
                    Toast.makeText(this, "Introduce manualmente la clave en el campo", Toast.LENGTH_SHORT).show()
                } else {
                    val item = keyItems.find { it.nickname == selectedNick }
                    if (item != null) {
                        binding.secretKeyInput.setText(item.secret)
                    } else {
                        Toast.makeText(this, "Error: no se encontró la clave", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun startDialogFlowExample() {
        val i = Intent(this, DialogFlowEncryptionActivity::class.java)
        i.putExtra("EXTRA_SELECTED_TEXT", "Hello from MainActivity!")
        startActivity(i)
    }

    // -----------------------------------------------------------------------------------------
    // LÓGICA DE AUTENTICACIÓN (PIN + BIOMÉTRICOS)
    // -----------------------------------------------------------------------------------------

    /**
     * Función principal que controla:
     *  1) Si no existe un PIN guardado, pedimos que el usuario lo cree.
     *  2) Si sí hay PIN, intentamos primero biometría, si falla/cancela -> fallback a PIN.
     *  3) Cuando esté todo OK, llamamos a onAuthSuccess().
     */
    private fun checkAndAuthenticateUser(onAuthSuccess: () -> Unit) {
        val currentPin = loadUserPin()
        if (currentPin.isNullOrEmpty()) {
            // 1) No hay PIN -> que el usuario lo registre
            showCreatePinDialog { newPin ->
                saveUserPin(newPin)
                // Ya hay pin, se considera autenticado en este momento
                onAuthSuccess()
            }
        } else {
            // 2) Sí hay PIN -> intentar biometría
            val canBio = canAuthenticateWithBiometrics()
            if (canBio) {
                showBiometricPrompt(
                    onSuccess = {
                        // Autenticación biométrica OK
                        onAuthSuccess()
                    },
                    onFailedOrCancel = {
                        // Fallback a PIN
                        showPinDialog { pinOk ->
                            if (pinOk) onAuthSuccess()
                        }
                    }
                )
            } else {
                // Si no hay biometría, vamos directo a PIN
                showPinDialog { pinOk ->
                    if (pinOk) onAuthSuccess()
                }
            }
        }
    }

    /**
     * Muestra un diálogo para que el usuario cree un nuevo PIN.
     */
    private fun showCreatePinDialog(onPinCreated: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Crea un PIN numérico"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Configurar PIN")
            .setMessage("Crea un PIN para proteger Manage Keys.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.isNotEmpty()) {
                    onPinCreated(pin)
                } else {
                    Toast.makeText(this, "PIN vacío. Operación cancelada.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Muestra un diálogo para pedir el PIN actual y compararlo.
     */
    private fun showPinDialog(onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            hint = "Ingresa tu PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Verifica tu PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val pinEntered = input.text.toString().trim()
                val storedPin = loadUserPin()
                if (pinEntered.isNotEmpty() && pinEntered == storedPin) {
                    onResult(true)
                } else {
                    Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                onResult(false)
            }
            .show()
    }

    /**
     * Chequea si el dispositivo puede usar biometría fuerte (huella/rostro).
     */
    private fun canAuthenticateWithBiometrics(): Boolean {
        val bioManager = BiometricManager.from(this)
        return when (bioManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Muestra un BiometricPrompt (huella, rostro).
     *  - onSuccess() se llama si la autenticación biométrica fue exitosa.
     *  - onFailedOrCancel() si falla o el usuario cancela.
     */
    private fun showBiometricPrompt(
        onSuccess: () -> Unit,
        onFailedOrCancel: () -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // El usuario canceló o hubo un error
                onFailedOrCancel()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // No coincide la huella
                Toast.makeText(this@MainActivity, "Biometría falló", Toast.LENGTH_SHORT).show()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Autenticación biométrica")
            .setSubtitle("Usa tu huella o rostro")
            .setNegativeButtonText("Usar PIN")
            .build()

        prompt.authenticate(promptInfo)
    }

    // -----------------------------------------------------------------------------------------
    // EncryptedSharedPreferences para guardar PIN
    // -----------------------------------------------------------------------------------------
    private fun getEncryptedPrefsForPin(): android.content.SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PIN_PREFS_NAME,
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun saveUserPin(pin: String) {
        val prefs = getEncryptedPrefsForPin()
        prefs.edit().putString(KEY_USER_PIN, pin).apply()
    }

    private fun loadUserPin(): String? {
        val prefs = getEncryptedPrefsForPin()
        return prefs.getString(KEY_USER_PIN, null)
    }
}
