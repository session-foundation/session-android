#pragma once

#include <chrono>
#include <cstddef>
#include <iterator>
#include <memory>
#include <session/config.hpp>

#include "base.hpp"
#include "community.hpp"

using namespace std::literals;

extern "C" {
struct convo_info_volatile_1to1;
struct convo_info_volatile_community;
struct convo_info_volatile_group;
struct convo_info_volatile_legacy_group;
}

namespace session::config {

class ConvoInfoVolatile;
class val_loader;

/// keys used in this config, either currently or in the past (so that we don't reuse):
///
/// Note that this is a high-frequency object, intended only for properties that change frequently (
/// (currently just the read timestamp for each conversation).
///
/// 1 - dict of one-to-one conversations.  Each key is the Session ID of the contact (in hex).
///     Values are dicts with keys:
///     r - the unix timestamp (in integer milliseconds) of the last-read message.  Always
///         included, but will be 0 if no messages are read.
///     u - will be present and set to 1 if this conversation is specifically marked unread.
///
/// o - community conversations.  This is a nested dict where the outer keys are the BASE_URL of the
///     community and the outer value is a dict containing:
///     - `#` -- the 32-byte server pubkey
///     - `R` -- dict of rooms on the server; each key is the lower-case room name, value is a dict
///       containing keys:
///       r - the unix timestamp (in integer milliseconds) of the last-read message.  Always
///           included, but will be 0 if no messages are read.
///       u - will be present and set to 1 if this conversation is specifically marked unread.
///
/// g - group conversations (aka new, non-legacy closed groups).  The key is the group identifier
///     (beginning with 03).  Values are dicts with keys:
///     r - the unix timestamp (in integer milliseconds) of the last-read message.  Always
///         included, but will be 0 if no messages are read.
///     u - will be present and set to 1 if this conversation is specifically marked unread.
///
/// C - legacy group conversations (aka closed groups).  The key is the group identifier (which
///     looks indistinguishable from a Session ID, but isn't really a proper Session ID).  Values
///     are dicts with keys:
///     r - the unix timestamp (integer milliseconds) of the last-read message.  Always included,
///         but will be 0 if no messages are read.
///     u - will be present and set to 1 if this conversation is specifically marked unread.

namespace convo {

    struct base {
        int64_t last_read = 0;
        bool unread = false;

      protected:
        void load(const dict& info_dict);
        friend class session::config::val_loader;
        friend class session::config::ConvoInfoVolatile;
    };

    struct one_to_one : base {
        std::string session_id;  // in hex

        /// API: convo_info_volatile/one_to_one::one_to_one
        ///
        /// Constructs an empty one_to_one from a session_id.  Session ID can be either bytes (33)
        /// or hex (66).
        ///
        /// Declaration:
        /// ```cpp
        /// explicit one_to_one(std::string&& session_id);
        /// explicit one_to_one(std::string_view session_id);
        /// ```
        ///
        /// Inputs:
        /// - `session_id` -- Hex string of the session id
        explicit one_to_one(std::string&& session_id);
        explicit one_to_one(std::string_view session_id);

        // Internal ctor/method for C API implementations:
        one_to_one(const struct convo_info_volatile_1to1& c);  // From c struct
        void into(convo_info_volatile_1to1& c) const;          // Into c struct
    };

    struct community : config::community, base {

        using config::community::community;

        /// API: convo_info_volatile/community::community
        ///
        /// Internal ctor/method for C API implementations:
        ///
        /// Inputs:
        /// - `c` -- From  c struct
        community(const convo_info_volatile_community& c);  // From c struct
        void into(convo_info_volatile_community& c) const;  // Into c struct

        friend class session::config::ConvoInfoVolatile;
        friend struct session::config::comm_iterator_helper;
    };

    struct group : base {
        std::string id;  // 66 hex digits starting with "03"

        /// API: convo_info_volatile/group::group
        ///
        /// Constructs an empty group from an id
        ///
        /// Inputs:
        /// - `group_id` -- hex string of group_id, 66 hex bytes starting with "03"
        explicit group(std::string&& group_id);
        explicit group(std::string_view group_id);

        // Internal ctor/method for C API implementations:
        group(const struct convo_info_volatile_group& c);  // From c struct
        void into(convo_info_volatile_group& c) const;     // Into c struct
    };

    struct legacy_group : base {
        std::string id;  // in hex, indistinguishable from a Session ID

        /// API: convo_info_volatile/legacy_group::legacy_group
        ///
        /// Constructs an empty legacy_group from a quasi-session_id
        ///
        /// Declaration:
        /// ```cpp
        /// explicit legacy_group(std::string&& group_id);
        /// explicit legacy_group(std::string_view group_id);
        /// ```
        ///
        /// Inputs:
        /// - `group_id` -- hex string of group_id, similar to a session_id
        explicit legacy_group(std::string&& group_id);
        explicit legacy_group(std::string_view group_id);

        // Internal ctor/method for C API implementations:
        legacy_group(const struct convo_info_volatile_legacy_group& c);  // From c struct
        void into(convo_info_volatile_legacy_group& c) const;            // Into c struct
    };

    using any = std::variant<one_to_one, community, group, legacy_group>;
}  // namespace convo

class ConvoInfoVolatile : public ConfigBase {

  public:
    // No default constructor
    ConvoInfoVolatile() = delete;

    /// API: convo_info_volatile/ConvoInfoVolatile::ConvoInfoVolatile
    ///
    /// Constructs a conversation list from existing data (stored from `dump()`) and the user's
    /// secret key for generating the data encryption key.  To construct a blank list (i.e. with no
    /// pre-existing dumped data to load) pass `std::nullopt` as the second argument.
    ///
    /// Inputs:
    /// - `ed25519_secretkey` -- contains the libsodium secret key used to encrypt/decrypt the
    /// data when pushing/pulling from the swarm.  This can either be the full 64-byte value (which
    /// is technically the 32-byte seed followed by the 32-byte pubkey), or just the 32-byte seed of
    /// the secret key.
    /// - `dumped` -- either `std::nullopt` to construct a new, empty object; or binary state data
    /// that was previously dumped from an instance of this class by calling `dump()`.
    ConvoInfoVolatile(ustring_view ed25519_secretkey, std::optional<ustring_view> dumped);

    /// API: convo_info_volatile/ConvoInfoVolatile::storage_namespace
    ///
    /// Returns the ConvoInfoVolatile namespace. Is constant, will always return 4
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `Namespace` - Will return 4
    Namespace storage_namespace() const override { return Namespace::ConvoInfoVolatile; }

    /// API: convo_info_volatile/ConvoInfoVolatile::encryption_domain
    ///
    /// Returns the domain. Is constant, will always return "ConvoInfoVolatile"
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `const char*` - Will return "ConvoInfoVolatile"
    const char* encryption_domain() const override { return "ConvoInfoVolatile"; }

    /// Our pruning ages.  We ignore added conversations that are more than PRUNE_LOW before now,
    /// and we actively remove (when doing a new push) any conversations that are more than
    /// PRUNE_HIGH before now.  Clients can mostly ignore these and just add all conversations; the
    /// class just transparently ignores (or removes) pruned values.
    static constexpr auto PRUNE_LOW = 30 * 24h;
    static constexpr auto PRUNE_HIGH = 45 * 24h;

    /// API: convo_info_volatile/ConvoInfoVolatile::prune_stale
    ///
    /// Prunes any "stale" conversations: that is, ones with a last read more than `prune` ago that
    /// are not specifically "marked as unread" by the client.
    ///
    /// This method is called automatically by `push()` and does not typically need to be invoked
    /// directly.
    ///
    /// Inputs:
    /// - `prune` the "too old" time; any conversations with a last_read time more than this
    ///   duration ago will be removed (unless they have the explicit `unread` flag set).  If
    ///   omitted, defaults to the PRUNE_HIGH constant (45 days).
    ///
    /// Outputs:
    /// - returns nothing.
    void prune_stale(std::chrono::milliseconds prune = PRUNE_HIGH);

    /// API: convo_info_volatile/ConvoInfoVolatile::push
    ///
    /// Overrides push() to prune stale last-read values before we do the push.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::tuple<seqno_t, ustring, std::vector<std::string>>` - Returns a tuple containing
    ///   - `seqno_t` -- sequence number
    ///   - `ustring` -- data message to push to the server
    ///   - `std::vector<std::string>` -- list of known message hashes
    std::tuple<seqno_t, ustring, std::vector<std::string>> push() override;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_1to1
    ///
    /// Looks up and returns a contact by session ID (hex).  Returns nullopt if the session ID was
    /// not found, otherwise returns a filled out `convo::one_to_one`.
    ///
    /// Inputs:
    /// - `session_id` -- Hex string of the Session ID
    ///
    /// Outputs:
    /// - `std::optional<convo::one_to_one>` - Returns a contact
    std::optional<convo::one_to_one> get_1to1(std::string_view session_id) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_community
    ///
    /// Looks up and returns a community conversation.  Takes the base URL and room name (case
    /// insensitive).  Retuns nullopt if the community was not found, otherwise a filled out
    /// `convo::community`.
    ///
    /// Inputs:
    /// - `base_url` -- String of the base URL
    /// - `room` -- String of the room
    ///
    /// Outputs:
    /// - `std::optional<convo::community>` - Returns a community
    std::optional<convo::community> get_community(
            std::string_view base_url, std::string_view room) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_community(partial_url)
    ///
    /// Shortcut for calling community::parse_partial_url then calling the above with the base url
    /// and room.  The URL is not required to contain the pubkey (if present it will be ignored).
    ///
    /// Inputs:
    /// - `partial_url` -- String of the partial URL
    ///
    /// Outputs:
    /// - `std::optional<convo::community>` - Returns a community
    std::optional<convo::community> get_community(std::string_view partial_url) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_group
    ///
    /// Looks up and returns a group conversation by ID.  The ID is a 66-character hex string
    /// beginning with "03".  Returns nullopt if there is no record of the group conversation.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- Hex string of the group ID
    ///
    /// Outputs:
    /// - `std::optional<convo::group>` - Returns a group
    std::optional<convo::group> get_group(std::string_view pubkey_hex) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_legacy_group
    ///
    /// Looks up and returns a legacy group conversation by ID.  The ID looks like a hex Session ID,
    /// but isn't really a Session ID.  Returns nullopt if there is no record of the group
    /// conversation.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- Hex string of the legacy group Session ID
    ///
    /// Outputs:
    /// - `std::optional<convo::legacy_group>` - Returns a group
    std::optional<convo::legacy_group> get_legacy_group(std::string_view pubkey_hex) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_or_construct_1to1
    ///
    /// These are the same as the above `get` methods (without "_or_construct" in the name), except
    /// that when the conversation doesn't exist a new one is created, prefilled with the
    /// pubkey/url/etc.
    ///
    /// Inputs:
    /// - `session_id` -- Hex string Session ID
    ///
    /// Outputs:
    /// - `convo::one_to_one` - Returns a contact
    convo::one_to_one get_or_construct_1to1(std::string_view session_id) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_or_construct_group
    ///
    /// These are the same as the above `get` methods (without "_or_construct" in the name), except
    /// that when the conversation doesn't exist a new one is created, prefilled with the
    /// pubkey/url/etc.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- Hex string pubkey
    ///
    /// Outputs:
    /// - `convo::group` - Returns a group
    convo::group get_or_construct_group(std::string_view pubkey_hex) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_or_construct_legacy_group
    ///
    /// These are the same as the above `get` methods (without "_or_construct" in the name), except
    /// that when the conversation doesn't exist a new one is created, prefilled with the
    /// pubkey/url/etc.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- Hex string pubkey
    ///
    /// Outputs:
    /// - `convo::legacy_group` - Returns a group
    convo::legacy_group get_or_construct_legacy_group(std::string_view pubkey_hex) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_or_construct_community
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
    /// Declaration:
    /// ```cpp
    /// convo::community get_or_construct_community(
    ///         std::string_view base_url, std::string_view room, std::string_view pubkey_hex)
    ///         const;
    /// convo::community get_or_construct_community(
    ///         std::string_view base_url, std::string_view room, ustring_view pubkey) const;
    /// ```
    ///
    /// Inputs:
    /// - `base_url` -- String of the base URL
    /// - `room` -- String of the room
    /// - `pubkey` -- Pubkey either as a hex string or binary
    ///
    /// Outputs:
    /// - `convo::community` - Returns a group
    convo::community get_or_construct_community(
            std::string_view base_url, std::string_view room, std::string_view pubkey_hex) const;
    convo::community get_or_construct_community(
            std::string_view base_url, std::string_view room, ustring_view pubkey) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::get_or_construct_community(full_url)
    ///
    /// Shortcut for calling community::parse_full_url then calling the above
    /// get_or_construct_community`
    ///
    /// Inputs:
    /// - `full_url` -- String of the full URL
    ///
    /// Outputs:
    /// - `convo::community` - Returns a group
    convo::community get_or_construct_community(std::string_view full_url) const;

    /// API: convo_info_volatile/ConvoInfoVolatile::set
    ///
    /// Inserts or replaces existing conversation info.  For example, to update a 1-to-1
    /// conversation last read time you would do:
    ///
    /// ```cpp
    ///     auto info = conversations.get_or_construct_1to1(some_session_id);
    ///     info.last_read = new_unix_timestamp;
    ///     conversations.set(info);
    /// ```
    ///
    /// Declaration:
    /// ```cpp
    /// void set(const convo::one_to_one& c);
    /// void set(const convo::group& c);
    /// void set(const convo::legacy_group& c);
    /// void set(const convo::community& c);
    /// void set(const convo::any& c);  // Variant which can be any of the above
    /// ```
    ///
    /// Inputs:
    /// - `c` -- struct containing any contact, community or group
    void set(const convo::one_to_one& c);
    void set(const convo::legacy_group& c);
    void set(const convo::group& c);
    void set(const convo::community& c);
    void set(const convo::any& c);  // Variant which can be any of the above

  protected:
    void set_base(const convo::base& c, DictFieldProxy& info);

    // Drills into the nested dicts to access community details; if the second argument is
    // non-nullptr then it will be set to the community's pubkey, if it exists.
    DictFieldProxy community_field(
            const convo::community& og, ustring_view* get_pubkey = nullptr) const;

  public:
    /// API: convo_info_volatile/ConvoInfoVolatile::erase_1to1
    ///
    /// Removes a one-to-one conversation.  Returns true if found and removed, false if not present.
    ///
    /// Inputs:
    /// - `pubkey` -- hex session id
    ///
    /// Outputs:
    /// - `bool` - Returns true if found and removed, otherwise false
    bool erase_1to1(std::string_view pubkey);

    /// API: convo_info_volatile/ConvoInfoVolatile::erase_community
    ///
    /// Removes a community conversation record.  Returns true if found and removed, false if not
    /// present.  Arguments are the same as `get_community`.
    ///
    /// Inputs:
    /// - `base_url` -- String of the base URL
    /// - `room` -- String of the room
    ///
    /// Outputs:
    /// - `bool` - Returns true if found and removed, otherwise false
    bool erase_community(std::string_view base_url, std::string_view room);

    /// API: convo_info_volatile/ConvoInfoVolatile::erase_group
    ///
    /// Removes a group conversation.  Returns true if found and removed, false if not present.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- String of the group pubkey
    ///
    /// Outputs:
    /// - `bool` - Returns true if found and removed, otherwise false
    bool erase_group(std::string_view pubkey_hex);

    /// API: convo_info_volatile/ConvoInfoVolatile::erase_legacy_group
    ///
    /// Removes a legacy group conversation.  Returns true if found and removed, false if not
    /// present.
    ///
    /// Inputs:
    /// - `pubkey_hex` -- String of the legacy groups pubkey
    ///
    /// Outputs:
    /// - `bool` - Returns true if found and removed, otherwise false
    bool erase_legacy_group(std::string_view pubkey_hex);

    /// API: convo_info_volatile/ConvoInfoVolatile::erase
    ///
    /// Removes a conversation taking the convo::whatever record (rather than the pubkey/url).
    ///
    /// Declaration:
    /// ```cpp
    /// bool erase(const convo::one_to_one& c);
    /// bool erase(const convo::community& c);
    /// bool erase(const convo::legacy_group& c);
    /// bool erase(const convo::any& c);  // Variant of any of them
    /// ```
    ///
    /// Inputs:
    /// - `c` -- Any contact, group or community
    ///
    /// Outputs:
    /// - `bool` - Returns true if found and removed, otherwise false
    bool erase(const convo::one_to_one& c);
    bool erase(const convo::community& c);
    bool erase(const convo::group& c);
    bool erase(const convo::legacy_group& c);

    bool erase(const convo::any& c);  // Variant of any of them

    /// API: convo_info_volatile/ConvoInfoVolatile::size
    ///
    /// Returns the number of conversations, if `size()` is called it will return the total of any
    /// conversations. Utilising the other functions to get specific counts of 1-to-1, community and
    /// legacy group converstations.
    ///
    /// Declaration:
    /// ```cpp
    /// size_t size() const;
    /// size_t size_1to1() const;
    /// size_t size_communities() const;
    /// size_t size_groups() const;
    /// size_t size_legacy_groups() const;
    /// ```
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `size_t` - Returns the number of conversations
    size_t size() const;

    /// Returns the number of 1-to-1, community, group, and legacy group conversations,
    /// respectively.
    size_t size_1to1() const;
    size_t size_communities() const;
    size_t size_groups() const;
    size_t size_legacy_groups() const;

    /// API: convo_info_volatile/ConvoInfoVolatile::empty
    ///
    /// Returns true if the conversation list is empty.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `bool` -- Returns true if the convesation list is empty
    bool empty() const { return size() == 0; }

    bool accepts_protobuf() const override { return true; }

    struct iterator;
    /// API: convo_info_volatile/ConvoInfoVolatile::begin
    ///
    /// Iterators for iterating through all conversations.  Typically you access this implicit via a
    /// for loop over the `ConvoInfoVolatile` object:
    ///
    /// ```cpp
    ///     for (auto& convo : conversations) {
    ///         if (const auto* dm = std::get_if<convo::one_to_one>(&convo)) {
    ///             // use dm->session_id, dm->last_read, etc.
    ///         } else if (const auto* og = std::get_if<convo::community>(&convo)) {
    ///             // use og->base_url, og->room, om->last_read, etc.
    ///         } else if (const auto* cg = std::get_if<convo::group>(&convo)) {
    ///             // use cg->id, cg->last_read
    ///         } else if (const auto* lcg = std::get_if<convo::legacy_group>(&convo)) {
    ///             // use lcg->id, lcg->last_read
    ///         }
    ///     }
    /// ```
    ///
    /// This iterates through all conversations in sorted order (sorted first by convo type, then by
    /// id within the type).
    ///
    /// The `begin_TYPE()` versions of the iterator return an iterator that loops only through the
    /// given `TYPE` of conversations.  (The .end() iterator works for all the iterator variations).
    ///
    /// It is NOT permitted to add/modify/remove records while iterating; performing modifications
    /// based on a condition requires two passes: one to collect the required changes, and another
    /// to apply them key by key.
    ///
    /// Declaration:
    /// ```cpp
    /// iterator begin() const;
    /// subtype_iterator<convo::one_to_one> begin_1to1() const;
    /// subtype_iterator<convo::community> begin_communities() const;
    /// subtype_iterator<convo::group> begin_groups() const;
    /// subtype_iterator<convo::legacy_group> begin_legacy_groups() const;
    /// ```
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `iterator` - Returns an iterator for the beginning of the contacts
    iterator begin() const { return iterator{data}; }

    /// API: convo_info_volatile/ConvoInfoVolatile::end
    ///
    /// Iterator for passing the end of the conversations.  This works for both the all-convo
    /// iterator (`begin()`) and the type-specific iterators (e.g. `begin_groups()`).
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `iterator` - Returns an iterator for the end of the conversations
    iterator end() const { return iterator{}; }

    template <typename ConvoType>
    struct subtype_iterator;

    /// Returns an iterator that iterates only through one type of conversations
    subtype_iterator<convo::one_to_one> begin_1to1() const { return {data}; }
    subtype_iterator<convo::community> begin_communities() const { return {data}; }
    subtype_iterator<convo::group> begin_groups() const { return {data}; }
    subtype_iterator<convo::legacy_group> begin_legacy_groups() const { return {data}; }

    using iterator_category = std::input_iterator_tag;
    using value_type =
            std::variant<convo::one_to_one, convo::community, convo::group, convo::legacy_group>;
    using reference = value_type&;
    using pointer = value_type*;
    using difference_type = std::ptrdiff_t;

    struct iterator {
      protected:
        std::shared_ptr<convo::any> _val;
        std::optional<dict::const_iterator> _it_11, _end_11, _it_group, _end_group, _it_lgroup,
                _end_lgroup;
        std::optional<comm_iterator_helper> _it_comm;
        void _load_val();
        iterator() = default;  // Constructs an end tombstone
        iterator(
                const DictFieldRoot& data,
                bool oneto1,
                bool communities,
                bool groups,
                bool legacy_groups);
        explicit iterator(const DictFieldRoot& data) : iterator(data, true, true, true, true) {}
        friend class ConvoInfoVolatile;

      public:
        bool operator==(const iterator& other) const;
        bool operator!=(const iterator& other) const { return !(*this == other); }
        bool done() const;  // Equivalent to comparing against the end iterator
        convo::any& operator*() const { return *_val; }
        convo::any* operator->() const { return _val.get(); }
        iterator& operator++();
        iterator operator++(int) {
            auto copy{*this};
            ++*this;
            return copy;
        }
    };

    template <typename ConvoType>
    struct subtype_iterator : iterator {
      protected:
        subtype_iterator(const DictFieldRoot& data) :
                iterator(
                        data,
                        std::is_same_v<convo::one_to_one, ConvoType>,
                        std::is_same_v<convo::community, ConvoType>,
                        std::is_same_v<convo::group, ConvoType>,
                        std::is_same_v<convo::legacy_group, ConvoType>) {}
        friend class ConvoInfoVolatile;

      public:
        ConvoType& operator*() const { return std::get<ConvoType>(*_val); }
        ConvoType* operator->() const { return &std::get<ConvoType>(*_val); }
        subtype_iterator& operator++() {
            iterator::operator++();
            return *this;
        }
        subtype_iterator operator++(int) {
            auto copy{*this};
            ++*this;
            return copy;
        }
    };
};

}  // namespace session::config
