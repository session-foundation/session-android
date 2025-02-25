#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "../base.h"
#include "../profile_pic.h"
#include "../util.h"

LIBSESSION_EXPORT extern const size_t GROUP_INFO_NAME_MAX_LENGTH;
LIBSESSION_EXPORT extern const size_t GROUP_INFO_DESCRIPTION_MAX_LENGTH;

/// API: groups/groups_info_init
///
/// Constructs a group info config object and sets a pointer to it in `conf`.
///
/// When done with the object the `config_object` must be destroyed by passing the pointer to
/// config_free() (in `session/config/base.h`).
///
/// Inputs:
/// - `conf` -- [out] Pointer to the config object
/// - `ed25519_pubkey` -- [in] 32-byte pointer to the group's public key
/// - `ed25519_secretkey` -- [in] optional 64-byte pointer to the group's secret key
///   (libsodium-style 64 byte value).  Pass as NULL for a non-admin member.
/// - `dump` -- [in] if non-NULL this restores the state from the dumped byte string produced by a
/// past instantiation's call to `dump()`.  To construct a new, empty object this should be NULL.
/// - `dumplen` -- [in] the length of `dump` when restoring from a dump, or 0 when `dump` is NULL.
/// - `error` -- [out] the pointer to a buffer in which we will write an error string if an error
/// occurs; error messages are discarded if this is given as NULL.  If non-NULL this must be a
/// buffer of at least 256 bytes.
///
/// Outputs:
/// - `int` -- Returns 0 on success; returns a non-zero error code and write the exception message
/// as a C-string into `error` (if not NULL) on failure.
LIBSESSION_EXPORT int groups_info_init(
        config_object** conf,
        const unsigned char* ed25519_pubkey,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) LIBSESSION_WARN_UNUSED;

/// API: groups_info/groups_info_get_name
///
/// Returns a pointer to the currently-set name (null-terminated), or NULL if there is no name at
/// all.  Should be copied right away as the pointer may not remain valid beyond other API calls.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `char*` -- Pointer to the currently-set name as a null-terminated string, or NULL if there is
/// no name
LIBSESSION_EXPORT const char* groups_info_get_name(const config_object* conf);

/// API: groups_info/groups_info_set_name
///
/// Sets the group's name to the null-terminated C string.  Returns 0 on success, non-zero on
/// error (and sets the config_object's error string).
///
/// If the given name is longer than GROUP_INFO_NAME_MAX_LENGTH (100) bytes then it will be
/// truncated.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `name` -- [in] Pointer to the name as a null-terminated C string
///
/// Outputs:
/// - `int` -- Returns 0 on success, non-zero on error
LIBSESSION_EXPORT int groups_info_set_name(config_object* conf, const char* name);

/// API: groups_info/groups_info_get_description
///
/// Returns a pointer to the currently-set description (null-terminated), or NULL if there is no
/// description at all.  Should be copied right away as the pointer may not remain valid beyond
/// other API calls.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `char*` -- Pointer to the currently-set description as a null-terminated string, or NULL if
///   there is no description
LIBSESSION_EXPORT const char* groups_info_get_description(const config_object* conf);

/// API: groups_info/groups_info_set_description
///
/// Sets the group's description to the null-terminated C string.  Returns 0 on success, non-zero on
/// error (and sets the config_object's error string).
///
/// If the given description is longer than GROUP_INFO_DESCRIPTION_MAX_LENGTH (2000) bytes then it
/// will be truncated.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `description` -- [in] Pointer to the description as a null-terminated C string
///
/// Outputs:
/// - `int` -- Returns 0 on success, non-zero on error
LIBSESSION_EXPORT int groups_info_set_description(config_object* conf, const char* description);

/// API: groups_info/groups_info_get_pic
///
/// Obtains the current profile pic.  The pointers in the returned struct will be NULL if a profile
/// pic is not currently set, and otherwise should be copied right away (they will not be valid
/// beyond other API calls on this config object).
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `user_profile_pic` -- Pointer to the currently-set profile pic (despite the "user_profile" in
///   the struct name, this is the group's profile pic).
LIBSESSION_EXPORT user_profile_pic groups_info_get_pic(const config_object* conf);

/// API: groups_info/groups_info_set_pic
///
/// Sets a user profile
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `pic` -- [in] Pointer to the pic
///
/// Outputs:
/// - `int` -- Returns 0 on success, non-zero on error
LIBSESSION_EXPORT int groups_info_set_pic(config_object* conf, user_profile_pic pic);

/// API: groups_info/groups_info_get_expiry_timer
///
/// Gets the group's message expiry timer (seconds).  Returns 0 if not set.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int` -- Returns the expiry timer in seconds. Returns 0 if not set
LIBSESSION_EXPORT int groups_info_get_expiry_timer(const config_object* conf);

/// API: groups_info/groups_info_set_expiry_timer
///
/// Sets the group's message expiry timer (seconds).  Setting 0 (or negative) will clear the current
/// timer.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `expiry` -- [in] Integer of the expiry timer in seconds
LIBSESSION_EXPORT void groups_info_set_expiry_timer(config_object* conf, int expiry);

/// API: groups_info/groups_info_get_created
///
/// Returns the timestamp (unix time, in seconds) when the group was created.  Returns 0 if unset.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int64_t` -- Unix timestamp when the group was created (if set by an admin).
LIBSESSION_EXPORT int64_t groups_info_get_created(const config_object* conf);

/// API: groups_info/groups_info_set_created
///
/// Sets the creation time (unix timestamp, in seconds) when the group was created.  Setting 0
/// clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_EXPORT void groups_info_set_created(config_object* conf, int64_t ts);

/// API: groups_info/groups_info_get_delete_before
///
/// Returns the delete-before timestamp (unix time, in seconds); clients should delete all messages
/// from the group with timestamps earlier than this value, if set.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int64_t` -- Unix timestamp before which messages should be deleted.  Returns 0 if not set.
LIBSESSION_EXPORT int64_t groups_info_get_delete_before(const config_object* conf);

/// API: groups_info/groups_info_set_delete_before
///
/// Sets the delete-before time (unix timestamp, in seconds) before which messages should be
/// deleted.  Setting 0 clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_EXPORT void groups_info_set_delete_before(config_object* conf, int64_t ts);

/// API: groups_info/groups_info_get_attach_delete_before
///
/// Returns the delete-before timestamp (unix time, in seconds) for attachments; clients should drop
/// all attachments from messages from the group with timestamps earlier than this value, if set.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int64_t` -- Unix timestamp before which messages should be deleted.  Returns 0 if not set.
LIBSESSION_EXPORT int64_t groups_info_get_attach_delete_before(const config_object* conf);

/// API: groups_info/groups_info_set_attach_delete_before
///
/// Sets the delete-before time (unix timestamp, in seconds) for attachments; attachments should be
/// dropped from messages older than this value.  Setting 0 clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_EXPORT void groups_info_set_attach_delete_before(config_object* conf, int64_t ts);

/// API: groups_info/groups_info_is_destroyed(const config_object* conf);
///
/// Returns true if this group has been marked destroyed by an admin, which indicates to a receiving
/// client that they should destroy it locally.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `true` if the group has been nuked, `false` otherwise.
LIBSESSION_EXPORT bool groups_info_is_destroyed(const config_object* conf);

/// API: groups_info/groups_info_destroy_group(const config_object* conf);
///
/// Nukes a group from orbit.  This is permanent (i.e. there is no removing this setting once set).
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
LIBSESSION_EXPORT void groups_info_destroy_group(config_object* conf);

#ifdef __cplusplus
}  // extern "C"
#endif
