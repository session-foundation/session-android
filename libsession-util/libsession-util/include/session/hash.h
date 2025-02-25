#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_hash
///
/// Wrapper around the crypto_generichash_blake2b function.
///
/// Inputs:
/// - `size` -- [in] length of the hash to be generated.
/// - `msg_in` -- [in] the message a hash should be generated for.
/// - `msg_len` -- [in] length of `msg_in`.
/// - `key_in` -- [in] an optional key to be used when generating the hash.
/// - `key_len` -- [in] length of `key_in`.
/// - `hash_out` -- [out] pointer to a buffer of at least `size` bytes where the
///   hash will be written.
///
/// Outputs:
/// - `bool` -- True if the generation was successful, false if generation failed.
LIBSESSION_EXPORT bool session_hash(
        size_t size,
        const unsigned char* msg_in,
        size_t msg_len,
        const unsigned char* key_in,
        size_t key_len,
        unsigned char* hash_out);

#ifdef __cplusplus
}
#endif
