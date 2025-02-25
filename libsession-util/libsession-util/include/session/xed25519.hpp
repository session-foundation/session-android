#pragma once
#include <array>
#include <string>
#include <string_view>

namespace session::xed25519 {

using ustring_view = std::basic_string_view<unsigned char>;

/// XEd25519-signs a message given the curve25519 privkey and message.
std::array<unsigned char, 64> sign(
        ustring_view curve25519_privkey /* 32 bytes */, ustring_view msg);

/// "Softer" version that takes and returns strings of regular chars
std::string sign(std::string_view curve25519_privkey /* 32 bytes */, std::string_view msg);

/// Verifies a curve25519 message allegedly signed by the given curve25519 pubkey
[[nodiscard]] bool verify(
        ustring_view signature /* 64 bytes */,
        ustring_view curve25519_pubkey /* 32 bytes */,
        ustring_view msg);

/// "Softer" version that takes strings of regular chars
[[nodiscard]] bool verify(
        std::string_view signature /* 64 bytes */,
        std::string_view curve25519_pubkey /* 32 bytes */,
        std::string_view msg);

/// Given a curve25519 pubkey, this returns the associated XEd25519-derived Ed25519 pubkey.  Note,
/// however, that there are *two* possible Ed25519 pubkeys that could result in a given curve25519
/// pubkey: this always returns the positive value.  You can get the other possibility (the
/// negative) by setting the sign bit, i.e. `returned_pubkey[31] |= 0x80`.
std::array<unsigned char, 32> pubkey(ustring_view curve25519_pubkey);

/// "Softer" version that takes/returns strings of regular chars
std::string pubkey(std::string_view curve25519_pubkey);

/// Utility function that provides a constant-time `if (b) f = g;` implementation for byte arrays.
template <size_t N>
void constant_time_conditional_assign(
        std::array<unsigned char, N>& f, const std::array<unsigned char, N>& g, bool b) {
    std::array<unsigned char, N> x;
    for (size_t i = 0; i < x.size(); i++)
        x[i] = f[i] ^ g[i];
    unsigned char mask = (unsigned char)(-(signed char)b);
    for (size_t i = 0; i < x.size(); i++)
        x[i] &= mask;
    for (size_t i = 0; i < x.size(); i++)
        f[i] ^= x[i];
}

}  // namespace session::xed25519
