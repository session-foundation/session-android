package network.loki.mesenger

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ImportKeyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY_SCANNED = "extra_key_scanned"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Recuperar la clave escaneada
        val scannedKey = intent?.getStringExtra(EXTRA_KEY_SCANNED).orEmpty()
        if (scannedKey.isEmpty()) {
            Toast.makeText(this, "No scanned key found in QR!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 2) Diálogo para pedir alias
        val editText = EditText(this).apply {
            hint = "Enter a nickname for this key"
        }

        AlertDialog.Builder(this)
            .setTitle("Import key from QR")
            .setMessage("Key scanned successfully. Enter a nickname:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val nickname = editText.text.toString().trim()
                if (nickname.isNotEmpty()) {
                    val item = KeyItem(nickname, scannedKey)
                    KeysRepository.addKey(this, item)
                    Toast.makeText(this, "Key imported successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Empty nickname, key not saved", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
