#include <oxenc/hex.h>
#include <sodium.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>

#include "session/xed25519.h"
#include "session/xed25519.hpp"

using session::xed25519::ustring_view;

constexpr std::array<unsigned char, 64> seed1{
        0xfe, 0xcd, 0x9a, 0x60, 0x34, 0xbc, 0x9a, 0xba, 0x27, 0x39, 0x25, 0xde, 0xe7,
        0x06, 0x2b, 0x12, 0x33, 0x34, 0x58, 0x7c, 0x3c, 0x62, 0x57, 0x34, 0x1a, 0xfa,
        0xe2, 0xd7, 0xfe, 0x85, 0xe1, 0x22, 0xf4, 0xef, 0x87, 0x39, 0x08, 0xf6, 0xa5,
        0x37, 0x7b, 0xa3, 0x85, 0x3f, 0x0e, 0x2f, 0xa3, 0x26, 0xee, 0xd9, 0xe7, 0x41,
        0xed, 0xf9, 0xf7, 0xd0, 0x31, 0x1a, 0x3e, 0xcc, 0x66, 0xa5, 0x7b, 0x32};
constexpr std::array<unsigned char, 64> seed2{
        0x86, 0x59, 0xef, 0xdc, 0xbe, 0x09, 0x49, 0xe0, 0xf8, 0x11, 0x41, 0xe6, 0xd3,
        0x97, 0xe8, 0xbe, 0x75, 0xf4, 0x5d, 0x09, 0x26, 0x2f, 0x20, 0x9d, 0x59, 0x50,
        0xe9, 0x79, 0x89, 0xeb, 0x43, 0xc7, 0x35, 0x70, 0xb6, 0x9a, 0x47, 0xdc, 0x09,
        0x45, 0x44, 0xc1, 0xc5, 0x08, 0x9c, 0x40, 0x41, 0x4b, 0xbd, 0xa1, 0xff, 0xdd,
        0xe8, 0xaa, 0xb2, 0x61, 0x7f, 0xe9, 0x37, 0xee, 0x74, 0xa5, 0xee, 0x81};

constexpr ustring_view pub1{seed1.data() + 32, 32};
constexpr ustring_view pub2{seed2.data() + 32, 32};

constexpr std::array<unsigned char, 32> xpub1{
        0xfe, 0x94, 0xb7, 0xad, 0x4b, 0x7f, 0x1c, 0xc1, 0xbb, 0x92, 0x67,
        0x1f, 0x1f, 0x0d, 0x24, 0x3f, 0x22, 0x6e, 0x11, 0x5b, 0x33, 0x77,
        0x04, 0x65, 0xe8, 0x2b, 0x50, 0x3f, 0xc3, 0xe9, 0x6e, 0x1f,
};
constexpr std::array<unsigned char, 32> xpub2{
        0x05, 0xc9, 0xa9, 0xbf, 0x17, 0x8f, 0xa6, 0x44, 0xd4, 0x4b, 0xeb,
        0xf6, 0x28, 0x71, 0x6d, 0xc7, 0xf2, 0xdf, 0x3d, 0x08, 0x42, 0xe9,
        0x78, 0x81, 0x96, 0x2c, 0x72, 0x36, 0x99, 0x15, 0x20, 0x73,
};

constexpr std::array<unsigned char, 32> pub2_abs{
        0x35, 0x70, 0xb6, 0x9a, 0x47, 0xdc, 0x09, 0x45, 0x44, 0xc1, 0xc5,
        0x08, 0x9c, 0x40, 0x41, 0x4b, 0xbd, 0xa1, 0xff, 0xdd, 0xe8, 0xaa,
        0xb2, 0x61, 0x7f, 0xe9, 0x37, 0xee, 0x74, 0xa5, 0xee, 0x01,
};

template <size_t N>
static ustring_view view(const std::array<unsigned char, N>& x) {
    return {x.data(), x.size()};
}
static ustring_view view(const std::string_view x) {
    return ustring_view{reinterpret_cast<const unsigned char*>(x.data()), x.size()};
}
template <size_t N>
static std::string view_hex(const std::array<unsigned char, N>& x) {
    return oxenc::to_hex(view(x));
}

TEST_CASE("XEd25519 pubkey conversion", "[xed25519][pubkey]") {
    std::array<unsigned char, 32> xpk1;
    int rc = crypto_sign_ed25519_pk_to_curve25519(xpk1.data(), pub1.data());
    REQUIRE(rc == 0);
    REQUIRE(view_hex(xpk1) == view_hex(xpub1));

    std::array<unsigned char, 32> xpk2;
    rc = crypto_sign_ed25519_pk_to_curve25519(xpk2.data(), pub2.data());
    REQUIRE(rc == 0);
    REQUIRE(view_hex(xpk2) == view_hex(xpub2));

    auto xed1 = session::xed25519::pubkey(view(xpub1));
    REQUIRE(view_hex(xed1) == oxenc::to_hex(pub1));

    // This one fails because the original Ed pubkey is negative
    auto xed2 = session::xed25519::pubkey(view(xpub2));
    REQUIRE(view_hex(xed2) != oxenc::to_hex(pub2));
    // After making the xed negative we should be okay:
    xed2[31] |= 0x80;
    REQUIRE(view_hex(xed2) == oxenc::to_hex(pub2));
}

TEST_CASE("XEd25519 signing", "[xed25519][sign]") {
    std::array<unsigned char, 32> xsk1;
    int rc = crypto_sign_ed25519_sk_to_curve25519(xsk1.data(), seed1.data());
    REQUIRE(rc == 0);
    std::array<unsigned char, 32> xpk1;
    rc = crypto_sign_ed25519_pk_to_curve25519(xpk1.data(), pub1.data());

    std::array<unsigned char, 32> xsk2;
    rc = crypto_sign_ed25519_sk_to_curve25519(xsk2.data(), seed2.data());
    REQUIRE(rc == 0);
    std::array<unsigned char, 32> xpk2;
    rc = crypto_sign_ed25519_pk_to_curve25519(xpk2.data(), pub2.data());

    const auto msg = view("hello world");

    auto xed_sig1 = session::xed25519::sign(view(xsk1), msg);

    rc = crypto_sign_ed25519_verify_detached(xed_sig1.data(), msg.data(), msg.size(), pub1.data());
    REQUIRE(rc == 0);

    auto xed_sig2 = session::xed25519::sign(view(xsk2), msg);

    // This one will fail, because Xed signing always uses the positive but our actual pub2 is the
    // negative:
    rc = crypto_sign_ed25519_verify_detached(xed_sig2.data(), msg.data(), msg.size(), pub2.data());
    REQUIRE(rc != 0);

    // Flip it, though, and it should work:
    rc = crypto_sign_ed25519_verify_detached(
            xed_sig2.data(), msg.data(), msg.size(), pub2_abs.data());
    REQUIRE(rc == 0);
}

TEST_CASE("XEd25519 verification", "[xed25519][verify]") {
    std::array<unsigned char, 32> xsk1;
    int rc = crypto_sign_ed25519_sk_to_curve25519(xsk1.data(), seed1.data());
    REQUIRE(rc == 0);

    std::array<unsigned char, 32> xsk2;
    rc = crypto_sign_ed25519_sk_to_curve25519(xsk2.data(), seed2.data());
    REQUIRE(rc == 0);

    const auto msg = view("hello world");

    auto xed_sig1 = session::xed25519::sign(view(xsk1), msg);
    auto xed_sig2 = session::xed25519::sign(view(xsk2), msg);

    REQUIRE(session::xed25519::verify(view(xed_sig1), view(xpub1), msg));
    REQUIRE(session::xed25519::verify(view(xed_sig2), view(xpub2), msg));

    // Unlike regular Ed25519, XEd25519 uses randomness in the signature, so signing the same value
    // a second should give us a different signature:
    auto xed_sig1b = session::xed25519::sign(view(xsk1), msg);
    REQUIRE(view_hex(xed_sig1b) != view_hex(xed_sig1));
}

TEST_CASE("XEd25519 pubkey conversion (C wrapper)", "[xed25519][pubkey][c]") {
    auto xed1 = session::xed25519::pubkey(view(xpub1));
    REQUIRE(view_hex(xed1) == oxenc::to_hex(pub1));

    // This one fails because the original Ed pubkey is negative
    auto xed2 = session::xed25519::pubkey(view(xpub2));
    REQUIRE(view_hex(xed2) != oxenc::to_hex(pub2));
    // After making the xed negative we should be okay:
    xed2[31] |= 0x80;
    REQUIRE(view_hex(xed2) == oxenc::to_hex(pub2));
}
TEST_CASE("XEd25519 signing (C wrapper)", "[xed25519][sign][c]") {
    std::array<unsigned char, 32> xsk1;
    int rc = crypto_sign_ed25519_sk_to_curve25519(xsk1.data(), seed1.data());
    REQUIRE(rc == 0);
    std::array<unsigned char, 32> xpk1;
    rc = crypto_sign_ed25519_pk_to_curve25519(xpk1.data(), pub1.data());

    std::array<unsigned char, 32> xsk2;
    rc = crypto_sign_ed25519_sk_to_curve25519(xsk2.data(), seed2.data());
    REQUIRE(rc == 0);
    std::array<unsigned char, 32> xpk2;
    rc = crypto_sign_ed25519_pk_to_curve25519(xpk2.data(), pub2.data());

    const auto msg = view("hello world");

    std::array<unsigned char, 64> xed_sig1, xed_sig2;
    REQUIRE(session_xed25519_sign(xed_sig1.data(), xsk1.data(), msg.data(), msg.size()));
    REQUIRE(session_xed25519_sign(xed_sig2.data(), xsk2.data(), msg.data(), msg.size()));

    rc = crypto_sign_ed25519_verify_detached(xed_sig1.data(), msg.data(), msg.size(), pub1.data());
    REQUIRE(rc == 0);

    rc = crypto_sign_ed25519_verify_detached(xed_sig2.data(), msg.data(), msg.size(), pub2.data());
    REQUIRE(rc != 0);  // Failure expected (pub2 is negative)

    rc = crypto_sign_ed25519_verify_detached(
            xed_sig2.data(), msg.data(), msg.size(), pub2_abs.data());
    REQUIRE(rc == 0);  // Flipped sign should work
}
TEST_CASE("XEd25519 verification (C wrapper)", "[xed25519][verify][c]") {
    std::array<unsigned char, 32> xsk1;
    int rc = crypto_sign_ed25519_sk_to_curve25519(xsk1.data(), seed1.data());
    REQUIRE(rc == 0);

    std::array<unsigned char, 32> xsk2;
    rc = crypto_sign_ed25519_sk_to_curve25519(xsk2.data(), seed2.data());
    REQUIRE(rc == 0);

    const auto msg = view("hello world");

    std::array<unsigned char, 64> xed_sig1, xed_sig2;
    REQUIRE(session_xed25519_sign(xed_sig1.data(), xsk1.data(), msg.data(), msg.size()));
    REQUIRE(session_xed25519_sign(xed_sig2.data(), xsk2.data(), msg.data(), msg.size()));

    REQUIRE(session_xed25519_verify(xed_sig1.data(), xpub1.data(), msg.data(), msg.size()));
    REQUIRE(session_xed25519_verify(xed_sig2.data(), xpub2.data(), msg.data(), msg.size()));
}
