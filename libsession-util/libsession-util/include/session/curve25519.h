#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_curve25519_key_pair
///
/// Generates a random curve25519 key pair.
///
/// Inputs:
/// - `curve25519_pk_out` -- [out] pointer to a buffer of 32 bytes where the public key will be
/// written.
/// - `curve25519_sk_out` -- [out] pointer to a buffer of 32 bytes where the private key will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the seed was successfully retrieved, false if failed.
LIBSESSION_EXPORT bool session_curve25519_key_pair(
        unsigned char* curve25519_pk_out, /* 32 byte output buffer */
        unsigned char* curve25519_sk_out /* 32 byte output buffer */);

/// API: crypto/session_to_curve25519_pubkey
///
/// Generates a curve25519 public key for an ed25519 public key.
///
/// Inputs:
/// - `ed25519_pubkey` -- the ed25519 public key (32 bytes).
/// - `curve25519_pk_out` -- [out] pointer to a buffer of 32 bytes where the public key will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the public key was successfully generated, false if failed.
LIBSESSION_EXPORT bool session_to_curve25519_pubkey(
        const unsigned char* ed25519_pubkey, /* 32 bytes */
        unsigned char* curve25519_pk_out /* 32 byte output buffer */);

/// API: crypto/session_to_curve25519_seckey
///
/// Generates a curve25519 secret key given given either a libsodium-style secret key, 64
/// bytes.  Can also be passed as a 32-byte seed.
///
/// Inputs:
/// - `ed25519_seckey` -- [in] the libsodium-style secret key, 64 bytes.  Can also be
///   passed as a 32-byte seed.
/// - `curve25519_sk_out` -- [out] pointer to a buffer of 32 bytes where the secret key will be
/// written.
///
/// Outputs:
/// - `bool` -- True if the secret key was successfully generated, false if failed.
LIBSESSION_EXPORT bool session_to_curve25519_seckey(
        const unsigned char* ed25519_seckey, /* 64 bytes */
        unsigned char* curve25519_sk_out /* 32 byte output buffer */);

#ifdef __cplusplus
}
#endif
