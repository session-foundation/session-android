#include "session/onionreq/key_types.hpp"

#include <oxenc/base32z.h>
#include <oxenc/base64.h>
#include <oxenc/hex.h>
#include <sodium.h>

#include <cstring>
#include <type_traits>

namespace session::onionreq {

namespace detail {

    void load_from_hex(void* buffer, size_t length, std::string_view hex) {
        if (!oxenc::is_hex(hex))
            throw std::runtime_error{"Hex key data is invalid: data is not hex"};
        if (hex.size() != 2 * length)
            throw std::runtime_error{
                    "Hex key data is invalid: expected " + std::to_string(length) +
                    " hex digits, received " + std::to_string(hex.size())};
        oxenc::from_hex(hex.begin(), hex.end(), reinterpret_cast<unsigned char*>(buffer));
    }

    void load_from_bytes(void* buffer, size_t length, std::string_view bytes) {
        if (bytes.size() != length)
            throw std::runtime_error{
                    "Key data is invalid: expected " + std::to_string(length) +
                    " bytes, received " + std::to_string(bytes.size())};
        std::memmove(buffer, bytes.data(), length);
    }

}  // namespace detail

std::string ed25519_pubkey::snode_address() const {
    auto addr = oxenc::to_base32z(begin(), end());
    addr += ".snode";
    return addr;
}

legacy_pubkey legacy_seckey::pubkey() const {
    legacy_pubkey pk;
    crypto_scalarmult_ed25519_base_noclamp(pk.data(), data());
    return pk;
};
ed25519_pubkey ed25519_seckey::pubkey() const {
    ed25519_pubkey pk;
    crypto_sign_ed25519_sk_to_pk(pk.data(), data());
    return pk;
};
x25519_pubkey x25519_seckey::pubkey() const {
    x25519_pubkey pk;
    crypto_scalarmult_curve25519_base(pk.data(), data());
    return pk;
};

template <typename T>
static T parse_pubkey(std::string_view pubkey_in) {
    T pk{};
    static_assert(pk.size() == 32);
    if (pubkey_in.size() == 32)
        detail::load_from_bytes(pk.data(), 32, pubkey_in);
    else if (pubkey_in.size() == 64 && oxenc::is_hex(pubkey_in))
        oxenc::from_hex(pubkey_in.begin(), pubkey_in.end(), pk.begin());
    else if (
            (pubkey_in.size() == 43 || (pubkey_in.size() == 44 && pubkey_in.back() == '=')) &&
            oxenc::is_base64(pubkey_in))
        oxenc::from_base64(pubkey_in.begin(), pubkey_in.end(), pk.begin());
    else if (pubkey_in.size() == 52 && oxenc::is_base32z(pubkey_in))
        oxenc::from_base32z(pubkey_in.begin(), pubkey_in.end(), pk.begin());

    return pk;
}

legacy_pubkey parse_legacy_pubkey(std::string_view pubkey_in) {
    return parse_pubkey<legacy_pubkey>(pubkey_in);
}
ed25519_pubkey parse_ed25519_pubkey(std::string_view pubkey_in) {
    return parse_pubkey<ed25519_pubkey>(pubkey_in);
}
x25519_pubkey parse_x25519_pubkey(std::string_view pubkey_in) {
    return parse_pubkey<x25519_pubkey>(pubkey_in);
}

}  // namespace session::onionreq
