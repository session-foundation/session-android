#include <oxenc/hex.h>

#include <catch2/catch_test_macros.hpp>
#include <session/util.hpp>

#include "session/curve25519.h"
#include "session/curve25519.hpp"
#include "utils.hpp"

TEST_CASE("X25519 key pair generation", "[curve25519][keypair]") {
    auto kp1 = session::curve25519::curve25519_key_pair();
    auto kp2 = session::curve25519::curve25519_key_pair();

    CHECK(kp1.first.size() == 32);
    CHECK(kp1.second.size() == 64);
    CHECK(kp1.first != kp2.first);
    CHECK(kp1.second != kp2.second);
}

TEST_CASE("X25519 conversion", "[curve25519][to curve25519 pubkey]") {
    using namespace session;

    auto ed_pk1 = "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7"_hexbytes;
    auto ed_pk2 = "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876"_hexbytes;

    auto x_pk1 = curve25519::to_curve25519_pubkey(to_unsigned_sv(ed_pk1));
    auto x_pk2 = curve25519::to_curve25519_pubkey(to_unsigned_sv(ed_pk2));

    CHECK(oxenc::to_hex(x_pk1.begin(), x_pk1.end()) ==
          "d2ad010eeb72d72e561d9de7bd7b6989af77dcabffa03a5111a6c859ae5c3a72");
    CHECK(oxenc::to_hex(x_pk2.begin(), x_pk2.end()) ==
          "aa654f00fc39fc69fd0db829410ca38177d7732a8d2f0934ab3872ac56d5aa74");
}

TEST_CASE("X25519 conversion", "[curve25519][to curve25519 seckey]") {
    using namespace session;

    auto ed_sk1 =
            "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab78862834829a"
            "87e0afadfed763fa8785e893dbde7f2c001ff1071aa55005c347f"_hexbytes;
    auto ed_sk2 =
            "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876cd83ca3d13a"
            "d8a954d5011aa7861abe3a29ac25b70c4ed5234aff74d34ef5786"_hexbytes;
    auto x_sk1 = curve25519::to_curve25519_seckey(to_unsigned_sv(ed_sk1));
    auto x_sk2 = curve25519::to_curve25519_seckey(to_unsigned_sv(ed_sk2));

    CHECK(oxenc::to_hex(x_sk1.begin(), x_sk1.end()) ==
          "207e5d97e761300f96c10adc11efdd6d5c15188a9a7682ec05b30ca017e9b447");
    CHECK(oxenc::to_hex(x_sk2.begin(), x_sk2.end()) ==
          "904943eff27142a8e5cd37c84e2437c9979a560b044bf9a65a8d644b325fe56a");
}
