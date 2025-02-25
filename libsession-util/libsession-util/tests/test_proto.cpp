#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_all.hpp>
#include <iostream>
#include <session/config/namespaces.hpp>
#include <session/config/protos.hpp>
#include <session/config/user_profile.hpp>

#include "utils.hpp"

using namespace session::config;

const std::vector<Namespace> groups{
        Namespace::UserProfile,
        Namespace::Contacts,
        Namespace::ConvoInfoVolatile,
        Namespace::UserGroups};

const auto seed = "0123456789abcdef0123456789abcdef00000000000000000000000000000000"_hexbytes;
std::array<unsigned char, 32> ed_pk_raw;
std::array<unsigned char, 64> ed_sk_raw;
ustring_view load_seed() {
    crypto_sign_ed25519_seed_keypair(ed_pk_raw.data(), ed_sk_raw.data(), seed.data());
    return {ed_sk_raw.data(), ed_sk_raw.size()};
}
auto ed_sk = load_seed();

TEST_CASE("Protobuf Handling - Wrap, Unwrap", "[config][proto][wrap]") {
    auto msg = "Hello from the other side"_bytes;

    SECTION("Wrap/unwrap message types") {
        for (auto& n : groups) {
            auto shared_config_msg = protos::wrap_config(ed_sk, msg, 1, n);

            CHECK(not shared_config_msg.empty());

            auto shared_config_parsed = protos::unwrap_config(ed_sk, shared_config_msg, n);
            // This will be false, as ::unwrap_config will return the parsed payload if it
            // successfully parses a protobuf wrapped message
            CHECK_FALSE(shared_config_msg == shared_config_parsed);
            // This will return true, as the parsed message will match the payload
            CHECK(shared_config_parsed == msg);
        }
    }

    SECTION("Message type payload comparison") {
        auto user_profile_msg = protos::wrap_config(ed_sk, msg, 1, Namespace::UserProfile);
        auto contacts_msg = protos::wrap_config(ed_sk, msg, 1, Namespace::Contacts);

        auto user_profile_parsed =
                protos::unwrap_config(ed_sk, user_profile_msg, Namespace::UserProfile);
        auto contacts_parsed = protos::unwrap_config(ed_sk, contacts_msg, Namespace::Contacts);

        // All of these will return true, as the parsed messages will be identical to the
        // payload, and therefore identical to one another
        CHECK(user_profile_parsed == contacts_parsed);
        CHECK(user_profile_parsed == msg);
        CHECK(contacts_parsed == msg);
    }
}

TEST_CASE("Protobuf Handling - Error Handling", "[config][proto][error]") {
    auto msg = "Hello from the other side"_bytes;
    auto addendum = "jfeejj0ifdoesam"_bytes;

    const auto user_profile_msg = protos::wrap_config(ed_sk, msg, 1, Namespace::UserProfile);
    const auto size = user_profile_msg.size();

    // Testing three positions: front, inside the payload, and at the end
    const std::vector<size_t> positions{0, size - 4, size};

    for (auto& p : positions) {
        auto msg_copy = user_profile_msg;
        msg_copy.insert(p, addendum);

        REQUIRE_THROWS(protos::unwrap_config(ed_sk, msg_copy, Namespace::UserProfile));
    }
}

TEST_CASE("Protobuf old config loading test", "[config][proto][old]") {

    const auto seed = "f887566576de6c16d9ec251d55e24c1400000000000000000000000000000000"_hexbytes;
    std::array<unsigned char, 32> ed_pk_raw;
    std::array<unsigned char, 64> ed_sk_raw;
    crypto_sign_ed25519_seed_keypair(ed_pk_raw.data(), ed_sk_raw.data(), seed.data());
    ustring_view ed_sk{ed_sk_raw.data(), ed_sk_raw.size()};

    auto old_conf =
            "080112c2060a03505554120f2f6170692f76312f6d6573736167651a9f060806120028e1c5a0beaf313801"
            "428f065228bb32b820169e0acb266f02efa007276be0668013a278fc9bfc111a40136f63de4206943c0509"
            "6155fa480cd0a7f5d27d6297166f5ed5c2a323ecdf7a754308dd385cdce81e7ed3a0a305577838105a0798"
            "dd92540f4b8eaa74f8c5720e0a394ce005444322354d6dfe1cb527520145f3794718e42730e15c97f7e45f"
            "b53f9f7d3918ee57e5c8462f80ae0d64792c261feb4b9ce06b18a10b3d8f7af8f791b1368bd4ae9bbe0036"
            "dc77f547c001e26c9c986269281bc3e8ef38c42ad2a02a9be517fc85c0c8fa4732f79138910f85bba0f898"
            "f278d8c2ed3e7d00cc5b4f1eb32ffc9572ec98fac529bec7ad8560dc06fc986516c00232e9618c372c0f57"
            "c19283e0424ec91864aad7277e22c085443cc0bfd39c0a83f0a1a8f856850ede7a751bd6206cb6683e462a"
            "033ad282e4947adbbe4973e823676ae0a72aa5f0f607f306fe82b91da9b7fe79d4fb4e8a45cb9ad5f20c15"
            "1a84073cc62d7ac794fdd2fe57bf49f1089f8644ad9f73d154d14c63d5ca7a07d1b6ab6b5846b2f4785fbf"
            "738de23c250a711f54c941fd6f5aac4417125bb2d0321cd9f1b97a31f310d4ea8149732276b8df9869fbc5"
            "412c9b7772961fab800a356155549ef54cefb9407d7f10b4323824aa8ea13facc79003b84dae3e5ef0db27"
            "5b056f4fbdf54f5f22e62291af8427fc17c3c1b3985f6ee149729d8a5b794b7e374f408eb8f36a76a89680"
            "e3c6106a9d5a82f6f04f5d8b603a97140b6469daac0ef32f84cc4ffc05f43c084591b10834b1d16d65ce14"
            "15dec77cb5851c338ccbb0d5ae2d2c1e5bc8ba0f59dfbc4575fd446c8486a1ac5370d5da8eb041f2ed560a"
            "bc1a6ad6ce6e00369ec5fd5eb0a35411ed24b36ecbf80f1dc6c18452c4b4bfc59131e04400df8986cac95c"
            "51bbd320ba901ff6110dad0c70442286cf6220a53c6f9693636a42d5523eeb1e5fb3453169581384fb8a8f"
            "3914fb6c01900a4f872f55742b117ddd7bd40c4c5911bb214e28eb9450dbdd0d831a93054c63f9a04bf50c"
            "db9aac0032c484062d7ba7bbe64e07bcd633eec8378d5d914732693c5e298f015ebde2ae45769ed319e267"
            "f0528f5cc6da268343b6647b20bae6e9ee8d92cca702"_hexbytes;

    CHECK_NOTHROW(protos::unwrap_config(ed_sk, old_conf, Namespace::UserProfile));
}
