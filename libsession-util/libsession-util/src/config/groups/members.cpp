#include "session/config/groups/members.hpp"

#include <oxenc/hex.h>

#include "../internal.hpp"
#include "session/config/groups/members.h"

namespace session::config::groups {

Members::Members(
        ustring_view ed25519_pubkey,
        std::optional<ustring_view> ed25519_secretkey,
        std::optional<ustring_view> dumped) :
        ConfigBase{dumped, ed25519_pubkey, ed25519_secretkey} {}

std::optional<member> Members::get(std::string_view pubkey_hex) const {
    std::string pubkey = session_id_to_bytes(pubkey_hex);

    auto* info_dict = data["m"][pubkey].dict();
    if (!info_dict)
        return std::nullopt;

    auto result = std::make_optional<member>(std::string{pubkey_hex});
    result->load(*info_dict);
    return result;
}

member Members::get_or_construct(std::string_view pubkey_hex) const {
    if (auto maybe = get(pubkey_hex))
        return *std::move(maybe);

    return member{std::string{pubkey_hex}};
}

void Members::set(const member& mem) {

    std::string pk = session_id_to_bytes(mem.session_id);
    auto info = data["m"][pk];

    // Always set the name, even if empty, to keep the dict from getting pruned if there are no
    // other entries.
    info["n"] = mem.name.substr(0, member::MAX_NAME_LENGTH);

    set_pair_if(
            mem.profile_picture,
            info["p"],
            mem.profile_picture.url,
            info["q"],
            mem.profile_picture.key);

    set_flag(info["A"], mem.admin);
    set_positive_int(info["P"], mem.promotion_status);
    set_positive_int(info["I"], mem.admin ? 0 : mem.invite_status);
    set_flag(info["s"], mem.supplement);
    set_positive_int(info["R"], mem.removed_status);
}

void member::load(const dict& info_dict) {
    name = maybe_string(info_dict, "n").value_or("");

    auto url = maybe_string(info_dict, "p");
    auto key = maybe_ustring(info_dict, "q");
    if (url && key && !url->empty() && key->size() == 32) {
        profile_picture.url = std::move(*url);
        profile_picture.key = std::move(*key);
    } else {
        profile_picture.clear();
    }

    admin = maybe_int(info_dict, "A").value_or(0);
    invite_status = admin ? 0 : maybe_int(info_dict, "I").value_or(0);
    promotion_status = maybe_int(info_dict, "P").value_or(0);
    removed_status = maybe_int(info_dict, "R").value_or(0);
    supplement = invite_status > 0 && !(admin || promotion_status > 0)
                       ? maybe_int(info_dict, "s").value_or(0)
                       : 0;
}

/// Load _val from the current iterator position; if it is invalid, skip to the next key until we
/// find one that is valid (or hit the end).
void Members::iterator::_load_info() {
    while (_it != _members->end()) {
        if (_it->first.size() == 33) {
            if (auto* info_dict = std::get_if<dict>(&_it->second)) {
                _val = std::make_shared<member>(oxenc::to_hex(_it->first));
                _val->load(*info_dict);
                return;
            }
        }

        // We found something we don't understand (wrong pubkey size, or not a dict value) so skip
        // it.
        ++_it;
    }
}

bool Members::iterator::operator==(const iterator& other) const {
    if (!_members && !other._members)
        return true;  // Both are end tombstones
    if (!other._members)
        // other is an "end" tombstone: return whether we are at the end
        return _it == _members->end();
    if (!_members)
        // we are an "end" tombstone: return whether the other one is at the end
        return other._it == other._members->end();
    return _it == other._it;
}

bool Members::iterator::done() const {
    return !_members || _it == _members->end();
}

Members::iterator& Members::iterator::operator++() {
    ++_it;
    _load_info();
    return *this;
}

bool Members::erase(std::string_view session_id) {
    std::string pk = session_id_to_bytes(session_id);
    auto info = data["m"][pk];
    bool ret = info.exists();
    info.erase();
    return ret;
}

size_t Members::size() const {
    if (auto d = data["m"].dict())
        return d->size();
    return 0;
}

member::member(std::string sid) : session_id{std::move(sid)} {
    check_session_id(session_id);
}

member::member(const config_group_member& m) : session_id{m.session_id, 66} {
    assert(std::strlen(m.name) <= MAX_NAME_LENGTH);
    name = m.name;
    assert(std::strlen(m.profile_pic.url) <= profile_pic::MAX_URL_LENGTH);
    if (std::strlen(m.profile_pic.url)) {
        profile_picture.url = m.profile_pic.url;
        profile_picture.key = {m.profile_pic.key, 32};
    }
    admin = m.admin;
    invite_status =
            (m.invited == STATUS_SENT || m.invited == STATUS_FAILED || m.invited == STATUS_NOT_SENT)
                    ? m.invited
                    : 0;
    promotion_status = (m.promoted == STATUS_SENT || m.promoted == STATUS_FAILED ||
                        m.invited == STATUS_NOT_SENT)
                             ? m.promoted
                             : 0;
    removed_status = (m.removed == REMOVED_MEMBER || m.removed == REMOVED_MEMBER_AND_MESSAGES)
                           ? m.removed
                           : 0;
    supplement = m.supplement;
}

void member::into(config_group_member& m) const {
    std::memcpy(m.session_id, session_id.data(), 67);
    copy_c_str(m.name, name);
    if (profile_picture) {
        copy_c_str(m.profile_pic.url, profile_picture.url);
        std::memcpy(m.profile_pic.key, profile_picture.key.data(), 32);
    } else {
        copy_c_str(m.profile_pic.url, "");
    }
    m.admin = admin;
    static_assert(groups::STATUS_SENT == ::STATUS_SENT);
    static_assert(groups::STATUS_FAILED == ::STATUS_FAILED);
    static_assert(groups::STATUS_NOT_SENT == ::STATUS_NOT_SENT);
    static_assert(
            static_cast<int>(groups::member::Status::invite_unknown) ==
            ::GROUP_MEMBER_STATUS_INVITE_UNKNOWN);
    static_assert(
            static_cast<int>(groups::member::Status::invite_not_sent) ==
            ::GROUP_MEMBER_STATUS_INVITE_NOT_SENT);
    static_assert(
            static_cast<int>(groups::member::Status::invite_failed) ==
            ::GROUP_MEMBER_STATUS_INVITE_FAILED);
    static_assert(
            static_cast<int>(groups::member::Status::invite_sent) ==
            ::GROUP_MEMBER_STATUS_INVITE_SENT);
    static_assert(
            static_cast<int>(groups::member::Status::invite_accepted) ==
            ::GROUP_MEMBER_STATUS_INVITE_ACCEPTED);
    static_assert(
            static_cast<int>(groups::member::Status::promotion_unknown) ==
            ::GROUP_MEMBER_STATUS_PROMOTION_UNKNOWN);
    static_assert(
            static_cast<int>(groups::member::Status::promotion_not_sent) ==
            ::GROUP_MEMBER_STATUS_PROMOTION_NOT_SENT);
    static_assert(
            static_cast<int>(groups::member::Status::promotion_failed) ==
            ::GROUP_MEMBER_STATUS_PROMOTION_FAILED);
    static_assert(
            static_cast<int>(groups::member::Status::promotion_sent) ==
            ::GROUP_MEMBER_STATUS_PROMOTION_SENT);
    static_assert(
            static_cast<int>(groups::member::Status::promotion_accepted) ==
            ::GROUP_MEMBER_STATUS_PROMOTION_ACCEPTED);
    static_assert(
            static_cast<int>(groups::member::Status::removed_unknown) ==
            ::GROUP_MEMBER_STATUS_REMOVED_UNKNOWN);
    static_assert(
            static_cast<int>(groups::member::Status::removed) == ::GROUP_MEMBER_STATUS_REMOVED);
    static_assert(
            static_cast<int>(groups::member::Status::removed_including_messages) ==
            ::GROUP_MEMBER_STATUS_REMOVED_MEMBER_AND_MESSAGES);
    m.invited = invite_status;
    m.promoted = promotion_status;
    m.removed = removed_status;
    m.supplement = supplement;
}

void member::set_name(std::string n) {
    if (n.size() > MAX_NAME_LENGTH)
        throw std::invalid_argument{"Invalid member name: exceeds maximum length"};
    name = std::move(n);
}

void member::set_name_truncated(std::string n) {
    set_name(utf8_truncate(std::move(n), MAX_NAME_LENGTH));
}

}  // namespace session::config::groups

using namespace session;
using namespace session::config;

LIBSESSION_C_API int groups_members_init(
        config_object** conf,
        const unsigned char* ed25519_pubkey,
        const unsigned char* ed25519_secretkey,
        const unsigned char* dump,
        size_t dumplen,
        char* error) {
    return c_group_wrapper_init<groups::Members>(
            conf, ed25519_pubkey, ed25519_secretkey, dump, dumplen, error);
}

LIBSESSION_C_API bool groups_members_get(
        config_object* conf, config_group_member* member, const char* session_id) {
    try {
        conf->last_error = nullptr;
        if (auto c = unbox<groups::Members>(conf)->get(session_id)) {
            c->into(*member);
            return true;
        }
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
    }
    return false;
}

LIBSESSION_C_API bool groups_members_get_or_construct(
        config_object* conf, config_group_member* member, const char* session_id) {
    try {
        conf->last_error = nullptr;
        unbox<groups::Members>(conf)->get_or_construct(session_id).into(*member);
        return true;
    } catch (const std::exception& e) {
        copy_c_str(conf->_error_buf, e.what());
        conf->last_error = conf->_error_buf;
        return false;
    }
}

LIBSESSION_C_API void groups_members_set(config_object* conf, const config_group_member* member) {
    unbox<groups::Members>(conf)->set(groups::member{*member});
}

LIBSESSION_C_API GROUP_MEMBER_STATUS group_member_status(const config_group_member* member) {
    try {
        auto m = groups::member{*member};
        return static_cast<GROUP_MEMBER_STATUS>(m.status());
    } catch (...) {
        return GROUP_MEMBER_STATUS_INVITE_NOT_SENT;
    }
}

LIBSESSION_C_API bool groups_members_set_invite_sent(config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_invite_sent();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_invite_failed(
        config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_invite_failed();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_invite_accepted(
        config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_invite_accepted();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_promoted(config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_promoted();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_promotion_sent(
        config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_promotion_sent();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_promotion_failed(
        config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_promotion_failed();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_promotion_accepted(
        config_object* conf, const char* session_id) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_promotion_accepted();
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_set_removed(
        config_object* conf, const char* session_id, bool messages) {
    try {
        if (auto m = unbox<groups::Members>(conf)->get(session_id)) {
            m->set_removed(messages);
            unbox<groups::Members>(conf)->set(*m);
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool groups_members_erase(config_object* conf, const char* session_id) {
    try {
        return unbox<groups::Members>(conf)->erase(session_id);
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API size_t groups_members_size(const config_object* conf) {
    return unbox<groups::Members>(conf)->size();
}

LIBSESSION_C_API groups_members_iterator* groups_members_iterator_new(const config_object* conf) {
    auto* it = new groups_members_iterator{};
    it->_internals = new groups::Members::iterator{unbox<groups::Members>(conf)->begin()};
    return it;
}

LIBSESSION_C_API void groups_members_iterator_free(groups_members_iterator* it) {
    delete static_cast<groups::Members::iterator*>(it->_internals);
    delete it;
}

LIBSESSION_C_API bool groups_members_iterator_done(
        groups_members_iterator* it, config_group_member* c) {
    auto& real = *static_cast<groups::Members::iterator*>(it->_internals);
    if (real.done())
        return true;
    real->into(*c);
    return false;
}

LIBSESSION_C_API void groups_members_iterator_advance(groups_members_iterator* it) {
    ++*static_cast<groups::Members::iterator*>(it->_internals);
}
