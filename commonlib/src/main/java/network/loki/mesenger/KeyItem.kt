// KeyItem.kt
package network.loki.mesenger

import android.util.Base64

data class KeyItem(
    val nickname: String,
    val secret: String
) {
    init {
        // Decodificamos en binario (16, 24 o 32 bytes)
        val keyBytes = Base64.decode(secret, Base64.NO_WRAP)
        val validKeySizes = listOf(16, 24, 32) // bytes => 128, 192, 256 bits

        require(validKeySizes.contains(keyBytes.size)) {
            "La clave AES debe tener longitud de 128/192/256 bits (16/24/32 bytes). " +
                    "Tamaño actual: ${keyBytes.size} bytes."
        }
    }

    /**
     * Helper opcional: para recuperar la clave cruda en bytes.
     */
    fun toRawKeyBytes(): ByteArray {
        return Base64.decode(secret, Base64.NO_WRAP)
    }
}
