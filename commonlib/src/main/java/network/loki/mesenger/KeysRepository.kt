// KeysRepository.kt
package network.loki.mesenger

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object KeysRepository {

    private const val PREFS_FILE = "keys_prefs"
    private const val PREFS_KEY_LIST = "SAVED_KEYS"

    private fun getEncryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun loadKeys(context: Context): MutableList<KeyItem> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString(PREFS_KEY_LIST, null) ?: return mutableListOf()
        val type = object : TypeToken<List<KeyItem>>() {}.type
        return Gson().fromJson(json, type) ?: mutableListOf()
    }

    fun addKey(context: Context, item: KeyItem) {
        val current = loadKeys(context)
        // Reemplazamos la clave si ya existía
        current.removeAll { it.nickname == item.nickname }
        current.add(item)
        saveKeys(context, current)
    }

    fun removeKey(context: Context, nickname: String) {
        val current = loadKeys(context)
        current.removeAll { it.nickname == nickname }
        saveKeys(context, current)
    }

    private fun saveKeys(context: Context, list: List<KeyItem>) {
        val prefs = getEncryptedPrefs(context)
        val json = Gson().toJson(list)
        prefs.edit().putString(PREFS_KEY_LIST, json).apply()
    }
}
