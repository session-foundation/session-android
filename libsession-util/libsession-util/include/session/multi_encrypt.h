#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_encrypt_for_multiple_simple
///
/// This function performs 1-to-N or N-to-N encryptions (i.e. N encrypted payloads of either the
/// same value, or N separate values) using a random nonce and encodes the resulting encrypted data
/// in a self-contained bt-encoded value suitable for decrypting by a recipient via
/// `session_decrypt_for_multiple_simple`.
///
/// Inputs:
/// - `out_len` -- pointer to a size_t where the length of the returned buffer will be written.
/// - `messages` -- array of pointers to messages to encrypt.  This vector can either be a single
/// message to
///   separately encrypt the same message for each member, or a vector of the same length as
///   recipients to encrypt a different message for each member.
/// - `messages_lengths` -- array of the length of the buffers in `messages`.  Must be the same
///   length as `messages`.
/// - `n_messages` -- the number of messages provided.
/// - `recipients` -- array of pointers to recipient X25519 pubkeys.  Each pubkey is 32 bytes.
///   These are typically binary Session IDs, not including the 0x05 prefix.
/// - `n_recipients` -- the length of `recipients`
/// - `x25519_privkey` -- the X25519 private key of the sender (32 bytes).  Note that this is *NOT*
///   the Ed25519 secret key; see the alternative version of the function below if you only have an
///   Ed25519 key.
/// - `x25519_pubkey` -- the X25519 public key of the sender (32 bytes).  This needs to be known by
///   the recipient in order to decrypt the message; unlike session-protocol encryption, the sender
///   identity is not included in the message.
/// - `domain` -- a regular C string that uniquely identifies the "domain" of encryption, such as
///   "SessionGroupKickedMessage".  The value is arbitrary: what matters is that it is unique for
///   different encryption types, and that both the sender and recipient use the same value.  Max
///   length is 64 bytes.  Using a domain is encouraged so that the resulting encryption key between
///   a sender and recipient will be different if the same keys are used for encryption of unrelated
///   data types.
/// - `nonce` -- optional nonce.  Typically you should pass `NULL` here, which will cause a random
///   nonce to be used, but a 24-byte nonce can be specified for deterministic encryption.  (Note
///   that steps should be taken to ensure the nonce is not reused if specifying a nonce).
/// - `pad` -- if set to a value greater than 1 then junk encrypted values will be added until there
///   are a multiple of this many encrypted values in total.  The size of each junk entry will be
///   the same as the (encrypted) size of the first message; this padding is most useful when all
///   messages are the same size (or the same message) as with variable-sized messages the junk
///   entries will be somewhat identifiable.  Set to 0 to disable junk entry padding.
///
/// Outputs:
/// malloced buffer containing the encoded data, or NULL if encryption failed.  It is the caller's
/// responsibility to `free()` this buffer (if non-NULL) when done with it!
LIBSESSION_EXPORT unsigned char* session_encrypt_for_multiple_simple(
        size_t* out_len,
        const unsigned char** messages,
        const size_t* message_lengths,
        size_t n_messages,
        const unsigned char** recipients,
        size_t n_recipients,
        const unsigned char* x25519_privkey,
        const unsigned char* x25519_pubkey,
        const char* domain,
        const unsigned char* nonce,
        int pad);

/// This does the same as the above, except that it takes a single, 64-byte libsodium-style Ed25519
/// secret key instead of the x25519 privkey/pubkey argument pair.  The X25519 keys are converted
/// from the Ed25519 key on the fly.
LIBSESSION_EXPORT unsigned char* session_encrypt_for_multiple_simple_ed25519(
        size_t* out_len,
        const unsigned char** messages,
        const size_t* message_lengths,
        size_t n_messages,
        const unsigned char** recipients,
        size_t n_recipients,
        const unsigned char* ed25519_secret_key,
        const char* domain,
        const unsigned char* nonce,
        int pad);

/// API: crypto/session_decrypt_for_multiple_simple
///
/// This function attempts to decrypt a message produced by `session_encrypt_for_multiple_simple`;
/// if encryption (of any of the contained messages) succeeds you get back the message, otherwise if
/// the message failed to parse or decryption of all parts fails, you get back NULL.
///
/// Inputs:
/// - `out_len` -- pointer to a size_t where the length of the decrypted value will be written *if*
///   decryption succeeds.
/// - `encoded` -- the incoming message, produced by session_encrypt_for_multiple_simple
/// - `encoded_len` -- size of `encoded`
/// - `x25519_privkey` -- the X25519 private key of the receiver (32 bytes).  Note that this is
///   *NOT* the Ed25519 secret key; see the alternative version of the function below if you only
///   have an Ed25519 key.
/// - `x25519_pubkey` -- the X25519 public key of the receiver (32 bytes).
/// - `sender_x25519_pubkey` -- the X25519 public key of the sender (32 bytes).  Note that unlike
///   session encryption, the sender's identify is not available in the encrypted message itself.
/// - `domain` -- the encryption domain, which must be the same as the value used in
///   `session_encrypt_for_multiple_simple`.
///
/// Outputs:
/// If decryption succeeds, returns a pointer to a malloc'ed buffer containing the decrypted message
/// data, with length stored in `out_len`.  If parsing or decryption fails, returns NULL.  If the
/// return is non-NULL it is the responsibility of the caller to free the returned pointer!
LIBSESSION_EXPORT unsigned char* session_decrypt_for_multiple_simple(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* x25519_privkey,
        const unsigned char* x25519_pubkey,
        const unsigned char* sender_x25519_pubkey,
        const char* domain);

/// Same as above, but takes the recipients privkey/pubkey as a single Ed25519 secret key (64 bytes)
/// instead of a pair of X25519 argumensts.  The sender pubkey is still specified as an X25519
/// pubkey.
LIBSESSION_EXPORT unsigned char* session_decrypt_for_multiple_simple_ed25519_from_x25519(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* ed25519_secret,
        const unsigned char* sender_x25519_pubkey,
        const char* domain);

/// Same as above, but takes the recipients privkey/pubkey as a single Ed25519 secret key (64 bytes)
/// instead of a pair of X25519 argumensts, *and* takes the sender's pubkey as an Ed25519 public
/// key.  This is the typically the version you want when the "sender" is a group or other
/// non-Session ID known by an Ed25519 pubkey (03... or other non-05 keys) rather than a Session ID.
LIBSESSION_EXPORT unsigned char* session_decrypt_for_multiple_simple_ed25519(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* ed25519_secret,
        const unsigned char* sender_ed25519_pubkey,
        const char* domain);

#ifdef __cplusplus
}
#endif
