#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_encrypt_for_recipient_deterministic
///
/// This function attempts to encrypt a message using the SessionProtocol.
///
/// Inputs:
/// - `plaintext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `plaintext_len` -- [in] Length of `plaintext_in`
/// - `ed25519_privkey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `recipient_pubkey` -- [in] the x25519 public key of the recipient (32 bytes).
/// - `ciphertext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   encrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `ciphertext_len` -- [out] Pointer to a size_t where the length of `ciphertext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the message was successfully decrypted, false if decryption failed.  If
///   (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool session_encrypt_for_recipient_deterministic(
        const unsigned char* plaintext_in,
        size_t plaintext_len,
        const unsigned char* ed25519_privkey,  /* 64 bytes */
        const unsigned char* recipient_pubkey, /* 32 bytes */
        unsigned char** ciphertext_out,
        size_t* ciphertext_len);

/// API: crypto/session_encrypt_for_blinded_recipient
///
/// This function attempts to encrypt a message using the SessionBlindingProtocol.
///
/// Inputs:
/// - `plaintext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `plaintext_len` -- [in] Length of `plaintext_in`
/// - `ed25519_privkey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `open_group_pubkey` -- [in] the public key of the open group server to route
///   the blinded message through (32 bytes).
/// - `recipient_blinded_id` -- [in] the blinded id of the recipient including the blinding
///   prefix (33 bytes), 'blind15' or 'blind25' encryption will be chosed based on this value.
/// - `ciphertext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   encrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `ciphertext_len` -- [out] Pointer to a size_t where the length of `ciphertext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the message was successfully decrypted, false if decryption failed.  If
///   (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool session_encrypt_for_blinded_recipient(
        const unsigned char* plaintext_in,
        size_t plaintext_len,
        const unsigned char* ed25519_privkey,      /* 64 bytes */
        const unsigned char* open_group_pubkey,    /* 32 bytes */
        const unsigned char* recipient_blinded_id, /* 33 bytes */
        unsigned char** ciphertext_out,
        size_t* ciphertext_len);

/// API: crypto/session_decrypt_incoming
///
/// This function attempts to decrypt a message using the SessionProtocol.
///
/// Inputs:
/// - `ciphertext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `ciphertext_len` -- [in] Length of `ciphertext_in`
/// - `ed25519_privkey` -- [in] the Ed25519 private key of the receiver (64 bytes).
/// - `session_id_out` -- [out] pointer to a buffer of at least 67 bytes where the null-terminated,
///   hex-encoded session_id of the message's author will be written if decryption/verification was
///   successful.
/// - `plaintext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   decrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `plaintext_len` -- [out] Pointer to a size_t where the length of `plaintext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the message was successfully decrypted, false if decryption failed.  If
///   (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool session_decrypt_incoming(
        const unsigned char* ciphertext_in,
        size_t ciphertext_len,
        const unsigned char* ed25519_privkey, /* 64 bytes */
        char* session_id_out,                 /* 67 byte output buffer */
        unsigned char** plaintext_out,
        size_t* plaintext_len);

/// API: crypto/session_decrypt_incoming_legacy_group
///
/// This function attempts to decrypt a message using the SessionProtocol.
///
/// Inputs:
/// - `ciphertext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `ciphertext_len` -- [in] Length of `ciphertext_in`
/// - `x25519_pubkey` -- [in] the x25519 public key of the receiver (32 bytes).
/// - `x25519_seckey` -- [in] the x25519 secret key of the receiver (32 bytes).
/// - `session_id_out` -- [out] pointer to a buffer of at least 67 bytes where the null-terminated,
///   hex-encoded session_id of the message's author will be written if decryption/verification was
///   successful.
/// - `plaintext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   decrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `plaintext_len` -- [out] Pointer to a size_t where the length of `plaintext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the message was successfully decrypted, false if decryption failed.  If
///   (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool session_decrypt_incoming_legacy_group(
        const unsigned char* ciphertext_in,
        size_t ciphertext_len,
        const unsigned char* x25519_pubkey, /* 32 bytes */
        const unsigned char* x25519_seckey, /* 32 bytes */
        char* session_id_out,               /* 67 byte output buffer */
        unsigned char** plaintext_out,
        size_t* plaintext_len);

/// API: crypto/session_decrypt_for_blinded_recipient
///
/// This function attempts to decrypt a message using the SessionBlindingProtocol.
///
/// Inputs:
/// - `ciphertext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `ciphertext_len` -- [in] Length of `ciphertext_in`
/// - `ed25519_privkey` -- [in] the Ed25519 private key of the receiver (64 bytes).
/// - `open_group_pubkey` -- [in] the public key of the open group server to route
///   the blinded message through (32 bytes).
/// - `sender_id` -- [in] the blinded id of the sender including the blinding prefix (33 bytes),
///   'blind15' or 'blind25' decryption will be chosed based on this value.
/// - `recipient_id` -- [in] the blinded id of the recipient including the blinding prefix (33
/// bytes),
///   must match the same 'blind15' or 'blind25' type of the `sender_id`.
/// - `session_id_out` -- [out] pointer to a buffer of at least 67 bytes where the null-terminated,
///   hex-encoded session_id of the message's author will be written if decryption/verification was
///   successful.
/// - `plaintext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   decrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `plaintext_len` -- [out] Pointer to a size_t where the length of `plaintext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the message was successfully decrypted, false if decryption failed.  If
///   (and only if) true is returned then `plaintext_out` must be freed when done with it.
LIBSESSION_EXPORT bool session_decrypt_for_blinded_recipient(
        const unsigned char* ciphertext_in,
        size_t ciphertext_len,
        const unsigned char* ed25519_privkey,   /* 64 bytes */
        const unsigned char* open_group_pubkey, /* 32 bytes */
        const unsigned char* sender_id,         /* 33 bytes */
        const unsigned char* recipient_id,      /* 33 bytes */
        char* session_id_out,                   /* 67 byte output buffer */
        unsigned char** plaintext_out,
        size_t* plaintext_len);

/// API: crypto/session_decrypt_ons_response
///
/// This function attempts to decrypt an ONS response.
///
/// Inputs:
/// - `lowercase_name_in` -- [in] Pointer to a buffer containing the lowercase name used to trigger
/// the response.
/// - `name_len` -- [in] Length of `name_in`.
/// - `ciphertext_in` -- [in] Pointer to a data buffer containing the encrypted data.
/// - `ciphertext_len` -- [in] Length of `ciphertext_in`.
/// - `nonce_in` -- [in] Pointer to a data buffer containing the nonce (24 bytes).
/// - `session_id_out` -- [out] pointer to a buffer of at least 67 bytes where the null-terminated,
///   hex-encoded session_id will be written if decryption was successful.
///
/// Outputs:
/// - `bool` -- True if the session ID was successfully decrypted, false if decryption failed.
LIBSESSION_EXPORT bool session_decrypt_ons_response(
        const char* lowercase_name_in,
        size_t name_len,
        const unsigned char* ciphertext_in,
        size_t ciphertext_len,
        const unsigned char* nonce_in, /* 24 bytes */
        char* session_id_out /* 67 byte output buffer */);

/// API: crypto/session_decrypt_push_notification
///
/// Decrypts a push notification payload.
///
/// Inputs:
/// - `payload_in` -- [in] the payload included in the push notification.
/// - `payload_len` -- [in] Length of `payload_in`.
/// - `enc_key_in` -- [in] the device encryption key used when subscribing for push notifications
/// (32 bytes).
/// - `plaintext_out` -- [out] Pointer-pointer to an output buffer; a new buffer is allocated, the
///   decrypted data written to it, and then the pointer to that buffer is stored here.
///   This buffer must be `free()`d by the caller when done with it *unless* the function returns
///   false, in which case the buffer pointer will not be set.
/// - `plaintext_len` -- [out] Pointer to a size_t where the length of `plaintext_out` is stored.
///   Not touched if the function returns false.
///
/// Outputs:
/// - `bool` -- True if the decryption was successful, false if decryption failed.
LIBSESSION_EXPORT bool session_decrypt_push_notification(
        const unsigned char* payload_in,
        size_t payload_len,
        const unsigned char* enc_key_in, /* 32 bytes */
        unsigned char** plaintext_out,
        size_t* plaintext_len);

#ifdef __cplusplus
}
#endif
