#include <oxenc/hex.h>

#include <catch2/catch_test_macros.hpp>
#include <session/util.hpp>

#include "session/ed25519.h"
#include "session/ed25519.hpp"
#include "utils.hpp"

TEST_CASE("Ed25519 key pair generation", "[ed25519][keypair]") {
    // Generate two random key pairs and make sure they don't match
    auto kp1 = session::ed25519::ed25519_key_pair();
    auto kp2 = session::ed25519::ed25519_key_pair();

    CHECK(kp1.first.size() == 32);
    CHECK(kp1.second.size() == 64);
    CHECK(kp1.first != kp2.first);
    CHECK(kp1.second != kp2.second);
}

TEST_CASE("Ed25519 key pair generation seed", "[ed25519][keypair]") {
    using namespace session;

    auto ed_seed1 = "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7"_hexbytes;
    auto ed_seed2 = "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876"_hexbytes;
    auto ed_seed_invalid = "010203040506070809"_hexbytes;

    auto kp1 = session::ed25519::ed25519_key_pair(to_unsigned_sv(ed_seed1));
    auto kp2 = session::ed25519::ed25519_key_pair(to_unsigned_sv(ed_seed2));
    CHECK_THROWS(session::ed25519::ed25519_key_pair(to_unsigned_sv(ed_seed_invalid)));

    CHECK(kp1.first.size() == 32);
    CHECK(kp1.second.size() == 64);
    CHECK(kp1.first != kp2.first);
    CHECK(kp1.second != kp2.second);
    CHECK(oxenc::to_hex(kp1.first.begin(), kp1.first.end()) ==
          "8862834829a87e0afadfed763fa8785e893dbde7f2c001ff1071aa55005c347f");
    CHECK(oxenc::to_hex(kp2.first.begin(), kp2.first.end()) ==
          "cd83ca3d13ad8a954d5011aa7861abe3a29ac25b70c4ed5234aff74d34ef5786");

    auto kp_sk1 =
            "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab78862834829a"
            "87e0afadfed763fa8785e893dbde7f2c001ff1071aa55005c347f";
    auto kp_sk2 =
            "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876cd83ca3d13a"
            "d8a954d5011aa7861abe3a29ac25b70c4ed5234aff74d34ef5786";
    CHECK(oxenc::to_hex(kp1.second.begin(), kp1.second.end()) == kp_sk1);
    CHECK(oxenc::to_hex(kp2.second.begin(), kp2.second.end()) == kp_sk2);
}

TEST_CASE("Ed25519 seed for private key", "[ed25519][seed]") {
    using namespace session;

    auto ed_sk1 =
            "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab78862834829a"
            "87e0afadfed763fa8785e893dbde7f2c001ff1071aa55005c347f"_hexbytes;
    auto ed_sk2 = "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876"_hexbytes;
    auto ed_sk_invalid = "010203040506070809"_hexbytes;

    auto seed1 = session::ed25519::seed_for_ed_privkey(to_unsigned_sv(ed_sk1));
    auto seed2 = session::ed25519::seed_for_ed_privkey(to_unsigned_sv(ed_sk2));
    CHECK_THROWS(session::ed25519::seed_for_ed_privkey(to_unsigned_sv(ed_sk_invalid)));

    CHECK(oxenc::to_hex(seed1.begin(), seed1.end()) ==
          "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7");
    CHECK(oxenc::to_hex(seed2.begin(), seed2.end()) ==
          "5ea34e72bb044654a6a23675690ef5ffaaf1656b02f93fb76655f9cbdbe89876");
}

TEST_CASE("Ed25519", "[ed25519][signature]") {
    using namespace session;

    auto ed_seed = "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7"_hexbytes;
    auto ed_pk = "8862834829a87e0afadfed763fa8785e893dbde7f2c001ff1071aa55005c347f"_hexbytes;
    auto ed_invalid = "010203040506070809"_hexbytes;

    auto sig1 = session::ed25519::sign(to_unsigned_sv(ed_seed), to_unsigned_sv("hello"));
    CHECK_THROWS(session::ed25519::sign(to_unsigned_sv(ed_invalid), to_unsigned_sv("hello")));

    auto expected_sig_hex =
            "e03b6e87a53d83f202f2501e9b52193dbe4a64c6503f88244948dee53271"
            "85011574589aa7b59bc9757f9b9c31b7be9c9212b92ac7c81e029ee21c338ee12405";
    CHECK(oxenc::to_hex(sig1.begin(), sig1.end()) == expected_sig_hex);

    CHECK(session::ed25519::verify(sig1, ed_pk, to_unsigned_sv("hello")));
    CHECK_THROWS(session::ed25519::verify(ed_invalid, ed_pk, to_unsigned_sv("hello")));
    CHECK_THROWS(session::ed25519::verify(ed_pk, ed_invalid, to_unsigned_sv("hello")));
}
