#include "session/curve25519.hpp"

#include <sodium/crypto_box.h>
#include <sodium/crypto_sign_ed25519.h>

#include <stdexcept>

#include "session/export.h"
#include "session/util.hpp"

namespace session::curve25519 {

std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> curve25519_key_pair() {
    std::array<unsigned char, 32> curve_pk;
    std::array<unsigned char, 64> curve_sk;
    crypto_box_keypair(curve_pk.data(), curve_sk.data());

    return {curve_pk, curve_sk};
}

std::array<unsigned char, 32> to_curve25519_pubkey(ustring_view ed25519_pubkey) {
    if (ed25519_pubkey.size() != 32) {
        throw std::invalid_argument{"Invalid ed25519_pubkey: expected 32 bytes"};
    }

    std::array<unsigned char, 32> curve_pk;

    if (0 != crypto_sign_ed25519_pk_to_curve25519(curve_pk.data(), ed25519_pubkey.data()))
        throw std::runtime_error{
                "An error occured while attempting to convert Ed25519 pubkey to curve25519; "
                "is the pubkey valid?"};

    return curve_pk;
}

std::array<unsigned char, 32> to_curve25519_seckey(ustring_view ed25519_seckey) {
    if (ed25519_seckey.size() != 64) {
        throw std::invalid_argument{"Invalid ed25519_seckey: expected 64 bytes"};
    }

    std::array<unsigned char, 32> curve_sk;
    if (0 != crypto_sign_ed25519_sk_to_curve25519(curve_sk.data(), ed25519_seckey.data()))
        throw std::runtime_error{
                "An error occured while attempting to convert Ed25519 pubkey to curve25519; "
                "is the seckey valid?"};

    return curve_sk;
}

}  // namespace session::curve25519

using namespace session;

LIBSESSION_C_API bool session_curve25519_key_pair(
        unsigned char* curve25519_pk_out, unsigned char* curve25519_sk_out) {
    try {
        auto result = session::curve25519::curve25519_key_pair();
        auto [curve_pk, curve_sk] = result;
        std::memcpy(curve25519_pk_out, curve_pk.data(), curve_pk.size());
        std::memcpy(curve25519_sk_out, curve_sk.data(), curve_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_to_curve25519_pubkey(
        const unsigned char* ed25519_pubkey, unsigned char* curve25519_pk_out) {
    try {
        auto curve_pk = session::curve25519::to_curve25519_pubkey(ustring_view{ed25519_pubkey, 32});
        std::memcpy(curve25519_pk_out, curve_pk.data(), curve_pk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_to_curve25519_seckey(
        const unsigned char* ed25519_seckey, unsigned char* curve25519_sk_out) {
    try {
        auto curve_sk = session::curve25519::to_curve25519_seckey(ustring_view{ed25519_seckey, 64});
        std::memcpy(curve25519_sk_out, curve_sk.data(), curve_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}
