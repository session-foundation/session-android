#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_ed25519_key_pair
///
/// Generates a random ed25519 key pair.
///
/// Inputs:
/// - `ed25519_pk_out` -- [out] pointer to a buffer of 32 bytes where the public key will be
/// written.
/// - `ed25519_sk_out` -- [out] pointer to a buffer of 64 bytes where the private key will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the seed was successfully retrieved, false if failed.
LIBSESSION_EXPORT bool session_ed25519_key_pair(
        unsigned char* ed25519_pk_out, /* 32 byte output buffer */
        unsigned char* ed25519_sk_out /* 64 byte output buffer */);

/// API: crypto/session_ed25519_key_pair_seed
///
/// Generates a ed25519 key pair for a 32 byte seed.
///
/// Inputs:
/// - `ed25519_seed` -- [in] the 32 byte seed.
/// - `ed25519_pk_out` -- [out] pointer to a buffer of 32 bytes where the public key will be
/// written.
/// - `ed25519_sk_out` -- [out] pointer to a buffer of 64 bytes where the private key will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the seed was successfully retrieved, false if failed.
LIBSESSION_EXPORT bool session_ed25519_key_pair_seed(
        const unsigned char* ed25519_seed, /* 32 bytes */
        unsigned char* ed25519_pk_out,     /* 32 byte output buffer */
        unsigned char* ed25519_sk_out /* 64 byte output buffer */);

/// API: crypto/session_seed_for_ed_privkey
///
/// Returns the seed for an ed25519 key pair given either the libsodium-style secret key, 64
/// bytes.
///
/// Inputs:
/// - `ed25519_privkey` -- [in] the libsodium-style secret key of the sender, 64 bytes.
/// - `ed25519_seed_out` -- [out] pointer to a buffer of 32 bytes where the seed will be written.
///
/// Outputs:
/// - `bool` -- True if the seed was successfully retrieved, false if failed.
LIBSESSION_EXPORT bool session_seed_for_ed_privkey(
        const unsigned char* ed25519_privkey, /* 64 bytes */
        unsigned char* ed25519_seed_out /* 32 byte output buffer */);

/// API: crypto/session_ed25519_sign
///
/// Generates a signature for the message using the libsodium-style ed25519 secret key, 64 bytes.
///
/// Inputs:
/// - `ed25519_privkey` -- [in] the libsodium-style secret key of the sender, 64 bytes.
/// - `msg` -- [in] the data to generate a signature for.
/// - `msg_len` -- [in] the length of the `msg` data.
/// - `ed25519_sig_out` -- [out] pointer to a buffer of 64 bytes where the signature will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the seed was successfully retrieved, false if failed.
LIBSESSION_EXPORT bool session_ed25519_sign(
        const unsigned char* ed25519_privkey, /* 64 bytes */
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* ed25519_sig_out /* 64 byte output buffer */);

/// API: crypto/session_ed25519_verify
///
/// Verify a message and signature for a given pubkey.
///
/// Inputs:
/// - `sig` -- [in] the signature to verify, 64 bytes.
/// - `pubkey` -- [in] the pubkey for the secret key that was used to generate the signature, 32
/// bytes.
/// - `msg` -- [in] the data to verify the signature for.
/// - `msg_len` -- [in] the length of the `msg` data.
///
/// Outputs:
/// - A flag indicating whether the signature is valid
LIBSESSION_EXPORT bool session_ed25519_verify(
        const unsigned char* sig, /* 64 bytes */
        const unsigned char* pubkey,
        const unsigned char* msg,
        size_t msg_len);

#ifdef __cplusplus
}
#endif
