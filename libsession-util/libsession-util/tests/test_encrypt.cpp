#include <oxenc/hex.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_sign.h>

#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_exception.hpp>
#include <iterator>
#include <session/config.hpp>
#include <session/config/encrypt.hpp>
#include <session/types.hpp>

#include "utils.hpp"

using namespace session;
using namespace std::literals;
using namespace oxenc::literals;

TEST_CASE("config message encryption", "[config][encrypt]") {
    auto message1 = "some message 1"_bytes;
    auto key1 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes;
    auto key2 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"_hexbytes;
    auto enc1 = config::encrypt(message1, key1, "test-suite1");
    CHECK(oxenc::to_hex(enc1.begin(), enc1.end()) ==
          "f14f242a26638f3305707d1035e734577f943cd7d28af58e32637e"
          "0966dcaf2f4860cb4d0f8ba7e09d29e31f5e4a18f65847287a54a0");
    auto enc2 = config::encrypt(message1, key1, "test-suite2");
    CHECK(to_hex(enc2) != to_hex(enc1));
    auto enc3 = config::encrypt(message1, key2, "test-suite1");
    CHECK(to_hex(enc3) != to_hex(enc1));
    auto nonce = enc1.substr(enc1.size() - 24);
    auto nonce2 = enc2.substr(enc2.size() - 24);
    auto nonce3 = enc3.substr(enc3.size() - 24);
    CHECK(to_hex(nonce) == "af2f4860cb4d0f8ba7e09d29e31f5e4a18f65847287a54a0");
    CHECK(to_hex(nonce2) == "277e639d36ba46470dfff509a68cb73d9a96386c51739bdd");
    CHECK(to_hex(nonce3) == to_hex(nonce));

    auto plain = config::decrypt(enc1, key1, "test-suite1");
    CHECK(plain == message1);
    CHECK_THROWS_AS(config::decrypt(enc1, key1, "test-suite2"), config::decrypt_error);
    CHECK_THROWS_AS(config::decrypt(enc1, key2, "test-suite1"), config::decrypt_error);

    enc1[3] = '\x42';
    CHECK_THROWS_AS(config::decrypt(enc1, key1, "test-suite1"), config::decrypt_error);
}

TEST_CASE("config message padding", "[config][padding]") {
    static_assert(config::padded_size(1, 0) == 256);
    static_assert(config::padded_size(1, 10) == 256 - 10);
    static_assert(config::padded_size(246, 10) == 256 - 10);
    static_assert(config::padded_size(247, 10) == 512 - 10);
    static_assert(config::padded_size(247, 10) == 512 - 10);
    static_assert(config::padded_size(247, 256) == 256);
    static_assert(config::padded_size(3839, 96) == 4000);
    static_assert(config::padded_size(1, 0) == 256);
    static_assert(config::padded_size(1, 10) == 256 - 10);
    static_assert(config::padded_size(246, 10) == 256 - 10);
    static_assert(config::padded_size(247, 10) == 512 - 10);
    static_assert(config::padded_size(247, 10) == 512 - 10);
    static_assert(config::padded_size(247, 256) == 256);
    static_assert(config::padded_size(3744, 96) == 3744);
    static_assert(config::padded_size(3745, 96) == 4000);
    static_assert(config::padded_size(4864, 0) == 4864);
    static_assert(config::padded_size(4865, 0) == 5_kiB);
    static_assert(config::padded_size(5_kiB + 1, 0) == 6_kiB);
    static_assert(config::padded_size(9_kiB, 0) == 9_kiB);
    static_assert(config::padded_size(9_kiB + 1, 0) == 10_kiB);
    static_assert(config::padded_size(10_kiB + 1, 0) == 11_kiB);
    static_assert(config::padded_size(20_kiB, 0) == 20_kiB);
    static_assert(config::padded_size(20_kiB + 1, 0) == 22_kiB);
    static_assert(config::padded_size(38_kiB, 0) == 38_kiB);
    static_assert(config::padded_size(38_kiB + 1, 0) == 40_kiB);
    static_assert(config::padded_size(40_kiB + 1, 0) == 45_kiB);
    static_assert(config::padded_size(45_kiB + 1, 0) == 50_kiB);
    static_assert(config::padded_size(70_kiB, 0) == 70_kiB);
    static_assert(config::padded_size(70_kiB + 1, 0) == 75_kiB);  // Coincides with max message size
    static_assert(config::padded_size(75_kiB, 0) == 75_kiB);      // Coincides with max message size
    static_assert(
            config::padded_size(75_kiB - 24, 24) ==
            75_kiB - 24);  // Coincides with max message size
    CHECK(true);
}
