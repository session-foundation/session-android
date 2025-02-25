//FileEncryptionActivity
package com.encrypt.bwt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class FileEncryptionActivity : AppCompatActivity() {

    private lateinit var radioEncrypt: RadioButton
    private lateinit var radioDecrypt: RadioButton
    private lateinit var textSelectedFile: TextView
    private lateinit var spinnerCipher: Spinner
    private lateinit var buttonPickKey: Button
    private lateinit var textChosenKey: TextView
    private lateinit var buttonPickFile: Button
    private lateinit var buttonPickFolder: Button
    private lateinit var buttonProcess: Button
    private lateinit var textResult: TextView

    private var selectedFileUri: Uri? = null
    private var selectedCipher: String = "AES"
    private var chosenKey: String? = null
    private var customOutputUri: Uri? = null   // carpeta elegida por el usuario
    private var isEncryptMode = true

    private lateinit var pickFileLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var pickFolderLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_encryption)

        // Referencias
        radioEncrypt = findViewById(R.id.radioEncrypt)
        radioDecrypt = findViewById(R.id.radioDecrypt)
        textSelectedFile = findViewById(R.id.textSelectedFile)
        spinnerCipher = findViewById(R.id.spinnerCipher)
        buttonPickKey = findViewById(R.id.buttonPickKey)
        textChosenKey = findViewById(R.id.textChosenKey)
        buttonPickFile = findViewById(R.id.buttonPickFile)
        buttonPickFolder = findViewById(R.id.buttonPickOutputDir)
        buttonProcess = findViewById(R.id.buttonProcess)
        textResult = findViewById(R.id.textResult)

        // --- Omitimos / quitamos la parte del checkboxUseDefaultPath ---
        // val checkboxUseDefaultPath = findViewById<CheckBox?>(R.id.checkboxUseDefaultPath)
        // checkboxUseDefaultPath?.visibility = View.GONE   // ya no hace falta

        // Spinner ciphers
        val ciphers = arrayOf("AES", "DES", "CAMELLIA", "CHACHA20POLY1305", "XCHACHA20POLY1305", "AEGIS256")
        spinnerCipher.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ciphers)
        spinnerCipher.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCipher = ciphers[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Cargar carpeta elegida anteriormente (SharedPreferences)
        val prefs = getSharedPreferences("FileEncPrefs", MODE_PRIVATE)
        val savedUriString = prefs.getString("customOutputUri", null)
        val textOutputDir = findViewById<TextView>(R.id.textOutputDir)
        if (savedUriString != null) {
            customOutputUri = Uri.parse(savedUriString)
            textOutputDir.text = "Chosen Folder:\n$customOutputUri"
        } else {
            textOutputDir.text = "(No folder selected yet)"
        }

        // Registrar los ActivityResultLaunchers

        // 1) Para elegir un archivo
        pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selectedFileUri = uri
                textSelectedFile.text = uri.toString()
            }
        }

        // 2) Para elegir la carpeta de salida
        pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                customOutputUri = uri

                // Guardar en prefs
                getSharedPreferences("FileEncPrefs", MODE_PRIVATE)
                    .edit()
                    .putString("customOutputUri", uri.toString())
                    .apply()

                textOutputDir.text = "Chosen Folder:\n$uri"
            }
        }

        // Botones
        buttonPickFile.setOnClickListener {
            pickFile()
        }
        buttonPickFolder.setOnClickListener {
            // ¡Ahora sí funciona porque está enabled!
            pickOutputFolder()
        }
        buttonPickKey.setOnClickListener {
            askForKey { key ->
                chosenKey = key
                textChosenKey.text = "(Key chosen: ${key.take(6)}...)"
            }
        }
        buttonProcess.setOnClickListener {
            doProcess()
        }
    }

    // Abrir el selector de archivo
    private fun pickFile() {
        pickFileLauncher.launch(arrayOf("*/*"))
    }

    // Abrir el selector de carpeta
    private fun pickOutputFolder() {
        pickFolderLauncher.launch(null)
    }

    private fun doProcess() {
        textResult.visibility = View.GONE
        isEncryptMode = radioEncrypt.isChecked

        if (selectedFileUri == null) {
            showMessage("No file selected.")
            return
        }
        if (customOutputUri == null) {
            showMessage("No destination folder selected. Pick a folder first.")
            return
        }
        if (chosenKey.isNullOrEmpty()) {
            showMessage("No key chosen.")
            return
        }

        // Leer bytes
        val data = org.thoughtcrime.securesms.FileHelper.readAllBytesFromUri(this, selectedFileUri!!)
            ?: run {
                showMessage("Failed to read input file.")
                return
            }

        // Procesar
        val processed = try {
            if (isEncryptMode) {
                encryptBytes(data, chosenKey!!, selectedCipher)
                    ?: throw Exception("Cipher not implemented")
            } else {
                decryptBytes(data, chosenKey!!, selectedCipher)
                    ?: throw Exception("Cipher not implemented")
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            showMessage("Error: ${ex.message}")
            return
        }

        // Nombre de salida
        val fileName = org.thoughtcrime.securesms.FileHelper.getFilenameFromUri(this, selectedFileUri!!) ?: "file"
        val finalName = if (isEncryptMode) {
            "$fileName.encrypted"
        } else {
            if (fileName.endsWith(".encrypted", ignoreCase = true)) {
                fileName.removeSuffix(".encrypted")
            } else {
                "$fileName.decrypted"
            }
        }

        // Crear en carpeta
        val baseDoc = DocumentFile.fromTreeUri(this, customOutputUri!!)
        if (baseDoc == null || !baseDoc.isDirectory) {
            showMessage("Selected output folder is invalid.")
            return
        }

        // Borrar si existía
        baseDoc.findFile(finalName)?.delete()

        // Crear y escribir
        val mimeType = "application/octet-stream"
        val newFile = baseDoc.createFile(mimeType, finalName)
        if (newFile == null) {
            showMessage("Failed to create output file.")
            return
        }
        val outUri = newFile.uri
        val success = org.thoughtcrime.securesms.FileHelper.writeAllBytesToUri(this, outUri, processed)
        if (!success) {
            showMessage("Failed to write output file.")
            return
        }

        showMessage("Success.\nSaved to:\n$outUri")
    }

    private fun encryptBytes(data: ByteArray, password: String, cipher: String): ByteArray? {
        return when (cipher) {
            "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesAES(data, password)
            "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesDES(data, password)
            "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesCamellia(data, password)
            "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesChaCha20(data, password)
            "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesXChaCha20(data, password)
            "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptBytesAegis256(data, password)
            else -> null
        }
    }

    private fun decryptBytes(data: ByteArray, password: String, cipher: String): ByteArray? {
        return when (cipher) {
            "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesAES(data, password)
            "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesDES(data, password)
            "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesCamellia(data, password)
            "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesChaCha20(data, password)
            "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesXChaCha20(data, password)
            "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptBytesAegis256(data, password)
            else -> null
        }
    }

    private fun askForKey(onKeyEntered: (String) -> Unit) {
        val keyItems = org.thoughtcrime.securesms.KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            askKeyManual(onKeyEntered)
        } else {
            val nicks = keyItems.map { it.nickname }.toMutableList()
            val addNew = "Enter new key..."
            nicks.add(addNew)
            AlertDialog.Builder(this)
                .setTitle("Choose Key")
                .setItems(nicks.toTypedArray()) { _, which ->
                    val sel = nicks[which]
                    if (sel == addNew) {
                        askKeyManual(onKeyEntered)
                    } else {
                        val item = keyItems.find { it.nickname == sel }
                        if (item != null) onKeyEntered(item.secret)
                        else askKeyManual(onKeyEntered)
                    }
                }
                .show()
        }
    }

    private fun askKeyManual(callback: (String) -> Unit) {
        val edt = EditText(this)
        edt.hint = "Enter key"
        AlertDialog.Builder(this)
            .setTitle("Manual Key")
            .setView(edt)
            .setPositiveButton("OK") { _, _ ->
                val k = edt.text.toString().trim()
                if (k.isNotEmpty()) {
                    callback(k)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMessage(msg: String) {
        textResult.visibility = View.VISIBLE
        textResult.text = msg
    }
}
