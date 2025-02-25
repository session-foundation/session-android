#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>

#include "../export.h"

// Maximum string length of a community base URL
LIBSESSION_EXPORT extern const size_t COMMUNITY_BASE_URL_MAX_LENGTH;

// Maximum string length of a community room token
LIBSESSION_EXPORT extern const size_t COMMUNITY_ROOM_MAX_LENGTH;

// Maximum string length of a full URL as produced by the community_make_full_url() function.
// Unlike the above constants, this *includes* space for a NULL string terminator.
LIBSESSION_EXPORT extern const size_t COMMUNITY_FULL_URL_MAX_LENGTH;

/// API: community/community_parse_full_url
///
/// Parses a community URL.  Writes the canonical base url, room token, and pubkey bytes into the
/// given pointers.  base_url must be at least BASE_URL_MAX_LENGTH+1; room must be at least
/// ROOM_MAX_LENGTH+1; and pubkey must be (at least) 32 bytes.
///
/// Returns true if the url was parsed successfully, false if parsing failed (i.e. an invalid URL).
///
/// Declaration:
/// ```cpp
/// BOOL community_parse_full_url(
///     [in]    const char*     full_url,
///     [out]   char*           base_url,
///     [out]   char*           room_token,
///     [out]   unsigned char*  pubkey
/// );
/// ```
///
/// Inputs:
/// - `full_url` -- [in] Text of the url
/// - `base_url` -- [out] Text of the base url
/// - `room_token` -- [out] Binary of the the token
/// - `pubkey` -- [out] Binary of the pubkey
///
/// Outputs:
///
/// - `bool` -- Whether the function succeeded or not
LIBSESSION_EXPORT bool community_parse_full_url(
        const char* full_url, char* base_url, char* room_token, unsigned char* pubkey);

/// API: community/community_parse_partial_url
///
/// Similar to the above `community_parse_full_url`, but allows a URL to omit the pubkey.  If no
/// pubkey is found, `pubkey` is left unchanged and `has_pubkey` is set to false; otherwise `pubkey`
/// is written and `has_pubkey` is set to true.  `pubkey` may be set to NULL, in which case it is
/// never written.  `has_pubkey` may be NULL in which case it is not set (typically both pubkey
/// arguments would be null for cases where you don't care at all about the pubkey).
///
/// Declaration:
/// ```cpp
/// BOOL community_parse_partial_url(
///     [in]    const char*     full_url,
///     [out]   char*           base_url,
///     [out]   char*           room_token,
///     [out]   unsigned char*  pubkey,
///     [out]   bool*           has_pubkey
/// );
/// ```
///
/// Inputs:
/// - `full_url` -- [in] Text of the url
/// - `base_url` -- [out] Text of the url
/// - `room_token` -- [out] Binary of the the token
/// - `pubkey` -- [out] Binary of the pubkey
/// - `has_pubkey` -- [out] Will be true if the full url has a pubkey
///
/// Outputs:
/// - `bool` -- true if successful
LIBSESSION_EXPORT bool community_parse_partial_url(
        const char* full_url,
        char* base_url,
        char* room_token,
        unsigned char* pubkey,
        bool* has_pubkey);

/// API: community/community_make_full_url
///
/// Produces a standard full URL from a given base_url (c string), room token (c string), and pubkey
/// (fixed-length 32 byte buffer).  The full URL is written to `full_url`, which must be at least
/// COMMUNITY_FULL_URL_MAX_LENGTH in size.
///
/// Declaration:
/// ```cpp
/// VOID community_make_full_url(
///     [in]    const char*             base_url,
///     [in]    const char*             room_token,
///     [in]    const unsigned char*    pubkey,
///     [out]   char*                   full_url
/// );
/// ```
///
/// Inputs:
/// - `base_url` -- [in] Text of the url
/// - `room` -- [in] Text of the the token
/// - `pubkey` -- [in] Binary of the pubkey, 32 bytes
/// - `full_url` -- [out] Text of the url
LIBSESSION_EXPORT void community_make_full_url(
        const char* base_url, const char* room, const unsigned char* pubkey, char* full_url);

#ifdef __cplusplus
}
#endif
