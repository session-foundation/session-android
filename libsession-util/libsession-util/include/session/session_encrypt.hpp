#pragma once

#include "types.hpp"

// Helper functions for the "Session Protocol" encryption mechanism.  This is the encryption used
// for DMs sent from one Session user to another.
//
// Suppose Alice with Ed25519 keys `a`/`A` and derived x25519 keys `x`/`X`, wants to send a mesage
// `M` to Brandy with Ed25519 keys `b`/`B` and derived x25519 keys `y`/`Y` (note that the x25519
// pubkeys in hex form are session ids, but without the `05` prefix).
//
// First she signs the message, her own *Ed25519* (not X) pubkey, and the recipients pubkey (X, not
// Ed):
//
//     SIG = Ed25519-sign(M || A || Y)
//
// Next a data message is composed of `M || A || SIG`, then encrypted for Brandy using:
//
//     CIPHERTEXT = crypto_box_seal(M || A || SIG)
//
// (see libsodium for details, but is effectively generating an ephemeral X25519 keypair, making a
// shared secret of that and the recipient key, then encrypting using XSalsa20-Poly1305 from the
// shared secret).
//
// On the decryption side, we do this in reverse and verify via the signature both the sender and
// intended (inner) recipient.  First, Brandy opens the ciphertext and extract the message, sender
// Ed pubkey, and signature:
//
//     M || A || SIG = crypto_box_seal_open(CIPHERTEXT)
//
// then constructs and verifies the expected signature DATA (recall Y = Brandy's X25519 pubkey):
//
//     Ed25519-verify(M || A || Y)
//
// Assuming this passes, we now know that `A` sent the message, and can convert this to a X25519
// pubkey to work out the Session ID:
//
//     X = Ed25519-pubkey-to-curve25519(A)
//
//     SENDER = '05' + hex(X)
//
// and thus Brandy now has decrypted, verified data sent by Alice.

namespace session {

/// API: crypto/encrypt_for_recipient
///
/// Performs session protocol encryption, typically for a DM sent between Session users.
///
/// Inputs:
/// - `ed25519_privkey` -- the libsodium-style secret key of the sender, 64 bytes.  Can also be
///   passed as a 32-byte seed, but the 64-byte value is preferrable (to avoid needing to
///   recompute the public key from the seed).
/// - `recipient_pubkey` -- the recipient X25519 pubkey, either as a 0x05-prefixed session ID
///   (33 bytes) or an unprefixed pubkey (32 bytes).
/// - `message` -- the message to encrypt for the recipient.
///
/// Outputs:
/// - The encrypted ciphertext to send.
/// - Throw if encryption fails or (which typically means invalid keys provided)
ustring encrypt_for_recipient(
        ustring_view ed25519_privkey, ustring_view recipient_pubkey, ustring_view message);

/// API: crypto/encrypt_for_recipient_deterministic
///
/// Performs session protocol encryption, but using a deterministic version of crypto_box_seal.
///
/// Warning: this determinism completely undermines the point of crypto_box_seal (compared to a
/// regular encrypted crypto_box): someone with the same sender Ed25519 keys and message could later
/// regenerate the same ephemeral key and nonce which would allow them to decrypt the sent message,
/// which is intentionally impossible with a crypto_box_seal.  This function is thus only
/// recommended for backwards compatibility with decryption mechanisms using that scheme where this
/// specific property is not needed, such as self-directed config messages.
///
/// Inputs:
/// Identical to `encrypt_for_recipient`.
///
/// Outputs:
/// Identical to `encrypt_for_recipient`.
ustring encrypt_for_recipient_deterministic(
        ustring_view ed25519_privkey, ustring_view recipient_pubkey, ustring_view message);

/// API: crypto/session_encrypt_for_blinded_recipient
///
/// This function attempts to encrypt a message using the SessionBlindingProtocol.
///
/// Inputs:
/// - `ed25519_privkey` -- the libsodium-style secret key of the sender, 64 bytes.  Can also be
///   passed as a 32-byte seed, but the 64-byte value is preferrable (to avoid needing to
///   recompute the public key from the seed).
/// - `recipient_pubkey` -- the recipient blinded id, either 0x15-prefixed or 0x25-prefixed
///   (33 bytes).
/// - `message` -- the message to encrypt for the recipient.
///
/// Outputs:
/// - The encrypted ciphertext to send.
/// - Throw if encryption fails or (which typically means invalid keys provided)
ustring encrypt_for_blinded_recipient(
        ustring_view ed25519_privkey,
        ustring_view server_pk,
        ustring_view recipient_blinded_id,
        ustring_view message);

/// API: crypto/sign_for_recipient
///
/// Performs the signing steps for session protocol encryption.  This is responsible for producing
/// a packed authored, signed message of:
///
///     MESSAGE || SENDER_ED25519_PUBKEY || SIG
///
/// where SIG is the signed value of:
///
///     MESSAGE || SENDER_ED25519_PUBKEY || RECIPIENT_X25519_PUBKEY
///
/// thus allowing both sender identification, recipient verification, and authentication.
///
/// This function is mostly for internal use, but is exposed for debugging purposes: it is typically
/// not called directly but rather used by `encrypt_for_recipient` or
/// `encrypt_for_recipient_deterministic`, both of which call this function to construct the inner
/// signed message.
///
/// Inputs:
/// - `ed25519_privkey` -- the seed (32 bytes) or secret key (64 bytes) of the sender
/// - `recipient_pubkey` -- the recipient X25519 pubkey, which may or may not be prefixed with the
///   0x05 session id prefix (33 bytes if prefixed, 32 if not prefixed).
/// - `message` -- the message to embed and sign.
ustring sign_for_recipient(
        ustring_view ed25519_privkey, ustring_view recipient_pubkey, ustring_view message);

/// API: crypto/decrypt_incoming
///
/// Inverse of `encrypt_for_recipient`: this decrypts the message, extracts the sender Ed25519
/// pubkey, and verifies that the sender Ed25519 signature on the message.
///
/// Inputs:
/// - `ed25519_privkey` -- the private key of the recipient.  Can be a 32-byte seed, or a 64-byte
///   libsodium secret key.  The latter is a bit faster as it doesn't have to re-compute the pubkey
///   from the seed.
/// - `ciphertext` -- the encrypted data
///
/// Outputs:
/// - `std::pair<ustring, ustring>` -- the plaintext binary data that was encrypted and the
///   sender's ED25519 pubkey, *if* the message decrypted and validated successfully.  Throws on
///   error.
std::pair<ustring, ustring> decrypt_incoming(ustring_view ed25519_privkey, ustring_view ciphertext);

/// API: crypto/decrypt_incoming
///
/// Inverse of `encrypt_for_recipient`: this decrypts the message, extracts the sender Ed25519
/// pubkey, and verifies that the sender Ed25519 signature on the message. This function is used
/// for decrypting legacy group messages which only have an x25519 key pair, the Ed25519 version
/// of this function should be preferred where possible.
///
/// Inputs:
/// - `ed25519_privkey` -- the private key of the recipient.  Can be a 32-byte seed, or a 64-byte
///   libsodium secret key.  The latter is a bit faster as it doesn't have to re-compute the pubkey
///   from the seed.
/// - `ciphertext` -- the encrypted data
///
/// Outputs:
/// - `std::pair<ustring, ustring>` -- the plaintext binary data that was encrypted and the
///   sender's ED25519 pubkey, *if* the message decrypted and validated successfully.  Throws on
///   error.
std::pair<ustring, ustring> decrypt_incoming(
        ustring_view x25519_pubkey, ustring_view x25519_seckey, ustring_view ciphertext);

/// API: crypto/decrypt_incoming
///
/// Inverse of `encrypt_for_recipient`: this decrypts the message, verifies that the sender Ed25519
/// signature on the message and converts the extracted sender's Ed25519 pubkey into a session ID.
///
/// Inputs:
/// - `ed25519_privkey` -- the private key of the recipient.  Can be a 32-byte seed, or a 64-byte
///   libsodium secret key.  The latter is a bit faster as it doesn't have to re-compute the pubkey
///   from the seed.
/// - `ciphertext` -- the encrypted data
///
/// Outputs:
/// - `std::pair<ustring, std::string>` -- the plaintext binary data that was encrypted and the
///   session ID (in hex), *if* the message decrypted and validated successfully.  Throws on error.
std::pair<ustring, std::string> decrypt_incoming_session_id(
        ustring_view ed25519_privkey, ustring_view ciphertext);

/// API: crypto/decrypt_incoming
///
/// Inverse of `encrypt_for_recipient`: this decrypts the message, verifies that the sender Ed25519
/// signature on the message and converts the extracted sender's Ed25519 pubkey into a session ID.
/// This function is used for decrypting legacy group messages which only have an x25519 key pair,
/// the Ed25519 version of this function should be preferred where possible.
///
/// Inputs:
/// - `x25519_pubkey` -- the 32 byte x25519 public key of the recipient.
/// - `x25519_seckey` -- the 32 byte x25519 private key of the recipient.
/// - `ciphertext` -- the encrypted data
///
/// Outputs:
/// - `std::pair<ustring, std::string>` -- the plaintext binary data that was encrypted and the
///   session ID (in hex), *if* the message decrypted and validated successfully.  Throws on error.
std::pair<ustring, std::string> decrypt_incoming_session_id(
        ustring_view x25519_pubkey, ustring_view x25519_seckey, ustring_view ciphertext);

/// API: crypto/decrypt_from_blinded_recipient
///
/// This function attempts to decrypt a message using the SessionBlindingProtocol. If the
/// `sender_id` matches the `blinded_id` generated from the `ed25519_privkey` this function assumes
/// the `ciphertext` is an outgoing message and decrypts it as such.
///
/// Inputs:
/// - `ed25519_privkey` -- the Ed25519 private key of the receiver.  Can be a 32-byte seed, or a
/// 64-byte
///   libsodium secret key.  The latter is a bit faster as it doesn't have to re-compute the pubkey
///   from the seed.
/// - `server_pk` -- the public key of the open group server to route the blinded message through
/// (32 bytes).
/// - `sender_id` -- the blinded id of the sender including the blinding prefix (33 bytes),
///   'blind15' or 'blind25' decryption will be chosed based on this value.
/// - `recipient_id` -- the blinded id of the recipient including the blinding prefix (33 bytes),
///   must match the same 'blind15' or 'blind25' type of the `sender_id`.
/// - `ciphertext` -- Pointer to a data buffer containing the encrypted data.
///
/// Outputs:
/// - `std::pair<ustring, std::string>` -- the plaintext binary data that was encrypted and the
///   session ID (in hex), *if* the message decrypted and validated successfully.  Throws on error.
std::pair<ustring, std::string> decrypt_from_blinded_recipient(
        ustring_view ed25519_privkey,
        ustring_view server_pk,
        ustring_view sender_id,
        ustring_view recipient_id,
        ustring_view ciphertext);

/// API: crypto/decrypt_ons_response
///
/// Decrypts the response of an ONS lookup.
///
/// Inputs:
/// - `lowercase_name` -- the lowercase name which was looked to up to retrieve this response.
/// - `ciphertext` -- ciphertext returned from the server.
/// - `nonce` -- the nonce returned from the server
///
/// Outputs:
/// - `std::string` -- the session ID (in hex) returned from the server, *if* the server returned
///   a session ID.  Throws on error/failure.
std::string decrypt_ons_response(
        std::string_view lowercase_name, ustring_view ciphertext, ustring_view nonce);

/// API: crypto/decrypt_push_notification
///
/// Decrypts a push notification payload.
///
/// Inputs:
/// - `payload` -- the payload included in the push notification.
/// - `enc_key` -- the device encryption key used when subscribing for push notifications (32
/// bytes).
///
/// Outputs:
/// - `ustring` -- the decrypted push notification payload, *if* the decryption was
///   successful.  Throws on error/failure.
ustring decrypt_push_notification(ustring_view payload, ustring_view enc_key);

}  // namespace session
