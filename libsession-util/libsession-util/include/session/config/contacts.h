#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "base.h"
#include "expiring.h"
#include "notify.h"
#include "profile_pic.h"
#include "util.h"

// Maximum length of a contact name/nickname, in bytes (not including the null terminator).
LIBSESSION_EXPORT extern const size_t CONTACT_MAX_NAME_LENGTH;

typedef struct contacts_contact {
    char session_id[67];  // in hex; 66 hex chars + null terminator.

    // These two will be 0-length strings when unset:
    char name[101];
    char nickname[101];
    user_profile_pic profile_pic;

    bool approved;
    bool approved_me;
    bool blocked;

    int priority;
    CONVO_NOTIFY_MODE notifications;
    int64_t mute_until;

    CONVO_EXPIRATION_MODE exp_mode;
    int exp_seconds;

    int64_t created;  // unix timestamp (seconds)

} contacts_contact;

/// API: contacts/contacts_init
///
/// Constructs a contacts config object and sets a pointer to it in `conf`.
///
/// When done with the object the `config_object` must be destroyed by passing the pointer to
/// config_free() (in `session/config/base.h`).
///
/// Declaration:
/// ```cpp
/// INT contacts_init(
///     [out]   config_object**         conf,
///     [in]    const unsigned char*    ed25519_secretkey,
///     [in]    const unsigned char*    dump,
///     [in]    size_t                  dumplen,
///     [out]   char*                   error
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [out] Pointer to the config object
/// - `ed25519_secretkey` -- [in] must be the 32-byte secret key seed value.  (You can also pass the
/// pointer to the beginning of the 64-byte value libsodium calls the "secret key" as the first 32
/// bytes of that are the seed).  This field cannot be null.
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
LIBSESSION_EXPORT int contacts_init(
        config_object** conf,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) LIBSESSION_WARN_UNUSED;

/// API: contacts/contacts_get
///
/// Fills `contact` with the contact info given a session ID (specified as a null-terminated hex
/// string), if the contact exists, and returns true.  If the contact does not exist then `contact`
/// is left unchanged and false is returned.
///
/// Declaration:
/// ```cpp
/// BOOL contacts_get(
///     [in]    config_object*      conf,
///     [out]   contacts_contact*   contact,
///     [in]    const char*         session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `contact` -- [out] the contact info data
/// - `session_id` -- [in] null terminated hex string
///
/// Output:
/// - `bool` -- Returns true if contact exsts
LIBSESSION_EXPORT bool contacts_get(
        config_object* conf,
        contacts_contact* contact,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: contacts/contacts_get_or_construct
///
/// Same as the above `contacts_get()` except that when the contact does not exist, this sets all
/// the contact fields to defaults and loads it with the given session_id.
///
/// Returns true as long as it is given a valid session_id.  A false return is considered an error,
/// and means the session_id was not a valid session_id.
///
/// This is the method that should usually be used to create or update a contact, followed by
/// setting fields in the contact, and then giving it to contacts_set().
///
/// Declaration:
/// ```cpp
/// BOOL contacts_get_or_construct(
///     [in]    config_object*      conf,
///     [out]   contacts_contact*   contact,
///     [in]    const char*         session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `contact` -- [out] the contact info data
/// - `session_id` -- [in] null terminated hex string
///
/// Output:
/// - `bool` -- Returns true if contact exsts
LIBSESSION_EXPORT bool contacts_get_or_construct(
        config_object* conf,
        contacts_contact* contact,
        const char* session_id) LIBSESSION_WARN_UNUSED;

/// API: contacts/contacts_set
///
/// Adds or updates a contact from the given contact info struct.
///
/// Declaration:
/// ```cpp
/// VOID contacts_set(
///     [in, out]   config_object*              conf,
///     [in]        const contacts_contact*     contact
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `contact` -- [in] Pointer containing the contact info data
///
/// Output:
/// - `void` -- Returns Nothing
LIBSESSION_EXPORT void contacts_set(config_object* conf, const contacts_contact* contact);

// NB: wrappers for set_name, set_nickname, etc. C++ methods are deliberately omitted as they would
// save very little in actual calling code.  The procedure for updating a single field without them
// is simple enough; for example to update `approved` and leave everything else unchanged:
//
// contacts_contact c;
// if (contacts_get_or_construct(conf, &c, some_session_id)) {
//     const char* new_nickname = "Joe";
//     c.approved = new_nickname;
//     contacts_set_or_create(conf, &c);
// } else {
//     // some_session_id was invalid!
// }

/// API: contacts/contacts_erase
///
/// Erases a contact from the contact list.  session_id is in hex.  Returns true if the contact was
/// found and removed, false if the contact was not present.  You must not call this during
/// iteration; see details below.
///
/// Declaration:
/// ```cpp
/// BOOL contacts_erase(
///     [in, out]   config_object*  conf,
///     [in]    const char*     session_id
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to the config object
/// - `session_id` -- [in] Text containing null terminated hex string
///
/// Outputs:
/// - `bool` -- True if erasing was successful
LIBSESSION_EXPORT bool contacts_erase(config_object* conf, const char* session_id);

/// API: contacts/contacts_size
///
/// Returns the number of contacts.
///
/// Declaration:
/// ```cpp
/// SIZE_T contacts_size(
///     [in]   const config_object*  conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- input - Pointer to the config object
///
/// Outputs:
/// - `size_t` -- number of contacts
LIBSESSION_EXPORT size_t contacts_size(const config_object* conf);

typedef struct contacts_iterator {
    void* _internals;
} contacts_iterator;

/// API: contacts/contacts_iterator_new
///
/// Starts a new iterator.
///
/// Functions for iterating through the entire contact list, in sorted order.  Intended use is:
///
///     contacts_contact c;
///     contacts_iterator *it = contacts_iterator_new(contacts);
///     for (; !contacts_iterator_done(it, &c); contacts_iterator_advance(it)) {
///         // c.session_id, c.nickname, etc. are loaded
///     }
///     contacts_iterator_free(it);
///
/// It is NOT permitted to add/remove/modify records while iterating.
///
/// Declaration:
/// ```cpp
/// CONTACTS_ITERATOR* contacts_iterator_new(
///     [in]   const config_object*  conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `contacts_iterator*` -- pointer to the iterator
LIBSESSION_EXPORT contacts_iterator* contacts_iterator_new(const config_object* conf);

/// API: contacts/contacts_iterator_free
///
/// Frees an iterator once no longer needed.
///
/// Declaration:
/// ```cpp
/// VOID contacts_iterator_free(
///     [in]   contacts_iterator*   it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] Pointer to the contacts_iterator
LIBSESSION_EXPORT void contacts_iterator_free(contacts_iterator* it);

/// API: contacts/contacts_iterator_done
///
/// Returns true if iteration has reached the end.  Otherwise `c` is populated and false is
/// returned.
///
/// Declaration:
/// ```cpp
/// BOOL contacts_iterator_done(
///     [in]    contacts_iterator*  it,
///     [out]   contacts_contact*   c
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] Pointer to the contacts_iterator
/// - `c` -- [out] Pointer to the contact, will be populated if false
///
/// Outputs:
/// - `bool` -- True if iteration has reached the end
LIBSESSION_EXPORT bool contacts_iterator_done(contacts_iterator* it, contacts_contact* c);

/// API: contacts/contacts_iterator_advance
///
/// Advances the iterator.
///
/// Declaration:
/// ```cpp
/// VOID contacts_iterator_advance(
///     [in]    contacts_iterator*  it
/// );
/// ```
///
/// Inputs:
/// - `it` -- [in] Pointer to the contacts_iterator
LIBSESSION_EXPORT void contacts_iterator_advance(contacts_iterator* it);

#ifdef __cplusplus
}  // extern "C"
#endif
