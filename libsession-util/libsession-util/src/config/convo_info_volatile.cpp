#include "session/config/convo_info_volatile.hpp"

#include <oxenc/base32z.h>
#include <oxenc/base64.h>
#include <oxenc/hex.h>
#include <oxenc/variant.h>
#include <sodium/crypto_generichash_blake2b.h>

#include <charconv>
#include <iterator>
#include <stdexcept>
#include <variant>

#include "internal.hpp"
#include "session/config/convo_info_volatile.h"
#include "session/config/error.h"
#include "session/export.h"
#include "session/types.hpp"
#include "session/util.hpp"

using namespace std::literals;
using session::ustring_view;

namespace session::config {

namespace convo {

    one_to_one::one_to_one(std::string&& sid) : session_id{std::move(sid)} {
        check_session_id(session_id);
    }
    one_to_one::one_to_one(std::string_view sid) : session_id{sid} {
        check_session_id(session_id);
    }
    one_to_one::one_to_one(const convo_info_volatile_1to1& c) :
            base{c.last_read, c.unread}, session_id{c.session_id, 66} {}

    void one_to_one::into(convo_info_volatile_1to1& c) const {
        std::memcpy(c.session_id, session_id.data(), 67);
        c.last_read = last_read;
        c.unread = unread;
    }

    community::community(const convo_info_volatile_community& c) :
            config::community{c.base_url, c.room, ustring_view{c.pubkey, 32}},
            base{c.last_read, c.unread} {}

    void community::into(convo_info_volatile_community& c) const {
        static_assert(sizeof(c.base_url) == BASE_URL_MAX_LENGTH + 1);
        static_assert(sizeof(c.room) == ROOM_MAX_LENGTH + 1);
        copy_c_str(c.base_url, base_url());
        copy_c_str(c.room, room_norm());
        std::memcpy(c.pubkey, pubkey().data(), 32);
        c.last_read = last_read;
        c.unread = unread;
    }

    group::group(std::string&& cgid) : id{std::move(cgid)} {
        check_session_id(id, "03");
    }
    group::group(std::string_view cgid) : id{cgid} {
        check_session_id(id, "03");
    }
    group::group(const convo_info_volatile_group& c) :
            base{c.last_read, c.unread}, id{c.group_id, 66} {}

    void group::into(convo_info_volatile_group& c) const {
        std::memcpy(c.group_id, id.c_str(), 67);
        c.last_read = last_read;
        c.unread = unread;
    }

    legacy_group::legacy_group(std::string&& cgid) : id{std::move(cgid)} {
        check_session_id(id);
    }
    legacy_group::legacy_group(std::string_view cgid) : id{cgid} {
        check_session_id(id);
    }
    legacy_group::legacy_group(const convo_info_volatile_legacy_group& c) :
            base{c.last_read, c.unread}, id{c.group_id, 66} {}

    void legacy_group::into(convo_info_volatile_legacy_group& c) const {
        std::memcpy(c.group_id, id.data(), 67);
        c.last_read = last_read;
        c.unread = unread;
    }

    void base::load(const dict& info_dict) {
        last_read = maybe_int(info_dict, "r").value_or(0);
        unread = (bool)maybe_int(info_dict, "u").value_or(0);
    }

}  // namespace convo

ConvoInfoVolatile::ConvoInfoVolatile(
        ustring_view ed25519_secretkey, std::optional<ustring_view> dumped) :
        ConfigBase{dumped} {
    load_key(ed25519_secretkey);
}

std::optional<convo::one_to_one> ConvoInfoVolatile::get_1to1(std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex);

    auto* info_dict = data["1"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<convo::one_to_one>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

convo::one_to_one ConvoInfoVolatile::get_or_construct_1to1(std::string_view pubkey_hex) const {
    if (auto maybe = get_1to1(pubkey_hex))
        return *std::move(maybe);

    return convo::one_to_one{std::string{pubkey_hex}};
}

ConfigBase::DictFieldProxy ConvoInfoVolatile::community_field(
        const convo::community& comm, ustring_view* get_pubkey) const {
    auto record = data["o"][comm.base_url()];
    if (get_pubkey) {
        auto pkrec = record["#"];
        if (auto pk = pkrec.string_view_or(""); pk.size() == 32)
            *get_pubkey =
                    ustring_view{reinterpret_cast<const unsigned char*>(pk.data()), pk.size()};
    }
    return record["R"][comm.room_norm()];
}

std::optional<convo::community> ConvoInfoVolatile::get_community(
        std::string_view base_url, std::string_view room) const {
    convo::community og{base_url, community::canonical_room(room)};

    ustring_view pubkey;
    if (auto* info_dict = community_field(og, &pubkey).dict()) {
        og.load(*info_dict);
        if (!pubkey.empty())
            og.set_pubkey(pubkey);
        return og;
    }
    return std::nullopt;
}

std::optional<convo::community> ConvoInfoVolatile::get_community(
        std::string_view partial_url) const {
    auto [base, room, pubkey] = community::parse_partial_url(partial_url);
    return get_community(base, room);
}

convo::community ConvoInfoVolatile::get_or_construct_community(
        std::string_view base_url, std::string_view room, ustring_view pubkey) const {
    convo::community result{base_url, community::canonical_room(room), pubkey};

    if (auto* info_dict = community_field(result).dict())
        result.load(*info_dict);

    return result;
}

convo::community ConvoInfoVolatile::get_or_construct_community(std::string_view full_url) const {
    auto [base, room, pubkey] = community::parse_full_url(full_url);
    return get_or_construct_community(base, room, pubkey);
}

convo::community ConvoInfoVolatile::get_or_construct_community(
        std::string_view base_url, std::string_view room, std::string_view pubkey_hex) const {
    convo::community result{base_url, room, pubkey_hex};

    if (auto* info_dict = community_field(result).dict())
        result.load(*info_dict);

    return result;
}

std::optional<convo::group> ConvoInfoVolatile::get_group(std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex, "03");

    auto* info_dict = data["g"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<convo::group>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

convo::group ConvoInfoVolatile::get_or_construct_group(std::string_view pubkey_hex) const {
    if (auto maybe = get_group(pubkey_hex))
        return *std::move(maybe);

    return convo::group{std::string{pubkey_hex}};
}

std::optional<convo::legacy_group> ConvoInfoVolatile::get_legacy_group(
        std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex);

    auto* info_dict = data["C"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<convo::legacy_group>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

convo::legacy_group ConvoInfoVolatile::get_or_construct_legacy_group(
        std::string_view pubkey_hex) const {
    if (auto maybe = get_legacy_group(pubkey_hex))
        return *std::move(maybe);

    return convo::legacy_group{std::string{pubkey_hex}};
}

void ConvoInfoVolatile::set(const convo::one_to_one& c) {
    auto info = data["1"][session_id_to_bytes(c.session_id)];
    set_base(c, info);
}

void ConvoInfoVolatile::set_base(const convo::base& c, DictFieldProxy& info) {
    auto r = info["r"];

    // If we're making the last_read value *older* for some reason then ignore the prune cutoff
    // (because we might be intentionally resetting the value after a deletion, for instance).
    if (auto* val = r.integer(); val && c.last_read < *val)
        r = c.last_read;
    else {
        std::chrono::system_clock::time_point last_read{std::chrono::milliseconds{c.last_read}};
        if (last_read > std::chrono::system_clock::now() - PRUNE_LOW)
            info["r"] = c.last_read;
    }

    set_flag(info["u"], c.unread);
}

void ConvoInfoVolatile::prune_stale(std::chrono::milliseconds prune) {
    const int64_t cutoff =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    (std::chrono::system_clock::now() - PRUNE_HIGH).time_since_epoch())
                    .count();

    std::vector<std::string> stale;
    for (auto it = begin_1to1(); it != end(); ++it)
        if (!it->unread && it->last_read < cutoff)
            stale.push_back(it->session_id);
    for (const auto& sid : stale)
        erase_1to1(sid);

    stale.clear();
    for (auto it = begin_legacy_groups(); it != end(); ++it)
        if (!it->unread && it->last_read < cutoff)
            stale.push_back(it->id);
    for (const auto& id : stale)
        erase_legacy_group(id);

    std::vector<std::pair<std::string, std::string>> stale_comms;
    for (auto it = begin_communities(); it != end(); ++it)
        if (!it->unread && it->last_read < cutoff)
            stale_comms.emplace_back(it->base_url(), it->room());
    for (const auto& [base, room] : stale_comms)
        erase_community(base, room);
}

std::tuple<seqno_t, ustring, std::vector<std::string>> ConvoInfoVolatile::push() {
    // Prune off any conversations with last_read timestamps more than PRUNE_HIGH ago (unless they
    // also have a `unread` flag set, in which case we keep them indefinitely).
    prune_stale();

    return ConfigBase::push();
}

void ConvoInfoVolatile::set(const convo::community& c) {
    auto info = community_field(c);
    data["o"][c.base_url()]["#"] = c.pubkey();
    set_base(c, info);
}

void ConvoInfoVolatile::set(const convo::group& c) {
    auto info = data["g"][session_id_to_bytes(c.id, "03")];
    set_base(c, info);
}

void ConvoInfoVolatile::set(const convo::legacy_group& c) {
    auto info = data["C"][session_id_to_bytes(c.id)];
    set_base(c, info);
}

template <typename Field>
static bool erase_impl(Field convo) {
    bool ret = convo.exists();
    convo.erase();
    return ret;
}

bool ConvoInfoVolatile::erase(const convo::one_to_one& c) {
    return erase_impl(data["1"][session_id_to_bytes(c.session_id)]);
}
bool ConvoInfoVolatile::erase(const convo::community& c) {
    bool gone = erase_impl(community_field(c));
    if (gone) {
        // If this was the last room on the server, also remove the server
        auto server_info = data["o"][c.base_url()];
        auto rooms = server_info["R"];
        if (auto* rd = rooms.dict(); !rd || rd->empty()) {
            rooms.erase();
            server_info.erase();
        }
    }
    return gone;
}
bool ConvoInfoVolatile::erase(const convo::group& c) {
    return erase_impl(data["g"][session_id_to_bytes(c.id, "03")]);
}
bool ConvoInfoVolatile::erase(const convo::legacy_group& c) {
    return erase_impl(data["C"][session_id_to_bytes(c.id)]);
}

bool ConvoInfoVolatile::erase(const convo::any& c) {
    return var::visit([this](const auto& c) { return erase(c); }, c);
}
bool ConvoInfoVolatile::erase_1to1(std::string_view session_id) {
    return erase(convo::one_to_one{session_id});
}
bool ConvoInfoVolatile::erase_community(std::string_view base_url, std::string_view room) {
    return erase(convo::community{base_url, room});
}
bool ConvoInfoVolatile::erase_group(std::string_view id) {
    return erase(convo::group{id});
}
bool ConvoInfoVolatile::erase_legacy_group(std::string_view id) {
    return erase(convo::legacy_group{id});
}

size_t ConvoInfoVolatile::size_1to1() const {
    if (auto* d = data["1"].dict())
        return d->size();
    return 0;
}

size_t ConvoInfoVolatile::size_communities() const {
    size_t count = 0;
    auto og = data["o"];
    if (auto* servers = og.dict()) {
        for (const auto& [baseurl, info] : *servers) {
            auto server = og[baseurl];
            if (!server["#"].exists<std::string>())
                continue;
            auto rooms = server["R"];
            if (auto* rd = rooms.dict())
                count += rd->size();
        }
    }
    return count;
}

size_t ConvoInfoVolatile::size_groups() const {
    if (auto* d = data["g"].dict())
        return d->size();
    return 0;
}

size_t ConvoInfoVolatile::size_legacy_groups() const {
    if (auto* d = data["C"].dict())
        return d->size();
    return 0;
}

size_t ConvoInfoVolatile::size() const {
    return size_1to1() + size_communities() + size_legacy_groups() + size_groups();
}

ConvoInfoVolatile::iterator::iterator(
        const DictFieldRoot& data, bool oneto1, bool communities, bool groups, bool legacy_groups) {
    if (oneto1)
        if (auto* d = data["1"].dict()) {
            _it_11 = d->begin();
            _end_11 = d->end();
        }
    if (communities)
        if (auto* d = data["o"].dict())
            _it_comm.emplace(d->begin(), d->end());
    if (groups)
        if (auto* d = data["g"].dict()) {
            _it_group = d->begin();
            _end_group = d->end();
        }
    if (legacy_groups)
        if (auto* d = data["C"].dict()) {
            _it_lgroup = d->begin();
            _end_lgroup = d->end();
        }
    _load_val();
}

class val_loader {
  public:
    template <typename ConvoType>
    static bool load(
            std::shared_ptr<convo::any>& val,
            std::optional<dict::const_iterator>& it,
            std::optional<dict::const_iterator>& end,
            char prefix) {
        while (it) {
            if (*it == *end) {
                it.reset();
                end.reset();
                return false;
            }

            auto& [k, v] = **it;

            if (k.size() == 33 && k[0] == prefix) {
                if (auto* info_dict = std::get_if<dict>(&v)) {
                    val = std::make_shared<convo::any>(ConvoType{oxenc::to_hex(k)});
                    std::get<ConvoType>(*val).load(*info_dict);
                    return true;
                }
            }
            ++*it;
        }
        return false;
    }
};

/// Load _val from the current iterator position; if it is invalid, skip to the next key until we
/// find one that is valid (or hit the end).  We also span across four different iterators: we
/// exhaust, in order: _it_11, _it_group, _it_comm, _it_lgroup.
///
/// We *always* call this after incrementing the iterator (and after iterator initialization), and
/// this is responsible for making sure that _it_11, _it_group, etc. are only set to non-nullopt if
/// the respective sub-iterator is *not* at the end (and resetting them when we hit the end).  Thus,
/// after calling this, our "end" condition will be simply that all of the three iterators are
/// nullopt.
void ConvoInfoVolatile::iterator::_load_val() {
    if (val_loader::load<convo::one_to_one>(_val, _it_11, _end_11, 0x05))
        return;

    if (val_loader::load<convo::group>(_val, _it_group, _end_group, 0x03))
        return;

    if (_it_comm) {
        if (_it_comm->load<convo::community>(_val))
            return;
        else
            _it_comm.reset();
    }

    if (val_loader::load<convo::legacy_group>(_val, _it_lgroup, _end_lgroup, 0x05))
        return;
}

bool ConvoInfoVolatile::iterator::operator==(const iterator& other) const {
    return _it_11 == other._it_11 && _it_group == other._it_group && _it_comm == other._it_comm &&
           _it_lgroup == other._it_lgroup;
}

bool ConvoInfoVolatile::iterator::done() const {
    return !_it_11 && !_it_group && (!_it_comm || _it_comm->done()) && !_it_lgroup;
}

ConvoInfoVolatile::iterator& ConvoInfoVolatile::iterator::operator++() {
    if (_it_11)
        ++*_it_11;
    else if (_it_group)
        ++*_it_group;
    else if (_it_comm && !_it_comm->done())
        _it_comm->advance();
    else {
        assert(_it_lgroup);
        ++*_it_lgroup;
    }
    _load_val();
    return *this;
}

}  // namespace session::config

using namespace session::config;

extern "C" {
struct convo_info_volatile_iterator {
    void* _internals;
};
}

LIBSESSION_C_API
int convo_info_volatile_init(
        config_object** conf,
        const unsigned char* ed25519_secretkey_bytes,
        const unsigned char* dumpstr,
        size_t dumplen,
        char* error) {
    return c_wrapper_init<ConvoInfoVolatile>(
            conf, ed25519_secretkey_bytes, dumpstr, dumplen, error);
}

LIBSESSION_C_API bool convo_info_volatile_get_1to1(
        config_object* conf, convo_info_volatile_1to1* convo, const char* session_id) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<ConvoInfoVolatile>(conf)->get_1to1(session_id)) {
            c->into(*convo);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}

LIBSESSION_C_API bool convo_info_volatile_get_or_construct_1to1(
        config_object* conf, convo_info_volatile_1to1* convo, const char* session_id) {
    try {
        conf->last_error = nullptr;
        unbox<ConvoInfoVolatile>(conf)->get_or_construct_1to1(session_id).into(*convo);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}

LIBSESSION_C_API bool convo_info_volatile_get_community(
        config_object* conf,
        convo_info_volatile_community* og,
        const char* base_url,
        const char* room) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<ConvoInfoVolatile>(conf)->get_community(base_url, room)) {
            c->into(*og);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}
LIBSESSION_C_API bool convo_info_volatile_get_or_construct_community(
        config_object* conf,
        convo_info_volatile_community* convo,
        const char* base_url,
        const char* room,
        unsigned const char* pubkey) {
    try {
        conf->last_error = nullptr;
        unbox<ConvoInfoVolatile>(conf)
                ->get_or_construct_community(base_url, room, ustring_view{pubkey, 32})
                .into(*convo);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}

LIBSESSION_C_API bool convo_info_volatile_get_group(
        config_object* conf, convo_info_volatile_group* convo, const char* id) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<ConvoInfoVolatile>(conf)->get_group(id)) {
            c->into(*convo);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}

LIBSESSION_C_API bool convo_info_volatile_get_or_construct_group(
        config_object* conf, convo_info_volatile_group* convo, const char* id) {
    try {
        conf->last_error = nullptr;
        unbox<ConvoInfoVolatile>(conf)->get_or_construct_group(id).into(*convo);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}

LIBSESSION_C_API bool convo_info_volatile_get_legacy_group(
        config_object* conf, convo_info_volatile_legacy_group* convo, const char* id) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<ConvoInfoVolatile>(conf)->get_legacy_group(id)) {
            c->into(*convo);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}

LIBSESSION_C_API bool convo_info_volatile_get_or_construct_legacy_group(
        config_object* conf, convo_info_volatile_legacy_group* convo, const char* id) {
    try {
        conf->last_error = nullptr;
        unbox<ConvoInfoVolatile>(conf)->get_or_construct_legacy_group(id).into(*convo);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}

LIBSESSION_C_API void convo_info_volatile_set_1to1(
        config_object* conf, const convo_info_volatile_1to1* convo) {
    unbox<ConvoInfoVolatile>(conf)->set(convo::one_to_one{*convo});
}
LIBSESSION_C_API void convo_info_volatile_set_community(
        config_object* conf, const convo_info_volatile_community* convo) {
    unbox<ConvoInfoVolatile>(conf)->set(convo::community{*convo});
}
LIBSESSION_C_API void convo_info_volatile_set_group(
        config_object* conf, const convo_info_volatile_group* convo) {
    unbox<ConvoInfoVolatile>(conf)->set(convo::group{*convo});
}
LIBSESSION_C_API void convo_info_volatile_set_legacy_group(
        config_object* conf, const convo_info_volatile_legacy_group* convo) {
    unbox<ConvoInfoVolatile>(conf)->set(convo::legacy_group{*convo});
}

LIBSESSION_C_API bool convo_info_volatile_erase_1to1(config_object* conf, const char* session_id) {
    try {
        return unbox<ConvoInfoVolatile>(conf)->erase_1to1(session_id);
    } catch (...) {
        return false;
    }
}
LIBSESSION_C_API bool convo_info_volatile_erase_community(
        config_object* conf, const char* base_url, const char* room) {
    try {
        return unbox<ConvoInfoVolatile>(conf)->erase_community(base_url, room);
    } catch (...) {
        return false;
    }
}
LIBSESSION_C_API bool convo_info_volatile_erase_group(config_object* conf, const char* group_id) {
    try {
        return unbox<ConvoInfoVolatile>(conf)->erase_group(group_id);
    } catch (...) {
        return false;
    }
}
LIBSESSION_C_API bool convo_info_volatile_erase_legacy_group(
        config_object* conf, const char* group_id) {
    try {
        return unbox<ConvoInfoVolatile>(conf)->erase_legacy_group(group_id);
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API size_t convo_info_volatile_size(const config_object* conf) {
    return unbox<ConvoInfoVolatile>(conf)->size();
}
LIBSESSION_C_API size_t convo_info_volatile_size_1to1(const config_object* conf) {
    return unbox<ConvoInfoVolatile>(conf)->size_1to1();
}
LIBSESSION_C_API size_t convo_info_volatile_size_communities(const config_object* conf) {
    return unbox<ConvoInfoVolatile>(conf)->size_communities();
}
LIBSESSION_C_API size_t convo_info_volatile_size_groups(const config_object* conf) {
    return unbox<ConvoInfoVolatile>(conf)->size_groups();
}
LIBSESSION_C_API size_t convo_info_volatile_size_legacy_groups(const config_object* conf) {
    return unbox<ConvoInfoVolatile>(conf)->size_legacy_groups();
}

LIBSESSION_C_API convo_info_volatile_iterator* convo_info_volatile_iterator_new(
        const config_object* conf) {
    auto* it = new convo_info_volatile_iterator{};
    it->_internals = new ConvoInfoVolatile::iterator{unbox<ConvoInfoVolatile>(conf)->begin()};
    return it;
}

LIBSESSION_C_API convo_info_volatile_iterator* convo_info_volatile_iterator_new_1to1(
        const config_object* conf) {
    auto* it = new convo_info_volatile_iterator{};
    it->_internals = new ConvoInfoVolatile::iterator{unbox<ConvoInfoVolatile>(conf)->begin_1to1()};
    return it;
}
LIBSESSION_C_API convo_info_volatile_iterator* convo_info_volatile_iterator_new_communities(
        const config_object* conf) {
    auto* it = new convo_info_volatile_iterator{};
    it->_internals =
            new ConvoInfoVolatile::iterator{unbox<ConvoInfoVolatile>(conf)->begin_communities()};
    return it;
}
LIBSESSION_C_API convo_info_volatile_iterator* convo_info_volatile_iterator_new_groups(
        const config_object* conf) {
    auto* it = new convo_info_volatile_iterator{};
    it->_internals =
            new ConvoInfoVolatile::iterator{unbox<ConvoInfoVolatile>(conf)->begin_groups()};
    return it;
}
LIBSESSION_C_API convo_info_volatile_iterator* convo_info_volatile_iterator_new_legacy_groups(
        const config_object* conf) {
    auto* it = new convo_info_volatile_iterator{};
    it->_internals =
            new ConvoInfoVolatile::iterator{unbox<ConvoInfoVolatile>(conf)->begin_legacy_groups()};
    return it;
}

LIBSESSION_C_API void convo_info_volatile_iterator_free(convo_info_volatile_iterator* it) {
    delete static_cast<ConvoInfoVolatile::iterator*>(it->_internals);
    delete it;
}

LIBSESSION_C_API bool convo_info_volatile_iterator_done(convo_info_volatile_iterator* it) {
    auto& real = *static_cast<ConvoInfoVolatile::iterator*>(it->_internals);
    return real.done();
}

LIBSESSION_C_API void convo_info_volatile_iterator_advance(convo_info_volatile_iterator* it) {
    ++*static_cast<ConvoInfoVolatile::iterator*>(it->_internals);
}

namespace {
template <typename Cpp, typename C>
bool convo_info_volatile_it_is_impl(convo_info_volatile_iterator* it, C* c) {
    auto& convo = **static_cast<ConvoInfoVolatile::iterator*>(it->_internals);
    if (auto* d = std::get_if<Cpp>(&convo)) {
        d->into(*c);
        return true;
    }
    return false;
}
}  // namespace

LIBSESSION_C_API bool convo_info_volatile_it_is_1to1(
        convo_info_volatile_iterator* it, convo_info_volatile_1to1* c) {
    return convo_info_volatile_it_is_impl<convo::one_to_one>(it, c);
}

LIBSESSION_C_API bool convo_info_volatile_it_is_community(
        convo_info_volatile_iterator* it, convo_info_volatile_community* c) {
    return convo_info_volatile_it_is_impl<convo::community>(it, c);
}

LIBSESSION_C_API bool convo_info_volatile_it_is_group(
        convo_info_volatile_iterator* it, convo_info_volatile_group* c) {
    return convo_info_volatile_it_is_impl<convo::group>(it, c);
}

LIBSESSION_C_API bool convo_info_volatile_it_is_legacy_group(
        convo_info_volatile_iterator* it, convo_info_volatile_legacy_group* c) {
    return convo_info_volatile_it_is_impl<convo::legacy_group>(it, c);
}
