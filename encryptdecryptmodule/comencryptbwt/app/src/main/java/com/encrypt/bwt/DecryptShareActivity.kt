//Decryptshareactivity
package com.encrypt.bwt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class DecryptShareActivity : AppCompatActivity() {

    private var selectedCipher = "AES"
    private var isFile = false
    private var fileUri: Uri? = null
    private var inputText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val clipUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (clipText.isNullOrEmpty() && clipUri == null) {
            showFinalDialog("No text or file to decrypt.")
            return
        }

        if (clipUri != null) {
            isFile = true
            fileUri = clipUri
        } else {
            isFile = false
            inputText = clipText
        }

        askForCipher { cipher ->
            selectedCipher = cipher
            askForKey { key ->
                if (isFile) {
                    decryptFile(fileUri!!, key, selectedCipher)
                } else {
                    decryptText(inputText!!, key, selectedCipher)
                }
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
            .setTitle("Choose Cipher")
            .setItems(ciphers) { _, which ->
                onCipherSelected(ciphers[which])
            }
            .show()
    }

    private fun askForKey(onKeyEntered: (String) -> Unit) {
        val keyItems = org.thoughtcrime.securesms.KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            askKeyManually(onKeyEntered)
        } else {
            val nicknames = keyItems.map { it.nickname }.toMutableList()
            val addNew = "Enter a new key..."
            nicknames.add(addNew)
            AlertDialog.Builder(this)
                .setTitle("Choose a key")
                .setItems(nicknames.toTypedArray()) { _, which ->
                    val selected = nicknames[which]
                    if (selected == addNew) {
                        askKeyManually(onKeyEntered)
                    } else {
                        val item = keyItems.find { it.nickname == selected }
                        if (item != null) {
                            onKeyEntered(item.secret)
                        } else {
                            askKeyManually(onKeyEntered)
                        }
                    }
                }
                .show()
        }
    }

    private fun askKeyManually(callback: (String) -> Unit) {
        val edit = EditText(this)
        edit.hint = "Enter decryption key"

        AlertDialog.Builder(this)
            .setTitle("Enter Key")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val key = edit.text.toString().trim()
                if (key.isNotEmpty()) {
                    callback(key)
                } else {
                    showFinalDialog("Empty key.")
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .show()
    }

    private fun decryptText(cipherText: String, key: String, cipher: String) {
        val result = try {
            when (cipher) {
                "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAES(cipherText, key)
                "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptDES(cipherText, key)
                "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptCamellia(cipherText, key)
                "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptChaCha20Poly1305(cipherText, key)
                "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptXChaCha20Poly1305(cipherText, key)
                "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAegis256(cipherText, key)
                else -> "Cipher not implemented."
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            "Error decrypting text: ${ex.message}"
        }
        showFinalDialog(result)
    }

    private fun decryptFile(uri: Uri, key: String, cipher: String) {
        val data = org.thoughtcrime.securesms.FileHelper.readAllBytesFromUri(this, uri)
        if (data == null) {
            showFinalDialog("Error reading file.")
            return
        }

        val decrypted: ByteArray = try {
            when (cipher) {
                "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesAES(data, key)
                "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesDES(data, key)
                "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesCamellia(data, key)
                "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesChaCha20(data, key)
                "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesXChaCha20(data, key)
                "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesAegis256(data, key)
                else -> {
                    showFinalDialog("Cipher not implemented.")
                    return
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            showFinalDialog("Error decrypting file: ${ex.message}")
            return
        }

        val originalName = org.thoughtcrime.securesms.FileHelper.getFilenameFromUri(this, uri) ?: "file"
        val finalName = if (originalName.endsWith(".encrypted", ignoreCase = true)) {
            originalName.removeSuffix(".encrypted")
        } else {
            "$originalName.decrypted"
        }

        val sourceDoc = DocumentFile.fromSingleUri(this, uri)
        if (sourceDoc == null) {
            showFinalDialog("Error: Could not access original file.")
            return
        }
        val parentDoc = sourceDoc.parentFile
        if (parentDoc == null) {
            showFinalDialog("Error: No parent folder accessible.")
            return
        }
        if (!parentDoc.isDirectory || !parentDoc.canWrite()) {
            showFinalDialog("Cannot write in original folder.")
            return
        }

        parentDoc.findFile(finalName)?.delete()

        val mimeType = "application/octet-stream"
        val newFile = parentDoc.createFile(mimeType, finalName)
        if (newFile == null) {
            showFinalDialog("Failed to create output file.")
            return
        }

        val success = org.thoughtcrime.securesms.FileHelper.writeAllBytesToUri(this, newFile.uri, decrypted)
        if (!success) {
            showFinalDialog("Error writing decrypted file.")
            return
        }

        showFinalDialog("File decrypted successfully.\nSaved to:\n${newFile.uri}")
    }

    // *** Ajustado para 2 botones: "Copy" y "Close" ***
    private fun showFinalDialog(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Decrypt Share")
            .setMessage(msg)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DecryptedResult", msg)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DecryptedText", text)
        cb.setPrimaryClip(clip)
    }
}
