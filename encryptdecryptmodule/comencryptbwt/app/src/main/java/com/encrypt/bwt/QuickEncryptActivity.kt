package com.encrypt.bwt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class QuickEncryptActivity : AppCompatActivity() {

    private var selectedText: String = ""
    private var selectedCipher: String = "AES"
    private var chosenKey: String? = null
    private var isEncryptMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_encrypt)

        // Recoger texto
        selectedText = intent.getStringExtra("EXTRA_SELECTED_TEXT").orEmpty()

        // Referencias
        val spinnerMode = findViewById<Spinner>(R.id.spinnerModeQE)
        val spinnerCipher = findViewById<Spinner>(R.id.spinnerCipherQE)
        val btnPickKey = findViewById<Button>(R.id.btnPickKeyQE)
        val textChosenKey = findViewById<TextView>(R.id.textChosenKeyQE)
        val edtInput = findViewById<EditText>(R.id.edtQEInput)
        val edtOutput = findViewById<EditText>(R.id.edtQEOutput)
        val btnExecute = findViewById<Button>(R.id.btnExecuteQE)
        val btnClose = findViewById<Button>(R.id.btnCloseQE)

        // Llenamos input con el texto detectado
        edtInput.setText(selectedText)

        // Spinner: modo (Encrypt/Decrypt)
        val modes = arrayOf("Encrypt", "Decrypt")
        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long
            ) {
                isEncryptMode = (position == 0)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Spinner: ciphers
        val ciphers = resources.getStringArray(R.array.cipher_types)
        spinnerCipher.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ciphers)
        spinnerCipher.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long
            ) {
                selectedCipher = ciphers[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pick key
        btnPickKey.setOnClickListener {
            pickKey { key ->
                chosenKey = key
                textChosenKey.text = "(Key chosen: ${key.take(6)}...)"
            }
        }

        // Execute
        btnExecute.setOnClickListener {
            val inputText = edtInput.text.toString().trim()
            if (inputText.isBlank()) {
                showToast("No input text.")
                return@setOnClickListener
            }
            val key = chosenKey
            if (key.isNullOrEmpty()) {
                showToast("No key selected.")
                return@setOnClickListener
            }

            val result = try {
                if (isEncryptMode) {
                    when (selectedCipher) {
                        "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAES(inputText, key)
                        "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptDES(inputText, key)
                        "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptCamellia(inputText, key)
                        "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptChaCha20Poly1305(inputText, key)
                        "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptXChaCha20Poly1305(inputText, key)
                        "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.encryptAegis256(inputText, key)
                        else -> "Cipher not implemented"
                    }
                } else {
                    when (selectedCipher) {
                        "AES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAES(inputText, key)
                        "DES" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptDES(inputText, key)
                        "CAMELLIA" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptCamellia(inputText, key)
                        "CHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptChaCha20Poly1305(inputText, key)
                        "XCHACHA20POLY1305" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptXChaCha20Poly1305(inputText, key)
                        "AEGIS256" -> org.thoughtcrime.securesms.EncryptDecryptHelper.decryptAegis256(inputText, key)
                        else -> "Cipher not implemented"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                "Error: ${e.message}"
            }

            edtOutput.setText(result)
            copyToClipboard(result)
            showToast("Result copied to clipboard.")
        }

        // Close
        btnClose.setOnClickListener {
            finish() // cierra la activity
        }
    }

    // Lógica para pickKey (similar a tu KeyManager)
    private fun pickKey(onKeyPicked: (String) -> Unit) {
        val keyItems = org.thoughtcrime.securesms.KeysRepository.loadKeys(this)
        if (keyItems.isEmpty()) {
            askKeyManual(onKeyPicked)
        } else {
            val nicks = keyItems.map { it.nickname }.toMutableList()
            val addNew = "Enter new key..."
            nicks.add(addNew)
            AlertDialog.Builder(this)
                .setTitle("Choose a Key")
                .setItems(nicks.toTypedArray()) { _, which ->
                    val sel = nicks[which]
                    if (sel == addNew) {
                        askKeyManual(onKeyPicked)
                    } else {
                        val item = keyItems.find { it.nickname == sel }
                        if (item != null) onKeyPicked(item.secret)
                        else askKeyManual(onKeyPicked)
                    }
                }
                .show()
        }
    }

    private fun askKeyManual(callback: (String) -> Unit) {
        val input = EditText(this)
        input.hint = "Enter key"
        AlertDialog.Builder(this)
            .setTitle("Manual Key")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) {
                    callback(k)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("EncryptedText", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
