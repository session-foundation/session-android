package org.thoughtcrime.securesms.security

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import network.loki.mesenger.KeyItem
import network.loki.mesenger.KeyManagerActivity
import network.loki.mesenger.KeysRepository
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address

class ExtraSecurityActivity : AppCompatActivity() {

    companion object {
        private const val PREF_FILE_NAME = "encryption_prefs"
        private const val BASE_KEY_ENCRYPTION_ENABLED = "encryption_enabled_universalConv"
        private const val BASE_KEY_ALGORITHM = "encryption_algorithm_universalConv"
        private const val BASE_KEY_SELECTED_KEY_ALIAS = "encryption_key_alias_universalConv"
    }

    private lateinit var switchEncryption: Switch
    private lateinit var spinnerAlgorithm: Spinner
    private lateinit var spinnerKey: Spinner
    private lateinit var buttonManageKeys: Button
    private lateinit var buttonSave: Button

    private val keyAliasesList = mutableListOf<String>()
    private lateinit var keyAdapter: ArrayAdapter<String>
    private val noKeysPlaceholder = "No hay claves de cifrado"
    private val algorithms = listOf("AES-RAW")

    // Para spinner de threads
    private lateinit var spinnerThread: Spinner
    private lateinit var threadAdapter: ArrayAdapter<String>
    private val threadList = mutableListOf<ThreadItem>()

    data class ThreadItem(val threadId: Long, val snippet: String)
    private var selectedThreadId: Long? = null
    private var selectedKeyAliasForThread: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extra_security)

        switchEncryption = findViewById(R.id.switch_enable)
        spinnerAlgorithm = findViewById(R.id.spinnerAlgorithm)
        spinnerKey = findViewById(R.id.spinnerKey)
        buttonManageKeys = findViewById(R.id.buttonManageKeys)
        buttonSave = findViewById(R.id.buttonSave)

        spinnerThread = findViewById(R.id.spinnerThread)

        // Algoritmos
        val algoAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, algorithms)
        algoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAlgorithm.adapter = algoAdapter

        // Claves (alias)
        keyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keyAliasesList)
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerKey.adapter = keyAdapter

        // Threads
        threadAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ArrayList<String>())
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerThread.adapter = threadAdapter

        // Cargar prefs
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val sharedPrefs = EncryptedSharedPreferences.create(
            this,
            PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val encryptionEnabled = sharedPrefs.getBoolean(BASE_KEY_ENCRYPTION_ENABLED, false)
        val savedAlgorithm = sharedPrefs.getString(BASE_KEY_ALGORITHM, algorithms.first()) ?: algorithms.first()
        val savedKeyAlias = sharedPrefs.getString(BASE_KEY_SELECTED_KEY_ALIAS, null)

        // UI
        switchEncryption.isChecked = encryptionEnabled
        spinnerAlgorithm.setSelection(algorithms.indexOf(savedAlgorithm).coerceAtLeast(0))

        // Recargamos keys
        reloadKeyList()
        if (!savedKeyAlias.isNullOrEmpty() && keyAliasesList.contains(savedKeyAlias)) {
            spinnerKey.setSelection(keyAliasesList.indexOf(savedKeyAlias))
        }

        spinnerAlgorithm.isEnabled = true
        spinnerKey.isEnabled = keyAliasesList.isNotEmpty() && keyAliasesList[0] != noKeysPlaceholder

        // Listeners
        switchEncryption.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && (keyAliasesList.isEmpty() || keyAliasesList[0] == noKeysPlaceholder)) {
                Toast.makeText(this, "No hay claves. Crea una primero.", Toast.LENGTH_SHORT).show()
                switchEncryption.isChecked = false
                return@setOnCheckedChangeListener
            }
            sharedPrefs.edit().putBoolean(BASE_KEY_ENCRYPTION_ENABLED, isChecked).apply()
            val msg = if (isChecked) "Cifrado universal activado." else "Cifrado universal desactivado."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        spinnerAlgorithm.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedAlgo = algorithms[position]
                sharedPrefs.edit().putString(BASE_KEY_ALGORITHM, selectedAlgo).apply()
                Toast.makeText(this@ExtraSecurityActivity, "Algoritmo: $selectedAlgo", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerKey.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val alias = keyAliasesList[position]
                if (alias != noKeysPlaceholder) {
                    sharedPrefs.edit().putString(BASE_KEY_SELECTED_KEY_ALIAS, alias).apply()
                    val k = KeysRepository.loadKeys(this@ExtraSecurityActivity).find { it.nickname == alias }
                    if (k == null) {
                        Toast.makeText(this@ExtraSecurityActivity, "Error: clave no encontrada para $alias", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ExtraSecurityActivity, "Clave universal cambiada => $alias", Toast.LENGTH_SHORT).show()
                    }
                    selectedKeyAliasForThread = alias
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerThread.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val item = threadList[position]
                selectedThreadId = item.threadId
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        buttonManageKeys.setOnClickListener {
            startActivity(Intent(this, KeyManagerActivity::class.java))
        }

        buttonSave.setOnClickListener {
            // Al dar Save, asignamos alias al thread
            if (selectedThreadId != null && !selectedKeyAliasForThread.isNullOrEmpty()) {
                val storage = MessagingModuleConfiguration.shared.storage
                storage.setThreadKeyAlias(selectedThreadId!!, selectedKeyAliasForThread) // <-- Sin DB direct
                Toast.makeText(this, "Alias '$selectedKeyAliasForThread' asignado a hilo $selectedThreadId", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        reloadKeyList()
        spinnerKey.isEnabled = keyAliasesList.isNotEmpty() && keyAliasesList[0] != noKeysPlaceholder
        reloadThreadList()
    }

    private fun reloadKeyList() {
        val keys = KeysRepository.loadKeys(this)
        keyAliasesList.clear()
        if (keys.isEmpty()) {
            keyAliasesList.add(noKeysPlaceholder)
        } else {
            keyAliasesList.addAll(keys.map { it.nickname })
        }
        keyAdapter.notifyDataSetChanged()
    }

    private fun reloadThreadList() {
        threadList.clear()
        threadAdapter.clear()
        // Pedimos a storage la lista de hilos
        val storage = MessagingModuleConfiguration.shared.storage
        val allThreads = storage.getAllThreadsForEncryptionSpinner() // <---
        for (t in allThreads) {
            val item = ThreadItem(t.threadId, t.snippet)
            threadList.add(item)
            threadAdapter.add("${t.threadId} => ${t.snippet}")
        }
        threadAdapter.notifyDataSetChanged()
        if (threadList.isNotEmpty()) {
            spinnerThread.setSelection(0)
            selectedThreadId = threadList[0].threadId
        }
    }
}
