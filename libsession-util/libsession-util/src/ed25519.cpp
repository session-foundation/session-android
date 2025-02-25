#include "session/ed25519.hpp"

#include <sodium/crypto_sign.h>
#include <sodium/crypto_sign_ed25519.h>

#include <stdexcept>

#include "session/export.h"
#include "session/sodium_array.hpp"

namespace session::ed25519 {

template <size_t N>
using cleared_array = sodium_cleared<std::array<unsigned char, N>>;

using uc32 = std::array<unsigned char, 32>;
using cleared_uc64 = cleared_array<64>;

std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> ed25519_key_pair() {
    std::array<unsigned char, 32> ed_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_keypair(ed_pk.data(), ed_sk.data());

    return {ed_pk, ed_sk};
}

std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> ed25519_key_pair(
        ustring_view ed25519_seed) {
    if (ed25519_seed.size() != 32) {
        throw std::invalid_argument{"Invalid ed25519_seed: expected 32 bytes"};
    }

    std::array<unsigned char, 32> ed_pk;
    std::array<unsigned char, 64> ed_sk;

    crypto_sign_ed25519_seed_keypair(ed_pk.data(), ed_sk.data(), ed25519_seed.data());

    return {ed_pk, ed_sk};
}

std::array<unsigned char, 32> seed_for_ed_privkey(ustring_view ed25519_privkey) {
    std::array<unsigned char, 32> seed;

    if (ed25519_privkey.size() == 32 || ed25519_privkey.size() == 64)
        // The first 32 bytes of a 64 byte ed25519 private key are the seed, otherwise
        // if the provided value is 32 bytes we just assume we were given a seed
        std::memcpy(seed.data(), ed25519_privkey.data(), 32);
    else
        throw std::invalid_argument{"Invalid ed25519_privkey: expected 32 or 64 bytes"};

    return seed;
}

ustring sign(ustring_view ed25519_privkey, ustring_view msg) {
    cleared_uc64 ed_sk_from_seed;
    if (ed25519_privkey.size() == 32) {
        uc32 ignore_pk;
        crypto_sign_ed25519_seed_keypair(
                ignore_pk.data(), ed_sk_from_seed.data(), ed25519_privkey.data());
        ed25519_privkey = {ed_sk_from_seed.data(), ed_sk_from_seed.size()};
    } else if (ed25519_privkey.size() != 64) {
        throw std::invalid_argument{"Invalid ed25519_privkey: expected 32 or 64 bytes"};
    }

    std::array<unsigned char, 64> sig;
    if (0 != crypto_sign_ed25519_detached(
                     sig.data(), nullptr, msg.data(), msg.size(), ed25519_privkey.data()))
        throw std::runtime_error{"Failed to sign; perhaps the secret key is invalid?"};

    return {sig.data(), sig.size()};
}

bool verify(ustring_view sig, ustring_view pubkey, ustring_view msg) {
    if (sig.size() != 64)
        throw std::invalid_argument{"Invalid sig: expected 64 bytes"};
    if (pubkey.size() != 32)
        throw std::invalid_argument{"Invalid pubkey: expected 32 bytes"};

    return (0 ==
            crypto_sign_ed25519_verify_detached(sig.data(), msg.data(), msg.size(), pubkey.data()));
}

}  // namespace session::ed25519

using namespace session;

LIBSESSION_C_API bool session_ed25519_key_pair(
        unsigned char* ed25519_pk_out, unsigned char* ed25519_sk_out) {
    try {
        auto result = session::ed25519::ed25519_key_pair();
        auto [ed_pk, ed_sk] = result;
        std::memcpy(ed25519_pk_out, ed_pk.data(), ed_pk.size());
        std::memcpy(ed25519_sk_out, ed_sk.data(), ed_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_ed25519_key_pair_seed(
        const unsigned char* ed25519_seed,
        unsigned char* ed25519_pk_out,
        unsigned char* ed25519_sk_out) {
    try {
        auto result = session::ed25519::ed25519_key_pair(ustring_view{ed25519_seed, 32});
        auto [ed_pk, ed_sk] = result;
        std::memcpy(ed25519_pk_out, ed_pk.data(), ed_pk.size());
        std::memcpy(ed25519_sk_out, ed_sk.data(), ed_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_seed_for_ed_privkey(
        const unsigned char* ed25519_privkey, unsigned char* ed25519_seed_out) {
    try {
        auto result = session::ed25519::seed_for_ed_privkey(ustring_view{ed25519_privkey, 64});
        std::memcpy(ed25519_seed_out, result.data(), result.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_ed25519_sign(
        const unsigned char* ed25519_privkey,
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* ed25519_sig_out) {
    try {
        auto result = session::ed25519::sign(
                ustring_view{ed25519_privkey, 64}, ustring_view{msg, msg_len});
        std::memcpy(ed25519_sig_out, result.data(), result.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_ed25519_verify(
        const unsigned char* sig,
        const unsigned char* pubkey,
        const unsigned char* msg,
        size_t msg_len) {
    return session::ed25519::verify(
            ustring_view{sig, 64}, ustring_view{pubkey, 32}, ustring_view{msg, msg_len});
}
