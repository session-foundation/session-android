#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "base.h"
#include "notify.h"
#include "util.h"

// Maximum length of a group name, in bytes
LIBSESSION_EXPORT extern const size_t GROUP_NAME_MAX_LENGTH;

/// Struct holding legacy group info; this struct owns allocated memory and *must* be freed via
/// either `ugroups_legacy_group_free()` or `user_groups_set_free_legacy_group()` when finished with
/// it.

typedef struct ugroups_legacy_group_info {
    char session_id[67];  // in hex; 66 hex chars + null terminator.

    char name[101];  // Null-terminated C string (human-readable).  Max length is 100 (plus 1 for
                     // null).  Will always be set (even if an empty string).

    bool have_enc_keys;            // Will be true if we have an encryption keypair, false if not.
    unsigned char enc_pubkey[32];  // If `have_enc_keys`, this is the 32-byte pubkey (no NULL
                                   // terminator).
    unsigned char enc_seckey[32];  // If `have_enc_keys`, this is the 32-byte secret key (no NULL
                                   // terminator).

    int64_t disappearing_timer;  // Seconds. 0 == disabled.
    int priority;       // pinned conversation priority; 0 = unpinned, negative = hidden, positive =
                        // pinned (with higher meaning pinned higher).
    int64_t joined_at;  // unix timestamp when joined (or re-joined)
    CONVO_NOTIFY_MODE notifications;  // When the user wants notifications
    int64_t mute_until;  // Mute notifications until this timestamp (overrides `notifications`
                         // setting until the timestamp)

    bool invited;  // True if this is in the invite-but-not-accepted state.

    // For members use the ugroups_legacy_group_members and associated calls.

    void* _internal;  // Internal storage, do not touch.
} ugroups_legacy_group_info;

/// Struct holding (non-legacy) group info; this struct owns allocated memory and *must* be freed
/// via either `ugroups_group_free()` or `ugroups_set_free_group()` when finished with it.
typedef struct ugroups_group_info {
    char id[67];  // in hex; 66 hex chars + null terminator

    char name[101];  // Null-terminated C string (human-readable).  Max length is 100 (plus 1 for
                     // null).  Will always be set (even if an empty string).

    bool have_secretkey;          // Will be true if the `secretkey` is populated
    unsigned char secretkey[64];  // If `have_secretkey` is set then this is the libsodium-style
                                  // "secret key" for the group (i.e. 32 byte seed + 32 byte pubkey)
    bool have_auth_data;          // Will be true if the `auth_data` is populated
    unsigned char auth_data[100];  // If `have_auth_data` is set then this is the authentication
                                   // signing value that can be used to produce signature values to
                                   // access the swarm.

    int priority;       // pinned conversation priority; 0 = unpinned, negative = hidden, positive =
                        // pinned (with higher meaning pinned higher).
    int64_t joined_at;  // unix timestamp when joined (or re-joined)
    CONVO_NOTIFY_MODE notifications;  // When the user wants notifications
    int64_t mute_until;  // Mute notifications until this timestamp (overrides `notifications`
                         // setting until the timestamp)

    bool invited;  // True if this is in the invite-but-not-accepted state.

    int removed_status;  // Tracks why we were removed from the group. Values are 0:NOT_REMOVED,
                         // 1:KICKED_FROM_GROUP or 2:GROUP_DESTROYED
} ugroups_group_info;

typedef struct ugroups_community_info {
    char base_url[268];  // null-terminated (max length 267), normalized (i.e. always lower-case,
                         // only has port if non-default, has trailing / removed)
    char room[65];       // null-terminated (max length 64); this is case-preserving (i.e. can be
                         // "SomeRoom" instead of "someroom".  Note this is different from volatile
                         // info (that one is always forced lower-cased).
    unsigned char pubkey[32];  // 32 bytes (not terminated, can contain nulls)

    int priority;       // pinned conversation priority; 0 = unpinned, negative = hidden, positive =
                        // pinned (with higher meaning pinned higher).
    int64_t joined_at;  // unix timestamp when joined (or re-joined)
    CONVO_NOTIFY_MODE notifications;  // When the user wants notifications
    int64_t mute_until;  // Mute notifications until this timestamp (overrides `notifications`
                         // setting until the timestamp)

    bool invited;  // True if this is in the invite-but-not-accepted state.

} ugroups_community_info;

/// API: user_groups/user_groups_init
///
/// Initializes the user groups object
///
/// Declaration:
/// ```cpp
/// INT user_groups_init(
///     [out]           config_object**     conf,
///     [in]            unsigned char*      ed25519_secretkey,
///     [in, optional]  unsigned char*      dump,
///     [in, optional]  size_t              dumplen,
///     [out]           char*               error
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] pointer to config_object object
/// - `ed25519_secretkey` -- [in] pointer to secret key
/// - `dump` -- [in, optional] text of dump
/// - `dumplen` -- [in, optional] size of the text passed in as dump
/// - `error` -- [out] of the error if failed
///
/// Outputs:
/// - `int` -- Whether the function succeeded or not
LIBSESSION_EXPORT int user_groups_init(
        config_object** conf,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) LIBSESSION_WARN_UNUSED;

/// API: user_groups/user_groups_get_group
///
/// Gets (non-legacy) group info into `group`, if the group was found.  `group_id` is a
/// null-terminated C string containing the 66 character group id in hex (beginning with "03").
///
/// Inputs:
/// `conf` -- pointer to the group config object
/// `group` -- [out] `ugroups_group_info` struct into which to store the group info.
/// `group_id` -- C string containing the hex group id (starting with "03")
///
/// Outputs:
/// Returns `true` and populates `group` if the group was found; returns false otherwise.
LIBSESSION_EXPORT bool user_groups_get_group(
        config_object* conf, ugroups_group_info* group, const char* group_id);

/// API: user_groups/user_groups_get_or_construct_group
///
/// Gets (non-legacy) group info into `group`, if the group was found.  Otherwise initialize `group`
/// to default values (and set its `.id` appropriately).
///
/// Inputs:
/// `conf` -- pointer to the group config object
/// `group` -- [out] `ugroups_group_info` struct into which to store the group info.
/// `group_id` -- C string containing the hex group id (starting with "03")
///
/// Outputs:
/// Returns `true` on success, `false` upon error (such as when given an invalid group id).
LIBSESSION_EXPORT bool user_groups_get_or_construct_group(
        config_object* conf, ugroups_group_info* group, const char* group_id);

/// API: user_groups/user_groups_get_community
///
/// Gets community conversation info into `comm`, if the community info was found. `base_url` and
/// `room` are null-terminated c strings.  base_url will be normalized/lower-cased; room is
/// case-insensitive for the lookup: note that this may well return a community info with a
/// different room capitalization than the one provided to the call.
///
/// Returns true if the community was found and `comm` populated; false otherwise.  A false return
/// can either be because it didn't exist (`conf->last_error` will be NULL) or because of some error
/// (`last_error` will be set to an error string).
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_get_community(
///     [in]    config_object*              conf,
///     [out]   ugroups_community_info*     comm,
///     [in]    const char*                 base_url,
///     [in]    const char*                 room
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] pointer to config_object object
/// - `comm` -- [out] pointer to ugroups_community_info object
/// - `base_url` -- [in] text of the url
/// - `room` -- [in] text of the room
///
/// Outputs:
/// - `bool` -- Whether the function succeeded or not
LIBSESSION_EXPORT bool user_groups_get_community(
        config_object* conf, ugroups_community_info* comm, const char* base_url, const char* room)
        LIBSESSION_WARN_UNUSED;

/// API: user_groups/user_groups_get_or_construct_community
///
/// Like the above, but if the community was not found, this constructs one that can be inserted.
/// `base_url` will be normalized in the returned object.  `room` is a case-insensitive lookup key
/// for the room token.  Note that it has subtle handling w.r.t its case: if an existing room is
/// found, you get back a record with the found case (which could differ in case from what you
/// provided).  If you want to override to what you provided regardless of what is there you should
/// immediately set the name of the returned object to the case you prefer.  If a *new* record is
/// constructed, however, it will match the room token case as given here.
///
/// Note that this is all different from convo_info_volatile, which always forces the room token to
/// lower-case (because it does not preserve the case).
///
/// Returns false (and sets `conf->last_error`) on error.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_get_or_construct_community(
///     [in]    config_object*              conf,
///     [out]   ugroups_community_info*     comm,
///     [in]    const char*                 base_url,
///     [in]    const char*                 room,
///     [in]    unsigned const char*        pubkey
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] pointer to config_object object
/// - `comm` -- [out] pointer to ugroups_community_info object
/// - `base_url` -- [in] text of the url
/// - `room` -- [in] text of the room
/// - `pubkey` -- [in] binary of pubkey
///
/// Outputs:
/// - `bool` -- Whether the function succeeded or not
LIBSESSION_EXPORT bool user_groups_get_or_construct_community(
        config_object* conf,
        ugroups_community_info* comm,
        const char* base_url,
        const char* room,
        unsigned const char* pubkey) LIBSESSION_WARN_UNUSED;

/// API: user_groups/user_groups_get_legacy_group
///
/// Returns a ugroups_legacy_group_info pointer containing the conversation info for a given legacy
/// group ID (specified as a null-terminated hex string), if the conversation exists.  If the
/// conversation does not exist, returns NULL.  Sets conf->last_error on error.
///
/// The returned pointer *must* be freed either by calling `ugroups_legacy_group_free()` when done
/// with it, or by passing it to `user_groups_set_free_legacy_group()`.
///
/// Declaration:
/// ```cpp
/// UGROUPS_LEGACY_GROUP_INFO* user_groups_get_legacy_group(
///     [in]    config_object*      conf,
///     [in]    const char*         id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `id` -- [in] Null terminated hex string
///
/// Outputs:
/// - `ugroupts_legacy_group_info*` -- Pointer containing conversation info
LIBSESSION_EXPORT ugroups_legacy_group_info* user_groups_get_legacy_group(
        config_object* conf, const char* id) LIBSESSION_WARN_UNUSED;

/// API: user_groups/user_groups_get_or_construct_legacy_group
///
/// Same as the above `get_legacy_group()`except that when the conversation does not exist, this
/// sets all the group fields to defaults and loads it with the given id.
///
/// Returns a ugroups_legacy_group_info as long as it is given a valid legacy group id (i.e. same
/// format as a session id); it will return NULL only if the given id is invalid (and so the caller
/// needs to either pre-validate the id, or post-validate the return value).
///
/// The returned pointer *must* be freed either by calling `ugroups_legacy_group_free()` when done
/// with it, or by passing it to `user_groups_set_free_legacy_group()`.
///
/// This is the method that should usually be used to create or update a conversation, followed by
/// setting fields in the group, and then giving it to user_groups_set().
///
/// On error, this returns NULL and sets `conf->last_error`.
///
/// Declaration:
/// ```cpp
/// UGROUPS_LEGACY_GROUP_INFO* user_groups_get_or_construct_legacy_group(
///     [in]    config_object*      conf,
///     [in]    const char*         id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `id` -- [in] Null terminated hex string
///
/// Outputs:
/// - `ugroupts_legacy_group_info*` -- Pointer containing conversation info
LIBSESSION_EXPORT ugroups_legacy_group_info* user_groups_get_or_construct_legacy_group(
        config_object* conf, const char* id) LIBSESSION_WARN_UNUSED;

/// API: user_groups/ugroups_legacy_group_free
///
/// Properly frees memory associated with a ugroups_legacy_group_info pointer (as returned by
/// get_legacy_group/get_or_construct_legacy_group).
///
/// Declaration:
/// ```cpp
/// VOID ugroups_legacy_group_free(
///     [in]    ugroups_community_info*   group
/// );
/// ```
///
/// Inputs:
/// - `group` -- [in] Pointer to ugroups_legacy_group_info
LIBSESSION_EXPORT void ugroups_legacy_group_free(ugroups_legacy_group_info* group);

/// API: user_groups/user_groups_set_community
///
/// Adds or updates a community conversation from the given group info
///
/// Declaration:
/// ```cpp
/// VOID user_groups_set_community(
///     [in]    config_object*                  conf,
///     [in]    const ugroups_community_info*   group
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group` -- [in] Pointer to a community group info object
LIBSESSION_EXPORT void user_groups_set_community(
        config_object* conf, const ugroups_community_info* group);

/// API: user_groups/user_groups_set_group
///
/// Adds or updates a (non-legacy) group conversation from the given group info
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group` -- [in] Pointer to a group info object
LIBSESSION_EXPORT void user_groups_set_group(config_object* conf, const ugroups_group_info* group);

/// API: user_groups/user_groups_set_legacy_group
///
/// Adds or updates a legacy group conversation from the into.  This version of the method should
/// only be used when you explicitly want the `group` to remain valid; if the set is the last thing
/// you need to do with it (which is common) it is more efficient to call the freeing version,
/// below.
///
/// Declaration:
/// ```cpp
/// VOID user_groups_set_legacy_group(
///     [in]    config_object*                      conf,
///     [in]    const ugroups_legacy_group_info*    group
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group` -- [in] Pointer to a legacy group info object
LIBSESSION_EXPORT void user_groups_set_legacy_group(
        config_object* conf, const ugroups_legacy_group_info* group);

/// API: user_groups/user_groups_set_free_legacy_group
///
/// Same as above `user_groups_set_free_legacy_group()`, except that this also frees the pointer for
/// you, which is commonly what is wanted when updating fields.  This is equivalent to, but more
/// efficient than, setting and then freeing.
///
/// Declaration:
/// ```cpp
/// VOID user_groups_set_free_legacy_group(
///     [in]    config_object*                      conf,
///     [in]    const ugroups_legacy_group_info*    group
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group` -- [in] Pointer to a legacy group info object
LIBSESSION_EXPORT void user_groups_set_free_legacy_group(
        config_object* conf, ugroups_legacy_group_info* group);

/// API: user_groups/user_groups_erase_community
///
/// Erases a conversation from the conversation list.  Returns true if the conversation was found
/// and removed, false if the conversation was not present.  You must not call this during
/// iteration; see details below.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_erase_community(
///     [in]    config_object*      conf,
///     [in]    const char*         base_url,
///     [in]    const char*         room
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `base_url` -- [in] null terminated string of the base url
/// - `room` -- [in] null terminated string of the room
///
/// Outputs:
/// - `bool` -- Returns True if conversation was found and removed
LIBSESSION_EXPORT bool user_groups_erase_community(
        config_object* conf, const char* base_url, const char* room);

/// API: user_groups/user_groups_erase_group
///
/// Erases a group conversation from the conversation list.  Returns true if the conversation was
/// found and removed, false if the conversation was not present.  You must not call this during
/// iteration; see details below.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_erase_group(
///     [in]    config_object*      conf,
///     [in]    const char*         group_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group_id` -- [in] null terminated string of the hex group id (starting with "03")
///
/// Outputs:
/// - `bool` -- Returns True if conversation was found and removed
LIBSESSION_EXPORT bool user_groups_erase_group(config_object* conf, const char* group_id);

/// API: user_groups/user_groups_erase_legacy_group
///
/// Erases a conversation from the conversation list.  Returns true if the conversation was found
/// and removed, false if the conversation was not present.  You must not call this during
/// iteration; see details below.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_erase_legacy_group(
///     [in]    config_object*      conf,
///     [in]    const char*         group_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `group_id` -- [in] null terminated string of the base url
///
/// Outputs:
/// - `bool` -- Returns True if conversation was found and removed
LIBSESSION_EXPORT bool user_groups_erase_legacy_group(config_object* conf, const char* group_id);

/// API: user_groups/ugroups_group_set_invited
///
/// Call when we have been invited to a group to reset the 'kicked' state.
///
/// Inputs:
/// - `group` -- [in] pointer to the group info which we should set to kicked
///
LIBSESSION_EXPORT void ugroups_group_set_invited(ugroups_group_info* group);

/// API: user_groups/ugroups_group_set_kicked
///
/// Call when we have been kicked from a group; this clears group's secret key and auth key from the
/// group config setting.
///
/// Inputs:
/// - `group` -- [in] pointer to the group info which we should set to kicked
///
LIBSESSION_EXPORT void ugroups_group_set_kicked(ugroups_group_info* group);

/// API: user_groups/ugroups_group_is_kicked
///
/// Returns true if we have been kicked (i.e. our secret key and auth data are empty).
///
/// Inputs:
/// - `group` -- [in] pointer to the group info to query
///
LIBSESSION_EXPORT bool ugroups_group_is_kicked(const ugroups_group_info* group);

/// API: user_groups/ugroups_group_set_destroyed
///
/// Mark the group as permanently destroyed and clears auth_data & secret_key. This cannot be
/// unset once set.
///
/// Inputs:
/// - `group` -- [in] pointer to the group info which we should set to kicked
///
LIBSESSION_EXPORT void ugroups_group_set_destroyed(ugroups_group_info* group);

/// API: user_groups/ugroups_group_is_destroyed
///
/// Returns true if the group was destroyed by one of the admin.
///
/// Inputs:
/// - `group` -- [in] pointer to the group info to query
///
LIBSESSION_EXPORT bool ugroups_group_is_destroyed(const ugroups_group_info* group);

typedef struct ugroups_legacy_members_iterator ugroups_legacy_members_iterator;

/// API: user_groups/ugroups_legacy_members_begin
///
/// Group member iteration; this lets you walk through the full group member list.  Example usage:
/// ```cpp
///     const char* session_id;
///     bool admin;
///     ugroups_legacy_members_iterator* it = ugroups_legacy_members_begin(legacy_info);
///     while (ugroups_legacy_members_next(it, &session_id, &admin)) {
///         if (admin)
///             printf("ADMIN: %s", session_id);
///     }
///     ugroups_legacy_members_free(it);
/// ```
///
/// Declaration:
/// ```cpp
/// UGROUPS_LEGACY_MEMBERS_ITERATOR ugroups_legacy_members_begin(
///     [in]    ugroups_legacy_group_info*      group
/// );
/// ```
///
/// Inputs:
/// - `group` -- [in] Pointer to ugroups_legacy_group_info
///
/// Outputs:
/// - `ugroups_legacy_members_iterator*` -- Iterator
LIBSESSION_EXPORT ugroups_legacy_members_iterator* ugroups_legacy_members_begin(
        ugroups_legacy_group_info* group);

/// API: user_groups/ugroups_legacy_members_next
///
/// Group member iteration; this lets you walk through the full group member list.  Example usage:
/// ```cpp
///     const char* session_id;
///     bool admin;
///     ugroups_legacy_members_iterator* it = ugroups_legacy_members_begin(legacy_info);
///     while (ugroups_legacy_members_next(it, &session_id, &admin)) {
///         if (admin)
///             printf("ADMIN: %s", session_id);
///     }
///     ugroups_legacy_members_free(it);
/// ```
///
/// Declaration:
/// ```cpp
/// BOOL ugroups_legacy_members_next(
///     [in]    ugroups_legacy_members_iterator*    it,
///     [out]   const char**                        session_id,
///     [out]   bool*                               admin
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] Iterator
/// - `session_id` -- [out] the session_id of the next member will be put here
/// - `admin` -- [out] will be true if the next member is an admin
///
/// Outputs:
/// - `bool` -- Returns False when end of group is reached
LIBSESSION_EXPORT bool ugroups_legacy_members_next(
        ugroups_legacy_members_iterator* it, const char** session_id, bool* admin);

/// API: user_groups/ugroups_legacy_members_free
///
/// Frees an iterator once no longer needed.
///
/// Declaration:
/// ```cpp
/// VOID ugroups_legacy_members_free(
///     [in]    ugroups_legacy_members_iterator*    it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The ugroups_legacy_members iterator
LIBSESSION_EXPORT void ugroups_legacy_members_free(ugroups_legacy_members_iterator* it);

/// API: user_groups/ugroups_legacy_members_erase
///
/// This erases the group member at the current iteration location during a member iteration,
/// allowing iteration to continue.
///
/// Example:
/// ```cpp
///     while (ugroups_legacy_members_next(it, &sid, &admin)) {
///         if (should_remove(sid))
///             ugroups_legacy_members_erase(it);
///     }
/// ```
///
/// Declaration:
/// ```cpp
/// VOID ugroups_legacy_members_erase(
///     [in]    ugroups_legacy_members_iterator*    it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The ugroups_legacy_members iterator
LIBSESSION_EXPORT void ugroups_legacy_members_erase(ugroups_legacy_members_iterator* it);

/// API: user_groups/ugroups_legacy_members_add
///
/// Adds a member (by session id and admin status) to this group.  Returns true if the member was
/// inserted or had the admin status changed, false if the member already existed with the given
/// status, or if the session_id is not valid.
///
/// Declaration:
/// ```cpp
/// BOOL ugroups_legacy_member_add(
///     [in]    ugroups_legacy_group_info*      group,
///     [in]    const char*                     session_id,
///     [in]    bool                            admin
/// );
/// ```
///
/// Inputs:
/// - `group` -- [in, out] group to be modified by adding a member
/// - `session_id` -- [in] null terminated session id
/// - `admin` -- [in] admin status of member
///
/// Outputs:
/// - `bool` -- Returns True if member was inserted or admin changed
LIBSESSION_EXPORT bool ugroups_legacy_member_add(
        ugroups_legacy_group_info* group, const char* session_id, bool admin);

/// API: user_groups/ugroups_legacy_members_remove
///
/// Removes a member (including admins) from the group given the member's session id.  This is not
/// safe to use on the current member during member iteration; for that see the above method
/// instead.  Returns true if the session id was found and removed, false if not found.
///
/// Declaration:
/// ```cpp
/// BOOL ugroups_legacy_member_remove(
///     [in]    ugroups_legacy_group_info*      group,
///     [in]    const char*                     session_id
/// );
/// ```
///
/// Inputs:
/// - `group` -- [in, out] group to be modified by removing a member
/// - `session_id` -- [in] null terminated session id
///
/// Outputs:
/// - `bool` -- Returns True if member was removed
LIBSESSION_EXPORT bool ugroups_legacy_member_remove(
        ugroups_legacy_group_info* group, const char* session_id);

/// API: user_groups/ugroups_legacy_members_count
///
/// Accesses the number of members in the group.  The overall number is returned (both admins and
/// non-admins); if the given variables are not NULL, they will be populated with the individual
/// counts of members/admins.
///
/// Declaration:
/// ```cpp
/// SIZE_T ugroups_legacy_members_count(
///     [in]    const ugroups_legacy_group_info*    group,
///     [out]   size_t*                             members,
///     [out]   size_t*                             admins
/// );
/// ```
///
/// Inputs:
/// - `group` -- [in] Users group
/// - `members` -- [out] the count of non-admin members
/// - `admins` -- [out] the count of admin members
///
/// Outputs:
/// - `size_t` -- Returns the count of all members
LIBSESSION_EXPORT size_t ugroups_legacy_members_count(
        const ugroups_legacy_group_info* group, size_t* members, size_t* admins);

/// API: user_groups/user_groups_size
///
/// Returns the number of conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T user_groups_size(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `size_t` -- Returns the number of conversations
LIBSESSION_EXPORT size_t user_groups_size(const config_object* conf);

/// API: user_groups/user_groups_size_communities
///
/// Returns the number of community conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T user_groups_size_communities(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `size_t` -- Returns the number of conversations
LIBSESSION_EXPORT size_t user_groups_size_communities(const config_object* conf);

/// API: user_groups/user_groups_size_groups
///
/// Returns the number of (non-legacy) group conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T user_groups_size_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `size_t` -- Returns the number of conversations
LIBSESSION_EXPORT size_t user_groups_size_groups(const config_object* conf);

/// API: user_groups/user_groups_size_legacy_groups
///
/// Returns the number of legacy group conversations.
///
/// Declaration:
/// ```cpp
/// SIZE_T user_groups_size_legacy_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `size_t` -- Returns the number of conversations
LIBSESSION_EXPORT size_t user_groups_size_legacy_groups(const config_object* conf);

typedef struct user_groups_iterator user_groups_iterator;

/// API: user_groups/user_groups_iterator_new
///
/// Starts a new iterator that iterates over all conversations.
///
/// Intended use is:
/// ```cpp
///     ugroups_community_info c2;
///     ugroups_legacy_group_info c3;
///     ugroups_group_info c4;
///     user_groups_iterator *it = user_groups_iterator_new(my_groups);
///     for (; !user_groups_iterator_done(it); user_groups_iterator_advance(it)) {
///         if (user_groups_it_is_community(it, &c2)) {
///             // use c2.whatever
///         } else if (user_groups_it_is_legacy_group(it, &c3)) {
///             // use c3.whatever
///         } else if (user_groups_it_is_group(it, &c4)) {
///             // use c4.whatever
///         }
///     }
///     user_groups_iterator_free(it);
/// ```
///
/// It is NOT permitted to add/remove/modify records while iterating.
///
/// Declaration:
/// ```cpp
/// USER_GROUPS_ITERATOR* user_groups_iterator_new(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `user_groups_iterator*` -- The Iterator
LIBSESSION_EXPORT user_groups_iterator* user_groups_iterator_new(const config_object* conf);

/// API: user_groups/user_groups_iterator_new_communities
///
/// The same as `user_groups_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `user_groups_it_is_community` (or the alternatives)
/// to load the data in each pass of the loop.  (You can, however, safely ignore the bool return
/// value of the `it_is_whatever` function: it will always be true for the particular type being
/// iterated over).
///
/// Declaration:
/// ```cpp
/// USER_GROUPS_ITERATOR* user_groups_iterator_new_communities(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `user_groups_iterator*` -- The Iterator
LIBSESSION_EXPORT user_groups_iterator* user_groups_iterator_new_communities(
        const config_object* conf);

/// API: user_groups/user_groups_iterator_new_legacy_groups
///
/// The same as `user_groups_iterator_new` except that this iterates *only* over one type of
/// conversation. You still need to use `user_groups_it_is_community` (or the alternatives)
/// to load the data in each pass of the loop.  (You can, however, safely ignore the bool return
/// value of the `it_is_whatever` function: it will always be true for the particular type being
/// iterated over).
///
/// Declaration:
/// ```cpp
/// USER_GROUPS_ITERATOR* user_groups_iterator_new_legacy_groups(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `user_groups_iterator*` -- The Iterator
LIBSESSION_EXPORT user_groups_iterator* user_groups_iterator_new_legacy_groups(
        const config_object* conf);

/// API: user_groups/user_groups_iterator_new_groups
///
/// The same as `user_groups_iterator_new` except that this iterates *only* over one type of
/// conversation: non-legacy groups. You still need to use `user_groups_it_is_group` to load the
/// data in each pass of the loop.  (You can, however, safely ignore the bool return value of the
/// `it_is_group` function: it will always be true for iterations for this iterator).
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `user_groups_iterator*` -- The Iterator
LIBSESSION_EXPORT user_groups_iterator* user_groups_iterator_new_groups(const config_object* conf);

/// API: user_groups/user_groups_iterator_free
///
/// Frees an iterator once no longer needed.
///
/// Declaration:
/// ```cpp
/// VOID user_groups_iterator_free(
///     [in]    user_groups_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in, out] The Iterator
LIBSESSION_EXPORT void user_groups_iterator_free(user_groups_iterator* it);

/// API: user_groups/user_groups_iterator_done
///
/// Returns true if iteration has reached the end.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_iterator_done(
///     [in]    user_groups_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in, out] The Iterator
///
/// Outputs:
/// - `bool` -- Returns true if iteration has reached the end
LIBSESSION_EXPORT bool user_groups_iterator_done(user_groups_iterator* it);

/// API: user_groups/user_groups_iterator_advance
///
/// Advances the iterator.
///
/// Declaration:
/// ```cpp
/// VOID user_groups_iterator_advance(
///     [in]    user_groups_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in, out] The Iterator
LIBSESSION_EXPORT void user_groups_iterator_advance(user_groups_iterator* it);

/// API: user_groups/user_groups_it_is_community
///
/// If the current iterator record is a community conversation this sets the details into `c` and
/// returns true.  Otherwise it returns false.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_it_is_community(
///     [in]    user_groups_iterator*       it,
///     [out]   ugroups_community_info*     c
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The iterator
/// - `c` -- [out] sets details of community into here if true
///
/// Outputs:
/// - `bool` -- Returns True if the group is a community
LIBSESSION_EXPORT bool user_groups_it_is_community(
        user_groups_iterator* it, ugroups_community_info* c);

/// API: user_groups/user_groups_it_is_group
///
/// If the current iterator record is a non-legacy group conversation this sets the details into
/// `group` and returns true.  Otherwise it returns false.
///
/// Inputs:
/// - `it` -- [in] The Iterator
/// - `group` -- [out] sets details of the group into here (if true is returned)
///
/// Outputs:
/// - `bool` -- Returns `true` and sets `group` if the group is a non-legacy group (aka closed
/// group).
LIBSESSION_EXPORT bool user_groups_it_is_group(user_groups_iterator* it, ugroups_group_info* group);

/// API: user_groups/user_groups_it_is_legacy_group
///
/// If the current iterator record is a legacy group conversation this sets the details into
/// `c` and returns true.  Otherwise it returns false.
///
/// Declaration:
/// ```cpp
/// BOOL user_groups_it_is_legacy_group(
///     [in]    user_groups_iterator*       it,
///     [out]   ugroups_legacy_group_info*  c
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] The iterator
/// - `c` -- [out] sets details of legacy group into here if true
///
/// Outputs:
/// - `bool` -- Returns True if the group is a legacy group
LIBSESSION_EXPORT bool user_groups_it_is_legacy_group(
        user_groups_iterator* it, ugroups_legacy_group_info* c);

#ifdef __cplusplus
}  // extern "C"
#endif
