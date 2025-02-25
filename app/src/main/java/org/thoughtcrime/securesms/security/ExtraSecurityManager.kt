package org.thoughtcrime.securesms.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

object ExtraSecurityManager {
    private const val PREF_NAME = "extra_security_prefs"
    private const val KEY_LIST = "keys_list"            // JSON con lista de claves
    private const val CHAT_KEY_PREFIX = "chat_key_"     // Prefijo para mapping chat -> clave alias
    private const val DEFAULT_ALGO = "AES-256"          // Algoritmo por defecto

    private lateinit var prefs: SharedPreferences

    /** Debe llamarse una vez (por ejemplo en Application.onCreate) para inicializar el gestor **/
    fun init(appContext: Context) {
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Si no hay un algoritmo por defecto guardado, establecemos uno
        if (!prefs.contains("default_algorithm")) {
            prefs.edit().putString("default_algorithm", DEFAULT_ALGO).apply()
        }
        // Si no existe aún lista de claves, inicializarla vacía
        if (!prefs.contains(KEY_LIST)) {
            prefs.edit().putString(KEY_LIST, "[]").apply()
        }
    }

    /** Genera una nueva clave de cifrado según el algoritmo especificado, la guarda y la devuelve **/
    fun generateKey(alias: String, algorithm: String): KeyEntry {
        val keyBytes: ByteArray = when (algorithm) {
            "AES-128" -> ByteArray(16)
            "AES-256" -> ByteArray(32)
            else -> ByteArray(32)  // Por defecto 256-bit si se extiende a otros algoritmos no implementados
        }
        SecureRandom().nextBytes(keyBytes)
        val keyBase64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        // Crear objeto de clave y agregarla a la lista en prefs
        val newKey = KeyEntry(alias, algorithm, keyBase64)
        val keys = getAllKeys().toMutableList()
        keys.add(newKey)
        saveKeyList(keys)
        return newKey
    }

    /** Importa una clave proporcionada (por ejemplo desde un QR escaneado). Se espera que valueBase64 sea el texto Base64 de la clave **/
    fun importKey(alias: String, algorithm: String, valueBase64: String): KeyEntry? {
        try {
            // Validar que el texto base64 corresponde a los bytes esperados según algoritmo
            val keyBytes = Base64.decode(valueBase64, Base64.NO_WRAP)
            val expectedLength = if (algorithm == "AES-128") 16 else 32
            if (keyBytes.size != expectedLength) {
                return null  // longitud inválida
            }
            // Crear objeto de clave e insertar
            val newKey = KeyEntry(alias, algorithm, valueBase64)
            val keys = getAllKeys().toMutableList()
            keys.add(newKey)
            saveKeyList(keys)
            return newKey
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /** Devuelve la lista de todas las claves almacenadas **/
    fun getAllKeys(): List<KeyEntry> {
        val listJson = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val jsonArr = JSONArray(listJson)
        val keys = mutableListOf<KeyEntry>()
        for (i in 0 until jsonArr.length()) {
            val obj = jsonArr.getJSONObject(i)
            val alias = obj.getString("alias")
            val algo = obj.getString("algorithm")
            val value = obj.getString("value")
            keys.add(KeyEntry(alias, algo, value))
        }
        return keys
    }

    /** Asigna (o cambia) la clave alias para un chat específico **/
    fun setChatKey(chatId: String, keyAlias: String) {
        prefs.edit().putString("$CHAT_KEY_PREFIX$chatId", keyAlias).apply()
    }

    /** Elimina cualquier clave asociada a un chat (desactiva la seguridad extra) **/
    fun clearChatKey(chatId: String) {
        prefs.edit().remove("$CHAT_KEY_PREFIX$chatId").apply()
    }

    /** Obtiene el alias de la clave asociada a un chat (o null si no tiene seguridad extra) **/
    fun getChatKeyAlias(chatId: String): String? {
        return prefs.getString("$CHAT_KEY_PREFIX$chatId", null)
    }

    /** Verifica si un chat tiene seguridad extra activada (es decir, tiene una clave asignada) **/
    fun hasKeyForChat(chatId: String): Boolean {
        return prefs.contains("$CHAT_KEY_PREFIX$chatId")
    }

    /** Obtiene el algoritmo por defecto configurado para generar nuevas claves **/
    fun getDefaultAlgorithm(): String {
        return prefs.getString("default_algorithm", DEFAULT_ALGO) ?: DEFAULT_ALGO
    }

    /** Establece el algoritmo por defecto para generación de claves nuevas **/
    fun setDefaultAlgorithm(algorithm: String) {
        prefs.edit().putString("default_algorithm", algorithm).apply()
    }

    /** Cifra un texto plano usando la clave identificada por alias. Devuelve el texto cifrado codificado en Base64 **/
    fun encryptText(plainText: String, keyAlias: String): String {
        // Buscar la clave por alias
        val keyEntry = getAllKeys().find { it.alias == keyAlias }
            ?: throw IllegalArgumentException("Clave $keyAlias no encontrada")
        val keyBytes = Base64.decode(keyEntry.value, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        // Usar AES/CBC/PKCS5Padding
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        // Generar IV aleatorio de 16 bytes
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ciphertextBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // Combinar IV + cifrado y codificar en Base64 para transmitir como texto
        val combined = ByteArray(iv.size + ciphertextBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertextBytes, 0, combined, iv.size, ciphertextBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Descifra un texto cifrado (en Base64) usando la clave identificada por alias. Devuelve el texto plano original **/
    fun decryptText(encryptedBase64: String, keyAlias: String): String {
        // Buscar la clave por alias
        val keyEntry = getAllKeys().find { it.alias == keyAlias }
            ?: throw IllegalArgumentException("Clave $keyAlias no encontrada")
        val keyBytes = Base64.decode(keyEntry.value, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipherData = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (cipherData.size < 16) {
            throw IllegalArgumentException("Datos cifrados inválidos")
        }
        // Separar IV y datos cifrados
        val iv = cipherData.copyOfRange(0, 16)
        val ciphertextBytes = cipherData.copyOfRange(16, cipherData.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val plainBytes = cipher.doFinal(ciphertextBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    /** Modelo de datos para representar una clave de cifrado */
    data class KeyEntry(val alias: String, val algorithm: String, val value: String)

    /** Guarda la lista de claves proporcionada en SharedPreferences (como JSON) */
    private fun saveKeyList(keys: List<KeyEntry>) {
        val jsonArr = JSONArray()
        for (key in keys) {
            val obj = JSONObject()
            obj.put("alias", key.alias)
            obj.put("algorithm", key.algorithm)
            obj.put("value", key.value)
            jsonArr.put(obj)
        }
        prefs.edit().putString(KEY_LIST, jsonArr.toString()).apply()
    }
}
