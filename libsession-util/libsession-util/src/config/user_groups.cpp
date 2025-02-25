#include "session/config/user_groups.hpp"

#include <oxenc/base32z.h>
#include <oxenc/base64.h>
#include <oxenc/hex.h>
#include <oxenc/variant.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_sign.h>

#include <charconv>
#include <iterator>
#include <stdexcept>
#include <variant>

#include "internal.hpp"
#include "session/config/error.h"
#include "session/config/user_groups.h"
#include "session/export.h"
#include "session/types.hpp"
#include "session/util.hpp"

using namespace std::literals;
using session::ustring_view;

LIBSESSION_C_API const size_t GROUP_NAME_MAX_LENGTH =
        session::config::legacy_group_info::NAME_MAX_LENGTH;

namespace {
struct ugroups_internals {
    std::map<std::string, bool> members;
};
}  // namespace

namespace session::config {

template <typename T>
static void base_into(const base_group_info& self, T& c) {
    c.priority = self.priority;
    c.joined_at = self.joined_at;
    c.notifications = static_cast<CONVO_NOTIFY_MODE>(self.notifications);
    c.mute_until = self.mute_until;
    c.invited = self.invited;
}

template <typename T>
static void base_from(base_group_info& self, const T& c) {
    self.priority = c.priority;
    self.joined_at = c.joined_at;
    self.notifications = static_cast<notify_mode>(c.notifications);
    self.mute_until = c.mute_until;
    self.invited = c.invited;
}

group_info::group_info(std::string sid) : id{std::move(sid)} {
    check_session_id(id, "03");
}

legacy_group_info::legacy_group_info(std::string sid) : session_id{std::move(sid)} {
    check_session_id(session_id);
}

community_info::community_info(const ugroups_community_info& c) :
        community_info{c.base_url, c.room, ustring_view{c.pubkey, 32}} {
    base_from(*this, c);
}

void community_info::into(ugroups_community_info& c) const {
    static_assert(sizeof(c.base_url) == BASE_URL_MAX_LENGTH + 1);
    static_assert(sizeof(c.room) == ROOM_MAX_LENGTH + 1);
    base_into(*this, c);
    copy_c_str(c.base_url, base_url());
    copy_c_str(c.room, room());
    std::memcpy(c.pubkey, pubkey().data(), 32);
}

static_assert(sizeof(ugroups_legacy_group_info::name) == base_group_info::NAME_MAX_LENGTH + 1);
static_assert(sizeof(ugroups_group_info::name) == base_group_info::NAME_MAX_LENGTH + 1);

legacy_group_info::legacy_group_info(const ugroups_legacy_group_info& c, impl_t) :
        session_id{c.session_id, 66}, disappearing_timer{c.disappearing_timer} {
    name = c.name;
    assert(name.size() <= NAME_MAX_LENGTH);  // Otherwise the caller messed up
    base_from(*this, c);
    if (c.have_enc_keys) {
        enc_pubkey.assign(c.enc_pubkey, 32);
        enc_seckey.assign(c.enc_seckey, 32);
    }
}

legacy_group_info::legacy_group_info(const ugroups_legacy_group_info& c) :
        legacy_group_info{c, impl} {
    if (c._internal)
        members_ = static_cast<const ugroups_internals*>(c._internal)->members;
}

legacy_group_info::legacy_group_info(ugroups_legacy_group_info&& c) : legacy_group_info{c, impl} {
    if (c._internal) {
        auto* internals = static_cast<ugroups_internals*>(c._internal);
        members_ = std::move(internals->members);
        delete internals;
        c._internal = nullptr;
    }
}

void legacy_group_info::into(ugroups_legacy_group_info& c, impl_t) const {
    assert(session_id.size() == 66);
    base_into(*this, c);
    copy_c_str(c.session_id, session_id);
    copy_c_str(c.name, name);
    c.have_enc_keys = enc_pubkey.size() == 32 && enc_seckey.size() == 32;
    if (c.have_enc_keys) {
        std::memcpy(c.enc_pubkey, enc_pubkey.data(), 32);
        std::memcpy(c.enc_seckey, enc_seckey.data(), 32);
    }
    c.disappearing_timer = disappearing_timer.count();
    if (c._internal)
        static_cast<ugroups_internals*>(c._internal)->members.clear();
    else
        c._internal = new ugroups_internals{};
}
void legacy_group_info::into(ugroups_legacy_group_info& c) const& {
    into(c, impl);
    static_cast<ugroups_internals*>(c._internal)->members = members_;
}
void legacy_group_info::into(ugroups_legacy_group_info& c) && {
    into(c, impl);
    static_cast<ugroups_internals*>(c._internal)->members = std::move(members_);
}

void base_group_info::load(const dict& info_dict) {
    priority = maybe_int(info_dict, "+").value_or(0);
    joined_at = std::max<int64_t>(0, maybe_int(info_dict, "j").value_or(0));

    int notify = maybe_int(info_dict, "@").value_or(0);
    if (notify >= 0 && notify <= 3)
        notifications = static_cast<notify_mode>(notify);
    else
        notifications = notify_mode::defaulted;

    mute_until = maybe_int(info_dict, "!").value_or(0);

    invited = maybe_int(info_dict, "i").value_or(0);
}

void legacy_group_info::load(const dict& info_dict) {
    base_group_info::load(info_dict);

    if (auto n = maybe_string(info_dict, "n"))
        name = std::move(*n);
    else
        name.clear();

    auto enc_pub = maybe_ustring(info_dict, "k");
    auto enc_sec = maybe_ustring(info_dict, "K");
    if (enc_pub && enc_sec && enc_pub->size() == 32 && enc_sec->size() == 32) {
        enc_pubkey = std::move(*enc_pub);
        enc_seckey = std::move(*enc_sec);
    } else {
        enc_pubkey.clear();
        enc_seckey.clear();
    }
    if (auto secs = maybe_int(info_dict, "E").value_or(0); secs > 0)
        disappearing_timer = std::chrono::seconds{secs};
    else
        disappearing_timer = 0s;

    members_.clear();
    if (auto* members = maybe_set(info_dict, "m"))
        for (const auto& field : *members)
            if (auto* s = std::get_if<std::string>(&field))
                if (s->size() == 33 && (*s)[0] == 0x05)
                    members_.emplace_hint(members_.end(), oxenc::to_hex(*s), false);

    if (auto* members = maybe_set(info_dict, "a"))
        for (const auto& field : *members)
            if (auto* s = std::get_if<std::string>(&field))
                if (s->size() == 33 && (*s)[0] == 0x05)
                    members_.emplace(oxenc::to_hex(*s), true);
}

std::pair<size_t, size_t> legacy_group_info::counts() const {
    std::pair<size_t, size_t> counts{0, 0};
    auto& [admins, members] = counts;
    for (const auto& [sid, admin] : members_)
        ++(admin ? admins : members);
    return counts;
}

bool legacy_group_info::insert(std::string session_id, bool admin) {
    check_session_id(session_id);
    auto [it, inserted] = members_.emplace(std::move(session_id), admin);
    if (inserted)
        return true;
    if (it->second != admin) {
        it->second = admin;
        return true;
    }
    return false;
}

bool legacy_group_info::erase(const std::string& session_id) {
    return members_.erase(session_id);
}

group_info::group_info(const ugroups_group_info& c) : id{c.id, 66} {
    base_from(*this, c);

    name = c.name;
    removed_status = c.removed_status;
    assert(name.size() <= NAME_MAX_LENGTH);  // Otherwise the caller messed up

    if (c.have_secretkey)
        secretkey.assign(c.secretkey, 64);
    if (c.have_auth_data)
        auth_data.assign(c.auth_data, sizeof(c.auth_data));
}

void group_info::into(ugroups_group_info& c) const {
    assert(id.size() == 66);
    base_into(*this, c);
    copy_c_str(c.id, id);
    copy_c_str(c.name, name);
    c.removed_status = removed_status;
    if ((c.have_secretkey = secretkey.size() == 64))
        std::memcpy(c.secretkey, secretkey.data(), 64);
    if ((c.have_auth_data = auth_data.size() == 100))
        std::memcpy(c.auth_data, auth_data.data(), 100);
}

void group_info::load(const dict& info_dict) {
    base_group_info::load(info_dict);

    if (auto n = maybe_string(info_dict, "n"))
        name = std::move(*n);
    else
        name.clear();

    if (auto seed = maybe_ustring(info_dict, "K"); seed && seed->size() == 32) {
        std::array<unsigned char, 33> pk;
        pk[0] = 0x03;
        secretkey.resize(64);
        crypto_sign_seed_keypair(pk.data() + 1, secretkey.data(), seed->data());
        if (id != oxenc::to_hex(pk.begin(), pk.end()))
            secretkey.clear();
    }
    if (auto sig = maybe_ustring(info_dict, "s"); sig && sig->size() == 100)
        auth_data = std::move(*sig);

    removed_status = maybe_int(info_dict, "r").value_or(0);
}

void group_info::mark_kicked() {
    secretkey.clear();
    auth_data.clear();
    if (removed_status != GROUP_DESTROYED) {
        removed_status = KICKED_FROM_GROUP;
    }
}

void group_info::mark_invited() {
    if (removed_status == KICKED_FROM_GROUP) {
        removed_status = NOT_REMOVED;
    }
}

bool group_info::kicked() const {
    return removed_status == KICKED_FROM_GROUP;
}

void group_info::mark_destroyed() {
    secretkey.clear();
    auth_data.clear();
    removed_status = GROUP_DESTROYED;
}

bool group_info::is_destroyed() const {
    return removed_status == GROUP_DESTROYED;
}

void community_info::load(const dict& info_dict) {
    base_group_info::load(info_dict);

    if (auto n = maybe_string(info_dict, "n"))
        set_room(std::move(*n));
}

UserGroups::UserGroups(ustring_view ed25519_secretkey, std::optional<ustring_view> dumped) :
        ConfigBase{dumped} {
    load_key(ed25519_secretkey);
}

ConfigBase::DictFieldProxy UserGroups::community_field(
        const community_info& og, ustring_view* get_pubkey) const {
    auto record = data["o"][og.base_url()];
    if (get_pubkey) {
        auto pkrec = record["#"];
        if (auto pk = pkrec.string_view_or(""); pk.size() == 32)
            *get_pubkey =
                    ustring_view{reinterpret_cast<const unsigned char*>(pk.data()), pk.size()};
    }
    return record["R"][og.room_norm()];
}

std::optional<community_info> UserGroups::get_community(
        std::string_view base_url, std::string_view room) const {
    community_info og{base_url, room};

    ustring_view pubkey;
    if (auto* info_dict = community_field(og, &pubkey).dict()) {
        og.load(*info_dict);
        if (!pubkey.empty())
            og.set_pubkey(pubkey);
        return std::move(og);
    }
    return std::nullopt;
}

std::optional<community_info> UserGroups::get_community(std::string_view partial_url) const {
    auto [base, room, pubkey] = community::parse_partial_url(partial_url);
    return get_community(base, room);
}

community_info UserGroups::get_or_construct_community(
        std::string_view base_url, std::string_view room, ustring_view pubkey) const {
    community_info result{base_url, room, pubkey};

    if (auto* info_dict = community_field(result).dict())
        result.load(*info_dict);

    return result;
}

community_info UserGroups::get_or_construct_community(
        std::string_view base_url, std::string_view room, std::string_view pubkey_encoded) const {
    community_info result{base_url, room, pubkey_encoded};

    if (auto* info_dict = community_field(result).dict())
        result.load(*info_dict);

    return result;
}

community_info UserGroups::get_or_construct_community(std::string_view full_url) const {
    auto [base, room, pubkey] = community::parse_full_url(full_url);
    return get_or_construct_community(base, room, pubkey);
}

std::optional<legacy_group_info> UserGroups::get_legacy_group(std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex);

    auto* info_dict = data["C"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<legacy_group_info>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

legacy_group_info UserGroups::get_or_construct_legacy_group(std::string_view pubkey_hex) const {
    if (auto maybe = get_legacy_group(pubkey_hex))
        return *std::move(maybe);

    return legacy_group_info{std::string{pubkey_hex}};
}

std::optional<group_info> UserGroups::get_group(std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex, "03");

    auto* info_dict = data["g"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<group_info>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

group_info UserGroups::get_or_construct_group(std::string_view pubkey_hex) const {
    if (auto maybe = get_group(pubkey_hex))
        return *std::move(maybe);

    return group_info{std::string{pubkey_hex}};
}

group_info UserGroups::create_group() const {
    std::array<unsigned char, 32> pk;
    ustring sk;
    sk.resize(64);
    crypto_sign_keypair(pk.data(), sk.data());
    std::string pk_hex;
    pk_hex.reserve(66);
    pk_hex += "03";
    oxenc::to_hex(pk.begin(), pk.end(), std::back_inserter(pk_hex));

    group_info gr{std::move(pk_hex)};
    gr.secretkey = std::move(sk);
    return gr;
}

void UserGroups::set(const community_info& c) {
    data["o"][c.base_url()]["#"] = c.pubkey();
    auto info = community_field(c);  // data["o"][base]["R"][lc_room]
    set_base(c, info);
    info["n"] = c.room();
}

void UserGroups::set_base(const base_group_info& bg, DictFieldProxy& info) const {
    set_nonzero_int(info["+"], bg.priority);
    set_positive_int(info["j"], bg.joined_at);
    set_positive_int(info["@"], static_cast<int>(bg.notifications));
    set_positive_int(info["!"], bg.mute_until);
    set_flag(info["i"], bg.invited);
    // We don't set n here because it's subtly different in the three group types
}

void UserGroups::set(const legacy_group_info& g) {
    auto info = data["C"][session_id_to_bytes(g.session_id)];
    set_base(g, info);
    info["n"] = std::string_view{g.name}.substr(0, legacy_group_info::NAME_MAX_LENGTH);

    set_pair_if(
            g.enc_pubkey.size() == 32 && g.enc_seckey.size() == 32,
            info["k"],
            g.enc_pubkey,
            info["K"],
            g.enc_seckey);

    config::set members, admins;
    for (const auto& [member, admin] : g.members_) {
        assert(oxenc::is_hex(member));
        (admin ? admins : members).emplace(oxenc::from_hex(member));
    }
    info["m"] = std::move(members);
    info["a"] = std::move(admins);
    set_positive_int(info["E"], g.disappearing_timer.count());
}

void UserGroups::set(const group_info& g) {
    auto pk_bytes = session_id_to_bytes(g.id, "03");
    auto info = data["g"][pk_bytes];
    set_base(g, info);

    set_nonempty_str(
            info["n"], std::string_view{g.name}.substr(0, legacy_group_info::NAME_MAX_LENGTH));
    set_positive_int(info["r"], g.removed_status);

    if (g.secretkey.size() == 64 &&
        // Make sure the secretkey's embedded pubkey matches the group id:
        ustring_view{g.secretkey.data() + 32, 32} ==
                ustring_view{
                        reinterpret_cast<const unsigned char*>(pk_bytes.data() + 1),
                        pk_bytes.size() - 1})
        info["K"] = ustring_view{g.secretkey.data(), 32};
    else {
        info["K"] = ustring_view{};
        if (g.auth_data.size() == 100)
            info["s"] = g.auth_data;
        else
            info["s"].erase();
    }
}

template <typename Field>
static bool erase_impl(Field convo) {
    bool ret = convo.exists();
    convo.erase();
    return ret;
}

bool UserGroups::erase(const community_info& c) {
    bool gone = erase_impl(community_field(c));
    if (gone) {
        // If this was the last room on the server, also remove the server (otherwise it would
        // persist because of the "#" pubkey).
        auto server_info = data["o"][c.base_url()];
        auto rooms = server_info["R"];
        if (auto* rd = rooms.dict(); !rd || rd->empty()) {
            rooms.erase();
            server_info.erase();
        }
    }
    return gone;
}
bool UserGroups::erase(const group_info& c) {
    return erase_impl(data["g"][session_id_to_bytes(c.id, "03")]);
}
bool UserGroups::erase(const legacy_group_info& c) {
    return erase_impl(data["C"][session_id_to_bytes(c.session_id)]);
}

bool UserGroups::erase(const any_group_info& c) {
    return var::visit([this](const auto& c) { return erase(c); }, c);
}
bool UserGroups::erase_community(std::string_view base_url, std::string_view room) {
    return erase(community_info{base_url, room});
}
bool UserGroups::erase_legacy_group(std::string_view id) {
    return erase(legacy_group_info{std::string{id}});
}
bool UserGroups::erase_group(std::string_view id) {
    return erase(group_info{std::string{id}});
}

size_t UserGroups::size_communities() const {
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

size_t UserGroups::size_legacy_groups() const {
    if (auto* d = data["C"].dict())
        return d->size();
    return 0;
}

size_t UserGroups::size_groups() const {
    if (auto* d = data["g"].dict())
        return d->size();
    return 0;
}

size_t UserGroups::size() const {
    return size_communities() + size_legacy_groups() + size_groups();
}

UserGroups::iterator::iterator(
        const DictFieldRoot& data, bool groups, bool communities, bool legacy_groups) {
    if (groups)
        if (auto* d = data["g"].dict()) {
            _it_group = d->begin();
            _end_group = d->end();
        }
    if (communities)
        if (auto* d = data["o"].dict())
            _it_comm.emplace(d->begin(), d->end());
    if (legacy_groups)
        if (auto* d = data["C"].dict()) {
            _it_legacy = d->begin();
            _end_legacy = d->end();
        }
    _load_val();
}

template <typename GroupInfo>
bool UserGroups::iterator::check_it() {
    static_assert(
            std::is_same_v<GroupInfo, legacy_group_info> || std::is_same_v<GroupInfo, group_info>);
    constexpr bool legacy = std::is_same_v<GroupInfo, legacy_group_info>;
    auto& it = legacy ? _it_legacy : _it_group;
    auto& end = legacy ? _end_legacy : _end_group;

    constexpr char prefix = legacy ? 0x05 : 0x03;
    while (it) {
        if (*it == *end) {
            it.reset();
            end.reset();
            break;
        }

        auto& [k, v] = **it;

        if (k.size() == 33 && k[0] == prefix) {
            if (auto* info_dict = std::get_if<dict>(&v)) {
                _val = std::make_shared<any_group_info>(GroupInfo{oxenc::to_hex(k)});
                std::get<GroupInfo>(*_val).load(*info_dict);
                return true;
            }
        }
        ++*it;
    }
    return false;
}

/// Load _val from the current iterator position; if it is invalid, skip to the next key until
/// we find one that is valid (or hit the end).  We also span across three different iterators:
/// first we exhaust communities, then legacy groups.
///
/// We *always* call this after incrementing the iterators (and after iterator initialization),
/// and this is responsible for making sure that the the _it variables are set up as required.
void UserGroups::iterator::_load_val() {
    if (check_it<group_info>())
        return;

    if (_it_comm) {
        if (_it_comm->load<community_info>(_val))
            return;
        else
            _it_comm.reset();
    }

    if (check_it<legacy_group_info>())
        return;
}

bool UserGroups::iterator::operator==(const iterator& other) const {
    return _it_group == other._it_group && _it_comm == other._it_comm &&
           _it_legacy == other._it_legacy;
}

bool UserGroups::iterator::done() const {
    return !_it_group && !_it_comm && !_it_legacy;
}

UserGroups::iterator& UserGroups::iterator::operator++() {
    if (_it_group)
        ++*_it_group;
    else if (_it_comm)
        _it_comm->advance();
    else {
        assert(_it_legacy);
        ++*_it_legacy;
    }
    _load_val();
    return *this;
}

}  // namespace session::config

using namespace session::config;

extern "C" {
struct user_groups_iterator {
    UserGroups::iterator it;
};
}

LIBSESSION_C_API
int user_groups_init(
        config_object** conf,
        const unsigned char* ed25519_secretkey_bytes,
        const unsigned char* dumpstr,
        size_t dumplen,
        char* error) {
    return c_wrapper_init<UserGroups>(conf, ed25519_secretkey_bytes, dumpstr, dumplen, error);
}

LIBSESSION_C_API bool user_groups_get_community(
        config_object* conf, ugroups_community_info* comm, const char* base_url, const char* room) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<UserGroups>(conf)->get_community(base_url, room)) {
            c->into(*comm);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}
LIBSESSION_C_API bool user_groups_get_or_construct_community(
        config_object* conf,
        ugroups_community_info* comm,
        const char* base_url,
        const char* room,
        unsigned const char* pubkey) {
    try {
        conf->last_error = nullptr;
        unbox<UserGroups>(conf)
                ->get_or_construct_community(base_url, room, ustring_view{pubkey, 32})
                .into(*comm);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}
LIBSESSION_C_API bool user_groups_get_group(
        config_object* conf, ugroups_group_info* group, const char* group_id) {
    try {
        conf->last_error = nullptr;
        if (auto g = unbox<UserGroups>(conf)->get_group(group_id)) {
            g->into(*group);
            return true;
        }
    } catch (const std::exception& e) {
        set_error(conf, e.what());
    }
    return false;
}
LIBSESSION_C_API bool user_groups_get_or_construct_group(
        config_object* conf, ugroups_group_info* group, const char* group_id) {
    try {
        conf->last_error = nullptr;
        unbox<UserGroups>(conf)->get_or_construct_group(group_id).into(*group);
        return true;
    } catch (const std::exception& e) {
        set_error(conf, e.what());
        return false;
    }
}

LIBSESSION_C_API void ugroups_legacy_group_free(ugroups_legacy_group_info* group) {
    if (group && group->_internal) {
        delete static_cast<ugroups_internals*>(group->_internal);
        group->_internal = nullptr;
    }
}

LIBSESSION_C_API ugroups_legacy_group_info* user_groups_get_legacy_group(
        config_object* conf, const char* id) {
    try {
        conf->last_error = nullptr;
        auto group = std::make_unique<ugroups_legacy_group_info>();
        group->_internal = nullptr;
        if (auto c = unbox<UserGroups>(conf)->get_legacy_group(id)) {
            std::move(c)->into(*group);
            return group.release();
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return nullptr;
}

LIBSESSION_C_API ugroups_legacy_group_info* user_groups_get_or_construct_legacy_group(
        config_object* conf, const char* id) {
    try {
        conf->last_error = nullptr;
        auto group = std::make_unique<ugroups_legacy_group_info>();
        group->_internal = nullptr;
        unbox<UserGroups>(conf)->get_or_construct_legacy_group(id).into(*group);
        return group.release();
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return nullptr;
    }
}

LIBSESSION_C_API void user_groups_set_community(
        config_object* conf, const ugroups_community_info* comm) {
    unbox<UserGroups>(conf)->set(community_info{*comm});
}
LIBSESSION_C_API void user_groups_set_group(config_object* conf, const ugroups_group_info* group) {
    unbox<UserGroups>(conf)->set(group_info{*group});
}
LIBSESSION_C_API void user_groups_set_legacy_group(
        config_object* conf, const ugroups_legacy_group_info* group) {
    unbox<UserGroups>(conf)->set(legacy_group_info{*group});
}
LIBSESSION_C_API void user_groups_set_free_legacy_group(
        config_object* conf, ugroups_legacy_group_info* group) {
    unbox<UserGroups>(conf)->set(legacy_group_info{std::move(*group)});
}

LIBSESSION_C_API bool user_groups_erase_community(
        config_object* conf, const char* base_url, const char* room) {
    try {
        return unbox<UserGroups>(conf)->erase_community(base_url, room);
    } catch (...) {
        return false;
    }
}
LIBSESSION_C_API bool user_groups_erase_group(config_object* conf, const char* group_id) {
    try {
        return unbox<UserGroups>(conf)->erase_group(group_id);
    } catch (...) {
        return false;
    }
}
LIBSESSION_C_API bool user_groups_erase_legacy_group(config_object* conf, const char* group_id) {
    try {
        return unbox<UserGroups>(conf)->erase_legacy_group(group_id);
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API void ugroups_group_set_invited(ugroups_group_info* group) {
    if (group->removed_status == KICKED_FROM_GROUP) {
        group->removed_status = NOT_REMOVED;
    }
}

LIBSESSION_C_API void ugroups_group_set_kicked(ugroups_group_info* group) {
    assert(group);
    group->have_auth_data = false;
    group->have_secretkey = false;
    group->removed_status = KICKED_FROM_GROUP;
}
LIBSESSION_C_API bool ugroups_group_is_kicked(const ugroups_group_info* group) {
    return group->removed_status == KICKED_FROM_GROUP;
}

LIBSESSION_C_API void ugroups_group_set_destroyed(ugroups_group_info* group) {
    assert(group);
    group->have_auth_data = false;
    group->have_secretkey = false;
    group->removed_status = GROUP_DESTROYED;
}
LIBSESSION_C_API bool ugroups_group_is_destroyed(const ugroups_group_info* group) {
    return group->removed_status == GROUP_DESTROYED;
}

struct ugroups_legacy_members_iterator {
    using map_t = std::map<std::string, bool>;
    map_t& members;
    map_t::iterator it{members.begin()};
    bool need_advance = false;
};

LIBSESSION_C_API ugroups_legacy_members_iterator* ugroups_legacy_members_begin(
        ugroups_legacy_group_info* group) {
    return new ugroups_legacy_members_iterator{
            static_cast<ugroups_internals*>(group->_internal)->members};
}

LIBSESSION_C_API bool ugroups_legacy_members_next(
        ugroups_legacy_members_iterator* it, const char** session_id, bool* admin) {
    if (it->need_advance)
        ++it->it;
    else
        it->need_advance = true;

    if (it->it != it->members.end()) {
        *session_id = it->it->first.data();
        *admin = it->it->second;
        return true;
    }
    return false;
}

LIBSESSION_C_API
void ugroups_legacy_members_erase(ugroups_legacy_members_iterator* it) {
    it->it = it->members.erase(it->it);
    it->need_advance = false;
}

LIBSESSION_C_API
void ugroups_legacy_members_free(ugroups_legacy_members_iterator* it) {
    delete it;
}

LIBSESSION_C_API
bool ugroups_legacy_member_add(
        ugroups_legacy_group_info* group, const char* session_id, bool admin) {
    try {
        check_session_id(session_id);
    } catch (...) {
        return false;
    }
    auto [it, ins] =
            static_cast<ugroups_internals*>(group->_internal)->members.emplace(session_id, admin);
    if (ins)
        return true;
    if (it->second == admin)
        return false;

    it->second = admin;
    return true;
}

LIBSESSION_C_API
bool ugroups_legacy_member_remove(ugroups_legacy_group_info* group, const char* session_id) {
    return static_cast<ugroups_internals*>(group->_internal)->members.erase(session_id);
}

LIBSESSION_C_API size_t ugroups_legacy_members_count(
        const ugroups_legacy_group_info* group, size_t* members, size_t* admins) {
    const auto& mems = static_cast<const ugroups_internals*>(group->_internal)->members;
    if (members || admins) {
        if (members)
            *members = 0;
        if (admins)
            *admins = 0;
        for (const auto& [sid, admin] : mems) {
            if (admin) {
                if (admins)
                    ++*admins;
            } else {
                if (members)
                    ++*members;
            }
        }
    }
    return mems.size();
}

LIBSESSION_C_API size_t user_groups_size(const config_object* conf) {
    return unbox<UserGroups>(conf)->size();
}
LIBSESSION_C_API size_t user_groups_size_communities(const config_object* conf) {
    return unbox<UserGroups>(conf)->size_communities();
}
LIBSESSION_C_API size_t user_groups_size_groups(const config_object* conf) {
    return unbox<UserGroups>(conf)->size_groups();
}
LIBSESSION_C_API size_t user_groups_size_legacy_groups(const config_object* conf) {
    return unbox<UserGroups>(conf)->size_legacy_groups();
}

LIBSESSION_C_API user_groups_iterator* user_groups_iterator_new(const config_object* conf) {
    return new user_groups_iterator{{unbox<UserGroups>(conf)->begin()}};
}

LIBSESSION_C_API user_groups_iterator* user_groups_iterator_new_communities(
        const config_object* conf) {
    return new user_groups_iterator{{unbox<UserGroups>(conf)->begin_communities()}};
}
LIBSESSION_C_API user_groups_iterator* user_groups_iterator_new_groups(const config_object* conf) {
    return new user_groups_iterator{{unbox<UserGroups>(conf)->begin_groups()}};
}
LIBSESSION_C_API user_groups_iterator* user_groups_iterator_new_legacy_groups(
        const config_object* conf) {
    return new user_groups_iterator{{unbox<UserGroups>(conf)->begin_legacy_groups()}};
}

LIBSESSION_C_API void user_groups_iterator_free(user_groups_iterator* it) {
    delete it;
}

LIBSESSION_C_API bool user_groups_iterator_done(user_groups_iterator* it) {
    return it->it.done();
}

LIBSESSION_C_API void user_groups_iterator_advance(user_groups_iterator* it) {
    ++it->it;
}

namespace {
template <typename Cpp, typename C>
bool user_groups_it_is_impl(user_groups_iterator* it, C* c) {
    auto& convo = *it->it;
    if (auto* d = std::get_if<Cpp>(&convo)) {
        d->into(*c);
        return true;
    }
    return false;
}
}  // namespace

LIBSESSION_C_API bool user_groups_it_is_community(
        user_groups_iterator* it, ugroups_community_info* c) {
    return user_groups_it_is_impl<community_info>(it, c);
}

LIBSESSION_C_API bool user_groups_it_is_group(user_groups_iterator* it, ugroups_group_info* g) {
    return user_groups_it_is_impl<group_info>(it, g);
}

LIBSESSION_C_API bool user_groups_it_is_legacy_group(
        user_groups_iterator* it, ugroups_legacy_group_info* g) {
    return user_groups_it_is_impl<legacy_group_info>(it, g);
}
