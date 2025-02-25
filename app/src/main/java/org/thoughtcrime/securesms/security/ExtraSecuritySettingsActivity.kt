package org.thoughtcrime.securesms.security

import network.loki.messenger.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
// Se asume que se ha agregado la dependencia ZXing para QR (p. ej., com.journeyapps:zxing-android-embedded)
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

class ExtraSecuritySettingsActivity : AppCompatActivity() {

    private lateinit var keyAdapter: KeyListAdapter
    private lateinit var keysRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extra_security_settings)

        // Configurar spinner de selección de algoritmo
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerAlgorithm)
        // Establecer la selección inicial al algoritmo por defecto almacenado
        val algorithms = resources.getStringArray(R.array.encryption_algorithms)
        val currentAlgo = ExtraSecurityManager.getDefaultAlgorithm()
        spinner.setSelection(algorithms.indexOf(currentAlgo))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val selectedAlgo = algorithms[position]
                ExtraSecurityManager.setDefaultAlgorithm(selectedAlgo)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Configurar RecyclerView con lista de claves
        keysRecyclerView = findViewById(R.id.keysRecyclerView)
        keysRecyclerView.layoutManager = LinearLayoutManager(this)
        keyAdapter = KeyListAdapter(this, ExtraSecurityManager.getAllKeys().toMutableList())
        keysRecyclerView.adapter = keyAdapter

        // Botón flotante para agregar nueva clave
        val fabAdd = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddKey)
        fabAdd.setOnClickListener {
            // Mostrar diálogo con opciones: Generar o Importar clave
            AlertDialog.Builder(this)
                .setTitle("Add Encryption Key")
                .setItems(arrayOf("Generate New Key", "Import Key (Scan QR)")) { dialog, which ->
                    if (which == 0) {
                        // Generar nueva clave con algoritmo seleccionado
                        promptGenerateKey()
                    } else if (which == 1) {
                        // Importar clave escaneando código QR
                        // Iniciar actividad de escaneo (usando ZXing integrator)
                        try {
                            val integrator = com.google.zxing.integration.android.IntentIntegrator(this)
                            integrator.setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                            integrator.setPrompt("Scan QR Code of Encryption Key")
                            integrator.initiateScan()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Scanner no disponible", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }

    /** Solicita un alias al usuario y genera una nueva clave con el algoritmo seleccionado */
    private fun promptGenerateKey() {
        // Pedir al usuario un nombre/alias para la nueva clave (por simplicidad, generar automático si no se ingresa)
        val input = android.widget.EditText(this)
        input.hint = "Key name (alias)"
        AlertDialog.Builder(this)
            .setTitle("Generate New Key")
            .setView(input)
            .setPositiveButton("Generate") { dialog, which ->
                val alias = if (input.text.isNotEmpty()) input.text.toString() else "Key${System.currentTimeMillis()}"
                val algo = ExtraSecurityManager.getDefaultAlgorithm()
                ExtraSecurityManager.generateKey(alias, algo)
                // Actualizar la lista de claves en la interfaz
                keyAdapter.keys = ExtraSecurityManager.getAllKeys().toMutableList()
                keyAdapter.notifyDataSetChanged()
                Toast.makeText(this, "Clave \"$alias\" generada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Recibir resultado del escaneo de QR (importación de clave)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            val scannedContent = result.contents  // Contenido del código QR escaneado
            // Suponer que el código QR contiene la clave en formato Base64 y tal vez información del algoritmo
            // Para simplicidad, asumamos que el QR es sólo la clave Base64 de algoritmo por defecto actual
            val alias = "ImportedKey${System.currentTimeMillis()}"
            val algo = ExtraSecurityManager.getDefaultAlgorithm()
            val newKey = ExtraSecurityManager.importKey(alias, algo, scannedContent)
            if (newKey != null) {
                keyAdapter.keys = ExtraSecurityManager.getAllKeys().toMutableList()
                keyAdapter.notifyDataSetChanged()
                Toast.makeText(this, "Clave importada: $alias", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Formato de clave inválido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Adaptador para la lista de claves de cifrado */
    inner class KeyListAdapter(val context: Context, var keys: MutableList<ExtraSecurityManager.KeyEntry>) :
        RecyclerView.Adapter<KeyListAdapter.KeyViewHolder>() {

        inner class KeyViewHolder(val itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val nameView: android.widget.TextView = itemView.findViewById(R.id.keyName)
            val algoView: android.widget.TextView = itemView.findViewById(R.id.keyAlgo)
            val copyBtn: android.widget.ImageButton = itemView.findViewById(R.id.copyBtn)
            val qrBtn: android.widget.ImageButton = itemView.findViewById(R.id.qrBtn)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): KeyViewHolder {
            val view = layoutInflater.inflate(R.layout.item_key, parent, false)
            return KeyViewHolder(view)
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            val key = keys[position]
            holder.nameView.text = key.alias
            holder.algoView.text = key.algorithm
            // Botón copiar clave
            holder.copyBtn.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Encryption Key", key.value)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Clave copiada al portapapeles", Toast.LENGTH_SHORT).show()
            }
            // Botón mostrar QR de la clave
            holder.qrBtn.setOnClickListener {
                // Generar código QR a partir del valor de la clave
                val qrBitmap = generateQRBitmap(key.value)
                // Mostrar en un diálogo
                val imageView = ImageView(context)
                imageView.setImageBitmap(qrBitmap)
                AlertDialog.Builder(context)
                    .setTitle("QR Code - ${key.alias}")
                    .setView(imageView)
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = keys.size

        /** Genera un Bitmap de código QR para un texto dado (valor de clave) */
        private fun generateQRBitmap(data: String): Bitmap {
            val size = 500
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            return bmp
        }
    }
}
