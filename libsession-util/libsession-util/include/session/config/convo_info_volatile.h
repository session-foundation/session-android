#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "base.h"
#include "profile_pic.h"

typedef struct convo_info_volatile_1to1 {
    char session_id[67];  // in hex; 66 hex chars + null terminator.

    int64_t last_read;  // milliseconds since unix epoch
    bool unread;        // true if the conversation is explicitly marked unread
} convo_info_volatile_1to1;

typedef struct convo_info_volatile_community {
    char base_url[268];  // null-terminated (max length 267), normalized (i.e. always lower-case,
                         // only has port if non-default, has trailing / removed)
    char room[65];       // null-terminated (max length 64), normalized (always lower-case)
    unsigned char pubkey[32];  // 32 bytes (not terminated, can contain nulls)

    int64_t last_read;  // ms since unix epoch
    bool unread;        // true if marked unread
} convo_info_volatile_community;

typedef struct convo_info_volatile_group {
    char group_id[67];  // in hex; 66 hex chars + null terminator.  Begins with "03".
    int64_t last_read;  // ms since unix epoch
    bool unread;        // true if marked unread
} convo_info_volatile_group;

typedef struct convo_info_volatile_legacy_group {
    char group_id[67];  // in hex; 66 hex chars + null terminator.  Looks just like a Session ID,
                        // though isn't really one.

    int64_t last_read;  // ms since unix epoch
    bool unread;        // true if marked unread
} convo_info_volatile_legacy_group;

/// API: convo_info_volatile/convo_info_volatile_init
///
/// Constructs a conversations config object and sets a pointer to it in `conf`.
///
/// When done with the object the `config_object` must be destroyed by passing the pointer to
/// config_free() (in `session/config/base.h`).
///
/// Declaration:
/// ```cpp
/// INT convo_info_volatile_init(
///     [out]           config_object**     conf,
///     [in]            unsigned char*      ed25519_secretkey,
///     [in, optional]  unsigned char*      dump,
///     [in, optional]  size_t              dumplen,
///     [out]           char*               error
/// );
/// ```
///
/// Inputs:
/// - `ed25519_secretkey` -- [out] must be the 32-byte secret key seed value.  (You can also pass
/// the pointer to the beginning of the 64-byte value libsodium calls the "secret key" as the first
/// 32 bytes of that are the seed).  This field cannot be null.
///
/// - `dump` -- [in, optional] if non-NULL this restores the state from the dumped byte string
/// produced by a past instantiation's call to `dump()`.  To construct a new, empty object this
/// should be NULL.
///
/// - `dumplen` -- [in, optional] the length of `dump` when restoring from a dump, or 0 when `dump`
/// is NULL.
///
/// - `error` -- [out] the pointer to a buffer in which we will write an error string if an error
/// occurs; error messages are discarded if this is given as NULL.  If non-NULL this must be a
/// buffer of at least 256 bytes.
///
/// Outputs:
/// - `int` --Returns 0 on success; returns a non-zero error code and write the exception message as
/// a C-string into `error` (if not NULL) on failure.
LIBSESSION_EXPORT int convo_info_volatile_init(
        config_object** conf,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_1to1
///
/// Fills `convo` with the conversation info given a session ID (specified as a null-terminated hex
/// string), if the conversation exists, and returns true.  If the conversation does not exist then
/// `convo` is left unchanged and false is returned.  If an error occurs, false is returned and
/// `conf->last_error` will be set to non-NULL containing the error string (if no error occurs, such
/// as in the case where the conversation merely doesn't exist, `last_error` will be set to NULL).
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_1to1(
///     [in]    config_object*              conf,
///     [out]   convo_info_volatile_1to1*   convo,
///     [in]    const char*                 session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to conversation info
/// - `session_id` -- [in] Null terminated hex string of the session_id
///
/// Outputs:
/// - `bool` - Returns true if the conversation exists
LIBSESSION_EXPORT bool convo_info_volatile_get_1to1(
        config_object* conf,
        convo_info_volatile_1to1* convo,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_or_construct_1to1
///
/// Same as the above convo_info_volatile_get_1to1 except that when the conversation does not exist,
/// this sets all the convo fields to defaults and loads it with the given session_id.
///
/// Returns true as long as it is given a valid session_id.  A false return is considered an error,
/// and means the session_id was not a valid session_id.  In such a case `conf->last_error` will be
/// set to an error string.
///
/// This is the method that should usually be used to create or update a conversation, followed by
/// setting fields in the convo, and then giving it to convo_info_volatile_set().
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_or_construct_1to1(
///     [in]    config_object*              conf,
///     [out]   convo_info_volatile_1to1*   convo,
///     [in]    const char*                 session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to conversation info
/// - `session_id` -- [in] Null terminated hex string of the session_id
///
/// Outputs:
/// - `bool` - Returns true if the conversation exists
LIBSESSION_EXPORT bool convo_info_volatile_get_or_construct_1to1(
        config_object* conf,
        convo_info_volatile_1to1* convo,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_community
///
/// community versions of the 1-to-1 functions:
///
/// Gets a community convo info.  `base_url` and `room` are null-terminated c strings.
/// base_url and room will always be lower-cased (if not already).
///
/// Error handling works the same as the 1-to-1 version.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_community(
///     [in]    config_object*                  conf,
///     [out]   convo_info_volatile_community*  comm,
///     [in]    const char*                     base_url,
///     [in]    const char*                     room
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `comm` -- [out] Pointer to community info structure
/// - `base_url` -- [in] Null terminated string
/// - `room` -- [in] Null terminated string
///
/// Outputs:
/// - `bool` - Returns true if the community exists
LIBSESSION_EXPORT bool convo_info_volatile_get_community(
        config_object* conf,
        convo_info_volatile_community* comm,
        const char* base_url,
        const char* room) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_or_construct_community
///
/// Gets a community convo info, but if the community does not exist will set all the fileds to
/// defaults and load it. `base_url` and `room` are null-terminated c strings; pubkey is 32 bytes.
/// base_url and room will always be lower-cased (if not already).
///
/// This is similar to get_community, except that it also takes the pubkey; the community is
/// looked up by the url & room; if not found, it is constructed using room, url, and pubkey; if
/// it *is* found, then it will always have the *input* pubkey, not the stored pubkey
/// (effectively the provided pubkey replaces the stored one in the returned object; this is not
/// applied to storage, however, unless/until the instance is given to `set()`).
///
/// Note, however, that when modifying an object like this the update is *only* applied to the
/// returned object; like other fields, it is not updated in the internal state unless/until
/// that community instance is passed to `set()`.
///
/// Error handling works the same as the 1-to-1 version.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_or_construct_community(
///     [in]    config_object*                  conf,
///     [out]   convo_info_volatile_community*  comm,
///     [in]    const char*                     base_url,
///     [in]    const char*                     room,
///     [in]    unsigned const char*            pubkey
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to community info structure
/// - `base_url` -- [in] Null terminated string
/// - `room` -- [in] Null terminated string
/// - `pubkey` -- [in] 32 byte binary data of the pubkey
///
/// Outputs:
/// - `bool` - Returns true if the call succeeds
LIBSESSION_EXPORT bool convo_info_volatile_get_or_construct_community(
        config_object* conf,
        convo_info_volatile_community* convo,
        const char* base_url,
        const char* room,
        unsigned const char* pubkey) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_group
///
/// Fills `convo` with the conversation info given a group ID (specified as a null-terminated
/// hex string), if the conversation exists, and returns true.  If the conversation does not exist
/// then `convo` is left unchanged and false is returned.  On error, false is returned and the error
/// is set in conf->last_error (on non-error, last_error is cleared).
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_group(
///     [in]    config_object*               conf,
///     [out]   convo_info_volatile_group*   convo,
///     [in]    const char*                  id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to group
/// - `id` -- [in] Null terminated hex string (66 chars, beginning with 03) specifying the ID of the
///   group
///
/// Outputs:
/// - `bool` - Returns true if the group exists
LIBSESSION_EXPORT bool convo_info_volatile_get_group(
        config_object* conf,
        convo_info_volatile_group* convo,
        const char* id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_or_construct_group
///
/// Same as the above except that when the conversation does not exist, this sets all the convo
/// fields to defaults and loads it with the given id.
///
/// Returns true as long as it is given a valid group id (i.e. 66 hex chars beginning with "03").  A
/// false return is considered an error, and means the id was not a valid session id; an error
/// string will be set in `conf->last_error`.
///
/// This is the method that should usually be used to create or update a conversation, followed by
/// setting fields in the convo, and then giving it to convo_info_volatile_set().
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_or_construct_group(
///     [in]    config_object*               conf,
///     [out]   convo_info_volatile_group*   convo,
///     [in]    const char*                  id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to group
/// - `id` -- [in] Null terminated hex string specifying the ID of the group
///
/// Outputs:
/// - `bool` - Returns true if the call succeeds
LIBSESSION_EXPORT bool convo_info_volatile_get_or_construct_group(
        config_object* conf,
        convo_info_volatile_group* convo,
        const char* id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_legacy_group
///
/// Fills `convo` with the conversation info given a legacy group ID (specified as a null-terminated
/// hex string), if the conversation exists, and returns true.  If the conversation does not exist
/// then `convo` is left unchanged and false is returned.  On error, false is returned and the error
/// is set in conf->last_error (on non-error, last_error is cleared).
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_legacy_group(
///     [in]    config_object*                      conf,
///     [out]   convo_info_volatile_legacy_group*   convo,
///     [in]    const char*                         id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to legacy group
/// - `id` -- [in] Null terminated hex string specifying the ID of the legacy group
///
/// Outputs:
/// - `bool` - Returns true if the legacy group exists
LIBSESSION_EXPORT bool convo_info_volatile_get_legacy_group(
        config_object* conf,
        convo_info_volatile_legacy_group* convo,
        const char* id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_get_or_construct_legacy_group
///
/// Same as the above except that when the conversation does not exist, this sets all the convo
/// fields to defaults and loads it with the given id.
///
/// Returns true as long as it is given a valid legacy group id (i.e. same format as a session id).
/// A false return is considered an error, and means the id was not a valid session id; an error
/// string will be set in `conf->last_error`.
///
/// This is the method that should usually be used to create or update a conversation, followed by
/// setting fields in the convo, and then giving it to convo_info_volatile_set().
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_get_or_construct_legacy_group(
///     [in]    config_object*                      conf,
///     [out]   convo_info_volatile_legacy_group*   convo,
///     [in]    const char*                         id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [out] Pointer to legacy group
/// - `id` -- [in] Null terminated hex string specifying the ID of the legacy group
///
/// Outputs:
/// - `bool` - Returns true if the call succeeds
LIBSESSION_EXPORT bool convo_info_volatile_get_or_construct_legacy_group(
        config_object* conf,
        convo_info_volatile_legacy_group* convo,
        const char* id) LIBSESSION_WARN_UNUSED;

/// API: convo_info_volatile/convo_info_volatile_set_1to1
///
/// Adds or updates a conversation from the given convo info
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_set_1to1(
///     [in]    config_object*                      conf,
///     [in]    const convo_info_volatile_1to1*     convo
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [in] Pointer to conversation info structure
LIBSESSION_EXPORT void convo_info_volatile_set_1to1(
        config_object* conf, const convo_info_volatile_1to1* convo);

/// API: convo_info_volatile/convo_info_volatile_set_community
///
/// Adds or updates a community from the given convo info
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_set_community(
///     [in]    config_object*                          conf,
///     [in]    const convo_info_volatile_community*    convo
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [in] Pointer to community info structure
LIBSESSION_EXPORT void convo_info_volatile_set_community(
        config_object* conf, const convo_info_volatile_community* convo);

/// API: convo_info_volatile/convo_info_volatile_set_group
///
/// Adds or updates a group from the given convo info
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_set_group(
///     [in]    config_object*                       conf,
///     [in]    const convo_info_volatile_group*     convo
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [in] Pointer to group info structure
LIBSESSION_EXPORT void convo_info_volatile_set_group(
        config_object* conf, const convo_info_volatile_group* convo);

/// API: convo_info_volatile/convo_info_volatile_set_legacy_group
///
/// Adds or updates a legacy group from the given convo info
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_set_legacy_group(
///     [in]    config_object*                              conf,
///     [in]    const convo_info_volatile_legacy_group*     convo
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [in] Pointer to legacy group info structure
LIBSESSION_EXPORT void convo_info_volatile_set_legacy_group(
        config_object* conf, const convo_info_volatile_legacy_group* convo);

/// API: convo_info_volatile/convo_info_volatile_erase_1to1
///
/// Erases a conversation from the conversation list.  Returns true if the conversation was found
/// and removed, false if the conversation was not present.  You must not call this during
/// iteration; see details below.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_erase_1to1(
///     [in]    config_object*  conf,
///     [in]    const char*     session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `convo` -- [in] Pointer to community info structure
///
/// Outputs:
/// - `bool` - Returns true if conversation was found and removed
LIBSESSION_EXPORT bool convo_info_volatile_erase_1to1(config_object* conf, const char* session_id);

/// API: convo_info_volatile/convo_info_volatile_erase_community
///
/// Erases a community.  Returns true if the community was found
/// and removed, false if the community was not present.  You must not call this during
/// iteration.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_erase_community(
///     [in]    config_object*  conf,
///     [in]    const char*     base_url,
///     [in]    const char*     room
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `base_url` -- [in] Null terminated string
/// - `room` -- [in] Null terminated string
///
/// Outputs:
/// - `bool` - Returns true if community was found and removed
LIBSESSION_EXPORT bool convo_info_volatile_erase_community(
        config_object* conf, const char* base_url, const char* room);

/// API: convo_info_volatile/convo_info_volatile_erase_group
///
/// Erases a group.  Returns true if the group was found and removed, false if the group was not
/// present.  You must not call this during iteration.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_erase_group(
///     [in]    config_object*  conf,
///     [in]    const char*     group_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `group_id` -- [in] Null terminated hex string
///
/// Outputs:
/// - `bool` - Returns true if group was found and removed
LIBSESSION_EXPORT bool convo_info_volatile_erase_group(config_object* conf, const char* group_id);

/// API: convo_info_volatile/convo_info_volatile_erase_legacy_group
///
/// Erases a legacy group.  Returns true if the group was found
/// and removed, false if the group was not present.  You must not call this during
/// iteration.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_erase_legacy_group(
///     [in]    config_object*  conf,
///     [in]    const char*     group_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `group_id` -- [in] Null terminated hex string
///
/// Outputs:
/// - `bool` - Returns true if group was found and removed
LIBSESSION_EXPORT bool convo_info_volatile_erase_legacy_group(
        config_object* conf, const char* group_id);

/// API: convo_info_volatile/convo_info_volatile_size
///
/// Returns the number of conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T convo_info_volatile_size(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of conversations
LIBSESSION_EXPORT size_t convo_info_volatile_size(const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_size_1to1
///
/// Returns the number of conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T convo_info_volatile_size_1to1(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of conversations
LIBSESSION_EXPORT size_t convo_info_volatile_size_1to1(const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_size_communities
///
/// Returns the number of communitites.
///
/// Declaration:
/// ```cpp
/// SIZE_T convo_info_volatile_size_communities(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of communities
LIBSESSION_EXPORT size_t convo_info_volatile_size_communities(const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_size_groups
///
/// Returns the number of groups.
///
/// Declaration:
/// ```cpp
/// SIZE_T convo_info_volatile_size_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of groups
LIBSESSION_EXPORT size_t convo_info_volatile_size_groups(const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_size_legacy_groups
///
/// Returns the number of legacy groups.
///
/// Declaration:
/// ```cpp
/// SIZE_T convo_info_volatile_size_legacy_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of legacy groups
LIBSESSION_EXPORT size_t convo_info_volatile_size_legacy_groups(const config_object* conf);

typedef struct convo_info_volatile_iterator convo_info_volatile_iterator;

/// API: convo_info_volatile/convo_info_volatile_iterator_new
///
/// Starts a new iterator that iterates over all conversations.
///
/// Functions for iterating through the entire conversation list.  Intended use is:
/// ```cpp
///     convo_info_volatile_1to1 c1;
///     convo_info_volatile_community c2;
///     convo_info_volatile_group c3;
///     convo_info_volatile_legacy_group c4;
///     convo_info_volatile_iterator *it = convo_info_volatile_iterator_new(my_convos);
///     for (; !convo_info_volatile_iterator_done(it); convo_info_volatile_iterator_advance(it)) {
///         if (convo_info_volatile_it_is_1to1(it, &c1)) {
///             // use c1.whatever
///         } else if (convo_info_volatile_it_is_community(it, &c2)) {
///             // use c2.whatever
///         } else if (convo_info_volatile_it_is_group(it, &c3)) {
///             // use c3.whatever
///         } else if (convo_info_volatile_it_is_legacy_group(it, &c4)) {
///             // use c4.whatever
///         }
///     }
///     convo_info_volatile_iterator_free(it);
/// ```
///
/// It is NOT permitted to add/modify/remove records while iterating; instead you must use two
/// loops: a first one to identify changes, and a second to apply them.
///
/// Declaration:
/// ```cpp
/// CONVO_INFO_VOLATILE_ITERATOR* convo_info_volatile_iterator_new(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `convo_info_volatile_iterator*` -- Iterator
LIBSESSION_EXPORT convo_info_volatile_iterator* convo_info_volatile_iterator_new(
        const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_iterator_new_1to1
///
/// The same as `convo_info_volatile_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `convo_info_volatile_it_is_1to1` (or the alternatives) to
/// load the data in each pass of the loop.  (You can, however, safely ignore the bool return value
/// of the `it_is_whatever` function: it will always be true for the particular type being iterated
/// over).
///
/// Declaration:
/// ```cpp
/// CONVO_INFO_VOLATILE_ITERATOR* convo_info_volatile_iterator_new_1to1(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `convo_info_volatile_iterator*` -- Iterator
LIBSESSION_EXPORT convo_info_volatile_iterator* convo_info_volatile_iterator_new_1to1(
        const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_iterator_new_communities
///
/// The same as `convo_info_volatile_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `convo_info_volatile_it_is_1to1` (or the alternatives) to
/// load the data in each pass of the loop.  (You can, however, safely ignore the bool return value
/// of the `it_is_whatever` function: it will always be true for the particular type being iterated
/// over).
///
/// Declaration:
/// ```cpp
/// CONVO_INFO_VOLATILE_ITERATOR* convo_info_volatile_iterator_new_communities(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `convo_info_volatile_iterator*` -- Iterator
LIBSESSION_EXPORT convo_info_volatile_iterator* convo_info_volatile_iterator_new_communities(
        const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_iterator_new_groups
///
/// The same as `convo_info_volatile_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `convo_info_volatile_it_is_group` (or the alternatives) to
/// load the data in each pass of the loop.  (You can, however, safely ignore the bool return value
/// of the `it_is_whatever` function: it will always be true for the particular type being iterated
/// over).
///
/// Declaration:
/// ```cpp
/// CONVO_INFO_VOLATILE_ITERATOR* convo_info_volatile_iterator_new_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `convo_info_volatile_iterator*` -- Iterator
LIBSESSION_EXPORT convo_info_volatile_iterator* convo_info_volatile_iterator_new_groups(
        const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_iterator_new_legacy_groups
///
/// The same as `convo_info_volatile_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `convo_info_volatile_it_is_1to1` (or the alternatives) to
/// load the data in each pass of the loop.  (You can, however, safely ignore the bool return value
/// of the `it_is_whatever` function: it will always be true for the particular type being iterated
/// over).
///
/// Declaration:
/// ```cpp
/// CONVO_INFO_VOLATILE_ITERATOR* convo_info_volatile_iterator_new_legacy_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `convo_info_volatile_iterator*` -- Iterator
LIBSESSION_EXPORT convo_info_volatile_iterator* convo_info_volatile_iterator_new_legacy_groups(
        const config_object* conf);

/// API: convo_info_volatile/convo_info_volatile_iterator_free
///
/// Frees an iterator once no longer needed.
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_iterator_free(
///     [in]    convo_info_volatile_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
LIBSESSION_EXPORT void convo_info_volatile_iterator_free(convo_info_volatile_iterator* it);

/// API: convo_info_volatile/convo_info_volatile_iterator_done
///
/// Returns true if iteration has reached the end.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_iterator_done(
///     [in]    convo_info_volatile_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
///
/// Outputs:
/// - `bool` -- True if iteration has reached the end
LIBSESSION_EXPORT bool convo_info_volatile_iterator_done(convo_info_volatile_iterator* it);

/// API: convo_info_volatile/convo_info_volatile_iterator_advance
///
/// Advances the iterator.
///
/// Declaration:
/// ```cpp
/// VOID convo_info_volatile_iterator_advance(
///     [in]    convo_info_volatile_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
LIBSESSION_EXPORT void convo_info_volatile_iterator_advance(convo_info_volatile_iterator* it);

/// API: convo_info_volatile/convo_info_volatile_it_is_1to1
///
/// If the current iterator record is a 1-to-1 conversation this sets the details into `c` and
/// returns true.  Otherwise it returns false.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_it_is_1to1(
///     [in]    convo_info_volatile_iterator*   it,
///     [out]   convo_info_volatile_1to1*       c
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
/// - `c` -- [out] Pointer to the convo_info_volatile, will be populated if true
///
/// Outputs:
/// - `bool` -- True if the record is a 1-to-1 conversation
LIBSESSION_EXPORT bool convo_info_volatile_it_is_1to1(
        convo_info_volatile_iterator* it, convo_info_volatile_1to1* c);

/// API: convo_info_volatile/convo_info_volatile_it_is_community
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_it_is_community(
///     [in]    convo_info_volatile_iterator*   it,
///     [out]   convo_info_volatile_community*  c
/// );
/// ```
///
/// If the current iterator record is a community conversation this sets the details into `c` and
/// returns true.  Otherwise it returns false.
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
/// - `c` -- [out] Pointer to the convo_info_volatile, will be populated if true
///
/// Outputs:
/// - `bool` -- True if the record is a community conversation
LIBSESSION_EXPORT bool convo_info_volatile_it_is_community(
        convo_info_volatile_iterator* it, convo_info_volatile_community* c);

/// API: convo_info_volatile/convo_info_volatile_it_is_group
///
/// If the current iterator record is a group conversation this sets the details into `g` and
/// returns true.  Otherwise it returns false.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_it_is_group(
///     [in]    convo_info_volatile_iterator*   it,
///     [out]   convo_info_volatile_group*      g
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
/// - `c` -- [out] Pointer to the convo_info_volatile, will be populated if true
///
/// Outputs:
/// - `bool` -- True if the record is a group conversation
LIBSESSION_EXPORT bool convo_info_volatile_it_is_group(
        convo_info_volatile_iterator* it, convo_info_volatile_group* c);

/// API: convo_info_volatile/convo_info_volatile_it_is_legacy_group
///
/// If the current iterator record is a legacy group conversation this sets the details into `c` and
/// returns true.  Otherwise it returns false.
///
/// Declaration:
/// ```cpp
/// BOOL convo_info_volatile_it_is_legacy_group(
///     [in]    convo_info_volatile_iterator*     it,
///     [out]   convo_info_volatile_legacy_group* c
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The convo_info_volatile_iterator
/// - `c` -- [out] Pointer to the convo_info_volatile, will be populated if true
///
/// Outputs:
/// - `bool` -- True if the record is a legacy group conversation
LIBSESSION_EXPORT bool convo_info_volatile_it_is_legacy_group(
        convo_info_volatile_iterator* it, convo_info_volatile_legacy_group* c);

#ifdef __cplusplus
}  // extern "C"
#endif
