package network.loki.mesenger

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.security.SecureRandom

class KeyManagerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val nicknameList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_manager)

        supportActionBar?.title = "Key Manager"

        val buttonAddKey = findViewById<Button>(R.id.buttonAddKey)
        val buttonGenerateRandom = findViewById<Button>(R.id.buttonGenerateRandom)
        val buttonImportQR = findViewById<Button>(R.id.buttonImportQR)
        listView = findViewById(R.id.listView)

        buttonAddKey.setOnClickListener { showAddKeyDialog() }
        buttonGenerateRandom.setOnClickListener { generateRandomKey() }
        buttonImportQR.setOnClickListener {
            val intent = Intent(this@KeyManagerActivity, QrScannerActivity::class.java)
            startActivity(intent)
        }

        val keyItems = KeysRepository.loadKeys(this)
        nicknameList.clear()
        nicknameList.addAll(keyItems.map { it.nickname })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, nicknameList)
        listView.adapter = adapter

        // Long-click para exportar QR o eliminar
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val nickname = nicknameList[position]
            val keyItem = keyItems.find { it.nickname == nickname }
            if (keyItem == null) {
                Toast.makeText(this, "Error: key not found", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            AlertDialog.Builder(this)
                .setTitle("Options for key: $nickname")
                .setItems(arrayOf("Export as QR", "Delete")) { dialog, which ->
                    when (which) {
                        0 -> showQrDialog(keyItem)
                        1 -> confirmDeleteKey(nickname, position)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Recargar lista por si QrScannerActivity añadió algo
        val keyItems = KeysRepository.loadKeys(this)
        nicknameList.clear()
        nicknameList.addAll(keyItems.map { it.nickname })
        adapter.notifyDataSetChanged()
    }

    private fun confirmDeleteKey(nickname: String, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Key")
            .setMessage("Are you sure you want to delete key '$nickname'?")
            .setPositiveButton("Delete") { _, _ ->
                KeysRepository.removeKey(this, nickname)
                nicknameList.removeAt(position)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddKeyDialog() {
        val linear = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val editNickname = EditText(this).apply { hint = "Nickname" }
        val editSecret   = EditText(this).apply { hint = "Secret (Base64 32 bytes)" }

        linear.addView(editNickname, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        linear.addView(editSecret, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        AlertDialog.Builder(this)
            .setTitle("Add Key")
            .setView(linear)
            .setPositiveButton("Save") { _, _ ->
                val nickname = editNickname.text.toString().trim()
                val secret   = editSecret.text.toString().trim()
                if (nickname.isNotEmpty() && secret.isNotEmpty()) {
                    val item = KeyItem(nickname, secret)
                    KeysRepository.addKey(this, item)
                    nicknameList.add(nickname)
                    adapter.notifyDataSetChanged()
                } else {
                    Toast.makeText(this, "Nickname or secret is empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Genera 32 bytes (256 bits) de forma aleatoria, los codifica en Base64, y los asocia a un alias.
     */
    private fun generateRandomKey() {
        val rawKey = RawKeyEncryptDecryptHelper.generateRawKey32()  // 32 bytes
        val base64Key = Base64.encodeToString(rawKey, Base64.NO_WRAP)

        val editNickname = EditText(this).apply {
            hint = "Nickname for random key"
        }

        AlertDialog.Builder(this)
            .setTitle("Generate Random Key")
            .setMessage("Enter an alias for this random key:")
            .setView(editNickname)
            .setPositiveButton("OK") { _, _ ->
                val nickname = editNickname.text.toString().trim()
                if (nickname.isNotEmpty()) {
                    val item = KeyItem(nickname, base64Key)
                    KeysRepository.addKey(this, item)
                    nicknameList.add(nickname)
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Raw key generated and saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Empty alias, key not saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQrDialog(keyItem: KeyItem) {
        // QrUtils es tu clase para generar un Bitmap con el QR
        val qrBitmap: Bitmap = QrUtils.generateQrCode(keyItem.secret, 512, 512)
        val imageView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
        }

        AlertDialog.Builder(this)
            .setTitle("Export Key as QR")
            .setMessage("Key alias: ${keyItem.nickname}")
            .setView(imageView)
            .setPositiveButton("OK", null)
            .show()
    }
}
