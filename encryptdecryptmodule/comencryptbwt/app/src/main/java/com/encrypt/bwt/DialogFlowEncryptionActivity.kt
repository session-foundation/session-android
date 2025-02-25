package com.encrypt.bwt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Muestra una secuencia de AlertDialogs para:
 *  1) Elegir si "Encrypt" o "Decrypt"
 *  2) Elegir cipher (AES, DES, etc.)
 *  3) Elegir/Introducir clave
 *  4) Mostrar resultado y copiar
 *
 * Cierra la Activity al final.
 */
class DialogFlowEncryptionActivity : AppCompatActivity() {

    private var selectedText: String = ""
    private var isEncryptMode = true
    private var selectedCipher = "AES"
    private var chosenKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Recuperar texto
        selectedText = intent?.getStringExtra("EXTRA_SELECTED_TEXT").orEmpty()
        if (selectedText.isBlank()) {
            Toast.makeText(this, "No text found!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Iniciar la cadena de diálogos
        chooseOperation()
    }

    /**
     * 1) Mostrar un AlertDialog: "Encrypt" o "Decrypt"
     */
    private fun chooseOperation() {
        val items = arrayOf("Encrypt", "Decrypt")
        AlertDialog.Builder(this)
            .setTitle("Choose Operation")
            .setItems(items) { _, which ->
                // which => 0 => "Encrypt", 1 => "Decrypt"
                isEncryptMode = (which == 0)
                chooseCipher()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * 2) Elegir el cifrado (AES, DES, etc.)
     */
    private fun chooseCipher() {
        val ciphers = arrayOf("AES","DES","CAMELLIA","CHACHA20POLY1305","XCHACHA20POLY1305","AEGIS256")
        AlertDialog.Builder(this)
            .setTitle("Choose Cipher")
            .setItems(ciphers) { _, which ->
                selectedCipher = ciphers[which]
                pickOrEnterKey()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * 3) Elegir o introducir la clave.
     *    Si tienes claves guardadas, muéstralas; si no, pides clave manual.
     */
    private fun pickOrEnterKey() {
        val keyItems = org.thoughtcrime.securesms.KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            askKeyManually { doCrypto(it) }
        } else {
            val arr = keyItems.map { it.nickname }.toMutableList()
            val addNew = "Enter new key..."
            arr.add(addNew)

            AlertDialog.Builder(this)
                .setTitle("Choose a Key")
                .setItems(arr.toTypedArray()) { _, which ->
                    val sel = arr[which]
                    if (sel == addNew) {
                        askKeyManually { doCrypto(it) }
                    } else {
                        // Buscar la clave real
                        val item = keyItems.find { it.nickname == sel }
                        if (item != null) {
                            doCrypto(item.secret)
                        } else {
                            askKeyManually { doCrypto(it) }
                        }
                    }
                }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    /**
     * Pedir clave manual con un AlertDialog input
     */
    private fun askKeyManually(callback: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Enter key"
        }
        AlertDialog.Builder(this)
            .setTitle("Manual Key")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    callback(key)
                } else {
                    Toast.makeText(this, "Empty key. Cancelled.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * 4) Ejecutar cifrado/descifrado y mostrar resultado final
     */
    private fun doCrypto(key: String) {
        chosenKey = key
        val result = try {
            if (isEncryptMode) {
                when (selectedCipher) {
                    "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAES(selectedText, key)
                    "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptDES(selectedText, key)
                    "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptCamellia(selectedText, key)
                    "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptChaCha20Poly1305(selectedText, key)
                    "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptXChaCha20Poly1305(selectedText, key)
                    "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAegis256(selectedText, key)
                    else -> "Cipher not implemented"
                }
            } else {
                when (selectedCipher) {
                    "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAES(selectedText, key)
                    "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptDES(selectedText, key)
                    "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptCamellia(selectedText, key)
                    "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptChaCha20Poly1305(selectedText, key)
                    "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptXChaCha20Poly1305(selectedText, key)
                    "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAegis256(selectedText, key)
                    else -> "Cipher not implemented"
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            "Error: ${ex.message}"
        }

        showResultDialog(result)
    }

    /**
     * Mostrar el resultado en un AlertDialog, con botón "Copy"
     */
    private fun showResultDialog(result: String) {
        AlertDialog.Builder(this)
            .setTitle(if (isEncryptMode) "Encrypt Result" else "Decrypt Result")
            .setMessage(result)
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard(result)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CryptoResult", text)
        cm.setPrimaryClip(clip)
    }
}
