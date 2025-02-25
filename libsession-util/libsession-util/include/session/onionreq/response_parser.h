#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>

#include "../export.h"
#include "builder.h"

/// API: onion_request_decrypt
///
/// Wrapper around session::onionreq::ResponseParser.  ciphertext_in is binary.
/// enc_type should be set to ENCRYPT_TYPE::ENCRYPT_TYPE_X_CHA_CHA_20 if it's not
/// set when creating the builder destination_x25519_pubkey, final_x25519_pubkey
/// and final_x25519_seckey should be in bytes and be exactly 32 bytes. Returns a
/// flag indicating success or failure.
///
/// Declaration:
/// ```cpp
/// bool onion_request_decrypt(
///     [in]    const unsigned char*    ciphertext,
///     [in]    size_t                  ciphertext_len,
///     [in]    ENCRYPT_TYPE            enc_type_,
///     [in]    const char*             destination_x25519_pubkey,
///     [in]    const char*             final_x25519_pubkey,
///     [in]    const char*             final_x25519_seckey,
///     [out]   unsigned char**         plaintext_out,
///     [out]   size_t*                 plaintext_out_len
/// );
/// ```
///
/// Inputs:
/// - `ciphertext` -- [in] The onion request response data
/// - `ciphertext_len` -- [in] The length of ciphertext
/// - `enc_type_` -- [in] The encryption type which was used for the onion request
/// - `destination_x25519_pubkey` -- [in] The x25519 public key for the server destination
/// - `final_x25519_pubkey` -- [in] The final x25519 public key used for the onion request
/// - `final_x25519_seckey` -- [in] The final x25519 secret key used for the onion request
/// - `plaintext_out` -- [out] decrypted content contained within ciphertext, will be nullptr on
/// error
/// - `plaintext_out_len` -- [out] length of plaintext_out if not null
///
/// Outputs:
/// - `bool` -- True if the onion request was successfully constructed, false if it failed.
///   If (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool onion_request_decrypt(
        const unsigned char* ciphertext,
        size_t ciphertext_len,
        ENCRYPT_TYPE enc_type_,
        unsigned char* destination_x25519_pubkey,
        unsigned char* final_x25519_pubkey,
        unsigned char* final_x25519_seckey,
        unsigned char** plaintext_out,
        size_t* plaintext_out_len);

#ifdef __cplusplus
}
#endif