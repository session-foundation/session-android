#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "../base.h"
#include "../profile_pic.h"
#include "../util.h"

enum groups_members_status { STATUS_SENT = 1, STATUS_FAILED = 2, STATUS_NOT_SENT = 3 };
enum groups_members_remove_status { REMOVED_MEMBER = 1, REMOVED_MEMBER_AND_MESSAGES = 2 };

/// A convenience status enum for the group member, this enum is returned by a function which
/// reviews the various member status values (eg. invite, promotion, etc.) and returns a single
/// consolidated value
typedef enum GROUP_MEMBER_STATUS {
    GROUP_MEMBER_STATUS_INVITE_UNKNOWN = 0,
    GROUP_MEMBER_STATUS_INVITE_NOT_SENT = 1,
    GROUP_MEMBER_STATUS_INVITE_FAILED = 2,
    GROUP_MEMBER_STATUS_INVITE_SENT = 3,
    GROUP_MEMBER_STATUS_INVITE_ACCEPTED = 4,
    GROUP_MEMBER_STATUS_PROMOTION_UNKNOWN = 5,
    GROUP_MEMBER_STATUS_PROMOTION_NOT_SENT = 6,
    GROUP_MEMBER_STATUS_PROMOTION_FAILED = 7,
    GROUP_MEMBER_STATUS_PROMOTION_SENT = 8,
    GROUP_MEMBER_STATUS_PROMOTION_ACCEPTED = 9,
    GROUP_MEMBER_STATUS_REMOVED_UNKNOWN = 10,
    GROUP_MEMBER_STATUS_REMOVED = 11,
    GROUP_MEMBER_STATUS_REMOVED_MEMBER_AND_MESSAGES = 12,
} GROUP_MEMBER_STATUS;

typedef struct config_group_member {
    char session_id[67];  // in hex; 66 hex chars + null terminator.

    // These two will be 0-length strings when unset:
    char name[101];
    user_profile_pic profile_pic;

    bool admin;
    int invited;   // 0 == unset, STATUS_SENT = invited, STATUS_FAILED = invite failed to send,
                   // STATUS_NOT_SENT = invite hasn't been sent yet
    int promoted;  // same value as `invited`, but for promotion-to-admin
    int removed;   // 0 == unset, REMOVED_MEMBER = removed, REMOVED_MEMBER_AND_MESSAGES = remove
                   // member and their messages
    bool supplement;

} config_group_member;

/// API: groups/groups_members_init
///
/// Constructs a group members config object and sets a pointer to it in `conf`.
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
LIBSESSION_EXPORT int groups_members_init(
        config_object** conf,
        const unsigned char* ed25519_pubkey,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) LIBSESSION_WARN_UNUSED;

/// API: groups/groups_members_get
///
/// Fills `member` with the member info given a session ID (specified as a null-terminated hex
/// string), if the member exists, and returns true.  If the member does not exist then `member`
/// is left unchanged and false is returned.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `member` -- [out] the member info data
/// - `session_id` -- [in] null terminated hex string
///
/// Output:
/// - `bool` -- Returns true if member exists
LIBSESSION_EXPORT bool groups_members_get(
        config_object* conf,
        config_group_member* member,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: groups/groups_members_get_or_construct
///
/// Same as the above `groups_members_get()` except that when the member does not exist, this sets
/// all the member fields to defaults and loads it with the given session_id.
///
/// Returns true as long as it is given a valid session_id.  A false return is considered an error,
/// and means the session_id was not a valid session_id.
///
/// This is the method that should usually be used to create or update a member, followed by
/// setting fields in the member, and then giving it to groups_members_set().
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `member` -- [out] the member info data
/// - `session_id` -- [in] null terminated hex string
///
/// Output:
/// - `bool` -- Returns true if the call succeeds, false if an error occurs (e.g. because of an
///   invalid session_id).
LIBSESSION_EXPORT bool groups_members_get_or_construct(
        config_object* conf,
        config_group_member* member,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: groups/groups_members_set
///
/// Adds or updates a member from the given member info struct.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `member` -- [in] Pointer containing the member info data
LIBSESSION_EXPORT void groups_members_set(config_object* conf, const config_group_member* member);

/// API: groups/group_member_status
///
/// This function goes through the various status values and returns a single consolidated
/// status for the member.
///
/// Inputs:
/// - `member` -- [in] The member to retrieve the status for
///
/// Outputs:
/// - `GROUP_MEMBER_STATUS` -- Returns an enum indicating the consolidated status of this member in
/// the group.
LIBSESSION_EXPORT GROUP_MEMBER_STATUS group_member_status(const config_group_member* member);

/// API: groups/groups_members_set_invite_sent
///
/// This marks the user as having a pending invitation in the group, and that an invitation message
/// has been sent to them.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- Returns true if the call succeeds, false if an error occurs.
LIBSESSION_EXPORT bool groups_members_set_invite_sent(config_object* conf, const char* session_id);

/// API: groups/groups_members_set_invite_failed
///
/// This marks the user as being invited to the group, but that their invitation message failed to
/// send (this is intended as a signal to other clients that the invitation should be reissued).
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_invite_failed(
        config_object* conf, const char* session_id);

/// API: groups/groups_members_set_invite_accepted
///
/// This clears the "invited" and "supplement" flags for this user, thus indicating that the
/// user has accepted an invitation and is now a regular member of the group.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_invite_accepted(
        config_object* conf, const char* session_id);

/// API: groups/groups_members_set_promoted
///
/// This marks the user as having a pending promotion-to-admin in the group, waiting for the
/// promotion message to be sent to them.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_promoted(config_object* conf, const char* session_id);

/// API: groups/groups_members_set_promotion_sent
///
/// This marks the user as having a pending promotion-to-admin in the group, and that a
/// promotion message has been sent to them.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_promotion_sent(
        config_object* conf, const char* session_id);

/// API: groups/groups_members_set_promotion_failed
///
/// This marks the user as being promoted to an admin, but that their promotion message failed
/// to send (this is intended as a signal to other clients that the promotion should be
/// reissued).
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_promotion_failed(
        config_object* conf, const char* session_id);

/// API: groups/groups_members_set_promotion_accepted
///
/// This marks the user as having accepted a promotion to admin in the group.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_promotion_accepted(
        config_object* conf, const char* session_id);

/// API: groups/groups_members_set_removed
///
/// Sets the "removed" flag for this user.  This marks the user as pending removal from the
/// group.  The optional `messages` parameter can be specified as true if we want to remove
/// any messages sent by the member upon a successful removal.
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] null terminated hex string
/// - `messages` -- [in] Flag which controls whether any messages sent by the member should also be
/// removed upon a successful member removal.
///
/// Outputs:
/// - `bool` -- True if change was successful
LIBSESSION_EXPORT bool groups_members_set_removed(
        config_object* conf, const char* session_id, bool messages);

/// API: groups/groups_members_erase
///
/// Erases a member from the member list.  session_id is in hex.  Returns true if the member was
/// found and removed, false if the member was not present.  You must not call this during
/// iteration; see details below.
///
/// Typically this should be followed by a group rekey (so that the removed member cannot read the
/// group).
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] Text containing null terminated hex string
///
/// Outputs:
/// - `bool` -- True if erasing was successful
LIBSESSION_EXPORT bool groups_members_erase(config_object* conf, const char* session_id);

/// API: groups/groups_members_size
///
/// Returns the number of group members.
///
/// Inputs:
/// - `conf` -- input - Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of contacts
LIBSESSION_EXPORT size_t groups_members_size(const config_object* conf);

typedef struct groups_members_iterator {
    void* _internals;
} groups_members_iterator;

/// API: groups/groups_members_iterator_new
///
/// Starts a new iterator.
///
/// Functions for iterating through the entire member list, in sorted order.  Intended use is:
///
///     group_member m;
///     groups_members_iterator *it = groups_members_iterator_new(group);
///     for (; !groups_members_iterator_done(it, &c); groups_members_iterator_advance(it)) {
///         // c.session_id, c.name, etc. are loaded
///     }
///     groups_members_iterator_free(it);
///
/// It is NOT permitted to add/remove/modify members while iterating.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `groups_members_iterator*` -- pointer to the new iterator
LIBSESSION_EXPORT groups_members_iterator* groups_members_iterator_new(const config_object* conf);

/// API: groups/groups_members_iterator_free
///
/// Frees an iterator once no longer needed.
///
/// Inputs:
/// - `it` -- [in] Pointer to the groups_members_iterator
LIBSESSION_EXPORT void groups_members_iterator_free(groups_members_iterator* it);

/// API: groups/groups_members_iterator_done
///
/// Returns true if iteration has reached the end.  Otherwise `m` is populated and false is
/// returned.
///
/// Inputs:
/// - `it` -- [in] Pointer to the groups_members_iterator
/// - `m` -- [out] Pointer to the config_group_member, will be populated if false is returned
///
/// Outputs:
/// - `bool` -- True if iteration has reached the end
LIBSESSION_EXPORT bool groups_members_iterator_done(
        groups_members_iterator* it, config_group_member* m);

/// API: groups/groups_members_iterator_advance
///
/// Advances the iterator.
///
/// Inputs:
/// - `it` -- [in] Pointer to the groups_members_iterator
LIBSESSION_EXPORT void groups_members_iterator_advance(groups_members_iterator* it);

#ifdef __cplusplus
}  // extern "C"
#endif
