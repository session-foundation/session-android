package network.loki.mesenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class EncryptProcessActivity : AppCompatActivity() {

    private var selectedCipher: String = "AES"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Texto seleccionado en otra app
        val inputText = intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT).orEmpty()
        if (inputText.isEmpty()) {
            showFinalDialog("No text provided!")
            return
        }

        // 1) Cifrado
        askForCipher { cipherChosen ->
            selectedCipher = cipherChosen

            // 2) Pedir clave
            askForKey { userKey ->
                // 3) Encriptar
                val encrypted = try {
                    when (selectedCipher) {
                        "AES"               -> EncryptDecryptHelper.encryptAES(inputText, userKey)
                        "DES"               -> EncryptDecryptHelper.encryptDES(inputText, userKey)
                        "CAMELLIA"          -> EncryptDecryptHelper.encryptCamellia(
                            inputText,
                            userKey
                        )
                        "CHACHA20POLY1305"  -> EncryptDecryptHelper.encryptChaCha20Poly1305(
                            inputText,
                            userKey
                        )
                        "XCHACHA20POLY1305" -> EncryptDecryptHelper.encryptXChaCha20Poly1305(
                            inputText,
                            userKey
                        )

                        else                -> "Encryption error!"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Encryption error!"
                }
                showFinalDialog(encrypted)
            }
        }
    }

    private fun askForCipher(onCipherSelected: (String) -> Unit) {
        val ciphers = arrayOf(
            "AES",
            "DES",
            "CAMELLIA",
            "CHACHA20POLY1305",
            "XCHACHA20POLY1305",
            "AEGIS256"
        )
        AlertDialog.Builder(this)
            .setTitle("Choose a cipher")
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
                .setTitle("Choose a key")
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
            hint = "Enter your key"
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
            .setTitle("Encrypted text")
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
        val clip = ClipData.newPlainText("EncryptedText", text)
        clipboard.setPrimaryClip(clip)
    }
}
