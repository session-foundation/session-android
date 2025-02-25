#pragma once

#include <chrono>
#include <memory>
#include <unordered_set>

#include "../../config.hpp"
#include "../base.hpp"
#include "../namespaces.hpp"
#include "../profile_pic.hpp"
#include "members.hpp"

namespace session::config::groups {

class Members;
class Info;

using namespace std::literals;

/// This "config" isn't exactly a regular config type that inherits from ConfigBase; in particular:
/// - it doesn't encrypt the message (but merely contains encrypted elements within it)
/// - it doesn't merge
/// - it does have a concept analogous to the message seqno
/// - conflict resolution involves regenerating and distributing new keys; nothing gets merged.
/// - it cares strongly about when new configs were pushed (configs expire after having been
///   replaced for a certain amount of time, not by being updated).
/// - its internal state isn't fully serialized when pushing updates
/// - messages don't contain the outer layer of config messages (where config metadata, references
///   to other objects, etc.) that ConfigBase-derived type hold.
/// - it isn't compressed (since most of the data fields are encrypted or random, compression
///   reduction would be minimal).
///
/// Fields used (in ascii order):
/// # -- 24-byte nonce used for all the encrypted values in this message; required.
///
/// For non-supplemental messages:
///
/// G -- monotonically incrementing counter identifying key generation changes
/// K -- encrypted copy of the key for admins (omitted for `+` incremental key messages)
/// k -- packed bytes of encrypted keys for non-admin members; this is a single byte string in which
///      each 48 bytes is a separate encrypted value.
///
/// For supplemental messages:
/// + -- encrypted supplemental key info list; this is a list of encrypted values, encrypted for
///      each member to whom keys are being disclosed.  The *decrypted* value of these entries are
///      the same value (encrypted separately for each member) which is a bt-encoded list of dicts
///      where each dict contains keys:
///      - g -- the key generation
///      - k -- the key itself (32 bytes).
///      - t -- the storage timestamp of the key (so that recipients know when keys expire)
/// G -- the maximum generation of the keys included in this message; this is used to track when
///      this message can be allowed to expire.
///
/// And finally, for both types:
///
/// ~ -- signature of the message signed by the group's master keypair, signing the message value up
///      to but not including the ~ keypair.  The signature must be the last key in the dict (thus
///      `~` since it is the largest 7-bit ascii character value).  Note that this signature
///      mechanism works exactly the same as the signature on regular config messages.
///
/// Some extra details:
///
/// - each copy of the encryption key uses xchacha20_poly1305 using the `#` nonce
/// - the `k` members list gets padded with junk entries up to the next multiple of 75 (for
///   non-supplemental messages).
/// - the decryption key for the admin version of the key is H(admin_seed,
///   key="SessionGroupKeyAdmin")
/// - the encryption key for a member is H(a'B || A' || B, key="SessionGroupKeyMember") where a'/A'
///   is the group Ed25519 master key converted to X25519, and b/B is the member's X25519 keypair
///   (i.e. B is the non-05-prefixed session_id).
///   - the decryption key is calculated by the member using `bA' || A' || B`
/// - A new key and nonce is created from a 56-byte H(M0 || M1 || ... || Mn || g || S,
///   key="SessionGroupKeyGen"), where S = H(group_seed, key="SessionGroupKeySeed").

class Keys : public ConfigSig {

    Ed25519Secret user_ed25519_sk;

    struct key_info {
        std::array<unsigned char, 32> key;
        std::chrono::system_clock::time_point timestamp;  // millisecond precision
        int64_t generation;

        auto cmpval() const { return std::tie(generation, timestamp, key); }
        bool operator<(const key_info& b) const { return cmpval() < b.cmpval(); }
        bool operator>(const key_info& b) const { return cmpval() > b.cmpval(); }
        bool operator<=(const key_info& b) const { return cmpval() <= b.cmpval(); }
        bool operator>=(const key_info& b) const { return cmpval() >= b.cmpval(); }
        bool operator==(const key_info& b) const { return cmpval() == b.cmpval(); }
        bool operator!=(const key_info& b) const { return cmpval() != b.cmpval(); }
    };

    /// Vector of keys that is kept sorted by generation/timestamp/key.  This gets pruned as keys
    /// have been superceded by another key for a sufficient amount of time (see KEY_EXPIRY).
    sodium_vector<key_info> keys_;

    /// Hashes of messages we have successfully parsed; used for deciding what needs to be renewed.
    std::map<int64_t, std::unordered_set<std::string>> active_msgs_;

    sodium_cleared<std::array<unsigned char, 32>> pending_key_;
    sodium_vector<unsigned char> pending_key_config_;
    int64_t pending_gen_ = -1;

    bool needs_dump_ = false;

    ConfigMessage::verify_callable verifier_;
    ConfigMessage::sign_callable signer_;

    void set_verifier(ConfigMessage::verify_callable v) override { verifier_ = std::move(v); }
    void set_signer(ConfigMessage::sign_callable s) override { signer_ = std::move(s); }

    ustring sign(ustring_view data) const;

    // Checks for and drops expired keys.
    void remove_expired();

    // Loads existing state from a previous dump of keys data
    void load_dump(ustring_view dump);

    // Inserts a key into the correct place in `keys_`.
    void insert_key(std::string_view message_hash, key_info&& key);

    // Returned the blinding factor for a given session X25519 pubkey.  This depends on the group's
    // seed and thus is only obtainable by an admin account.
    std::array<unsigned char, 32> subaccount_blind_factor(
            const std::array<unsigned char, 32>& session_xpk) const;

  public:
    /// The multiple of members keys we include in the message; we add junk entries to the key list
    /// to reach a multiple of this.  75 is chosen because it's a decently large human-round number
    /// that should still fit within 4kiB page size on the storage server (allowing for some extra
    /// row field storage).
    static constexpr int MESSAGE_KEY_MULTIPLE = 75;

    // 75 because:
    // 2              // for the 'de' delimiters of the outer dict
    // + 3 + 2 + 12   // for the `1:g` and `iNNNNNNNNNNe` generation keypair
    // + 3 + 3 + 24   // for the `1:n`, `24:`, and 24 byte nonce
    // + 3 + 3 + 48   // for the `1:K`, `48:`, and 48 byte ciphertexted key
    // + 3 + 6        // for the `1:k` and `NNNNN:` key and prefix of the keys pair
    // + N * 48       // for the packed encryption keys
    // + 3 + 3 + 64;  // for the `1:~` and `64:` and 64 byte signature
    // = 177 + 48N
    //
    // and N=75 puts us a little bit under 4kiB (which is sqlite's default page size).

    /// A key expires when it has been surpassed by another key for at least this amount of time.
    /// We default this to double the 30 days that we strictly need to avoid race conditions with
    /// 30-day old config messages that might need the key for a client that is only very rarely
    /// online.
    static constexpr auto KEY_EXPIRY = 2 * 30 * 24h;

    /// The maximum uncompressed message size we allow in message decryption/encryption.
    static constexpr size_t MAX_PLAINTEXT_MESSAGE_SIZE = 1'000'000;

    // No default constructor
    Keys() = delete;

    /// API: groups/Keys::Keys
    ///
    /// Constructs a group members config object from existing data (stored from `dump()`) and a
    /// list of encryption keys for encrypting new and decrypting existing messages.
    ///
    /// To construct a blank info object (i.e. with no pre-existing dumped data to load) pass
    /// `std::nullopt` as the last argument.
    ///
    /// When no dump is provided the initial Keys object will be created with no keys loaded at all,
    /// these will be loaded later into this and the info/members objects when loading keys via
    /// received config messages. If this is a brand new group then rekey() MUST be called,
    /// otherwise the group will be in an invalid state.
    ///
    /// Inputs:
    /// - `user_ed25519_secretkey` is the ed25519 secret key backing the current user's session ID,
    ///   and is used to decrypt incoming keys.  It is required.
    /// - `group_ed25519_pubkey` is the public key of the group, used to verify message signatures
    ///   on key updates.  Required.  Should not include the `03` prefix.
    /// - `group_ed25519_secretkey` is the secret key of the group, used to encrypt, decrypt, and
    ///   sign config messages.  This is only possessed by the group admin(s), and must be provided
    ///   in order to make and push config changes.
    /// - `dumped` -- either `std::nullopt` to construct a new, empty object; or binary state data
    ///   that was previously dumped from an instance of this class by calling `dump()`.
    /// - `info` and `members` -- will be loaded with the group keys, if present in the dump.
    Keys(ustring_view user_ed25519_secretkey,
         ustring_view group_ed25519_pubkey,
         std::optional<ustring_view> group_ed25519_secretkey,
         std::optional<ustring_view> dumped,
         Info& info,
         Members& members);

    /// API: groups/Keys::storage_namespace
    ///
    /// Returns the Keys namespace. Is constant, will always return Namespace::GroupKeys
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `Namespace` - Will return Namespace::GroupKeys
    Namespace storage_namespace() const { return Namespace::GroupKeys; }

    /// API: groups/Keys::encryption_domain
    ///
    /// Returns the encryption domain used when encrypting messages of this type.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `const char*` - Will return "groups::Keys"
    const char* encryption_domain() const { return "groups::Keys"; }

    /// API: groups/Keys::group_keys
    ///
    /// Returns all the unexpired decryption keys that we know about.  Keys are returned ordered
    /// from most-recent to least-recent (and so the first one is meant to be used as the encryption
    /// key), including a pending key if this object is in the process of pushing a new keys
    /// message.
    ///
    /// This isn't typically directly needed: this object manages the key lists in the `info` and
    /// `members` objects itself.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `std::vector<ustring_view>` - vector of encryption keys.
    std::vector<ustring_view> group_keys() const;

    /// API: groups/Keys::size
    ///
    /// Returns the number of distinct decryption keys that we know about.  Mainly for
    /// debugging/information purposes.
    ///
    /// Inputs: none
    ///
    /// Outputs:
    /// - `size_t` of the number of keys we know about
    size_t size() const;

    /// API: groups/Keys::encryption_key
    ///
    /// Accesses the current encryption key: that is, the most current group decryption key.  Throws
    /// if there are no encryption keys at all.  (This is essentially the same as `group_keys()[0]`,
    /// except for the throwing and avoiding needing to constructor a vector).
    ///
    /// You normally don't need to call this; the key is used automatically by methods such as
    /// encrypt_message() that need it.
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `ustring_view` of the most current group encryption key.
    ustring_view group_enc_key() const;

    /// API: groups/Keys::is_admin
    ///
    /// True if we have admin permissions (i.e. we know the group's master secret key).
    ///
    /// Inputs: none.
    ///
    /// Outputs:
    /// - `true` if this object knows the group's master key
    bool admin() const { return _sign_sk && _sign_pk; }

    /// API: groups/Keys::load_admin_key
    ///
    /// Loads the group secret key into the Keys object (as well as passing it along to the Info and
    /// Members objects).
    ///
    /// The primary use of this is when accepting a promotion-to-admin: the Keys object would be
    /// constructed as a regular member (without the admin key) then this method "upgrades" the
    /// object with the group signing key.
    ///
    /// This will do nothing if the secret key is already known; it will throw if
    /// the given secret key does not yield the group's public key.  The given key can be either the
    /// 32 byte seed, or the libsodium 64 byte "secret key" (which is just the seed and cached
    /// public key stuck together).
    ///
    /// Inputs:
    /// - `secret` -- the group's 64-byte secret key or 32-byte seed
    /// - `info` and `members` -- will be loaded with the group keys if the key is loaded
    ///   successfully.
    ///
    /// Outputs: nothing.  After a successful call, `admin()` will return true.  Throws if the given
    /// secret key does not match the group's pubkey.
    void load_admin_key(ustring_view secret, Info& info, Members& members);

    /// API: groups/Keys::rekey
    ///
    /// Generate a new encryption key for the group and returns an encrypted key message to be
    /// pushed to the swarm containing the key, encrypted for the members of the given
    /// config::groups::Members object.  This can only be done by an admin account (i.e. we must
    /// have the group's private key).
    ///
    /// This method is intended to be called in these situations:
    /// - potentially after loading new keys config messages (see `needs_rekey()`)
    /// - when removing a member to switch to a new encryption key for the group that excludes that
    ///   member.
    /// - when adding a member *and* switching to a new encryption key (without making the old key
    ///   available to the member) so that the new member cannot decipher pre-existing configs and
    ///   messages.
    ///
    /// This method is closely coupled to the group's Info and Members configs: it updates their
    /// encryption keys and sets them as dirty, requiring a re-push to re-encrypt each of them.
    /// Typically a rekey is performed as follows:
    ///
    /// - `rekey()` is called, returning the new keys config.
    /// - `info.push()` is called to get the new info config (re-encrypted with the new key)
    /// - `members.push()` is called to get the new members config (using the new key)
    /// - all three new configs are pushed (ideally all at once, in a single batch request).
    ///
    /// Inputs:
    /// - `Info` - the group's Info; it will be dirtied after the rekey and will require a push.
    /// - `Members` - the current Members config for the group.  When removing one or more members
    ///   this should be the list of members with the specific members already removed.  The members
    ///   config will be dirtied after the rekey and will require a push.
    ///
    /// Outputs:
    /// - `ustring_view` containing the data that needs to be pushed to the config keys namespace
    ///   for the group.  (This can be re-obtained from `pending_config()` if needed until it has
    ///   been confirmed or superceded).  This data must be consumed or copied from the returned
    ///   string_view immediately: it will not be valid past other calls on the Keys config object.
    ustring_view rekey(Info& info, Members& members);

    /// API: groups/Keys::key_supplement
    ///
    /// Generates a supplemental key message for one or more session IDs.  This is used to
    /// distribute existing active keys to a new member so that that member can access existing
    /// keys, configs, and messages.  Only admins can call this.
    ///
    /// The recommended order of operations for adding such a member is:
    /// - add the member to Members
    /// - generate the key supplement
    /// - push new members & key supplement (ideally in a batch)
    /// - send invite details, auth signature, etc. to the new user
    ///
    /// To add a member *without* giving them access you would use rekey() instead of this method.
    ///
    /// Inputs:
    /// - `sid` or `sids` -- session ID(s) of the members to generate a supplemental key for (there
    ///   are two versions of this function, one taking a single ID and one taking a vector).
    ///   Session IDs are specified in hex.
    ///
    /// Outputs:
    /// - `ustring` containing the message that should be pushed to the swarm containing encrypted
    ///   keys for the given user(s).
    ustring key_supplement(const std::vector<std::string>& sids) const;
    ustring key_supplement(std::string sid) const {
        return key_supplement(std::vector{{std::move(sid)}});
    }

    /// API: groups/current_generation
    ///
    /// Returns the current generation number for the latest keys message.
    ///
    /// Oututs:
    /// - `int` -- latest keys generation number.
    int current_generation() const { return keys_.empty() ? 0 : keys_.back().generation; }

    /// API: groups/Keys::swarm_make_subaccount
    ///
    /// Constructs a swarm subaccount signing value that a member can use to access messages in the
    /// swarm.  Requires group admins keys.
    ///
    /// Inputs:
    /// - `session_id` -- the session ID of the member (in hex)
    /// - `write` -- if true (which is the default if omitted) then the member shall be allowed to
    ///   submit messages into the group account of the swarm and extend (but not shorten) the
    ///   expiry of messages in the group account.  If false then the user can only retrieve
    ///   messages.
    /// - `del` -- if true (default is false) then the user shall be allowed to delete messages
    ///   from the swarm.  This permission can be used to appoint a sort of "moderator" who can
    ///   delete messages without having the full admin group keys.
    ///
    /// Outputs:
    /// - `ustring` -- contains a subaccount swarm signing value; this can be passed (by the user)
    ///   into `swarm_subaccount_sign` to sign a value suitable for swarm authentication.
    ///   (Internally this packs the flags, blinding factor, and group admin signature together and
    ///   will be 4 + 32 + 64 = 100 bytes long).
    ///
    ///   This value must be provided to the user so that they can authentication.  The user should
    ///   call `swarm_verify_subaccount` to verify that the signing value was indeed signed by a
    ///   group admin before using/storing it.
    ///
    ///   The signing value produced will be the same (for a given `session_id`/`write`/`del`
    ///   values) when constructed by any admin of the group.
    ustring swarm_make_subaccount(
            std::string_view session_id, bool write = true, bool del = false) const;

    /// API: groups/Keys::swarm_verify_subaccount
    ///
    /// Verifies that a received subaccount signing value (allegedly produced by
    /// swarm_make_subaccount) is a valid subaccount signing value for the given group pubkey,
    /// including a proper signature by an admin of the group.  The signing value must have read
    /// permission, but parameters can be given to also require write or delete permissions.  A
    /// subaccount signing value should always be checked for validity using this before creating a
    /// group that would depend on it.
    ///
    /// There are two versions of this function: a static one callable without having a Keys
    /// instance that takes the group id and user's session Ed25519 secret key as arguments; and a
    /// member function that omits these first two arguments (using the ones from the Keys
    /// instance).
    ///
    /// Inputs:
    /// - `groupid` -- the group id/pubkey, in hex, beginning with "03".
    /// - `session_ed25519_secretkey` -- the user's Session ID secret key.
    /// - `signing_value` -- the subaccount signing value to validate
    /// - `write` -- if true, require that the signing_value has write permission (i.e. that the
    ///   user will be allowed to post messages).
    /// - `del` -- if true, required that the signing_value has delete permissions (i.e. that the
    ///   user will be allowed to remove storage messages from the group's swarm).  Note that this
    ///   permission is about forcible swarm message deletion, and has no effect on an ability to
    ///   submit a deletion meta-message to the group (which only requires writing a message).
    ///
    /// Outputs:
    /// - `true` if `signing_value` is a valid subaccount signing value for `groupid` with read (and
    ///   possible write and/or del permissions, if requested).  `false` if the signing value does
    ///   not validate or does not meet the requirements.
    static bool swarm_verify_subaccount(
            std::string group_id,
            ustring_view session_ed25519_secretkey,
            ustring_view signing_value,
            bool write = false,
            bool del = false);
    bool swarm_verify_subaccount(
            ustring_view signing_value, bool write = false, bool del = false) const;

    /// API: groups/Keys::swarm_auth
    ///
    /// This struct containing the storage server authentication values for subaccount
    /// authentication.  The three strings in this struct may be either raw bytes, or base64
    /// encoded, depending on the `binary` parameter passed to `swarm_subaccount_sign`.
    ///
    /// `.subaccount` is the value to be passed as the "subaccount" authentication parameter.  (It
    /// consists of permission flags followed by a blinded public key.)
    ///
    /// `.subaccount_sig` is the value to be passed as the "subaccount_sig" authentication
    /// parameter.  (It consists of an admin-produced signature of the subaccount, providing
    /// permission for that token to be used for authentication).
    ///
    /// `.signature` is the value to be passed as the "signature" authentication parameter.  (It is
    /// an Ed25519 signature that validates using the blinded public key inside `subaccount`).
    ///
    /// Inputs: none.
    struct swarm_auth {
        std::string subaccount;
        std::string subaccount_sig;
        std::string signature;
    };

    /// API: groups/Keys::swarm_subaccount_sign
    ///
    /// This helper function generates the required signature for swarm subaccount authentication,
    /// given the user's keys and swarm auth keys (as provided by an admin, produced via
    /// `swarm_make_subaccount`).
    ///
    /// Storage server subaccount authentication requires passing the three values in the returned
    /// struct in the storage server request.  (See Keys::swarm_auth for details).
    ///
    /// Inputs:
    /// - `msg` -- the data that needs to be signed (which depends on the storage server request
    ///   being made; for example, "retrieve9991234567890123" for a retrieve request to namespace
    ///   999 made at unix time 1234567890.123; see storage server RPC documentation for details).
    /// - `signing_value` -- the 100-byte subaccount signing value, as produced by an admin's
    ///   `swarm_make_subaccount` and provided to this member.
    /// - `binary` -- if set to true then the returned values will be binary.  If omitted (or
    ///   explicitly false), the returned struct values will be base64-encoded suitable for direct
    ///   passing as JSON values to the storage server without further encoding/modification.
    ///
    /// Outputs:
    /// - struct containing three binary values enabling swarm authentication (see description
    /// above).
    swarm_auth swarm_subaccount_sign(
            ustring_view msg, ustring_view signing_value, bool binary = false) const;

    /// API: groups/Keys::swarm_subaccount_token
    ///
    /// Constructs the subaccount token for a session id.  The main use of this is to submit a swarm
    /// token revocation; for issuing subaccount tokens you want to use `swarm_make_subaccount`
    /// instead.  This will produce the same subaccount token that `swarm_make_subaccount`
    /// implicitly creates that can be passed to a swarm to add a revocation for that subaccount.
    ///
    /// This is recommended to be used when removing a non-admin member to prevent their access.
    /// (Note, however, that there are circumstances where this can fail to prevent access, and so
    /// should be combined with proper member removal and key rotation so that even if the member
    /// gains access to messages, they cannot read them).
    ///
    /// Inputs:
    /// - `session_id` -- the session ID of the member (in hex)
    /// - `write`, `del` -- optional; see `swarm_make_subaccount`.  The same arguments should be
    ///   provided (or omitted) as were used in `swarm_make_subaccount`.
    ///
    /// Outputs:
    /// - 36 byte token that can be used for swarm token revocation.
    ustring swarm_subaccount_token(
            std::string_view session_id, bool write = true, bool del = false) const;

    /// API: groups/Keys::pending_config
    ///
    /// If a rekey has been performed but not yet confirmed then this will contain the config
    /// message to be pushed to the swarm.  If there is no push current pending then this returns
    /// nullopt.  The value should be used immediately (i.e. the ustring_view may not remain valid
    /// if other calls to the config object are made).
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::optional<ustring_view>` -- returns a populated config message that should be pushed,
    ///   if not yet confirmed, otherwise when no pending update is present this returns nullopt.
    std::optional<ustring_view> pending_config() const;

    /// API: groups/Keys::pending_key
    ///
    /// After calling rekey() this contains the new group encryption key *before* it is confirmed
    /// pushed into the swarm.  This is primarily intended for internal use as this key is generally
    /// already propagated to the member/info lists when rekeying occurs.
    ///
    /// The pending key is dropped when an incoming keys message is successfully loaded with either
    /// the pending key itself, or a keys message with a higher generation.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `std::optional<ustring_view>` the encryption key generated by the last `rekey()` call.
    ///   This is set to a new key when `rekey()` is called, and is cleared when any config message
    ///   is successfully loaded by `load_key`.
    std::optional<ustring_view> pending_key() const;

    /// API: groups/Keys::load_key
    ///
    /// Loads a key pulled down from the swarm into this Keys object.
    ///
    /// A Session client must process messages from the keys namespace *before* other group config
    /// messages as new key messages may contain encryption keys needed to decrypt the other group
    /// config message types.
    ///
    /// It is safe to load the same config multiple times, and to load expired configs; such cases
    /// would typically not change the keys, but are allowed anyway.
    ///
    /// This method should always be wrapped in a `try/catch`: if the given configuration data is
    /// malformed or is not properly signed an exception will be raised (but the Keys object remains
    /// usable).
    ///
    /// Inputs:
    /// - `hash` - the message hash from the swarm
    /// - `data` - the full stored config message value
    /// - `timestamp_ms` - the timestamp (from the swarm) when this message was stored (used to
    ///   track when other keys expire).
    /// - `info` - the given group::Info object's en/decryption key list will be updated to match
    ///   this object's key list.
    /// - `members` - the given group::Members object's en/decryption key list will be updated to
    ///   match this object's key list.
    ///
    /// Outputs:
    /// - throws `std::runtime_error` (typically a subclass thereof) on failure to parse.
    /// - returns true if we found a key for us in the message, false if we did not.  Note that this
    ///   is mainly informative and does not signal an error: false could mean, for instance, be a
    ///   supplemental message that wasn't for us.  Note also that true doesn't mean keys changed:
    ///   it could mean we decrypted one for us, but already had it.
    bool load_key_message(
            std::string_view hash,
            ustring_view data,
            int64_t timestamp_ms,
            Info& info,
            Members& members);

    /// API: groups/Keys::current_hashes
    ///
    /// Returns a set of message hashes of messages that contain currently active decryption keys.
    /// These are the messages that should be periodically renewed by clients with write access to
    /// keep them alive for other accounts (or devices) who might need them in the future.
    ///
    /// Inputs: none
    ///
    /// Outputs:
    /// - vector of message hashes
    std::unordered_set<std::string> current_hashes() const;

    /// API: groups/Keys::needs_rekey
    ///
    /// Returns true if the key list requires a new key to be generated and pushed to the server (by
    /// calling `rekey()`).  This will only be true for admin accounts (as only admin accounts can
    /// call rekey()).  Note that this value will also remain true until the pushed data is fetched
    /// and loaded via `load_key_message`.
    ///
    /// Note that this not only tracks when an automatic `rekey()` is needed because of a key
    /// collision (such as two admins removing different members at the same time); there are other
    /// situations in which rekey() should also be called (such as when kicking a member) that are
    /// not reflected by this flag.
    ///
    /// The recommended use of this method is to call it immediately after fetching messages from
    /// the group config namespace of the swarm, whether or not new configs were retrieved, but
    /// after processing incoming new config messages that were pulled down.
    ///
    /// Unlike regular config messages, there is no need to confirm the push: confirmation (and
    /// adoption of the new keys) happens when the new keys arrived back down from the swarm in the
    /// next fetch.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `true` if a rekey is needed, `false` otherwise.
    bool needs_rekey() const;

    /// API: groups/Keys::needs_dump
    ///
    /// Returns true if this Keys config has changes, either made directly or from incoming configs,
    /// that need to be dumped to the database (made since the last call to `dump()`), false if no
    /// changes have been made.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `true` if state needs to be dumped, `false` if state hasn't changed since the last
    ///   call to `dump()`.
    bool needs_dump() const;

    /// API: groups/Keys::dump
    ///
    /// Returns a dump of the current state of this keys config that allows the Keys object to be
    /// reinstantiated from scratch. Updates the internal needs_dump flag to false.
    ///
    /// Although this can be called at any time, it is recommended to only do so when
    /// `needs_dump()` returns true.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - opaque binary data containing the group keys and other Keys config data that can be passed
    ///   to the `Keys` constructor to reinitialize a Keys object with the current state.
    ustring dump();

    /// API: groups/Keys::make_dump
    ///
    /// Returns a dump of the current state; unlike `dump()` this does *not* update the internal
    /// needs_dump flag; it is mostly used internally (by `dump()`), but can also be called
    /// externally for debugging purposes.
    ///
    /// Inputs: None
    ///
    /// Outputs:
    /// - `ustring` -- Returns binary data of the state dump
    ustring make_dump() const;

    /// API: groups/Keys::encrypt_message
    ///
    /// Compresses, signs, and encrypts group message content.
    ///
    /// This method is passed a binary value containing a group message (typically a serialized
    /// protobuf, but this method doesn't care about the specific data).  That data will be, in
    /// order:
    /// - compressed (but only if this actually reduces the data size)
    /// - signed by the user's underlying session Ed25519 pubkey
    /// - tagged with the user's underlying session Ed25519 pubkey (from which the session id can be
    ///   computed).
    /// - all of the above encoded into a bt-encoded dict
    /// - suffix-padded with null bytes so that the final output value will be a multiple of 256
    ///   bytes
    /// - encrypted with the most-current group encryption key
    ///
    /// Since compression and padding is applied as part of this method, it is not required that the
    /// given message include its own padding (and in fact, such padding will typically be
    /// compressed down to nothing (if non-random)).
    ///
    /// This final encrypted value is then returned to be pushed to the swarm as-is (i.e. not
    /// further wrapped).  For users downloading the message, all of the above is processed in
    /// reverse by passing the returned message into `decrypt_message()`.
    ///
    /// The current implementation uses XChaCha20-Poly1305 for encryption and zstd for compression;
    /// the bt-encoded value is a dict consisting of keys:
    /// - "": the version of this encoding, currently set to 1.  This *MUST* be bumped if this is
    ///   changed in such a way that older clients will not be able to properly decrypt such a
    ///   message.
    /// - "a": the *Ed25519* pubkey (32 bytes) of the author of the message.  (This will be
    ///   converted to a x25519 pubkey to extract the sender's session id when decrypting).
    /// - "s": signature by "a" of whichever of "d" or "z" are included in the data.
    /// Exacly one of:
    /// - "d": the uncompressed data (which must be non-empty if present)
    /// - "z": the zstd-compressed data (which must be non-empty if present)
    ///
    /// When compression is enabled (by omitting the `compress` argument or specifying it as true)
    /// then ZSTD compression will be *attempted* on the plaintext message and will be used if the
    /// compressed data is smaller than the uncompressed data.  If disabled, or if compression does
    /// not reduce the size, then the message will not be compressed.
    ///
    /// This method will throw on failure, which can happen in two cases:
    /// - if there no encryption keys are available at all (which should not occur in normal use).
    /// - if given a plaintext buffer larger than 1MB (even if the compressed version would be much
    ///   smaller).  It is recommended that clients impose their own limits much smaller than this
    ///   on data passed into encrypt_message; this limitation is in *this* function to match the
    ///   `decrypt_message` limit which is merely intended to guard against decompression memory
    ///   exhaustion attacks.
    ///
    /// Inputs:
    /// - `plaintext` -- the binary message to encrypt.
    /// - `compress` -- can be specified as `false` to forcibly disable compression.  Normally
    ///   omitted, to use compression if and only if it reduces the size.
    /// - `padding` -- the padding multiple: padding will be added as needed to attain a multiple of
    ///   this value for the final result.  0 or 1 disables padding entirely.  Normally omitted to
    ///   use the default of next-multiple-of-256.
    ///
    /// Outputs:
    /// - `ciphertext` -- the encrypted, etc. value to send to the swarm
    ustring encrypt_message(
            ustring_view plaintext, bool compress = true, size_t padding = 256) const;

    /// API: groups/Keys::decrypt_message
    ///
    /// Decrypts group message content that was presumably encrypted with `encrypt_message`,
    /// verifies the sender signature, decompresses the message (if necessary) and then returns the
    /// author pubkey and the plaintext data.
    ///
    /// To prevent against memory exhaustion attacks, this method will fail if the value is
    /// a compressed value that would decompress to a value larger than 1MB.
    ///
    /// Inputs:
    /// - `ciphertext` -- an encrypted, encoded, signed, (possibly) compressed message as produced
    ///   by `encrypt_message()`.
    ///
    /// Outputs:
    /// - `std::pair<std::string, ustring>` -- the session ID (in hex) and the plaintext binary
    ///   data that was encrypted.
    ///
    /// On failure this throws a std::exception-derived exception with a `.what()` string containing
    /// some diagnostic info on what part failed.  Typically a production session client would catch
    /// (and possibly log) but otherwise ignore such exceptions and just not process the message if
    /// it throws.
    std::pair<std::string, ustring> decrypt_message(ustring_view ciphertext) const;
};

}  // namespace session::config::groups
