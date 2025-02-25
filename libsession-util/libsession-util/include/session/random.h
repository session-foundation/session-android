#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "export.h"

/// API: crypto/session_random
///
/// Wrapper around the randombytes_buf function.
///
/// Inputs:
/// - `size` -- [in] number of bytes to be generated.
///
/// Outputs:
/// - `unsigned char*` -- pointer to random bytes of `size` bytes.
LIBSESSION_EXPORT unsigned char* session_random(size_t size);

#ifdef __cplusplus
}
#endif
