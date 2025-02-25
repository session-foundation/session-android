#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>

#include "../export.h"

/// API: util/session_id_is_valid
///
/// Returns true if session_id has the right form (66 hex digits).  This is a quick check, not a
/// robust one: it does not check the leading byte prefix, nor the cryptographic properties of the
/// pubkey for actual validity.
///
/// Declaration:
/// ```cpp
/// BOOL session_id_is_valid(
///     [in]    const char*     session_id
/// );
/// ```
///
/// Inputs:
/// - `session_id` -- [in] hex string of the session id
///
/// Outputs:
/// - `bool` -- Returns true if the session id has the right form
LIBSESSION_EXPORT bool session_id_is_valid(const char* session_id);

#ifdef __cplusplus
}
#endif
