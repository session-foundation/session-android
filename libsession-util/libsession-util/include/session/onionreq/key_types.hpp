#pragma once

#include <oxenc/hex.h>

#include <array>
#include <cstddef>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

#include "../types.hpp"
#include "../util.hpp"

namespace session::onionreq {

using namespace std::literals;

namespace detail {
    template <size_t Length>
    inline constexpr std::array<unsigned char, Length> null_bytes = {0};

    void load_from_hex(void* buffer, size_t length, std::string_view hex);
    void load_from_bytes(void* buffer, size_t length, std::string_view bytes);

}  // namespace detail

template <typename Derived, size_t KeyLength>
struct alignas(size_t) key_base : std::array<unsigned char, KeyLength> {
    std::string_view view() const {
        return {reinterpret_cast<const char*>(this->data()), KeyLength};
    }
    std::string hex() const { return oxenc::to_hex(view()); }
    explicit operator bool() const { return *this != detail::null_bytes<KeyLength>; }

    // Loads the key from a hex string; throws if the hex is the wrong size or not hex.
    static Derived from_hex(std::string_view hex) {
        Derived d;
        detail::load_from_hex(d.data(), d.size(), hex);
        return d;
    }
    // Same as above, but returns nullopt if invalid instead of throwing
    static std::optional<Derived> maybe_from_hex(std::string_view hex) {
        try {
            return from_hex(hex);
        } catch (...) {
        }
        return std::nullopt;
    }
    // Loads the key from a byte string; throws if the wrong size.
    static Derived from_bytes(std::string_view bytes) {
        Derived d;
        detail::load_from_bytes(d.data(), d.size(), bytes);
        return d;
    }
    static Derived from_bytes(ustring_view bytes) { return from_bytes(from_unsigned_sv(bytes)); }
};

template <typename Derived, size_t KeyLength>
struct pubkey_base : key_base<Derived, KeyLength> {
    using PubKeyBase = pubkey_base<Derived, KeyLength>;
};

struct legacy_pubkey : pubkey_base<legacy_pubkey, 32> {};
struct x25519_pubkey : pubkey_base<x25519_pubkey, 32> {};
struct ed25519_pubkey : pubkey_base<ed25519_pubkey, 32> {
    // Returns the {base32z}.snode representation of this pubkey
    std::string snode_address() const;
};

template <typename Derived, size_t KeyLength>
struct seckey_base : key_base<Derived, KeyLength> {};

struct legacy_seckey : seckey_base<legacy_seckey, 32> {
    legacy_pubkey pubkey() const;
};
struct ed25519_seckey : seckey_base<ed25519_seckey, 64> {
    ed25519_pubkey pubkey() const;
};
struct x25519_seckey : seckey_base<x25519_seckey, 32> {
    x25519_pubkey pubkey() const;
};

using legacy_keypair = std::pair<legacy_pubkey, legacy_seckey>;
using ed25519_keypair = std::pair<ed25519_pubkey, ed25519_seckey>;
using x25519_keypair = std::pair<x25519_pubkey, x25519_seckey>;

/// Parse a pubkey string value encoded in any of base32z, b64, hex, or raw bytes, based on the
/// length of the value.  Returns a null pk (i.e. operator bool() returns false) and warns on
/// invalid input (i.e. wrong length or invalid encoding).
legacy_pubkey parse_legacy_pubkey(std::string_view pubkey_in);
ed25519_pubkey parse_ed25519_pubkey(std::string_view pubkey_in);
x25519_pubkey parse_x25519_pubkey(std::string_view pubkey_in);

}  // namespace session::onionreq

namespace std {
template <typename Derived, size_t N>
struct hash<session::onionreq::pubkey_base<Derived, N>> {
    size_t operator()(const session::onionreq::pubkey_base<Derived, N>& pk) const {
        // pubkeys are already random enough to use the first bytes directly as a good (and fast)
        // hash value
        static_assert(alignof(decltype(pk)) >= alignof(size_t));
        return *reinterpret_cast<const size_t*>(pk.data());
    }
};

template <>
struct hash<session::onionreq::legacy_pubkey> : hash<session::onionreq::legacy_pubkey::PubKeyBase> {
};
template <>
struct hash<session::onionreq::x25519_pubkey> : hash<session::onionreq::x25519_pubkey::PubKeyBase> {
};
template <>
struct hash<session::onionreq::ed25519_pubkey>
        : hash<session::onionreq::ed25519_pubkey::PubKeyBase> {};

}  // namespace std
