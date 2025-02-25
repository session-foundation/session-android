package network.loki.mesenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class DecryptProcessActivity : AppCompatActivity() {

    private var selectedCipher: String = "AES"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Obtenemos el texto seleccionado
        val inputText = intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT).orEmpty()
        if (inputText.isEmpty()) {
            showFinalDialog("No text provided!")  // <-- Cadena en duro
            return
        }

        // 1) Preguntar cifrado a usar
        askForCipher { cipherChosen ->
            selectedCipher = cipherChosen

            // 2) Pedimos la clave
            askForKey { userKey ->
                // 3) Descifrar según el cifrado
                val decrypted = try {
                    when (selectedCipher) {
                        "AES" -> EncryptDecryptHelper.decryptAES(inputText, userKey)
                        "DES" -> EncryptDecryptHelper.decryptDES(inputText, userKey)
                        "CAMELLIA" -> EncryptDecryptHelper.decryptCamellia(inputText, userKey)
                        "CHACHA20POLY1305" -> EncryptDecryptHelper.decryptChaCha20Poly1305(
                            inputText,
                            userKey
                        )
                        "XCHACHA20POLY1305" -> EncryptDecryptHelper.decryptXChaCha20Poly1305(
                            inputText,
                            userKey
                        )
                        else -> "Decryption error!"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Decryption error!"
                }
                showFinalDialog(decrypted)
            }
        }
    }

    private fun askForCipher(onCipherSelected: (String) -> Unit) {
        val ciphers = arrayOf(
            "AES",
            "DES",
            "CAMELLIA",
            "CHACHA20POLY1305",
            "XCHACHA20POLY1305"
        )
        // Ejemplo: setItems pide texto en duro
        AlertDialog.Builder(this)
            .setTitle("Choose a cipher")  // <-- Texto en duro
            .setItems(ciphers) { _, which ->
                onCipherSelected(ciphers[which])
            }
            .show()
    }

    private fun askForKey(onKeyEntered: (String) -> Unit) {
        val keyItems = KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            askKeyManually { onKeyEntered(it) }
        } else {
            val nicknames = keyItems.map { it.nickname }.toMutableList()
            val addNew = "Add new key"
            nicknames.add(addNew)

            AlertDialog.Builder(this)
                .setTitle("Choose key")  // <-- Texto en duro
                .setItems(nicknames.toTypedArray()) { _, which ->
                    val selected = nicknames[which]
                    if (selected == addNew) {
                        askKeyManually { onKeyEntered(it) }
                    } else {
                        val item = keyItems.find { it.nickname == selected }
                        if (item != null) {
                            onKeyEntered(item.secret)
                        } else {
                            askKeyManually { onKeyEntered(it) }
                        }
                    }
                }
                .show()
        }
    }

    private fun askKeyManually(callback: (String) -> Unit) {
        val keyInput = EditText(this).apply {
            hint = "Enter your key"   // <-- Texto en duro
        }

        AlertDialog.Builder(this)
            .setTitle("Enter key")
            .setView(keyInput)
            .setPositiveButton("OK") { _, _ ->
                val userKey = keyInput.text.toString().trim()
                if (userKey.isNotEmpty()) {
                    callback(userKey)
                } else {
                    showFinalDialog("Key is empty!")
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showFinalDialog(result: String) {
        AlertDialog.Builder(this)
            .setTitle("Decrypted text")
            .setMessage(result)
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard(result)
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DecryptedText", text)
        clipboard.setPrimaryClip(clip)
    }
}
