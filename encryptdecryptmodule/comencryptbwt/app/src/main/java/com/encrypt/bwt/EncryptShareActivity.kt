//encryptshareactivity
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

class EncryptShareActivity : AppCompatActivity() {

    private var selectedCipher = "AES"
    private var isFile = false
    private var fileUri: Uri? = null
    private var inputText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val clipUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (clipText.isNullOrEmpty() && clipUri == null) {
            showFinalDialog("No text or file to encrypt.")
            return
        }

        if (clipUri != null) {
            isFile = true
            fileUri = clipUri
        } else {
            isFile = false
            inputText = clipText
        }

        askForCipher { cipherChosen ->
            selectedCipher = cipherChosen
            askForKey { key ->
                if (isFile) {
                    encryptFile(fileUri!!, key, selectedCipher)
                } else {
                    encryptText(inputText!!, key, selectedCipher)
                }
            }
        }
    }

    private fun askForCipher(onCipherSelected: (String) -> Unit) {
        val ciphers = arrayOf("AES","DES","CAMELLIA","CHACHA20POLY1305","XCHACHA20POLY1305","AEGIS256")
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
                        if (item != null) onKeyEntered(item.secret)
                        else askKeyManually(onKeyEntered)
                    }
                }
                .show()
        }
    }

    private fun askKeyManually(callback: (String) -> Unit) {
        val edit = EditText(this)
        edit.hint = "Enter encryption key"

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

    private fun encryptText(plainText: String, key: String, cipher: String) {
        val result = try {
            when (cipher) {
                "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAES(plainText, key)
                "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptDES(plainText, key)
                "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptCamellia(plainText, key)
                "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptChaCha20Poly1305(plainText, key)
                "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptXChaCha20Poly1305(plainText, key)
                "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAegis256(plainText, key)
                else -> "Error: cipher not implemented"
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            "Error encrypting text: ${ex.message}"
        }
        showFinalDialog(result)
    }

    private fun encryptFile(uri: Uri, key: String, cipher: String) {
        val data = org.thoughtcrime.securesms.FileHelper.readAllBytesFromUri(this, uri)
        if (data == null) {
            showFinalDialog("Error reading file.")
            return
        }

        val encrypted: ByteArray = try {
            when (cipher) {
                "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesAES(data, key)
                "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesDES(data, key)
                "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesCamellia(data, key)
                "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesChaCha20(data, key)
                "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesXChaCha20(data, key)
                "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesAegis256(data, key)
                else -> {
                    showFinalDialog("Cipher not implemented.")
                    return
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            showFinalDialog("Error encrypting file: ${ex.message}")
            return
        }

        val originalName = org.thoughtcrime.securesms.FileHelper.getFilenameFromUri(this, uri) ?: "file"
        val finalName = "$originalName.encrypted"

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
        val success = org.thoughtcrime.securesms.FileHelper.writeAllBytesToUri(this, newFile.uri, encrypted)
        if (!success) {
            showFinalDialog("Error writing encrypted file.")
            return
        }

        showFinalDialog("File encrypted successfully.\nSaved to:\n${newFile.uri}")
    }

    // CAMBIADO: 2 botones => "Copy" y "Close"
    private fun showFinalDialog(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Encrypt Share")
            .setMessage(msg)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("EncryptedResult", msg)
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
        val clip = ClipData.newPlainText("EncryptedText", text)
        cb.setPrimaryClip(clip)
    }
}
