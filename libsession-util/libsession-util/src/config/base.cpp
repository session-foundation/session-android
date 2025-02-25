#include "session/config/base.hpp"

#include <oxenc/bt_producer.h>
#include <oxenc/bt_value_producer.h>
#include <oxenc/hex.h>
#include <sodium/core.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_sign_ed25519.h>
#include <sodium/utils.h>

#include <stdexcept>
#include <string>
#include <vector>

#include "internal.hpp"
#include "session/config/base.h"
#include "session/config/encrypt.hpp"
#include "session/config/protos.hpp"
#include "session/export.h"
#include "session/util.hpp"

using namespace std::literals;

namespace session::config {

void ConfigBase::set_state(ConfigState s) {
    if (s == ConfigState::Dirty && is_readonly())
        throw std::runtime_error{"Unable to make changes to a read-only config object"};

    if (_state == ConfigState::Clean && !_curr_hash.empty()) {
        _old_hashes.insert(std::move(_curr_hash));
        _curr_hash.clear();
    }
    _state = s;
    _needs_dump = true;
}

MutableConfigMessage& ConfigBase::dirty() {
    if (_state != ConfigState::Dirty) {
        set_state(ConfigState::Dirty);
        _config = std::make_unique<MutableConfigMessage>(*_config, increment_seqno);
    } else {
        _needs_dump = true;
    }

    if (auto* mut = dynamic_cast<MutableConfigMessage*>(_config.get()))
        return *mut;
    throw std::runtime_error{"Internal error: unexpected dirty but non-mutable ConfigMessage"};
}

template <typename... Args>
std::unique_ptr<ConfigMessage> make_config_message(bool from_dirty, Args&&... args) {
    if (from_dirty)
        return std::make_unique<MutableConfigMessage>(std::forward<Args>(args)...);
    return std::make_unique<ConfigMessage>(std::forward<Args>(args)...);
}

std::vector<std::string> ConfigBase::merge(
        const std::vector<std::pair<std::string, ustring>>& configs) {
    std::vector<std::pair<std::string, ustring_view>> config_views;
    config_views.reserve(configs.size());
    for (auto& [hash, data] : configs)
        config_views.emplace_back(hash, data);
    return merge(config_views);
}

std::vector<std::string> ConfigBase::merge(
        const std::vector<std::pair<std::string, ustring_view>>& configs) {
    if (accepts_protobuf() && !_keys.empty()) {
        std::list<ustring> keep_alive;
        std::vector<std::pair<std::string, ustring_view>> parsed;
        parsed.reserve(configs.size());

        for (auto& [h, c] : configs) {
            try {
                auto unwrapped = protos::unwrap_config(
                        ustring_view{_keys.front().data(), _keys.front().size()},
                        c,
                        storage_namespace());

                // There was a release of one of the clients which resulted in double-wrapped
                // config messages so we now need to try to double-unwrap in order to better
                // support multi-device for users running those old versions
                try {
                    auto unwrapped2 = protos::unwrap_config(
                            ustring_view{_keys.front().data(), _keys.front().size()},
                            unwrapped,
                            storage_namespace());
                    parsed.emplace_back(h, keep_alive.emplace_back(std::move(unwrapped2)));
                } catch (...) {
                    parsed.emplace_back(h, keep_alive.emplace_back(std::move(unwrapped)));
                }
            } catch (...) {
                parsed.emplace_back(h, c);
            }
        }

        return _merge(parsed);
    }

    return _merge(configs);
}

std::vector<std::string> ConfigBase::_merge(
        const std::vector<std::pair<std::string, ustring_view>>& configs) {

    if (_keys.empty())
        throw std::logic_error{"Cannot merge configs without any decryption keys"};

    const auto old_seqno = _config->seqno();
    std::vector<std::string_view> all_hashes;
    std::vector<ustring_view> all_confs;
    all_hashes.reserve(configs.size() + 1);
    all_confs.reserve(configs.size() + 1);

    // We serialize our current config and include it in the list of configs to be merged, as if it
    // had already been pushed to the server (so that this code will be identical whether or not the
    // value was pushed).
    //
    // (We skip this for seqno=0, but that's just a default-constructed, nothing-in-the-config case
    // for which we also can't have or produce a signature, so there's no point in even trying to
    // merge it).

    ustring mine;
    if (old_seqno != 0 || is_dirty()) {
        mine = _config->serialize();
        all_hashes.emplace_back(_curr_hash);
        all_confs.emplace_back(mine);
    }

    std::vector<std::pair<std::string_view, ustring>> plaintexts;

    // TODO:
    // - handle multipart messages.  Each part of a multipart message starts with `m` and then is
    //   immediately followed by a bt_list where:
    //   - element 0 is 'z' for a zstd-compressed message, 'p' for an uncompressed message.
    //   - element 1 is the hash of the final, uncompressed, re-assembled message.
    //   - element 2 is the numeric sequence number of the message, starting from 0.
    //   - element 3 is the total number of messages in the sequence.
    //   - element 4 is a chunk of the data.
    for (size_t ci = 0; ci < configs.size(); ci++) {
        auto& [hash, conf] = configs[ci];
        bool decrypted = false;
        for (size_t i = 0; !decrypted && i < _keys.size(); i++) {
            try {
                plaintexts.emplace_back(hash, decrypt(conf, key(i), encryption_domain()));
                decrypted = true;
            } catch (const decrypt_error&) {
                log(LogLevel::debug,
                    "Failed to decrypt message " + std::to_string(ci) + " using key " +
                            std::to_string(i));
            }
        }
        if (!decrypted)
            log(LogLevel::warning, "Failed to decrypt message " + std::to_string(ci));
    }
    log(LogLevel::debug,
        "successfully decrypted " + std::to_string(plaintexts.size()) + " of " +
                std::to_string(configs.size()) + " incoming messages");

    for (auto& [hash, plain] : plaintexts) {
        // Remove prefix padding:
        if (auto p = plain.find_first_not_of((unsigned char)0); p > 0 && p != std::string::npos) {
            std::memmove(plain.data(), plain.data() + p, plain.size() - p);
            plain.resize(plain.size() - p);
        }
        if (plain.empty()) {
            log(LogLevel::error, "Invalid config message: contains no data");
            continue;
        }

        // TODO FIXME (see above)
        if (plain[0] == 'm') {
            log(LogLevel::warning, "multi-part messages not yet supported!");
            continue;
        }

        // 'z' prefix indicates zstd-compressed data:
        if (plain[0] == 'z') {
            if (auto decompressed =
                        zstd_decompress(ustring_view{plain.data() + 1, plain.size() - 1});
                decompressed && !decompressed->empty())
                plain = std::move(*decompressed);
            else {
                log(LogLevel::warning, "Invalid config message: decompression failed");
                continue;
            }
        }

        if (plain[0] != 'd')
            log(LogLevel::error,
                "invalid/unsupported config message with type " +
                        (plain[0] >= 0x20 && plain[0] <= 0x7e
                                 ? "'" + std::string{from_unsigned_sv(plain.substr(0, 1))} + "'"
                                 : "0x" + oxenc::to_hex(plain.begin(), plain.begin() + 1)));

        all_hashes.emplace_back(hash);
        all_confs.emplace_back(plain);
    }

    std::set<size_t> bad_confs;

    auto new_conf = make_config_message(
            _state == ConfigState::Dirty,
            all_confs,
            _config->verifier,
            _config->signer,
            config_lags(),
            [&](size_t i, const config_error& e) {
                log(LogLevel::warning, e.what());
                assert(i > 0);  // i == 0 means we can't deserialize our own serialization
                bad_confs.insert(i);
            });

    // All the given config msgs are stale except for:
    // - the message we used, if we found and used a single config that includes all configs.  (This
    //   might be our current config, or might be one single one of the new incoming messages).
    // - confs that failed to parse (we can't understand them, so leave them behind as they may be
    //   some future message).
    int superconf = new_conf->unmerged_index();  // -1 if we had to merge
    for (int i = 0; i < all_hashes.size(); i++) {
        if (i != superconf && !bad_confs.count(i) && !all_hashes[i].empty())
            _old_hashes.emplace(all_hashes[i]);
    }

    if (new_conf->seqno() != old_seqno) {
        if (new_conf->merged()) {
            if (_state != ConfigState::Dirty) {
                // Merging resulted in a merge conflict resolution message, but won't currently be
                // mutable (because we weren't dirty to start with).  Convert into a Mutable message
                // and mark ourselves dirty so that we'll get pushed.
                _config =
                        std::make_unique<MutableConfigMessage>(std::move(*new_conf), retain_seqno);
            } else {
                _config = std::move(new_conf);
            }
            set_state(ConfigState::Dirty);
        } else if (
                _state == ConfigState::Dirty && new_conf->unmerged_index() == 0 &&
                new_conf->seqno() == old_seqno + 1) {
            // Constructing a new MutableConfigMessage always increments the seqno (by design) but
            // in this case nothing changed: every other config got ignored and we didn't change
            // anything, so we can ignore the new config and just keep our current one, despite the
            // seqno increment.
            /* do nothing */
        } else {
            _config = std::move(new_conf);
            assert(((old_seqno == 0 && mine.empty()) || _config->unmerged_index() >= 1) &&
                   _config->unmerged_index() < all_hashes.size());
            set_state(ConfigState::Clean);
            _curr_hash = all_hashes[_config->unmerged_index()];
        }
    } else {
        // the merging affect nothing (if it had seqno would have been incremented), so don't
        // pointlessly replace the inner config object.
        assert(new_conf->unmerged_index() == 0);
    }

    std::vector<std::string> good_hashes;
    good_hashes.reserve(all_hashes.size() - (mine.empty() ? 0 : 1) - bad_confs.size());
    for (size_t i = mine.empty() ? 0 : 1; i < all_hashes.size(); i++)
        if (!bad_confs.count(i))
            good_hashes.emplace_back(all_hashes[i]);

    return good_hashes;
}

std::vector<std::string> ConfigBase::current_hashes() const {
    std::vector<std::string> hashes;
    if (!_curr_hash.empty())
        hashes.push_back(_curr_hash);
    return hashes;
}

bool ConfigBase::needs_push() const {
    return !is_clean();
}

// Tries to compresses the message; if the compressed version (including the 'z' prefix tag) is
// smaller than the source message then we modify `msg` to contain the 'z'-prefixed compressed
// message, otherwise we leave it as-is.
void compress_message(ustring& msg, int level) {
    if (!level)
        return;
    // "z" is our zstd compression marker prefix byte
    ustring compressed = zstd_compress(msg, level, to_unsigned_sv("z"sv));
    if (compressed.size() < msg.size())
        msg = std::move(compressed);
}

std::tuple<seqno_t, ustring, std::vector<std::string>> ConfigBase::push() {
    if (_keys.empty())
        throw std::logic_error{"Cannot push data without an encryption key!"};

    auto s = _config->seqno();

    std::tuple<seqno_t, ustring, std::vector<std::string>> ret{s, _config->serialize(), {}};

    auto& [seqno, msg, obs] = ret;
    if (auto lvl = compression_level())
        compress_message(msg, *lvl);

    pad_message(msg);  // Prefix pad with nulls
    encrypt_inplace(msg, key(), encryption_domain());

    if (accepts_protobuf() && !_keys.empty())
        msg = protos::wrap_config(
                ustring_view{_keys.front().data(), _keys.front().size()},
                msg,
                s,
                storage_namespace());

    if (msg.size() > MAX_MESSAGE_SIZE)
        throw std::length_error{"Config data is too large"};

    if (is_dirty())
        set_state(ConfigState::Waiting);

    if (!is_readonly())
        for (auto& old : _old_hashes)
            obs.push_back(std::move(old));
    _old_hashes.clear();

    return ret;
}

void ConfigBase::confirm_pushed(seqno_t seqno, std::string msg_hash) {
    // Make sure seqno hasn't changed; if it has then that means we set some other data *after* the
    // caller got the last data to push, and so we don't care about this confirmation.
    if (_state == ConfigState::Waiting && seqno == _config->seqno()) {
        set_state(ConfigState::Clean);
        _curr_hash = std::move(msg_hash);
    }
}

ustring ConfigBase::dump() {
    if (is_readonly())
        _old_hashes.clear();

    auto d = make_dump();
    _needs_dump = false;
    return d;
}

ustring ConfigBase::make_dump() const {
    auto data = _config->serialize(false /* disable signing for local storage */);
    auto data_sv = from_unsigned_sv(data);
    oxenc::bt_list old_hashes;

    oxenc::bt_dict_producer d;
    d.append("!", static_cast<int>(_state));
    d.append("$", data_sv);
    d.append("(", _curr_hash);

    d.append_list(")").append(_old_hashes.begin(), _old_hashes.end());

    if (auto extra = extra_data(); !extra.empty())
        d.append_bt("+", std::move(extra));

    return ustring{to_unsigned_sv(d.view())};
}

ConfigBase::ConfigBase(
        std::optional<ustring_view> dump,
        std::optional<ustring_view> ed25519_pubkey,
        std::optional<ustring_view> ed25519_secretkey) {

    if (sodium_init() == -1)
        throw std::runtime_error{"libsodium initialization failed!"};

    if (dump)
        init_from_dump(from_unsigned_sv(*dump));
    else
        _config = std::make_unique<ConfigMessage>();

    init_sig_keys(ed25519_pubkey, ed25519_secretkey);
}

void ConfigSig::init_sig_keys(
        std::optional<ustring_view> ed25519_pubkey, std::optional<ustring_view> ed25519_secretkey) {
    if (ed25519_secretkey) {
        if (ed25519_pubkey && *ed25519_pubkey != ed25519_secretkey->substr(32))
            throw std::invalid_argument{"Invalid signing keys: secret key and pubkey do not match"};
        set_sig_keys(*ed25519_secretkey);
    } else if (ed25519_pubkey) {
        set_sig_pubkey(*ed25519_pubkey);
    } else {
        clear_sig_keys();
    }
}

void ConfigBase::init_from_dump(std::string_view dump) {
    oxenc::bt_dict_consumer d{dump};
    if (!d.skip_until("!"))
        throw std::runtime_error{"Unable to parse dumped config data: did not find '!' state key"};
    _state = static_cast<ConfigState>(d.consume_integer<int>());

    if (!d.skip_until("$"))
        throw std::runtime_error{"Unable to parse dumped config data: did not find '$' data key"};
    auto data = to_unsigned_sv(d.consume_string_view());
    if (_state == ConfigState::Dirty)
        // If we dumped dirty data then we need to reload it as a mutable config message so that the
        // seqno gets incremented.  This "wastes" one seqno value (since we didn't send the old
        // one), but that's minor and easier than extracting and restoring all the fields we set and
        // is a little more robust against failure if we actually sent it but got killed before we
        // could store a dump.
        _config = std::make_unique<MutableConfigMessage>(
                data,
                nullptr,  // We omit verifier and signer for now because we don't want this dump to
                nullptr,  // be signed (since it's just a dump).
                config_lags());
    else
        _config = std::make_unique<ConfigMessage>(
                data,
                nullptr,
                nullptr,
                config_lags(),
                /*trust_signature=*/true);

    if (d.skip_until("(")) {
        _curr_hash = d.consume_string();
        if (!d.skip_until(")"))
            throw std::runtime_error{"Unable to parse dumped config data: found '(' without ')'"};
        for (auto old = d.consume_list_consumer(); !old.is_finished();)
            _old_hashes.insert(old.consume_string());
    }

    if (d.skip_until("+"))
        if (auto extra = d.consume_dict(); !extra.empty())
            load_extra_data(std::move(extra));
}

int ConfigBase::key_count() const {
    return _keys.size();
}

bool ConfigBase::has_key(ustring_view key) const {
    if (key.size() != 32)
        throw std::invalid_argument{"invalid key given to has_key(): not 32-bytes"};

    auto* keyptr = key.data();
    for (const auto& key : _keys)
        if (sodium_memcmp(keyptr, key.data(), KEY_SIZE) == 0)
            return true;
    return false;
}

std::vector<ustring_view> ConfigBase::get_keys() const {
    std::vector<ustring_view> ret;
    ret.reserve(_keys.size());
    for (const auto& key : _keys)
        ret.emplace_back(key.data(), key.size());
    return ret;
}

void ConfigBase::add_key(ustring_view key, bool high_priority, bool dirty_config) {
    static_assert(
            sizeof(Key) == KEY_SIZE, "std::array appears to have some overhead which seems bad");

    if (key.size() != KEY_SIZE)
        throw std::invalid_argument{"add_key failed: key size must be 32 bytes"};

    if (!_keys.empty() && sodium_memcmp(_keys.front().data(), key.data(), KEY_SIZE) == 0)
        return;
    else if (!high_priority && has_key(key))
        return;

    if (_keys.capacity() == 0)
        // There's not a lot of point in starting this off really small: sodium is likely going to
        // use at least a page size anyway.
        _keys.reserve(64);

    if (high_priority)
        remove_key(key, 1);

    auto& newkey = *_keys.emplace(high_priority ? _keys.begin() : _keys.end());
    std::memcpy(newkey.data(), key.data(), KEY_SIZE);

    if (dirty_config && !is_readonly() && (_keys.size() == 1 || high_priority))
        dirty();
}

int ConfigBase::clear_keys(bool dirty_config) {
    int ret = _keys.size();
    _keys.clear();
    _keys.shrink_to_fit();

    if (dirty_config && !is_readonly() && ret > 0)
        dirty();

    return ret;
}

void ConfigBase::replace_keys(const std::vector<ustring_view>& new_keys, bool dirty_config) {
    if (new_keys.empty()) {
        if (_keys.empty())
            return;
        clear_keys(dirty_config);
        return;
    }

    for (auto& k : new_keys)
        if (k.size() != KEY_SIZE)
            throw std::invalid_argument{"replace_keys failed: keys must be 32 bytes"};

    dirty_config = dirty_config && !is_readonly() &&
                   (_keys.empty() ||
                    sodium_memcmp(_keys.front().data(), new_keys.front().data(), KEY_SIZE) != 0);

    _keys.clear();
    for (auto& k : new_keys)
        add_key(k, /*high_priority=*/false);  // The first key gets the high priority spot even
                                              // with `false` since we just emptied the list

    if (dirty_config)
        dirty();
}

bool ConfigBase::remove_key(ustring_view key, size_t from, bool dirty_config) {
    auto starting_size = _keys.size();
    if (from >= starting_size)
        return false;

    dirty_config = dirty_config && !is_readonly() &&
                   sodium_memcmp(key.data(), _keys.front().data(), KEY_SIZE) == 0;

    _keys.erase(
            std::remove_if(
                    _keys.begin() + from,
                    _keys.end(),
                    [&key](const auto& k) {
                        return sodium_memcmp(key.data(), k.data(), KEY_SIZE) == 0;
                    }),
            _keys.end());

    if (dirty_config)
        dirty();

    return _keys.size() < starting_size;
}

void ConfigBase::load_key(ustring_view ed25519_secretkey) {
    if (!(ed25519_secretkey.size() == 64 || ed25519_secretkey.size() == 32))
        throw std::invalid_argument{
                encryption_domain() + " requires an Ed25519 64-byte secret key or 32-byte seed"s};

    add_key(ed25519_secretkey.substr(0, 32));
}

void ConfigSig::set_sig_keys(ustring_view secret) {
    if (secret.size() != 64)
        throw std::invalid_argument{"Invalid sodium secret: expected 64 bytes"};
    clear_sig_keys();
    _sign_sk.reset(64);
    std::memcpy(_sign_sk.data(), secret.data(), secret.size());
    _sign_pk.emplace();
    crypto_sign_ed25519_sk_to_pk(_sign_pk->data(), _sign_sk.data());

    set_verifier([this](ustring_view data, ustring_view sig) {
        return 0 == crypto_sign_ed25519_verify_detached(
                            sig.data(), data.data(), data.size(), _sign_pk->data());
    });
    set_signer([this](ustring_view data) {
        ustring sig;
        sig.resize(64);
        if (0 != crypto_sign_ed25519_detached(
                         sig.data(), nullptr, data.data(), data.size(), _sign_sk.data()))
            throw std::runtime_error{"Internal error: config signing failed!"};
        return sig;
    });
}

void ConfigSig::set_sig_pubkey(ustring_view pubkey) {
    if (pubkey.size() != 32)
        throw std::invalid_argument{"Invalid pubkey: expected 32 bytes"};
    _sign_pk.emplace();
    std::memcpy(_sign_pk->data(), pubkey.data(), 32);

    set_verifier([this](ustring_view data, ustring_view sig) {
        return 0 == crypto_sign_ed25519_verify_detached(
                            sig.data(), data.data(), data.size(), _sign_pk->data());
    });
}

void ConfigSig::clear_sig_keys() {
    _sign_pk.reset();
    _sign_sk.reset();
    set_signer(nullptr);
    set_verifier(nullptr);
}

void ConfigBase::set_verifier(ConfigMessage::verify_callable v) {
    _config->verifier = std::move(v);
}

void ConfigBase::set_signer(ConfigMessage::sign_callable s) {
    _config->signer = std::move(s);
}

std::array<unsigned char, 32> ConfigSig::seed_hash(std::string_view key) const {
    if (!_sign_sk)
        throw std::runtime_error{"Cannot make a seed hash without a signing secret key"};
    std::array<unsigned char, 32> out;
    crypto_generichash_blake2b(
            out.data(),
            out.size(),
            _sign_sk.data(),
            32,  // Just the seed part of the value, not the last half (which is just the pubkey)
            reinterpret_cast<const unsigned char*>(key.data()),
            std::min<size_t>(key.size(), 64));
    return out;
}

void set_error(config_object* conf, std::string e) {
    auto& error = unbox(conf).error;
    error = std::move(e);
    conf->last_error = error.c_str();
}

}  // namespace session::config

extern "C" {

using namespace session;
using namespace session::config;

LIBSESSION_EXPORT void config_free(config_object* conf) {
    delete static_cast<internals<>*>(conf->internals);
    delete conf;
}

LIBSESSION_EXPORT int16_t config_storage_namespace(const config_object* conf) {
    return static_cast<int16_t>(unbox(conf)->storage_namespace());
}

LIBSESSION_EXPORT config_string_list* config_merge(
        config_object* conf,
        const char** msg_hashes,
        const unsigned char** configs,
        const size_t* lengths,
        size_t count) {
    auto& config = *unbox(conf);
    std::vector<std::pair<std::string, ustring_view>> confs;
    confs.reserve(count);
    for (size_t i = 0; i < count; i++)
        confs.emplace_back(msg_hashes[i], ustring_view{configs[i], lengths[i]});

    return make_string_list(config.merge(confs));
}

LIBSESSION_EXPORT bool config_needs_push(const config_object* conf) {
    return unbox(conf)->needs_push();
}

LIBSESSION_EXPORT config_push_data* config_push(config_object* conf) {
    auto& config = *unbox(conf);
    auto [seqno, data, obs] = config.push();

    // We need to do one alloc here that holds everything:
    // - the returned struct
    // - pointers to the obsolete message hash strings
    // - the data
    // - the message hash strings
    size_t buffer_size = sizeof(config_push_data) + obs.size() * sizeof(char*) + data.size();
    for (auto& o : obs)
        buffer_size += o.size();
    buffer_size += obs.size();  // obs msg hash string NULL terminators

    auto* ret = static_cast<config_push_data*>(std::malloc(buffer_size));

    ret->seqno = seqno;

    static_assert(alignof(config_push_data) >= alignof(char*));
    ret->obsolete = reinterpret_cast<char**>(ret + 1);
    ret->obsolete_len = obs.size();

    ret->config = reinterpret_cast<unsigned char*>(ret->obsolete + ret->obsolete_len);
    ret->config_len = data.size();

    std::memcpy(ret->config, data.data(), data.size());
    char* obsptr = reinterpret_cast<char*>(ret->config + ret->config_len);
    for (size_t i = 0; i < obs.size(); i++) {
        std::memcpy(obsptr, obs[i].c_str(), obs[i].size() + 1);
        ret->obsolete[i] = obsptr;
        obsptr += obs[i].size() + 1;
    }

    return ret;
}

LIBSESSION_EXPORT void config_confirm_pushed(
        config_object* conf, seqno_t seqno, const char* msg_hash) {
    unbox(conf)->confirm_pushed(seqno, msg_hash);
}

LIBSESSION_EXPORT void config_dump(config_object* conf, unsigned char** out, size_t* outlen) {
    assert(out && outlen);
    auto data = unbox(conf)->dump();
    *outlen = data.size();
    *out = static_cast<unsigned char*>(std::malloc(data.size()));
    std::memcpy(*out, data.data(), data.size());
}

LIBSESSION_EXPORT bool config_needs_dump(const config_object* conf) {
    return unbox(conf)->needs_dump();
}

LIBSESSION_EXPORT config_string_list* config_current_hashes(const config_object* conf) {
    return make_string_list(unbox(conf)->current_hashes());
}

LIBSESSION_EXPORT unsigned char* config_get_keys(const config_object* conf, size_t* len) {
    const auto keys = unbox(conf)->get_keys();
    assert(std::count_if(keys.begin(), keys.end(), [](const auto& k) { return k.size() == 32; }) ==
           keys.size());
    assert(len);
    *len = keys.size();
    if (keys.empty())
        return nullptr;
    auto* buf = static_cast<unsigned char*>(std::malloc(32 * keys.size()));
    auto* cur = buf;
    for (const auto& k : keys) {
        std::memcpy(cur, k.data(), 32);
        cur += 32;
    }

    return buf;
}

LIBSESSION_EXPORT void config_add_key(config_object* conf, const unsigned char* key) {
    unbox(conf)->add_key({key, 32});
}
LIBSESSION_EXPORT void config_add_key_low_prio(config_object* conf, const unsigned char* key) {
    unbox(conf)->add_key({key, 32}, /*high_priority=*/false);
}
LIBSESSION_EXPORT int config_clear_keys(config_object* conf) {
    return unbox(conf)->clear_keys();
}
LIBSESSION_EXPORT bool config_remove_key(config_object* conf, const unsigned char* key) {
    return unbox(conf)->remove_key({key, 32});
}
LIBSESSION_EXPORT int config_key_count(const config_object* conf) {
    return unbox(conf)->key_count();
}
LIBSESSION_EXPORT bool config_has_key(const config_object* conf, const unsigned char* key) {
    return unbox(conf)->has_key({key, 32});
}
LIBSESSION_EXPORT const unsigned char* config_key(const config_object* conf, size_t i) {
    return unbox(conf)->key(i).data();
}

LIBSESSION_EXPORT const char* config_encryption_domain(const config_object* conf) {
    return unbox(conf)->encryption_domain();
}

LIBSESSION_EXPORT void config_set_sig_keys(config_object* conf, const unsigned char* secret) {
    unbox(conf)->set_sig_keys({secret, 64});
}

LIBSESSION_EXPORT void config_set_sig_pubkey(config_object* conf, const unsigned char* pubkey) {
    unbox(conf)->set_sig_pubkey({pubkey, 32});
}

LIBSESSION_EXPORT const unsigned char* config_get_sig_pubkey(const config_object* conf) {
    const auto& pk = unbox(conf)->get_sig_pubkey();
    if (pk)
        return pk->data();
    return nullptr;
}

LIBSESSION_EXPORT void config_clear_sig_keys(config_object* conf) {
    unbox(conf)->clear_sig_keys();
}

LIBSESSION_EXPORT void config_set_logger(
        config_object* conf, void (*callback)(config_log_level, const char*, void*), void* ctx) {
    if (!callback)
        unbox(conf)->logger = nullptr;
    else
        unbox(conf)->logger = [callback, ctx](LogLevel lvl, std::string msg) {
            callback(static_cast<config_log_level>(static_cast<int>(lvl)), msg.c_str(), ctx);
        };
}

}  // extern "C"
