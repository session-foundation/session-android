#include "session/blinding.hpp"

#include <oxenc/hex.h>
#include <sodium/crypto_core_ed25519.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_scalarmult_ed25519.h>
#include <sodium/crypto_sign_ed25519.h>

#include <cassert>
#include <stdexcept>

#include "session/ed25519.hpp"
#include "session/export.h"
#include "session/platform.h"
#include "session/platform.hpp"
#include "session/xed25519.hpp"

namespace session {

using namespace std::literals;

using uc32 = std::array<unsigned char, 32>;
using uc33 = std::array<unsigned char, 33>;
using uc64 = std::array<unsigned char, 64>;

std::array<unsigned char, 32> blind15_factor(ustring_view server_pk) {
    assert(server_pk.size() == 32);

    crypto_generichash_blake2b_state st;
    crypto_generichash_blake2b_init(&st, nullptr, 0, 64);
    crypto_generichash_blake2b_update(&st, server_pk.data(), server_pk.size());
    uc64 blind_hash;
    crypto_generichash_blake2b_final(&st, blind_hash.data(), blind_hash.size());

    uc32 k;
    crypto_core_ed25519_scalar_reduce(k.data(), blind_hash.data());
    return k;
}

std::array<unsigned char, 32> blind25_factor(ustring_view session_id, ustring_view server_pk) {
    assert(session_id.size() == 32 || session_id.size() == 33);
    assert(server_pk.size() == 32);

    crypto_generichash_blake2b_state st;
    crypto_generichash_blake2b_init(&st, nullptr, 0, 64);
    if (session_id.size() == 32) {
        constexpr unsigned char prefix = 0x05;
        crypto_generichash_blake2b_update(&st, &prefix, 1);
    }
    crypto_generichash_blake2b_update(&st, session_id.data(), session_id.size());
    crypto_generichash_blake2b_update(&st, server_pk.data(), server_pk.size());
    uc64 blind_hash;
    crypto_generichash_blake2b_final(&st, blind_hash.data(), blind_hash.size());

    uc32 k;
    crypto_core_ed25519_scalar_reduce(k.data(), blind_hash.data());
    return k;
}

namespace {

    void blind15_id_impl(ustring_view session_id, ustring_view server_pk, unsigned char* out) {
        auto k = blind15_factor(server_pk);
        if (session_id.size() == 33)
            session_id.remove_prefix(1);
        auto ed_pk = xed25519::pubkey(session_id);
        if (0 != crypto_scalarmult_ed25519_noclamp(out + 1, k.data(), ed_pk.data()))
            throw std::runtime_error{"Cannot blind: invalid session_id (not on main subgroup)"};
        out[0] = 0x15;
    }

    void blind25_id_impl(ustring_view session_id, ustring_view server_pk, unsigned char* out) {
        auto k = blind25_factor(session_id, server_pk);
        if (session_id.size() == 33)
            session_id.remove_prefix(1);
        auto ed_pk = xed25519::pubkey(session_id);
        if (0 != crypto_scalarmult_ed25519_noclamp(out + 1, k.data(), ed_pk.data()))
            throw std::runtime_error{"Cannot blind: invalid session_id (not on main subgroup)"};
        out[0] = 0x25;
    }

}  // namespace

ustring blind15_id(ustring_view session_id, ustring_view server_pk) {
    if (session_id.size() == 33) {
        if (session_id[0] != 0x05)
            throw std::invalid_argument{"blind15_id: session_id must start with 0x05"};
        session_id.remove_prefix(1);
    } else if (session_id.size() != 32) {
        throw std::invalid_argument{"blind15_id: session_id must be 32 or 33 bytes"};
    }
    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind15_id: server_pk must be 32 bytes"};

    ustring result;
    result.resize(33);
    blind15_id_impl(session_id, server_pk, result.data());
    return result;
}

std::array<std::string, 2> blind15_id(std::string_view session_id, std::string_view server_pk) {
    if (session_id.size() != 66 || !oxenc::is_hex(session_id))
        throw std::invalid_argument{"blind15_id: session_id must be hex (66 digits)"};
    if (session_id[0] != '0' || session_id[1] != '5')
        throw std::invalid_argument{"blind15_id: session_id must start with 05"};
    if (server_pk.size() != 64 || !oxenc::is_hex(server_pk))
        throw std::invalid_argument{"blind15_id: server_pk must be hex (64 digits)"};

    uc33 raw_sid;
    oxenc::from_hex(session_id.begin(), session_id.end(), raw_sid.begin());
    uc32 raw_server_pk;
    oxenc::from_hex(server_pk.begin(), server_pk.end(), raw_server_pk.begin());

    uc33 blinded;
    blind15_id_impl(to_sv(raw_sid), to_sv(raw_server_pk), blinded.data());
    std::array<std::string, 2> result;
    result[0] = oxenc::to_hex(blinded.begin(), blinded.end());
    blinded.back() ^= 0x80;
    result[1] = oxenc::to_hex(blinded.begin(), blinded.end());
    return result;
}

ustring blind25_id(ustring_view session_id, ustring_view server_pk) {
    if (session_id.size() == 33) {
        if (session_id[0] != 0x05)
            throw std::invalid_argument{"blind25_id: session_id must start with 0x05"};
    } else if (session_id.size() != 32) {
        throw std::invalid_argument{"blind25_id: session_id must be 32 or 33 bytes"};
    }
    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind25_id: server_pk must be 32 bytes"};

    ustring result;
    result.resize(33);
    blind25_id_impl(session_id, server_pk, result.data());
    return result;
}

std::string blind25_id(std::string_view session_id, std::string_view server_pk) {
    if (session_id.size() != 66 || !oxenc::is_hex(session_id))
        throw std::invalid_argument{"blind25_id: session_id must be hex (66 digits)"};
    if (session_id[0] != '0' || session_id[1] != '5')
        throw std::invalid_argument{"blind25_id: session_id must start with 05"};
    if (server_pk.size() != 64 || !oxenc::is_hex(server_pk))
        throw std::invalid_argument{"blind25_id: server_pk must be hex (64 digits)"};

    uc33 raw_sid;
    oxenc::from_hex(session_id.begin(), session_id.end(), raw_sid.begin());
    uc32 raw_server_pk;
    oxenc::from_hex(server_pk.begin(), server_pk.end(), raw_server_pk.begin());

    uc33 blinded;
    blind25_id_impl(to_sv(raw_sid), to_sv(raw_server_pk), blinded.data());
    return oxenc::to_hex(blinded.begin(), blinded.end());
}

ustring blinded15_id_from_ed(ustring_view ed_pubkey, ustring_view server_pk, ustring* session_id) {
    if (ed_pubkey.size() != 32)
        throw std::invalid_argument{"blind15_id_from_ed: ed_pubkey must be 32 bytes"};
    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind15_id_from_ed: server_pk must be 32 bytes"};
    if (session_id && !session_id->empty())
        throw std::invalid_argument{
                "blind15_id_from_ed: session_id pointer must be an empty string"};

    if (session_id) {
        session_id->resize(33);
        session_id->front() = 0x05;
        if (0 != crypto_sign_ed25519_pk_to_curve25519(session_id->data() + 1, ed_pubkey.data()))
            throw std::runtime_error{"ed25519 pubkey to x25519 pubkey conversion failed"};
    }

    ustring result;
    result.resize(33);
    auto k = blind15_factor(server_pk);
    if (0 != crypto_scalarmult_ed25519_noclamp(result.data() + 1, k.data(), ed_pubkey.data()))
        throw std::runtime_error{"Cannot blind: invalid session_id (not on main subgroup)"};
    result[0] = 0x15;
    return result;
}

ustring blinded25_id_from_ed(ustring_view ed_pubkey, ustring_view server_pk, ustring* session_id) {
    if (ed_pubkey.size() != 32)
        throw std::invalid_argument{"blind25_id_from_ed: ed_pubkey must be 32 bytes"};
    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind25_id_from_ed: server_pk must be 32 bytes"};
    if (session_id && session_id->size() != 0 && session_id->size() != 33)
        throw std::invalid_argument{"blind25_id_from_ed: session_id pointer must be 0 or 33 bytes"};

    ustring tmp_session_id;
    if (!session_id)
        session_id = &tmp_session_id;
    if (session_id->size() == 0) {
        session_id->resize(33);
        session_id->front() = 0x05;
        if (0 != crypto_sign_ed25519_pk_to_curve25519(session_id->data() + 1, ed_pubkey.data()))
            throw std::runtime_error{"ed25519 pubkey to x25519 pubkey conversion failed"};
    }

    auto k = blind25_factor(*session_id, server_pk);

    ustring result;
    result.resize(33);
    // Blinded25 ids are always constructed using the absolute value of the ed pubkey, so if
    // negative we need to clear the sign bit to make it positive before computing the blinded
    // pubkey.
    uc32 pos_ed_pubkey;
    std::memcpy(pos_ed_pubkey.data(), ed_pubkey.data(), 32);
    pos_ed_pubkey[31] &= 0x7f;

    if (0 != crypto_scalarmult_ed25519_noclamp(result.data() + 1, k.data(), pos_ed_pubkey.data()))
        throw std::runtime_error{"Cannot blind: invalid session_id (not on main subgroup)"};
    result[0] = 0x25;
    return result;
}

std::pair<uc32, cleared_uc32> blind15_key_pair(
        ustring_view ed25519_sk, ustring_view server_pk, uc32* k) {
    std::array<unsigned char, 64> ed_sk_tmp;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> pk_ignore;
        crypto_sign_ed25519_seed_keypair(pk_ignore.data(), ed_sk_tmp.data(), ed25519_sk.data());
        ed25519_sk = {ed_sk_tmp.data(), 64};
    }
    if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "blind15_key_pair: Invalid ed25519_sk is not the expected 32- or 64-byte value"};

    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind15_key_pair: server_pk must be 32 bytes"};

    std::pair<uc32, cleared_uc32> result;
    auto& [A, a] = result;

    /// Generate the blinding factor (storing into `*k`, if a pointer was provided)
    uc32 k_tmp;
    if (!k)
        k = &k_tmp;
    *k = blind15_factor(server_pk);

    /// Generate a scalar for the private key
    if (0 != crypto_sign_ed25519_sk_to_curve25519(a.data(), ed25519_sk.data()))
        throw std::runtime_error{
                "blind15_key_pair: Invalid ed25519_sk; conversion to curve25519 seckey failed"};

    // Turn a, A into their blinded versions
    crypto_core_ed25519_scalar_mul(a.data(), k->data(), a.data());
    crypto_scalarmult_ed25519_base_noclamp(A.data(), a.data());

    return result;
}

std::pair<uc32, cleared_uc32> blind25_key_pair(
        ustring_view ed25519_sk, ustring_view server_pk, uc32* k_prime) {
    std::array<unsigned char, 64> ed_sk_tmp;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> pk_ignore;
        crypto_sign_ed25519_seed_keypair(pk_ignore.data(), ed_sk_tmp.data(), ed25519_sk.data());
        ed25519_sk = {ed_sk_tmp.data(), 64};
    }
    if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "blind15_key_pair: Invalid ed25519_sk is not the expected 32- or 64-byte value"};

    if (server_pk.size() != 32)
        throw std::invalid_argument{"blind15_key_pair: server_pk must be 32 bytes"};

    uc33 session_id;
    session_id[0] = 0x05;
    if (0 != crypto_sign_ed25519_pk_to_curve25519(session_id.data() + 1, ed25519_sk.data() + 32))
        throw std::runtime_error{
                "blind25_key_pair: Invalid ed25519_sk; conversion to curve25519 pubkey failed"};

    ustring_view X{session_id.data() + 1, 32};

    /// Generate the blinding factor (storing into `*k`, if a pointer was provided)
    uc32 k_tmp;
    if (!k_prime)
        k_prime = &k_tmp;
    *k_prime = blind25_factor(X, {server_pk.data(), server_pk.size()});

    // For a negative pubkey we use k' = -k so that k'A == kA when A is positive, and k'A = -kA =
    // k|A| when A is negative.
    if (*(ed25519_sk.data() + 63) & 0x80)
        crypto_core_ed25519_scalar_negate(k_prime->data(), k_prime->data());

    std::pair<uc32, cleared_uc32> result;
    auto& [A, a] = result;

    // Generate the private key (scalar), a; (the sodium function naming here is misleading; this
    // call actually has nothing to do with conversion to X25519, it just so happens that the
    // conversion method is the easiest way to get `a` out of libsodium).
    if (0 != crypto_sign_ed25519_sk_to_curve25519(a.data(), ed25519_sk.data()))
        throw std::runtime_error{
                "blind25_key_pair: Invalid ed25519_sk; conversion to curve25519 seckey failed"};

    // Turn a, A into their blinded versions
    crypto_core_ed25519_scalar_mul(a.data(), k_prime->data(), a.data());
    crypto_scalarmult_ed25519_base_noclamp(A.data(), a.data());

    return result;
}

static const auto version_blinding_hash_key_sig = to_unsigned_sv("VersionCheckKey_sig"sv);

std::pair<uc32, cleared_uc64> blind_version_key_pair(ustring_view ed25519_sk) {
    if (ed25519_sk.size() != 32 && ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "blind_version_key_pair: Invalid ed25519_sk is not the expected 32- or 64-byte "
                "value"};

    std::pair<uc32, cleared_uc64> result;
    cleared_uc32 blind_seed;
    auto& [pk, sk] = result;
    crypto_generichash_blake2b(
            blind_seed.data(),
            32,
            ed25519_sk.data(),
            32,
            version_blinding_hash_key_sig.data(),
            version_blinding_hash_key_sig.size());

    // Reuse `sk` to avoid needing extra secure erasing:
    if (0 != crypto_sign_ed25519_seed_keypair(pk.data(), sk.data(), blind_seed.data()))
        throw std::runtime_error{"blind_version_key_pair: ed25519 generation from seed failed"};

    return result;
}

static const auto hash_key_seed = to_unsigned_sv("SessCommBlind25_seed"sv);
static const auto hash_key_sig = to_unsigned_sv("SessCommBlind25_sig"sv);

ustring blind25_sign(ustring_view ed25519_sk, std::string_view server_pk_in, ustring_view message) {
    std::array<unsigned char, 64> ed_sk_tmp;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> pk_ignore;
        crypto_sign_ed25519_seed_keypair(pk_ignore.data(), ed_sk_tmp.data(), ed25519_sk.data());
        ed25519_sk = {ed_sk_tmp.data(), 64};
    }
    if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "blind25_sign: Invalid ed25519_sk is not the expected 32- or 64-byte value"};
    uc32 server_pk;
    if (server_pk_in.size() == 32)
        std::memcpy(server_pk.data(), server_pk_in.data(), 32);
    else if (server_pk_in.size() == 64 && oxenc::is_hex(server_pk_in))
        oxenc::from_hex(server_pk_in.begin(), server_pk_in.end(), server_pk.begin());
    else
        throw std::invalid_argument{"blind25_sign: Invalid server_pk: expected 32 bytes or 64 hex"};

    auto [A, a] = blind25_key_pair(ed25519_sk, to_sv(server_pk));

    uc32 seedhash;
    crypto_generichash_blake2b(
            seedhash.data(),
            seedhash.size(),
            ed25519_sk.data(),
            32,
            hash_key_seed.data(),
            hash_key_seed.size());

    uc64 r_hash;
    crypto_generichash_blake2b_state st;
    crypto_generichash_blake2b_init(&st, hash_key_sig.data(), hash_key_sig.size(), r_hash.size());
    crypto_generichash_blake2b_update(&st, seedhash.data(), seedhash.size());
    crypto_generichash_blake2b_update(&st, A.data(), A.size());
    crypto_generichash_blake2b_update(&st, message.data(), message.size());
    crypto_generichash_blake2b_final(&st, r_hash.data(), r_hash.size());

    uc32 r;
    crypto_core_ed25519_scalar_reduce(r.data(), r_hash.data());

    ustring result;
    result.resize(64);
    auto* sig_R = result.data();
    auto* sig_S = result.data() + 32;
    crypto_scalarmult_ed25519_base_noclamp(sig_R, r.data());

    crypto_hash_sha512_state st2;
    crypto_hash_sha512_init(&st2);
    crypto_hash_sha512_update(&st2, sig_R, 32);
    crypto_hash_sha512_update(&st2, A.data(), A.size());
    crypto_hash_sha512_update(&st2, message.data(), message.size());
    uc64 hram;
    crypto_hash_sha512_final(&st2, hram.data());

    crypto_core_ed25519_scalar_reduce(sig_S, hram.data());  // S = H(R||A||M)

    crypto_core_ed25519_scalar_mul(sig_S, sig_S, a.data());  // S = H(R||A||M) a
    crypto_core_ed25519_scalar_add(sig_S, sig_S, r.data());  // S = r + H(R||A||M) a

    return result;
}

ustring blind15_sign(ustring_view ed25519_sk, std::string_view server_pk_in, ustring_view message) {
    std::array<unsigned char, 64> ed_sk_tmp;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> pk_ignore;
        crypto_sign_ed25519_seed_keypair(pk_ignore.data(), ed_sk_tmp.data(), ed25519_sk.data());
        ed25519_sk = {ed_sk_tmp.data(), 64};
    }
    if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "blind15_sign: Invalid ed25519_sk is not the expected 32- or 64-byte value"};

    uc32 server_pk;
    if (server_pk_in.size() == 32)
        std::memcpy(server_pk.data(), server_pk_in.data(), 32);
    else if (server_pk_in.size() == 64 && oxenc::is_hex(server_pk_in))
        oxenc::from_hex(server_pk_in.begin(), server_pk_in.end(), server_pk.begin());
    else
        throw std::invalid_argument{"blind15_sign: Invalid server_pk: expected 32 bytes or 64 hex"};

    auto [blind_15_pk, blind_15_sk] = blind15_key_pair(ed25519_sk, {server_pk.data(), 32});

    // H_rh = sha512(s.encode()).digest()[32:]
    uc64 hrh;
    crypto_hash_sha512_state st1;
    crypto_hash_sha512_init(&st1);
    crypto_hash_sha512_update(&st1, ed25519_sk.data(), 64);
    crypto_hash_sha512_final(&st1, hrh.data());

    // r = salt.crypto_core_ed25519_scalar_reduce(sha512_multipart(H_rh, kA, message_parts))
    auto hrh_suffix = hrh.data() + 32;
    uc32 r;
    uc64 r_hash;
    crypto_hash_sha512_state st2;
    crypto_hash_sha512_init(&st2);
    crypto_hash_sha512_update(&st2, hrh_suffix, 32);
    crypto_hash_sha512_update(&st2, blind_15_pk.data(), blind_15_pk.size());
    crypto_hash_sha512_update(&st2, message.data(), message.size());
    crypto_hash_sha512_final(&st2, r_hash.data());
    crypto_core_ed25519_scalar_reduce(r.data(), r_hash.data());

    // sig_R = salt.crypto_scalarmult_ed25519_base_noclamp(r)
    ustring result;
    result.resize(64);
    auto* sig_R = result.data();
    auto* sig_S = result.data() + 32;
    crypto_scalarmult_ed25519_base_noclamp(sig_R, r.data());

    // HRAM = salt.crypto_core_ed25519_scalar_reduce(sha512_multipart(sig_R, kA, message_parts))
    uc64 hram;
    crypto_hash_sha512_state st3;
    crypto_hash_sha512_init(&st3);
    crypto_hash_sha512_update(&st3, sig_R, 32);
    crypto_hash_sha512_update(&st3, blind_15_pk.data(), blind_15_pk.size());
    crypto_hash_sha512_update(&st3, message.data(), message.size());
    crypto_hash_sha512_final(&st3, hram.data());

    // sig_s = salt.crypto_core_ed25519_scalar_add(r, salt.crypto_core_ed25519_scalar_mul(HRAM, ka))
    crypto_core_ed25519_scalar_reduce(sig_S, hram.data());             // S = H(R||A||M)
    crypto_core_ed25519_scalar_mul(sig_S, sig_S, blind_15_sk.data());  // S = H(R||A||M) a
    crypto_core_ed25519_scalar_add(sig_S, sig_S, r.data());            // S = r + H(R||A||M) a

    return result;
}

ustring blind_version_sign_request(
        ustring_view ed25519_sk,
        uint64_t timestamp,
        std::string_view method,
        std::string_view path,
        std::optional<ustring_view> body) {
    auto [pk, sk] = blind_version_key_pair(ed25519_sk);

    // Signature should be on `TIMESTAMP || METHOD || PATH || BODY`
    ustring buf;
    buf.reserve(10 /* timestamp */ + method.size() + path.size() + (body ? body->size() : 0));
    buf += to_unsigned_sv(std::to_string(timestamp));
    buf += to_unsigned_sv(method);
    buf += to_unsigned_sv(path);

    if (body)
        buf += *body;

    return ed25519::sign({sk.data(), sk.size()}, buf);
}

ustring blind_version_sign(ustring_view ed25519_sk, Platform platform, uint64_t timestamp) {
    auto [pk, sk] = blind_version_key_pair(ed25519_sk);

    // Signature should be on `TIMESTAMP || METHOD || PATH`
    ustring buf;
    buf.reserve(10 + 6 + 33);
    buf += to_unsigned_sv(std::to_string(timestamp));
    buf += to_unsigned("GET");

    switch (platform) {
        case Platform::android: buf += to_unsigned("/session_version?platform=android"); break;
        case Platform::desktop: buf += to_unsigned("/session_version?platform=desktop"); break;
        case Platform::ios: buf += to_unsigned("/session_version?platform=ios"); break;
    }

    return ed25519::sign({sk.data(), sk.size()}, buf);
}

bool session_id_matches_blinded_id(
        std::string_view session_id, std::string_view blinded_id, std::string_view server_pk) {
    if (session_id.size() != 66 || !oxenc::is_hex(session_id))
        throw std::invalid_argument{
                "session_id_matches_blinded_id: session_id must be hex (66 digits)"};
    if (session_id[0] != '0' || session_id[1] != '5')
        throw std::invalid_argument{"session_id_matches_blinded_id: session_id must start with 05"};
    if (blinded_id[1] != '5' && (blinded_id[0] != '1' || blinded_id[0] != '2'))
        throw std::invalid_argument{
                "session_id_matches_blinded_id: blinded_id must start with 15 or 25"};
    if (server_pk.size() != 64 || !oxenc::is_hex(server_pk))
        throw std::invalid_argument{
                "session_id_matches_blinded_id: server_pk must be hex (64 digits)"};

    std::string converted_blind_id1, converted_blind_id2;
    ustring converted_blind_id1_raw;

    switch (blinded_id[0]) {
        case '1': {
            auto [converted_blind_id1, converted_blind_id2] = blind15_id(session_id, server_pk);
            return (blinded_id == converted_blind_id1 || blinded_id == converted_blind_id2);
        }

        // blind25 doesn't run into the negative issue that blind15 did
        case '2': return blinded_id == blind25_id(session_id, server_pk);
        default: throw std::invalid_argument{"Invalid blinded_id: must start with 15 or 25"};
    }
}

}  // namespace session

using namespace session;

LIBSESSION_C_API bool session_blind15_key_pair(
        const unsigned char* ed25519_seckey,
        const unsigned char* server_pk,
        unsigned char* blinded_pk_out,
        unsigned char* blinded_sk_out) {
    try {
        auto [b_pk, b_sk] = session::blind15_key_pair({ed25519_seckey, 64}, {server_pk, 32});
        std::memcpy(blinded_pk_out, b_pk.data(), b_pk.size());
        std::memcpy(blinded_sk_out, b_sk.data(), b_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind25_key_pair(
        const unsigned char* ed25519_seckey,
        const unsigned char* server_pk,
        unsigned char* blinded_pk_out,
        unsigned char* blinded_sk_out) {
    try {
        auto [b_pk, b_sk] = session::blind25_key_pair({ed25519_seckey, 64}, {server_pk, 32});
        std::memcpy(blinded_pk_out, b_pk.data(), b_pk.size());
        std::memcpy(blinded_sk_out, b_sk.data(), b_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind_version_key_pair(
        const unsigned char* ed25519_seckey,
        unsigned char* blinded_pk_out,
        unsigned char* blinded_sk_out) {
    try {
        auto [b_pk, b_sk] = session::blind_version_key_pair({ed25519_seckey, 64});
        std::memcpy(blinded_pk_out, b_pk.data(), b_pk.size());
        std::memcpy(blinded_sk_out, b_sk.data(), b_sk.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind15_sign(
        const unsigned char* ed25519_seckey,
        const unsigned char* server_pk,
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* blinded_sig_out) {
    try {
        auto sig = session::blind15_sign(
                {ed25519_seckey, 64}, {from_unsigned(server_pk), 32}, {msg, msg_len});
        std::memcpy(blinded_sig_out, sig.data(), sig.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind25_sign(
        const unsigned char* ed25519_seckey,
        const unsigned char* server_pk,
        const unsigned char* msg,
        size_t msg_len,
        unsigned char* blinded_sig_out) {
    try {
        auto sig = session::blind25_sign(
                {ed25519_seckey, 64}, {from_unsigned(server_pk), 32}, {msg, msg_len});
        std::memcpy(blinded_sig_out, sig.data(), sig.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind_version_sign_request(
        const unsigned char* ed25519_seckey,
        size_t timestamp,
        const char* method,
        const char* path,
        const unsigned char* body,
        size_t body_len,
        unsigned char* blinded_sig_out) {
    std::string_view method_sv{method};
    std::string_view path_sv{path};

    std::optional<ustring_view> body_sv{std::nullopt};
    if (body)
        body_sv = ustring_view{body, body_len};

    try {
        auto sig = session::blind_version_sign_request(
                {ed25519_seckey, 64}, timestamp, method_sv, path_sv, body_sv);
        std::memcpy(blinded_sig_out, sig.data(), sig.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_blind_version_sign(
        const unsigned char* ed25519_seckey,
        CLIENT_PLATFORM platform,
        size_t timestamp,
        unsigned char* blinded_sig_out) {
    try {
        auto sig = session::blind_version_sign(
                {ed25519_seckey, 64}, static_cast<Platform>(platform), timestamp);
        std::memcpy(blinded_sig_out, sig.data(), sig.size());
        return true;
    } catch (...) {
        return false;
    }
}

LIBSESSION_C_API bool session_id_matches_blinded_id(
        const char* session_id, const char* blinded_id, const char* server_pk) {
    try {
        return session::session_id_matches_blinded_id(
                {session_id, 66}, {blinded_id, 66}, {server_pk, 64});
    } catch (...) {
        return false;
    }
}
