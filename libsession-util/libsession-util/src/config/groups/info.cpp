#include "session/config/groups/info.hpp"

#include <oxenc/hex.h>
#include <sodium/crypto_generichash_blake2b.h>

#include <variant>

#include "../internal.hpp"
#include "session/config/error.h"
#include "session/config/groups/info.h"
#include "session/export.h"
#include "session/types.hpp"
#include "session/util.hpp"

using namespace std::literals;

namespace session::config::groups {

Info::Info(
        ustring_view ed25519_pubkey,
        std::optional<ustring_view> ed25519_secretkey,
        std::optional<ustring_view> dumped) :
        ConfigBase{dumped, ed25519_pubkey, ed25519_secretkey},
        id{"03" + oxenc::to_hex(ed25519_pubkey.begin(), ed25519_pubkey.end())} {}

std::optional<std::string_view> Info::get_name() const {
    if (auto* s = data["n"].string(); s && !s->empty())
        return *s;
    return std::nullopt;
}

void Info::set_name(std::string_view new_name) {
    if (new_name.size() > NAME_MAX_LENGTH)
        throw std::invalid_argument{"Invalid group name: exceeds maximum length"};
    set_nonempty_str(data["n"], new_name);
}

void Info::set_name_truncated(std::string new_name) {
    set_name(utf8_truncate(std::move(new_name), NAME_MAX_LENGTH));
}

std::optional<std::string_view> Info::get_description() const {
    if (auto* s = data["o"].string(); s && !s->empty())
        return *s;
    return std::nullopt;
}

void Info::set_description(std::string_view new_desc) {
    if (new_desc.size() > DESCRIPTION_MAX_LENGTH)
        throw std::invalid_argument{"Invalid group description: exceeds maximum length"};
    set_nonempty_str(data["o"], new_desc);
}

void Info::set_description_truncated(std::string new_desc) {
    set_description(utf8_truncate(std::move(new_desc), DESCRIPTION_MAX_LENGTH));
}

profile_pic Info::get_profile_pic() const {
    profile_pic pic{};
    if (auto* url = data["p"].string(); url && !url->empty())
        pic.url = *url;
    if (auto* key = data["q"].string(); key && key->size() == 32)
        pic.key = {reinterpret_cast<const unsigned char*>(key->data()), 32};
    return pic;
}

void Info::set_profile_pic(std::string_view url, ustring_view key) {
    set_pair_if(!url.empty() && key.size() == 32, data["p"], url, data["q"], key);
}

void Info::set_profile_pic(profile_pic pic) {
    set_profile_pic(pic.url, pic.key);
}

std::optional<std::chrono::seconds> Info::get_expiry_timer() const {
    if (auto exp = data["E"].integer())
        return *exp * 1s;
    return std::nullopt;
}

void Info::set_expiry_timer(std::chrono::seconds expiration_timer) {
    set_positive_int(data["E"], expiration_timer.count());
}

void Info::set_created(int64_t timestamp) {
    set_positive_int(data["c"], timestamp);
}

std::optional<int64_t> Info::get_created() const {
    if (auto* ts = data["c"].integer())
        return *ts;
    return std::nullopt;
}

void Info::set_delete_before(int64_t timestamp) {
    set_positive_int(data["d"], timestamp);
}

std::optional<int64_t> Info::get_delete_before() const {
    if (auto* ts = data["d"].integer())
        return *ts;
    return std::nullopt;
}

void Info::set_delete_attach_before(int64_t timestamp) {
    set_positive_int(data["D"], timestamp);
}

std::optional<int64_t> Info::get_delete_attach_before() const {
    if (auto* ts = data["D"].integer())
        return *ts;
    return std::nullopt;
}

void Info::destroy_group() {
    set_flag(data["!"], true);
}

bool Info::is_destroyed() const {
    if (auto* ts = data["!"].integer(); ts && *ts > 0)
        return true;
    return false;
}

}  // namespace session::config::groups

using namespace session;
using namespace session::config;

LIBSESSION_C_API const size_t GROUP_INFO_NAME_MAX_LENGTH = groups::Info::NAME_MAX_LENGTH;
LIBSESSION_C_API const size_t GROUP_INFO_DESCRIPTION_MAX_LENGTH =
        groups::Info::DESCRIPTION_MAX_LENGTH;

LIBSESSION_C_API int groups_info_init(
        config_object** conf,
        const unsigned char* ed25519_pubkey,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) {
    return c_group_wrapper_init<groups::Info>(
            conf, ed25519_pubkey, ed25519_secretkey, dump, dumplen, error);
}

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
LIBSESSION_C_API const char* groups_info_get_name(const config_object* conf) {
    if (auto s = unbox<groups::Info>(conf)->get_name())
        return s->data();
    return nullptr;
}

/// API: groups_info/groups_info_set_name
///
/// Sets the group's name to the null-terminated C string.  Returns 0 on success, non-zero on
/// error (and sets the config_object's error string).
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `name` -- [in] Pointer to the name as a null-terminated C string
///
/// Outputs:
/// - `int` -- Returns 0 on success, non-zero on error
LIBSESSION_C_API int groups_info_set_name(config_object* conf, const char* name) {
    try {
        unbox<groups::Info>(conf)->set_name(name);
    } catch (const std::exception& e) {
        return set_error(conf, SESSION_ERR_BAD_VALUE, e);
    }
    return 0;
}

/// API: groups_info/groups_info_get_description
///
/// Returns a pointer to the currently-set description (null-terminated), or NULL if there is
/// no description at all.  Should be copied right away as the pointer may not remain valid
/// beyond other API calls.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `char*` -- Pointer to the currently-set description as a null-terminated string, or NULL
/// if there is no description
LIBSESSION_C_API const char* groups_info_get_description(const config_object* conf) {
    if (auto s = unbox<groups::Info>(conf)->get_description())
        return s->data();
    return nullptr;
}

/// API: groups_info/groups_info_set_description
///
/// Sets the group's description to the null-terminated C string.  Returns 0 on success, non-zero on
/// error (and sets the config_object's error string).
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `description` -- [in] Pointer to the description as a null-terminated C string
///
/// Outputs:
/// - `int` -- Returns 0 on success, non-zero on error
LIBSESSION_C_API int groups_info_set_description(config_object* conf, const char* description) {
    try {
        unbox<groups::Info>(conf)->set_description(description);
    } catch (const std::exception& e) {
        return set_error(conf, SESSION_ERR_BAD_VALUE, e);
    }
    return 0;
}

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
LIBSESSION_C_API user_profile_pic groups_info_get_pic(const config_object* conf) {
    user_profile_pic p;
    if (auto pic = unbox<groups::Info>(conf)->get_profile_pic(); pic) {
        copy_c_str(p.url, pic.url);
        std::memcpy(p.key, pic.key.data(), 32);
    } else {
        p.url[0] = 0;
    }
    return p;
}

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
LIBSESSION_C_API int groups_info_set_pic(config_object* conf, user_profile_pic pic) {
    std::string_view url{pic.url};
    ustring_view key;
    if (!url.empty())
        key = {pic.key, 32};

    try {
        unbox<groups::Info>(conf)->set_profile_pic(url, key);
    } catch (const std::exception& e) {
        return set_error(conf, SESSION_ERR_BAD_VALUE, e);
    }

    return 0;
}

/// API: groups_info/groups_info_get_expiry_timer
///
/// Gets the group's message expiry timer (seconds).  Returns 0 if not set.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int` -- Returns the expiry timer in seconds. Returns 0 if not set
LIBSESSION_C_API int groups_info_get_expiry_timer(const config_object* conf) {
    if (auto t = unbox<groups::Info>(conf)->get_expiry_timer(); t && *t > 0s)
        return t->count();
    return 0;
}

/// API: groups_info/groups_info_set_expiry_timer
///
/// Sets the group's message expiry timer (seconds).  Setting 0 (or negative) will clear the current
/// timer.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `expiry` -- [in] Integer of the expiry timer in seconds
LIBSESSION_C_API void groups_info_set_expiry_timer(config_object* conf, int expiry) {
    unbox<groups::Info>(conf)->set_expiry_timer(std::max(0, expiry) * 1s);
}

/// API: groups_info/groups_info_get_created
///
/// Returns the timestamp (unix time, in seconds) when the group was created.  Returns 0 if unset.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int64_t` -- Unix timestamp when the group was created (if set by an admin).
LIBSESSION_C_API int64_t groups_info_get_created(const config_object* conf) {
    return unbox<groups::Info>(conf)->get_created().value_or(0);
}

/// API: groups_info/groups_info_set_created
///
/// Sets the creation time (unix timestamp, in seconds) when the group was created.  Setting 0
/// clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_C_API void groups_info_set_created(config_object* conf, int64_t ts) {
    unbox<groups::Info>(conf)->set_created(std::max<int64_t>(0, ts));
}

/// API: groups_info/groups_info_get_delete_before
///
/// Returns the delete-before timestamp (unix time, in seconds); clients should deleted all messages
/// from the group with timestamps earlier than this value, if set.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
///
/// Outputs:
/// - `int64_t` -- Unix timestamp before which messages should be deleted.  Returns 0 if not set.
LIBSESSION_C_API int64_t groups_info_get_delete_before(const config_object* conf) {
    return unbox<groups::Info>(conf)->get_delete_before().value_or(0);
}

/// API: groups_info/groups_info_set_delete_before
///
/// Sets the delete-before time (unix timestamp, in seconds) before which messages should be delete.
/// Setting 0 clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_C_API void groups_info_set_delete_before(config_object* conf, int64_t ts) {
    unbox<groups::Info>(conf)->set_delete_before(std::max<int64_t>(0, ts));
}

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
LIBSESSION_C_API int64_t groups_info_get_attach_delete_before(const config_object* conf) {
    return unbox<groups::Info>(conf)->get_delete_attach_before().value_or(0);
}

/// API: groups_info/groups_info_set_attach_delete_before
///
/// Sets the delete-before time (unix timestamp, in seconds) for attachments; attachments should be
/// dropped from messages older than this value.  Setting 0 clears the value.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
/// - `ts` -- [in] the unix timestamp, or 0 to clear a current value.
LIBSESSION_C_API void groups_info_set_attach_delete_before(config_object* conf, int64_t ts) {
    unbox<groups::Info>(conf)->set_delete_attach_before(std::max<int64_t>(0, ts));
}

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
LIBSESSION_C_API bool groups_info_is_destroyed(const config_object* conf) {
    return unbox<groups::Info>(conf)->is_destroyed();
}

/// API: groups_info/groups_info_destroy_group(const config_object* conf);
///
/// Nukes a group from orbit.  This is permanent (i.e. there is no removing this setting once set).
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config object
LIBSESSION_C_API void groups_info_destroy_group(config_object* conf) {
    unbox<groups::Info>(conf)->destroy_group();
}
