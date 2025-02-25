#pragma once

#include <chrono>
#include <memory>
#include <session/config.hpp>

#include "../base.hpp"
#include "../namespaces.hpp"
#include "../profile_pic.hpp"

namespace session::config::groups {

using namespace std::literals;

/// keys used in this config, either currently or in the past (so that we don't reuse):
///
/// ! - set to true if the group has been destroyed (and should be removed from receiving clients)
/// c - creation unix timestamp (seconds)
/// d - delete before timestamp: this instructs receiving clients that they should delete all
///     messages with a timestamp < the set value.
/// D - delete attachments before - same as above, but specific to attachments.
/// E - disappearing message timer (seconds) if the delete-after-send disappearing messages mode is
///     enabled for the group.  Omitted if disappearing messages is disabled.
/// n - utf8 group name (human-readable); may not contain nulls, max length 100.
/// o - utf8 group description (human-readable); may not contain nulls, max length 2000.
/// p - group profile url
/// q - group profile decryption key (binary)

class Info : public ConfigBase {

  public:
    /// Limits for the name & description strings, in bytes.  If longer, we truncate to these
    /// lengths:
    static constexpr size_t NAME_MAX_LENGTH = 100;  // same as base_group_info::NAME_MAX_LENGTH
    static constexpr size_t DESCRIPTION_MAX_LENGTH = 2000;

    // No default constructor
    Info() = delete;

    /// API: groups/Info::Info
    ///
    /// Constructs a group info config object from existing data (stored from `dump()`).
    ///
    /// To construct a blank info object (i.e. with no pre-existing dumped data to load) pass
    /// `std::nullopt` as the third argument.
    ///
    /// Encryption keys must be loaded before the Info object can be modified or parse other Info
    /// messages, and are typically loaded by providing the `Info` object to the `Keys` class.
    ///
    /// Inputs:
    /// - `ed25519_pubkey` is the public key of this group, used to validate config messages.
    ///   Config messages not signed with this key will be rejected.
    /// - `ed25519_secretkey` is the secret key of the group, used to sign pushed config messages.
    ///   This is only possessed by the group admin(s), and must be provided in order to make and
    ///   push config changes.
    /// - `dumped` -- either `std::nullopt` to construct a new, empty object; or binary state data
    ///   that was previously dumped from an instance of this class by calling `dump()`.
    Info(ustring_view ed25519_pubkey,
         std::optional<ustring_view> ed25519_secretkey,
         std::optional<ustring_view> dumped);

    /// API: groups/Info::storage_namespace
    ///
    /// Returns the Info namespace. Is constant, will always return Namespace::GroupInfo
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `Namespace` - Will return Namespace::GroupInfo
    Namespace storage_namespace() const override { return Namespace::GroupInfo; }

    /// API: groups/Info::encryption_domain
    ///
    /// Returns the encryption domain used when encrypting messages of this type.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `const char*` - Will return "groups::Info"
    const char* encryption_domain() const override { return "groups::Info"; }

    /// API: groups/Info::id
    ///
    /// Contains the (read-only) id of this group, that is, 03 followed by the pubkey in hex.  (This
    /// is equivalent to a 05-prefixed session_id, but is the group-specific identifier).
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::string` containing the hex group id/pubkey
    const std::string id;

    /// API: groups/Info::get_name
    ///
    /// Returns the group name, or std::nullopt if there is no group name set.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::optional<std::string_view>` - Returns the group name if it is set
    std::optional<std::string_view> get_name() const;

    /// API: groups/Info::set_name
    ///
    /// Sets the group name; if given an empty string then the name is removed.
    ///
    /// If given a name longer than `Info::NAME_MAX_LENGTH` (100) bytes an error will be thrown.
    ///
    /// Inputs:
    /// - `new_name` -- The name to be put into the group Info
    void set_name(std::string_view new_name);

    /// API: groups/Info::set_name_truncated
    ///
    /// Sets the group name; if given an empty string then the name is removed.
    ///
    /// If given a name longer than `Info::NAME_MAX_LENGTH` (100) bytes it will be truncated.
    ///
    /// Inputs:
    /// - `new_name` -- The name to be put into the group Info
    void set_name_truncated(std::string new_name);

    /// API: groups/Info::get_description
    ///
    /// Returns the group description, or std::nullopt if there is no group description set.
    ///
    /// If given a description longer than `Info::DESCRIPTION_MAX_LENGTH` (2000) bytes it will be
    /// truncated.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::optional<std::string_view>` - Returns the group description if it is set
    std::optional<std::string_view> get_description() const;

    /// API: groups/Info::set_description
    ///
    /// Sets the optional group description; if given an empty string then an existing description
    /// is removed.
    ///
    /// Inputs:
    /// - `new_desc` -- The new description to be put into the group Info
    void set_description(std::string_view new_desc);

    /// API: groups/Info::set_description_truncated
    ///
    /// Sets the optional group description; if given an empty string then an existing description
    /// is removed. The same as `set_description` but if the name is too long it'll be truncated.
    ///
    /// Inputs:
    /// - `new_desc` -- The new description to be put into the group Info
    void set_description_truncated(std::string new_desc);

    /// API: groups/Info::get_profile_pic
    ///
    /// Gets the group's current profile pic URL and decryption key.  The returned object will
    /// evaluate as false if the URL and/or key are not set.
    ///
    /// Declaration:
    /// ```cpp
    /// profile_pic get_group_pic() const;
    /// ```
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `profile_pic` - Returns the group's profile pic
    profile_pic get_profile_pic() const;

    /// API: groups/Info::set_profile_pic
    ///
    /// Sets the group's current profile pic to a new URL and decryption key.  Clears both if either
    /// one is empty.
    ///
    /// Declaration:
    /// ```cpp
    /// void set_profile_pic(std::string_view url, ustring_view key);
    /// void set_profile_pic(profile_pic pic);
    /// ```
    ///
    /// Inputs:
    /// - First function:
    ///    - `url` -- URL pointing to the profile pic
    ///    - `key` -- Decryption key
    /// - Second function:
    ///    - `pic` -- Profile pic object
    void set_profile_pic(std::string_view url, ustring_view key);
    void set_profile_pic(profile_pic pic);

    /// API: groups/Info::set_expiry_timer
    ///
    /// Sets (or clears) the group's message expiry timer.  If > 0s the setting becomes the
    /// delete-after-send value; if omitted or given a 0 or negative duration then the expiring
    /// message timer is disabled for the group.
    ///
    /// Inputs:
    /// - `expiration_timer` -- how long the expiration timer should be, defaults to zero (disabling
    ///   message expiration) if the argument is omitted.
    void set_expiry_timer(std::chrono::seconds expiration_timer = 0min);

    /// API: groups/Info::get_expiry_timer
    ///
    /// Returns the group's current message expiry timer, or `std::nullopt` if no expiry timer is
    /// set.  If not nullopt then the expiry will always be >= 1s.
    ///
    /// Note that groups only support expire-after-send expiry timers and so there is no separate
    /// expiry type setting.
    ///
    /// Inputs: none
    ///
    /// Outputs:
    /// - `std::chrono::seconds` -- the expiry timer duration
    std::optional<std::chrono::seconds> get_expiry_timer() const;

    /// API: groups/Info::set_created
    ///
    /// Sets the created timestamp.  It's recommended (but not required) that you only set this if
    /// not already set.
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `timestamp` -- standard unix timestamp when the group was created
    void set_created(int64_t timestamp);

    /// API: groups/Info::get_created
    ///
    /// Returns the creation timestamp, if set/known.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `std::optional<int64_t>` -- the unix timestamp when the group was created, or nullopt if
    ///   the creation timestamp is not set.
    std::optional<int64_t> get_created() const;

    /// API: groups/Info::set_delete_before
    ///
    /// Sets a "delete before" unix timestamp: this instructs clients to delete all messages from
    /// the closed group history with a timestamp earlier than this value.  Returns nullopt if no
    /// delete-before timestamp is set.
    ///
    /// The given value is not checked for sanity (e.g. if you pass milliseconds it will be
    /// interpreted as deleting everything for the next 50000+ years).  Be careful!
    ///
    /// Inputs:
    /// - `timestamp` -- the new unix timestamp before which clients should delete messages.  Pass 0
    ///   (or negative) to disable the delete-before timestamp.
    void set_delete_before(int64_t timestamp);

    /// API: groups/Info::get_delete_before
    ///
    /// Returns the delete-before unix timestamp (seconds) for the group; clients should delete all
    /// messages from the closed group with timestamps earlier than this value, if set.
    ///
    /// Returns std::nullopt if no delete-before timestamp is set.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `int64_t` -- the unix timestamp for which all older messages shall be delete
    std::optional<int64_t> get_delete_before() const;

    /// API: groups/Info::set_delete_attach_before
    ///
    /// Sets a "delete attachments before" unix timestamp: this instructs clients to drop the
    /// attachments (though not necessarily the messages themselves; see `get_delete_before` for
    /// that) from any messages older than the given timestamp.  Returns nullopt if no
    /// delete-attachments-before timestamp is set.
    ///
    /// The given value is not checked for sanity (e.g. if you pass milliseconds it will be
    /// interpreted as deleting all attachments for the next 50000+ years).  Be careful!
    ///
    /// Inputs:
    /// - `timestamp` -- the new unix timestamp before which clients should delete attachments. Pass
    /// 0
    ///   (or negative) to disable the delete-attachment-before timestamp.
    void set_delete_attach_before(int64_t timestamp);

    /// API: groups/Info::get_delete_attach_before
    ///
    /// Returns the delete-attachments-before unix timestamp (seconds) for the group; clients should
    /// delete all messages from the closed group with timestamps earlier than this value, if set.
    ///
    /// Returns std::nullopt if no delete-attachments-before timestamp is set.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `int64_t` -- the unix timestamp for which all older message attachments shall be deleted
    std::optional<int64_t> get_delete_attach_before() const;

    /// API: groups/Info::destroy_group
    ///
    /// Sets the group as permanently deleted, and set this status in the group's config.  Receiving
    /// clients are supposed to remove the conversation from their conversation list when this
    /// happens.
    ///
    /// This change is permanent; the flag cannot be unset once set!
    ///
    /// Inputs:
    ///
    /// None: this call is destructive and permanent.  Be careful!
    void destroy_group();

    /// API: groups/Info::is_destroyed
    ///
    /// Returns true if this group has been marked destroyed; the receiving client is expected to
    /// delete it.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `true` if the group has been destroyed, `false` otherwise.
    bool is_destroyed() const;
};

}  // namespace session::config::groups
