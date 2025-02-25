#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"
#include "platform.h"

/// API: crypto/session_blind15_key_pair
///
/// This function attempts to generate a blind15 key pair.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `server_pk` -- [in] the public key of the open group server to generate the
///   blinded id for (32 bytes).
/// - `blinded_pk_out` -- [out] pointer to a buffer of at least 32 bytes where the blinded_pk will
///   be written if generation was successful.
/// - `blinded_sk_out` -- [out] pointer to a buffer of at least 32 bytes where the blinded_sk will
///   be written if generation was successful.
///
/// Outputs:
/// - `bool` -- True if the key was successfully generated, false if generation failed.
LIBSESSION_EXPORT bool session_blind15_key_pair(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        const unsigned char* server_pk,      /* 32 bytes */
        unsigned char* blinded_pk_out,       /* 32 byte output buffer */
        unsigned char* blinded_sk_out /* 32 byte output buffer */);

/// API: crypto/session_blind25_key_pair
///
/// This function attempts to generate a blind25 key pair.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `server_pk` -- [in] the public key of the open group server to generate the
///   blinded id for (32 bytes).
/// - `blinded_pk_out` -- [out] pointer to a buffer of at least 32 bytes where the blinded_pk will
///   be written if generation was successful.
/// - `blinded_sk_out` -- [out] pointer to a buffer of at least 32 bytes where the blinded_sk will
///   be written if generation was successful.
///
/// Outputs:
/// - `bool` -- True if the key was successfully generated, false if generation failed.
LIBSESSION_EXPORT bool session_blind25_key_pair(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        const unsigned char* server_pk,      /* 32 bytes */
        unsigned char* blinded_pk_out,       /* 32 byte output buffer */
        unsigned char* blinded_sk_out /* 32 byte output buffer */);

/// API: crypto/session_blind_version_key_pair
///
/// This function attempts to generate a blind-version key pair.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the Ed25519 private key of the user (64 bytes).
/// - `blinded_pk_out` -- [out] pointer to a buffer of at least 32 bytes where the blinded_pk will
///   be written if generation was successful.
/// - `blinded_sk_out` -- [out] pointer to a buffer of at least 64 bytes where the blinded_sk will
///   be written if generation was successful.
///
/// Outputs:
/// - `bool` -- True if the key was successfully generated, false if generation failed.
LIBSESSION_EXPORT bool session_blind_version_key_pair(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        unsigned char* blinded_pk_out,       /* 32 byte output buffer */
        unsigned char* blinded_sk_out /* 64 byte output buffer */);

/// API: crypto/session_blind15_sign
///
/// This function attempts to generate a signature for a message using a blind15 private key.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `server_pk` -- [in] the public key of the open group server to generate the
///   blinded id for (32 bytes).
/// - `msg` -- [in] Pointer to a data buffer containing the message to generate a signature for.
/// - `msg_len` -- [in] Length of `msg`
/// - `blinded_sig_out` -- [out] pointer to a buffer of at least 64 bytes where the signature will
///   be written if generation was successful.
///
/// Outputs:
/// - `bool` -- True if the signature was successfully generated, false if generation failed.
LIBSESSION_EXPORT bool session_blind15_sign(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        const unsigned char* server_pk,      /* 32 bytes */
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* blinded_sig_out /* 64 byte output buffer */);

/// API: crypto/session_blind25_sign
///
/// This function attempts to generate a signature for a message using a blind25 private key.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the Ed25519 private key of the sender (64 bytes).
/// - `server_pk` -- [in] the public key of the open group server to generate the
///   blinded id for (32 bytes).
/// - `msg` -- [in] Pointer to a data buffer containing the message to generate a signature for.
/// - `msg_len` -- [in] Length of `msg`
/// - `blinded_sig_out` -- [out] pointer to a buffer of at least 64 bytes where the signature will
///   be written if generation was successful.
///
/// Outputs:
/// - `bool` -- True if the signature was successfully generated, false if generation failed.
LIBSESSION_EXPORT bool session_blind25_sign(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        const unsigned char* server_pk,      /* 32 bytes */
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* blinded_sig_out /* 64 byte output buffer */);

/// Computes a verifiable version-blinded signature that validates with the version-blinded pubkey
/// that would be returned from blind_version_key_pair.
///
/// Takes the Ed25519 secret key (64 bytes), unix timestamp, method, path, and optional body.
/// Returns a version-blinded signature.
LIBSESSION_EXPORT bool session_blind_version_sign_request(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        uint64_t timestamp,
        const char* method,
        const char* path,
        const unsigned char* body, /* optional */
        size_t body_len,
        unsigned char* blinded_sig_out /* 64 byte output buffer */);

/// Computes a verifiable version-blinded signature that validates with the version-blinded pubkey
/// that would be returned from blind_version_key_pair.
///
/// Takes the Ed25519 secret key (64 bytes), platform and unix timestamp.  Returns a version-blinded
/// signature.
LIBSESSION_EXPORT bool session_blind_version_sign(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        CLIENT_PLATFORM platform,
        uint64_t timestamp,
        unsigned char* blinded_sig_out /* 64 byte output buffer */);

/// API: crypto/session_blind25_sign
///
/// This function attempts to generate a signature for a message using a blind25 private key.
///
/// Inputs:
/// - `session_id` -- [in] the session_id to compare (66 bytes with a 05 prefix).
/// - `blinded_id` -- [in] the blinded_id to compare, can be either 15 or 25 blinded (66 bytes).
/// - `server_pk` -- [in] the public key of the open group server to the blinded id came from (64
/// bytes).
///
/// Outputs:
/// - `bool` -- True if the session_id matches the blinded_id, false if not.
LIBSESSION_EXPORT bool session_id_matches_blinded_id(
        const char* session_id, /* 66 bytes */
        const char* blinded_id, /* 66 bytes */
        const char* server_pk /* 64 bytes */);

#ifdef __cplusplus
}
#endif
