//Xchacha20poly1305
package network.loki.mesenger;



import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Implementación de XChaCha20-Poly1305 usando la Lightweight API de Bouncy Castle.
 * Usa un nonce extendido de 24 bytes (192 bits).
 *
 * Métodos principales:
 *  - encrypt(key, nonce24, aad, plaintext)
 *  - decrypt(key, nonce24, aad, ciphertextAndTag)
 *
 * Requiere Bouncy Castle >= 1.58.
 */
public final class XChaCha20Poly1305 {

    private static final int KEY_LEN = 32;        // 256 bits
    private static final int NONCE_LEN = 24;      // 192 bits para XChaCha
    private static final int TAG_LEN_BITS = 128;  // Tag de 128 bits (16 bytes)

    // Constantes "sigma" de ChaCha
    private static final int[] SIGMA = new int[]{
            0x61707865, 0x3320646E, 0x79622D32, 0x6B206574
    };

    private XChaCha20Poly1305() {
        // Evitar instancias
    }

    /**
     * Cifra con XChaCha20-Poly1305.
     *
     * @param key       Clave de 32 bytes (256 bits).
     * @param nonce24   Nonce de 24 bytes (192 bits).
     * @param aad       Datos adicionales autenticados (puede ser null o vacío).
     * @param plaintext Bytes del mensaje a cifrar.
     * @return ciphertext || tag (16 bytes al final).
     */
    public static byte[] encrypt(byte[] key, byte[] nonce24, byte[] aad, byte[] plaintext) {
        validateInputs(key, nonce24, plaintext);

        if (aad == null) {
            aad = new byte[0];
        }

        // 1. Subclave derivada vía HChaCha20 con los primeros 16 bytes del nonce
        byte[] subKey = hChaCha20(key, nonce24);

        // 2. Nonce corto de 12 bytes:
        //    - bytes [0..3] = 0x00000000
        //    - bytes [4..11] = nonce24[16..23]
        byte[] shortNonce = new byte[12];
        System.arraycopy(nonce24, 16, shortNonce, 4, 8);

        // 3. Configurar ChaCha20-Poly1305 normal (BouncyCastle)
        ChaCha20Poly1305 aead = new ChaCha20Poly1305();
        AEADParameters params = new AEADParameters(new KeyParameter(subKey), TAG_LEN_BITS, shortNonce, aad);
        aead.init(true, params);

        // 4. AAD + cifrar
        aead.processAADBytes(aad, 0, aad.length);
        byte[] outBuf = new byte[aead.getOutputSize(plaintext.length)];
        int off = aead.processBytes(plaintext, 0, plaintext.length, outBuf, 0);
        try {
            off += aead.doFinal(outBuf, off);
        } catch (InvalidCipherTextException e) {
            throw new RuntimeException("Error inesperado al cifrar XChaCha20-Poly1305", e);
        }

        // outBuf = ciphertext + tag
        return outBuf;
    }

    /**
     * Descifra con XChaCha20-Poly1305.
     *
     * @param key             Clave de 32 bytes (256 bits).
     * @param nonce24         Nonce de 24 bytes (192 bits).
     * @param aad             Datos adicionales autenticados (puede ser null o vacío).
     * @param ciphertextAndTag Ciphertext + tag (16 bytes al final).
     * @return plaintext descifrado. Lanza InvalidCipherTextException si la autenticidad falla.
     */
    public static byte[] decrypt(byte[] key, byte[] nonce24, byte[] aad, byte[] ciphertextAndTag)
            throws InvalidCipherTextException {

        validateInputs(key, nonce24, ciphertextAndTag);

        if (aad == null) {
            aad = new byte[0];
        }

        // 1. Subclave
        byte[] subKey = hChaCha20(key, nonce24);

        // 2. Nonce corto (12 bytes)
        byte[] shortNonce = new byte[12];
        System.arraycopy(nonce24, 16, shortNonce, 4, 8);

        // 3. ChaCha20-Poly1305 en modo descifrado
        ChaCha20Poly1305 aead = new ChaCha20Poly1305();
        AEADParameters params = new AEADParameters(new KeyParameter(subKey), TAG_LEN_BITS, shortNonce, aad);
        aead.init(false, params);

        aead.processAADBytes(aad, 0, aad.length);
        byte[] outBuf = new byte[aead.getOutputSize(ciphertextAndTag.length)];
        int off = aead.processBytes(ciphertextAndTag, 0, ciphertextAndTag.length, outBuf, 0);
        off += aead.doFinal(outBuf, off);

        // outBuf contiene el plaintext
        // Si el TAG no coincide, se lanza InvalidCipherTextException
        if (off < outBuf.length) {
            // Ajustamos si sobra espacio
            byte[] tmp = new byte[off];
            System.arraycopy(outBuf, 0, tmp, 0, off);
            return tmp;
        }
        return outBuf;
    }

    // ------------------------------------------------------------------------
    // HChaCha20: derivación de subclave
    // ------------------------------------------------------------------------
    private static byte[] hChaCha20(byte[] key, byte[] nonce24) {
        int[] state = new int[16];
        // (0..3) => SIGMA
        state[0]  = SIGMA[0];
        state[1]  = SIGMA[1];
        state[2]  = SIGMA[2];
        state[3]  = SIGMA[3];

        // (4..11) => 8 words de la key (32 bytes)
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i * 4);
        }

        // (12..15) => primeros 16 bytes del nonce
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce24, i * 4);
        }

        // 20 rondas => 10 double rounds
        for (int i = 0; i < 10; i++) {
            // Ronda par (columnas)
            quarterRound(state, 0, 4,  8, 12);
            quarterRound(state, 1, 5,  9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);

            // Ronda impar (diagonales)
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7,  8, 13);
            quarterRound(state, 3, 4,  9, 14);
        }

        // Subclave = state[0..3], state[12..15] (8 words = 32 bytes)
        byte[] outKey = new byte[32];
        intToLittleEndian(state[0],  outKey, 0);
        intToLittleEndian(state[1],  outKey, 4);
        intToLittleEndian(state[2],  outKey, 8);
        intToLittleEndian(state[3],  outKey, 12);

        intToLittleEndian(state[12], outKey, 16);
        intToLittleEndian(state[13], outKey, 20);
        intToLittleEndian(state[14], outKey, 24);
        intToLittleEndian(state[15], outKey, 28);

        return outKey;
    }

    // ------------------------------------------------------------------------
    // Funciones auxiliares
    // ------------------------------------------------------------------------
    private static void validateInputs(byte[] key, byte[] nonce24, byte[] data) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("La clave debe tener 32 bytes (256 bits).");
        }
        if (nonce24 == null || nonce24.length != NONCE_LEN) {
            throw new IllegalArgumentException("El nonce debe tener 24 bytes para XChaCha20.");
        }
        if (data == null || data.length == 0) {
            // Permitir data vacía, pero al menos no nula
        }
    }

    private static void quarterRound(int[] st, int a, int b, int c, int d) {
        st[a] += st[b]; st[d] = rotateLeft(st[d] ^ st[a], 16);
        st[c] += st[d]; st[b] = rotateLeft(st[b] ^ st[c], 12);
        st[a] += st[b]; st[d] = rotateLeft(st[d] ^ st[a], 8);
        st[c] += st[d]; st[b] = rotateLeft(st[b] ^ st[c], 7);
    }

    private static int rotateLeft(int v, int bits) {
        return (v << bits) | (v >>> (32 - bits));
    }

    private static int littleEndianToInt(byte[] bs, int off) {
        return  (bs[off] & 0xff)
                | ((bs[off + 1] & 0xff) << 8)
                | ((bs[off + 2] & 0xff) << 16)
                | ((bs[off + 3] & 0xff) << 24);
    }

    private static void intToLittleEndian(int val, byte[] bs, int off) {
        bs[off]     = (byte)(val);
        bs[off + 1] = (byte)(val >> 8);
        bs[off + 2] = (byte)(val >> 16);
        bs[off + 3] = (byte)(val >> 24);
    }
}
