#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "../export.h"

/// API: encrypt/config_encrypt
///
/// Wrapper around session::config::encrypt.  message and key_base are binary: message has the
/// length provided, key_base must be exactly 32 bytes.  domain is a c string.  Returns a newly
/// allocated buffer containing the encrypted data, and sets the data's length into
/// `ciphertext_size`.  It is the caller's responsibility to `free()` the returned buffer!
///
/// Declaration:
/// ```cpp
/// UNSIGNED CHAR* config_encrypt(
///     [in]    const unsigned char*    message,
///     [in]    size_t                  mlen,
///     [in]    const unsigned char*    key_base,
///     [in]    const char*             domain,
///     [out]   size_t*                 ciphertext_size
/// );
/// ```
///
/// Inputs:
/// - `message` -- [in] The message to encrypted in binary
/// - `mlen` -- [in] Length of the message provided
/// - `key_base` -- [in] Key, must be binary
/// - `domain` -- [in] Text
/// - `ciphertext_size` -- [out] will contain the size of the returned ciphertext
///
/// Outputs:
/// - `unsigned char*` -- ciphertext, will be nullptr on error
LIBSESSION_EXPORT unsigned char* config_encrypt(
        const unsigned char* message,
        size_t mlen,
        const unsigned char* key_base,
        const char* domain,
        size_t* ciphertext_size);

/// API: encrypt/config_decrypt
///
/// Wrapper around session::config::decrypt.  ciphertext and key_base are binary: ciphertext has the
/// length provided, key_base must be exactly 32 bytes.  domain is a c string.  Returns a newly
/// allocated buffer containing the decrypted data, and sets the data's length into
/// `plaintext_size`.  It is the caller's responsibility to `free()` the returned buffer!
///
/// Declaration:
/// ```cpp
/// UNSIGNED CHAR* config_decrypt(
///     [in]    const unsigned char*    ciphertext,
///     [in]    size_t                  clen,
///     [in]    const unsigned char*    key_base,
///     [in]    const char*             domain,
///     [out]   size_t*                 plaintext_size
/// );
/// ```
///
/// Inputs:
/// - `ciphertext` -- [in] the message to be decrypted in binary
/// - `clen` -- [in] length of the message provided
/// - `key_base` -- [in] key, must be binary
/// - `domain` -- [in] text
/// - `plaintext_size` -- [out] will contain the size of the returned plaintext
///
/// Outputs:
/// - `unsigned char*` -- decrypted message, will be nullptr on error
LIBSESSION_EXPORT unsigned char* config_decrypt(
        const unsigned char* ciphertext,
        size_t clen,
        const unsigned char* key_base,
        const char* domain,
        size_t* plaintext_size);

/// API: encrypt/config_padded_size
///
/// Returns the amount of padding needed for a plaintext of size s with encryption overhead
/// `overhead`.
///
/// Declaration:
/// ```cpp
/// SIZE_T config_padded_size(
///     [in]   size_t   s,
///     [in]   size_t   overhead
/// );
/// ```
///
/// Inputs:
/// - `s` -- [in] unsigned integer of the size of the plaintext
/// - `overhead` -- [in] unsigned integer of the desired overhead
///
/// Outputs:
/// - `size_t` -- Unsigned integer of the amount of padding necessary
LIBSESSION_EXPORT size_t config_padded_size(size_t s, size_t overhead);

#ifdef __cplusplus
}
#endif
