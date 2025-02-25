#include <sodium/crypto_aead_xchacha20poly1305.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <session/multi_encrypt.hpp>
#include <session/util.hpp>

#include "utils.hpp"

using namespace std::literals;
using namespace oxenc::literals;

using x_pair = std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 32>>;

// Returns X25519 privkey, pubkey from an Ed25519 seed
x_pair to_x_keys(ustring_view ed_seed) {
    std::array<unsigned char, 32> ed_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_seed_keypair(ed_pk.data(), ed_sk.data(), ed_seed.data());
    x_pair ret;
    auto& [x_priv, x_pub] = ret;
    [[maybe_unused]] int rc = crypto_sign_ed25519_pk_to_curve25519(x_pub.data(), ed_pk.data());
    assert(rc == 0);
    crypto_sign_ed25519_sk_to_curve25519(x_priv.data(), ed_sk.data());
    return ret;
}

TEST_CASE("Multi-recipient encryption", "[encrypt][multi]") {

    const std::array seeds = {
            "0123456789abcdef0123456789abcdef00000000000000000000000000000000"_hexbytes,
            "0123456789abcdef000000000000000000000000000000000000000000000000"_hexbytes,
            "0123456789abcdef111111111111111100000000000000000000000000000000"_hexbytes,
            "0123456789abcdef222222222222222200000000000000000000000000000000"_hexbytes,
            "0123456789abcdef333333333333333300000000000000000000000000000000"_hexbytes};

    std::array<x_pair, seeds.size()> x_keys;
    for (int i = 0; i < seeds.size(); i++)
        x_keys[i] = to_x_keys(seeds[i]);

    CHECK(oxenc::to_hex(to_usv(x_keys[0].second)) ==
          "d2ad010eeb72d72e561d9de7bd7b6989af77dcabffa03a5111a6c859ae5c3a72");
    CHECK(oxenc::to_hex(to_usv(x_keys[1].second)) ==
          "d673a8fb4800d2a252d2fc4e3342a88cdfa9412853934e8993d12d593be13371");
    CHECK(oxenc::to_hex(to_usv(x_keys[2].second)) ==
          "afd9716ea69ab8c7f475e1b250c86a6539e260804faecf2a803e9281a4160738");
    CHECK(oxenc::to_hex(to_usv(x_keys[3].second)) ==
          "03be14feabd59122349614b88bdc90db1d1af4c230e9a73c898beec833d51f11");
    CHECK(oxenc::to_hex(to_usv(x_keys[4].second)) ==
          "27b5c1ea87cef76284c752fa6ee1b9186b1a95e74e8f5b88f8b47e5191ce6f08");

    auto nonce = "32ab4bb45d6df5cc14e1c330fb1a8b68ea3826a8c2213a49"_hexbytes;

    std::vector<ustring_view> recipients;
    for (auto& [_, pubkey] : x_keys)
        recipients.emplace_back(pubkey.data(), pubkey.size());

    std::vector<std::string> msgs{{"hello", "cruel", "world"}};
    std::vector<ustring> encrypted;
    session::encrypt_for_multiple(
            msgs[0],
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            nonce,
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite",
            [&](ustring_view enc) { encrypted.emplace_back(enc); });

    REQUIRE(encrypted.size() == 3);
    CHECK(oxenc::to_hex(encrypted[0]) == "e64937e5ea201b84f4e88a976dad900d91caaf6a17");
    CHECK(oxenc::to_hex(encrypted[1]) == "b7a15bcd9f7b09445defcae2f1dc5085dd75cb085b");
    CHECK(oxenc::to_hex(encrypted[2]) == "01c4fc2156327735f3fb5063b11ea95f6ebcc5b6cc");

    auto m1 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[1].first),
            to_usv(x_keys[1].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m2 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[2].first),
            to_usv(x_keys[2].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m3 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m3b = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "not test suite");
    auto m4 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[4].first),
            to_usv(x_keys[4].second),
            to_usv(x_keys[0].second),
            "test suite");

    REQUIRE(m1);
    REQUIRE(m2);
    REQUIRE(m3);
    CHECK_FALSE(m3b);
    CHECK_FALSE(m4);

    CHECK(to_sv(*m1) == "hello");
    CHECK(to_sv(*m2) == "hello");
    CHECK(to_sv(*m3) == "hello");

    encrypted.clear();
    session::encrypt_for_multiple(
            session::to_view_vector(msgs.begin(), msgs.end()),
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            nonce,
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite",
            [&](ustring_view enc) { encrypted.emplace_back(enc); });

    REQUIRE(encrypted.size() == 3);
    CHECK(oxenc::to_hex(encrypted[0]) == "e64937e5ea201b84f4e88a976dad900d91caaf6a17");
    CHECK(oxenc::to_hex(encrypted[1]) == "bcb642c49c6da03f70cdaab2ed6666721318afd631");
    CHECK(oxenc::to_hex(encrypted[2]) == "1ecee2215d226817edfdb097f05037eb799309103a");

    m1 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[1].first),
            to_usv(x_keys[1].second),
            to_usv(x_keys[0].second),
            "test suite");
    m2 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[2].first),
            to_usv(x_keys[2].second),
            to_usv(x_keys[0].second),
            "test suite");
    m3 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "test suite");
    m3b = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "not test suite");
    m4 = session::decrypt_for_multiple(
            session::to_view_vector(encrypted),
            nonce,
            to_usv(x_keys[4].first),
            to_usv(x_keys[4].second),
            to_usv(x_keys[0].second),
            "test suite");

    REQUIRE(m1);
    REQUIRE(m2);
    REQUIRE(m3);
    CHECK_FALSE(m3b);
    CHECK_FALSE(m4);

    CHECK(to_sv(*m1) == "hello");
    CHECK(to_sv(*m2) == "cruel");
    CHECK(to_sv(*m3) == "world");

    // Mismatch messages & recipients size throws:
    CHECK_THROWS(session::encrypt_for_multiple(
            session::to_view_vector(msgs.begin(), std::prev(msgs.end())),
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            nonce,
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite",
            [&](ustring_view enc) { encrypted.emplace_back(enc); }));
}

TEST_CASE("Multi-recipient encryption, simpler interface", "[encrypt][multi][simple]") {

    const std::array seeds = {
            "0123456789abcdef0123456789abcdef00000000000000000000000000000000"_hexbytes,
            "0123456789abcdef000000000000000000000000000000000000000000000000"_hexbytes,
            "0123456789abcdef111111111111111100000000000000000000000000000000"_hexbytes,
            "0123456789abcdef222222222222222200000000000000000000000000000000"_hexbytes,
            "0123456789abcdef333333333333333300000000000000000000000000000000"_hexbytes};

    std::array<x_pair, seeds.size()> x_keys;
    for (int i = 0; i < seeds.size(); i++)
        x_keys[i] = to_x_keys(seeds[i]);

    CHECK(oxenc::to_hex(to_usv(x_keys[0].second)) ==
          "d2ad010eeb72d72e561d9de7bd7b6989af77dcabffa03a5111a6c859ae5c3a72");
    CHECK(oxenc::to_hex(to_usv(x_keys[1].second)) ==
          "d673a8fb4800d2a252d2fc4e3342a88cdfa9412853934e8993d12d593be13371");
    CHECK(oxenc::to_hex(to_usv(x_keys[2].second)) ==
          "afd9716ea69ab8c7f475e1b250c86a6539e260804faecf2a803e9281a4160738");
    CHECK(oxenc::to_hex(to_usv(x_keys[3].second)) ==
          "03be14feabd59122349614b88bdc90db1d1af4c230e9a73c898beec833d51f11");
    CHECK(oxenc::to_hex(to_usv(x_keys[4].second)) ==
          "27b5c1ea87cef76284c752fa6ee1b9186b1a95e74e8f5b88f8b47e5191ce6f08");

    auto nonce = "32ab4bb45d6df5cc14e1c330fb1a8b68ea3826a8c2213a49"_hexbytes;

    std::vector<ustring_view> recipients;
    for (auto& [_, pubkey] : x_keys)
        recipients.emplace_back(pubkey.data(), pubkey.size());

    std::vector<std::string> msgs{{"hello", "cruel", "world"}};
    ustring encrypted = session::encrypt_for_multiple_simple(
            msgs[0],
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite");

    REQUIRE(encrypted.size() ==
            /* de */ 2 +
                    /* 1:# 24:...nonce... */ 3 + 27 +
                    /* 1:e le */ 3 + 2 +
                    /* XX: then data with overhead */ 3 *
                            (3 + 5 + crypto_aead_xchacha20poly1305_ietf_ABYTES));

    // If we encrypt again the value should be different (because of the default randomized nonce):
    CHECK(encrypted != session::encrypt_for_multiple_simple(
                               msgs[0],
                               session::to_view_vector(
                                       std::next(recipients.begin()), std::prev(recipients.end())),
                               to_usv(x_keys[0].first),
                               to_usv(x_keys[0].second),
                               "test suite"));

    auto m1 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[1].first),
            to_usv(x_keys[1].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m2 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[2].first),
            to_usv(x_keys[2].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m3 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "test suite");
    auto m3b = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "not test suite");
    auto m4 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[4].first),
            to_usv(x_keys[4].second),
            to_usv(x_keys[0].second),
            "test suite");

    REQUIRE(m1);
    REQUIRE(m2);
    REQUIRE(m3);
    CHECK_FALSE(m3b);
    CHECK_FALSE(m4);

    CHECK(to_sv(*m1) == "hello");
    CHECK(to_sv(*m2) == "hello");
    CHECK(to_sv(*m3) == "hello");

    encrypted = session::encrypt_for_multiple_simple(
            session::to_view_vector(msgs),
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite",
            nonce);

    CHECK(printable(encrypted) ==
          printable(
                  "d1:#24:" + "32ab4bb45d6df5cc14e1c330fb1a8b68ea3826a8c2213a49"_hex + "1:el" +
                  "21:" + "e64937e5ea201b84f4e88a976dad900d91caaf6a17"_hex +
                  "21:" + "bcb642c49c6da03f70cdaab2ed6666721318afd631"_hex +
                  "21:" + "1ecee2215d226817edfdb097f05037eb799309103a"_hex + "ee"));

    m1 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[1].first),
            to_usv(x_keys[1].second),
            to_usv(x_keys[0].second),
            "test suite");
    m2 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[2].first),
            to_usv(x_keys[2].second),
            to_usv(x_keys[0].second),
            "test suite");
    m3 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "test suite");
    m3b = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[3].first),
            to_usv(x_keys[3].second),
            to_usv(x_keys[0].second),
            "not test suite");
    m4 = session::decrypt_for_multiple_simple(
            encrypted,
            to_usv(x_keys[4].first),
            to_usv(x_keys[4].second),
            to_usv(x_keys[0].second),
            "test suite");

    REQUIRE(m1);
    REQUIRE(m2);
    REQUIRE(m3);
    CHECK_FALSE(m3b);
    CHECK_FALSE(m4);

    CHECK(to_sv(*m1) == "hello");
    CHECK(to_sv(*m2) == "cruel");
    CHECK(to_sv(*m3) == "world");

    CHECK_THROWS(session::encrypt_for_multiple_simple(
            session::to_view_vector(msgs.begin(), std::prev(msgs.end())),
            session::to_view_vector(std::next(recipients.begin()), std::prev(recipients.end())),
            to_usv(x_keys[0].first),
            to_usv(x_keys[0].second),
            "test suite"));
}
