#pragma once

#include <chrono>
#include <cstddef>
#include <iterator>
#include <memory>
#include <session/config.hpp>

#include "base.hpp"
#include "expiring.hpp"
#include "namespaces.hpp"
#include "notify.hpp"
#include "profile_pic.hpp"

extern "C" struct contacts_contact;

using namespace std::literals;

namespace session::config {

/// keys used in this config, either currently or in the past (so that we don't reuse):
///
/// c - dict of contacts; within this dict each key is the session pubkey (binary, 33 bytes) and
///     value is a dict containing keys:
///
///     n - contact name (string).  This is always serialized, even if empty (but empty indicates
///         no name) so that we always have at least one key set (required to keep the dict value
///         alive as empty dicts get pruned).
///     N - contact nickname (string)
///     p - profile url (string)
///     q - profile decryption key (binary)
///     a - 1 if approved, omitted otherwise (int)
///     A - 1 if remote has approved me, omitted otherwise (int)
///     b - 1 if contact is blocked, omitted otherwise
///     @ - notification setting (int).  Omitted = use default setting; 1 = all; 2 = disabled.
///     ! - mute timestamp: if this is set then notifications are to be muted until the given unix
///         timestamp (seconds, not milliseconds).
///     + - the conversation priority; -1 means hidden; omitted means not pinned; otherwise an
///         integer value >0, where a higher priority means the conversation is meant to appear
///         earlier in the pinned conversation list.
///     e - Disappearing messages expiration type.  Omitted if disappearing messages are not enabled
///         for the conversation with this contact; 1 for delete-after-send, and 2 for
///         delete-after-read.
///     E - Disappearing message timer, in seconds.  Omitted when `e` is omitted.
///     j - Unix timestamp (seconds) when the contact was created ("j" to match user_groups
///         equivalent "j"oined field). Omitted if 0.

/// Struct containing contact info.
struct contact_info {
    static constexpr size_t MAX_NAME_LENGTH = 100;

    std::string session_id;  // in hex
    std::string name;
    std::string nickname;
    profile_pic profile_picture;
    bool approved = false;
    bool approved_me = false;
    bool blocked = false;
    int priority = 0;  // If >0 then this message is pinned; higher values mean higher priority
                       // (i.e. pinned earlier in the pinned list).  If negative then this
                       // conversation is hidden.  Otherwise (0) this is a regular, unpinned
                       // conversation.
    notify_mode notifications = notify_mode::defaulted;
    int64_t mute_until = 0;  // If non-zero, disable notifications until the given unix timestamp
                             // (overriding whatever the current `notifications` value is until the
                             // timestamp expires).
    expiration_mode exp_mode = expiration_mode::none;  // The expiry time; none if not expiring.
    std::chrono::seconds exp_timer{0};                 // The expiration timer (in seconds)
    int64_t created = 0;                               // Unix timestamp when this contact was added

    explicit contact_info(std::string sid);

    // Internal ctor/method for C API implementations:
    contact_info(const struct contacts_contact& c);  // From c struct
                                                     //
    /// API: contacts/contact_info::into
    ///
    /// converts the contact info into a c struct
    ///
    /// Inputs:
    /// - `c` -- Return Parameter that will be filled with data in contact_info
    void into(contacts_contact& c) const;

    /// API: contacts/contact_info::set_name
    ///
    /// Sets a name or nickname; this is exactly the same as assigning to .name/.nickname directly,
    /// except that we throw an exception if the given name is longer than MAX_NAME_LENGTH.
    ///
    /// Inputs:
    /// - `name` -- Name to assign to the contact
    void set_name(std::string name);
    void set_nickname(std::string nickname);
    void set_nickname_truncated(std::string nickname);

  private:
    friend class Contacts;
    void load(const dict& info_dict);
};

class Contacts : public ConfigBase {

  public:
    // No default constructor
    Contacts() = delete;

    /// API: contacts/Contacts::Contacts
    ///
    /// Constructs a contact list from existing data (stored from `dump()`) and the user's secret
    /// key for generating the data encryption key.  To construct a blank list (i.e. with no
    /// pre-existing dumped data to load) pass `std::nullopt` as the second argument.
    ///
    /// Inputs:
    /// - `ed25519_secretkey` -- contains the libsodium secret key used to encrypt/decrypt the
    /// data when pushing/pulling from the swarm.  This can either be the full 64-byte value (which
    /// is technically the 32-byte seed followed by the 32-byte pubkey), or just the 32-byte seed of
    /// the secret key.
    /// - `dumped` -- either `std::nullopt` to construct a new, empty object; or binary state data
    /// that was previously dumped from an instance of this class by calling `dump()`.
    ///
    /// Outputs:
    /// - `Contact` - Constructor
    Contacts(ustring_view ed25519_secretkey, std::optional<ustring_view> dumped);

    /// API: contacts/Contacts::storage_namespace
    ///
    /// Returns the Contacts namespace. Is constant, will always return 3
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `Namespace` - Will return 3
    Namespace storage_namespace() const override { return Namespace::Contacts; }

    /// API: contacts/Contacts::encryption_domain
    ///
    /// Returns the domain. Is constant, will always return "Contacts"
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `const char*` - Will return "Contacts"
    const char* encryption_domain() const override { return "Contacts"; }

    /// API: contacts/Contacts::get
    ///
    /// Looks up and returns a contact by session ID (hex).  Returns nullopt if the session ID was
    /// not found, otherwise returns a filled out `contact_info`.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- hex string of the session id
    ///
    /// Outputs:
    /// - `std::optional<contact_info>` - Returns nullopt if session ID was not found, otherwise a
    /// filled out contact_info
    std::optional<contact_info> get(std::string_view pubkey_hex) const;

    /// API: contacts/Contacts::get_or_construct
    ///
    /// Similar to get(), but if the session ID does not exist this returns a filled-out
    /// contact_info containing the session_id (all other fields will be empty/defaulted).  This is
    /// intended to be combined with `set` to set-or-create a record.
    ///
    /// NB: calling this does *not* add the session id to the contact list when called: that
    /// requires also calling `set` with this value.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- hex string of the session id
    ///
    /// Outputs:
    /// - `contact_info` - Returns a filled out contact_info
    contact_info get_or_construct(std::string_view pubkey_hex) const;

    /// API: contacts/contacts::set
    ///
    /// Sets or updates multiple contact info values at once with the given info.  The usual use is
    /// to access the current info, change anything desired, then pass it back into set_contact,
    /// e.g.:
    ///
    ///```cpp
    ///     auto c = contacts.get_or_construct(pubkey);
    ///     c.name = "Session User 42";
    ///     c.nickname = "BFF";
    ///     contacts.set(c);
    ///```
    ///
    /// Inputs:
    /// - `contact` -- contact_info value to set
    void set(const contact_info& contact);

    /// API: contacts/contacts::set_name
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `name` -- string of the contacts name
    void set_name(std::string_view session_id, std::string name);

    /// API: contacts/contacts::set_nickname
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `nickname` -- string of the contacts nickname
    void set_nickname(std::string_view session_id, std::string nickname);

    /// API: contacts/contacts::set_nickname_truncated
    ///
    /// Alternative to `set()` for setting a single field. The same as `set_name` except truncates
    /// the value when it's too long.  (If setting multiple fields at once you should use `set()`
    /// instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `nickname` -- string of the contacts nickname
    void set_nickname_truncated(std::string_view session_id, std::string nickname);

    /// API: contacts/contacts::set_profile_pic
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `profile_pic` -- profile pic of the contact
    void set_profile_pic(std::string_view session_id, profile_pic pic);

    /// API: contacts/contacts::set_approved
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `approved` -- boolean on whether the contact is approved by me (to send messages to me)
    void set_approved(std::string_view session_id, bool approved);

    /// API: contacts/contacts::set_approved_me
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `approved_me` -- boolean on whether the contact has approved the user (so we can send
    /// messages to them)
    void set_approved_me(std::string_view session_id, bool approved_me);

    /// API: contacts/contacts::set_blocked
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `blocked` -- boolean on whether the contact is blocked by us
    void set_blocked(std::string_view session_id, bool blocked);

    /// API: contacts/contacts::set_priority
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `priority` -- numerical value on the contacts priority (pinned, normal, hidden etc)
    void set_priority(std::string_view session_id, int priority);

    /// API: contacts/contacts::set_notifications
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `notifications` -- detail on notifications
    void set_notifications(std::string_view session_id, notify_mode notifications);

    /// API: contacts/contacts::set_expiry
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `exp_mode` -- detail on expirations
    /// - `expiration_timer` -- how long the expiration timer should be, defaults to zero
    void set_expiry(
            std::string_view session_id,
            expiration_mode exp_mode,
            std::chrono::seconds expiration_timer = 0min);

    /// API: contacts/contacts::set_created
    ///
    /// Alternative to `set()` for setting a single field.  (If setting multiple fields at once you
    /// should use `set()` instead).
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    /// - `timestamp` -- standard unix timestamp of the time contact was created
    void set_created(std::string_view session_id, int64_t timestamp);

    /// API: contacts/contacts::erase
    ///
    /// Removes a contact, if present.  Returns true if it was found and removed, false otherwise.
    /// Note that this removes all fields related to a contact, even fields we do not know about.
    ///
    /// Inputs:
    /// - `session_id` -- hex string of the session id
    ///
    /// Outputs:
    /// - `bool` - Returns true if contact was found and removed, false otherwise
    bool erase(std::string_view session_id);

    /// API: contacts/contacts::size
    ///
    /// Returns the number of contacts.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `size_t` - Returns the number of contacts
    size_t size() const;

    /// API: contacts/contacts::empty
    ///
    /// Returns true if the contact list is empty.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `bool` - Returns true if the contact list is empty
    bool empty() const { return size() == 0; }

    bool accepts_protobuf() const override { return true; }

    struct iterator;
    /// API: contacts/contacts::begin
    ///
    /// Iterators for iterating through all contacts.  Typically you access this implicit via a for
    /// loop over the `Contacts` object:
    ///
    ///```cpp
    ///     for (auto& contact : contacts) {
    ///         // use contact.session_id, contact.name, etc.
    ///     }
    ///```
    ///
    /// This iterates in sorted order through the session_ids.
    ///
    /// It is NOT permitted to add/modify/remove records while iterating; instead such modifications
    /// require two passes: an iterator loop to collect the required modifications, then a second
    /// pass to apply the modifications.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `iterator` - Returns an iterator for the beginning of the contacts
    iterator begin() const { return iterator{data["c"].dict()}; }

    /// API: contacts/contacts::end
    ///
    /// Iterator for passing the end of the contacts
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `iterator` - Returns an iterator for the end of the contacts
    iterator end() const { return iterator{nullptr}; }

    using iterator_category = std::input_iterator_tag;
    using value_type = contact_info;
    using reference = value_type&;
    using pointer = value_type*;
    using difference_type = std::ptrdiff_t;

    struct iterator {
      private:
        std::shared_ptr<contact_info> _val;
        dict::const_iterator _it;
        const dict* _contacts;
        void _load_info();
        iterator(const dict* contacts) : _contacts{contacts} {
            if (_contacts) {
                _it = _contacts->begin();
                _load_info();
            }
        }
        friend class Contacts;

      public:
        bool operator==(const iterator& other) const;
        bool operator!=(const iterator& other) const { return !(*this == other); }
        bool done() const;  // Equivalent to comparing against the end iterator
        contact_info& operator*() const { return *_val; }
        contact_info* operator->() const { return _val.get(); }
        iterator& operator++();
        iterator operator++(int) {
            auto copy{*this};
            ++*this;
            return copy;
        }
    };
};

}  // namespace session::config
