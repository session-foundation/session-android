#include <oxenc/hex.h>
#include <session/config/encrypt.h>
#include <session/config/user_profile.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <cstring>
#include <session/config/user_profile.hpp>
#include <string_view>

#include "utils.hpp"

using namespace std::literals;
using namespace oxenc::literals;

void log_msg(config_log_level lvl, const char* msg, void*) {
    INFO((lvl == LOG_LEVEL_ERROR     ? "ERROR"
          : lvl == LOG_LEVEL_WARNING ? "Warning"
          : lvl == LOG_LEVEL_INFO    ? "Info"
                                     : "debug")
         << ": " << msg);
}

TEST_CASE("UserProfile", "[config][user_profile]") {

    const auto seed = "0123456789abcdef0123456789abcdef00000000000000000000000000000000"_hexbytes;
    std::array<unsigned char, 32> ed_pk, curve_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_seed_keypair(
            ed_pk.data(), ed_sk.data(), reinterpret_cast<const unsigned char*>(seed.data()));
    int rc = crypto_sign_ed25519_pk_to_curve25519(curve_pk.data(), ed_pk.data());
    REQUIRE(rc == 0);

    REQUIRE(oxenc::to_hex(ed_pk.begin(), ed_pk.end()) ==
            "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7");
    REQUIRE(oxenc::to_hex(curve_pk.begin(), curve_pk.end()) ==
            "d2ad010eeb72d72e561d9de7bd7b6989af77dcabffa03a5111a6c859ae5c3a72");
    CHECK(oxenc::to_hex(seed.begin(), seed.end()) ==
          oxenc::to_hex(ed_sk.begin(), ed_sk.begin() + 32));

    session::config::UserProfile profile{ustring_view{seed}, std::nullopt};

    CHECK_THROWS(
            profile.set_name("123456789012345678901234567890123456789012345678901234567890123456789"
                             "01"
                             "23456789012345678901234567890A"));
    CHECK_NOTHROW(
            profile.set_name_truncated("12345678901234567890123456789012345678901234567890123456789"
                                       "01"
                                       "234567890123456789012345678901234567890A"));
    CHECK(profile.get_name() ==
          "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
          "901234567890");
    CHECK_NOTHROW(
            profile.set_name_truncated("12345678901234567890123456789012345678901234567890123456789"
                                       "01"
                                       "234567890123456789012345678901234567🎂"));
    CHECK(profile.get_name() ==
          "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
          "901234567");
    CHECK_NOTHROW(
            profile.set_name_truncated("12345678901234567890123456789012345678901234567890123456789"
                                       "01"
                                       "2345678901234567890123456789012345🎂🎂"));
    CHECK(profile.get_name() ==
          "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
          "9012345🎂");
}

TEST_CASE("user profile C API", "[config][user_profile][c]") {

    const auto seed = "0123456789abcdef0123456789abcdef00000000000000000000000000000000"_hex;
    std::array<unsigned char, 32> ed_pk, curve_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_seed_keypair(
            ed_pk.data(), ed_sk.data(), reinterpret_cast<const unsigned char*>(seed.data()));
    int rc = crypto_sign_ed25519_pk_to_curve25519(curve_pk.data(), ed_pk.data());
    REQUIRE(rc == 0);

    REQUIRE(oxenc::to_hex(ed_pk.begin(), ed_pk.end()) ==
            "4cb76fdc6d32278e3f83dbf608360ecc6b65727934b85d2fb86862ff98c46ab7");
    REQUIRE(oxenc::to_hex(curve_pk.begin(), curve_pk.end()) ==
            "d2ad010eeb72d72e561d9de7bd7b6989af77dcabffa03a5111a6c859ae5c3a72");
    CHECK(oxenc::to_hex(seed) == oxenc::to_hex(ed_sk.begin(), ed_sk.begin() + 32));

    // Initialize a brand new, empty config because we have no dump data to deal with.
    char err[256];
    config_object* conf;
    rc = user_profile_init(&conf, ed_sk.data(), NULL, 0, err);
    REQUIRE(rc == 0);

    config_set_logger(conf, log_msg, NULL);

    // We don't need to push anything, since this is an empty config
    CHECK_FALSE(config_needs_push(conf));
    // And we haven't changed anything so don't need to dump to db
    CHECK_FALSE(config_needs_dump(conf));

    // Since it's empty there shouldn't be a name.
    const char* name = user_profile_get_name(conf);
    CHECK(name == nullptr);  // (should be NULL instead of nullptr in C)

    // We don't need to push since we haven't changed anything, so this call is mainly just for
    // testing:
    config_push_data* to_push = config_push(conf);
    REQUIRE(to_push);
    CHECK(to_push->seqno == 0);
    CHECK(to_push->config_len == 256 + 176);  // 176 = protobuf overhead
    const char* enc_domain = "UserProfile";
    REQUIRE(config_encryption_domain(conf) == std::string_view{enc_domain});

    // There's nothing particularly profound about this value (it is multiple layers of nested
    // protobuf with some encryption and padding halfway through); this test is just here to ensure
    // that our pushed messages are deterministic:
    CHECK(oxenc::to_hex(to_push->config, to_push->config + to_push->config_len) ==
          "080112ab030a0012001aa20308062801429b0326ec9746282053eb119228e6c36012966e7d2642163169ba39"
          "98af44ca65f967768dd78ee80fffab6f809f6cef49c73a36c82a89622ff0de2ceee06b8c638e2c876fa9047f"
          "449dbe24b1fc89281a264fe90abdeffcdd44f797bd4572a6c5ae8d88bf372c3c717943ebd570222206fabf0e"
          "e9f3c6756f5d71a32616b1df53d12887961f5c129207a79622ccc1a4bba976886d9a6ddf0fe5d570e5075d01"
          "ecd627f656e95f27b4c40d5661b5664cedd3e568206effa1308b0ccd663ca61a6d39c0731891804a8cf5edcf"
          "8b98eaa5580c3d436e22156e38455e403869700956c3c1dd0b4470b663e75c98c5b859b53ccef6559215d804"
          "9f755be9c2d6b3f4a310f97c496fc392f65b6431dd87788ac61074fd8cd409702e1b839b3f774d38cf8b28f0"
          "226c4efa5220ac6ae060793e36e7ef278d42d042f15b21291f3bb29e3158f09d154b93f83fd8a319811a26cb"
          "5240d90cbb360fafec0b7eff4c676ae598540813d062dc9468365c73b4cfa2ffd02d48cdcd8f0c71324c6d0a"
          "60346a7a0e50af3be64684b37f9e6c831115bf112ddd18acde08eaec376f0872a3952000");

    free(to_push);

    // These should also be unset:
    auto pic = user_profile_get_pic(conf);
    CHECK(strlen(pic.url) == 0);
    CHECK(user_profile_get_nts_priority(conf) == 0);
    CHECK(user_profile_get_nts_expiry(conf) == 0);

    // Now let's go set them:
    CHECK(0 == user_profile_set_name(conf, "Kallie"));
    user_profile_pic p;
    strcpy(p.url, "http://example.org/omg-pic-123.bmp");  // NB: length must be < sizeof(p.url)!
    memcpy(p.key, "secret78901234567890123456789012", 32);
    CHECK(0 == user_profile_set_pic(conf, p));
    user_profile_set_nts_priority(conf, 9);

    // Retrieve them just to make sure they set properly:
    name = user_profile_get_name(conf);
    REQUIRE(name != nullptr);  // (should be NULL instead of nullptr in C)
    CHECK(name == "Kallie"sv);

    pic = user_profile_get_pic(conf);
    REQUIRE(pic.url);
    REQUIRE(pic.key);
    CHECK(pic.url == "http://example.org/omg-pic-123.bmp"sv);
    CHECK(ustring_view{pic.key, 32} == "secret78901234567890123456789012"_bytes);

    CHECK(user_profile_get_nts_priority(conf) == 9);

    // Since we've made changes, we should need to push new config to the swarm, *and* should need
    // to dump the updated state:

    CHECK(config_needs_push(conf));
    CHECK(config_needs_dump(conf));
    to_push = config_push(conf);
    CHECK(to_push->seqno == 1);  // incremented since we made changes (this only increments once
                                 // between dumps; even though we changed two fields here).

    // The hash of a completely empty, initial seqno=0 message:
    auto exp_hash0 = "ea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c965"_hexbytes;

    // The data to be actually pushed, expanded like this to make it somewhat human-readable:
    // clang-format off
    auto exp_push1_decrypted =
        "d"
          "1:#" "i1e"
          "1:&" "d"
            "1:+" "i9e"
            "1:n" "6:Kallie"
            "1:p" "34:http://example.org/omg-pic-123.bmp"
            "1:q" "32:secret78901234567890123456789012"
          "e"
          "1:<" "l"
            "l" "i0e" "32:"_bytes + exp_hash0 + "de" "e"
          "e"
          "1:=" "d"
            "1:+" "0:"
            "1:n" "0:"
            "1:p" "0:"
            "1:q" "0:"
          "e"
        "e"_bytes;
    // clang-format on
    auto exp_push1_encrypted =
            "9693a69686da3055f1ecdfb239c3bf8e746951a36d888c2fb7c02e856a5c2091b24e39a7e1af828f"
            "1fa09fe8bf7d274afde0a0847ba143c43ffb8722301b5ae32e2f078b9a5e19097403336e50b18c84"
            "aade446cd2823b011f97d6ad2116a53feb814efecc086bc172d31f4214b4d7c630b63bbe575b0868"
            "2d146da44915063a07a78556ab5eff4f67f6aa26211e8d330b53d28567a931028c393709a325425d"
            "e7486ccde24416a7fd4a8ba5fa73899c65f4276dfaddd5b2100adcf0f793104fb235b31ce32ec656"
            "056009a9ebf58d45d7d696b74e0c7ff0499c4d23204976f19561dc0dba6dc53a2497d28ce03498ea"
            "49bf122762d7bc1d6d9c02f6d54f8384"_hexbytes;

    // Copy this out; we need to hold onto it to do the confirmation later on
    seqno_t seqno = to_push->seqno;

    // config_push gives us back a buffer that we are required to free when done.  (Without this
    // we'd leak memory!)
    free(to_push);

    // We haven't dumped, so still need to dump:
    CHECK(config_needs_dump(conf));
    // We did call push, but we haven't confirmed it as stored yet, so this will still return true:
    CHECK(config_needs_push(conf));
    unsigned char* dump1;
    size_t dump1len;

    config_dump(conf, &dump1, &dump1len);
    // (in a real client we'd now store this to disk)

    CHECK_FALSE(config_needs_dump(conf));

    // clang-format off
    CHECK(printable(dump1, dump1len) == printable(
        "d"
          "1:!" "i2e"
          "1:$" + std::to_string(exp_push1_decrypted.size()) + ":" + std::string{to_sv(exp_push1_decrypted)} + ""
          "1:(" "0:"
          "1:)" "le"
        "e"));
    // clang-format on
    free(dump1);  // done with the dump; don't leak!

    // So now imagine we got back confirmation from the swarm that the push has been stored:
    config_confirm_pushed(conf, seqno, "fakehash1");

    CHECK_FALSE(config_needs_push(conf));
    CHECK(config_needs_dump(conf));  // The confirmation changes state, so this makes us need a dump
                                     // again.
    config_dump(conf, &dump1, &dump1len);

    // clang-format off
    CHECK(printable(dump1, dump1len) == printable(
        "d"
          "1:!" "i0e"
          "1:$" + std::to_string(exp_push1_decrypted.size()) + ":" + std::string{to_sv(exp_push1_decrypted)} + ""
          "1:(" "9:fakehash1"
          "1:)" "le"
        "e"));
    // clang-format on
    free(dump1);

    CHECK_FALSE(config_needs_dump(conf));

    // Now we're going to set up a second, competing config object (in the real world this would be
    // another Session client somewhere).

    // Start with an empty config, as above:
    config_object* conf2;
    REQUIRE(user_profile_init(&conf2, ed_sk.data(), NULL, 0, err) == 0);
    config_set_logger(conf2, log_msg, NULL);
    CHECK_FALSE(config_needs_dump(conf2));

    // Now imagine we just pulled down the encrypted string from the swarm; we merge it into conf2:
    const unsigned char* merge_data[1];
    const char* merge_hash[1];
    size_t merge_size[1];
    merge_hash[0] = "fakehash1";
    merge_data[0] = exp_push1_encrypted.data();
    merge_size[0] = exp_push1_encrypted.size();
    config_string_list* accepted = config_merge(conf2, merge_hash, merge_data, merge_size, 1);
    REQUIRE(accepted->len == 1);
    CHECK(accepted->value[0] == "fakehash1"sv);
    free(accepted);

    // Our state has changed, so we need to dump:
    CHECK(config_needs_dump(conf2));
    unsigned char* dump2;
    size_t dump2len;
    config_dump(conf2, &dump2, &dump2len);
    // (store in db)
    free(dump2);
    CHECK_FALSE(config_needs_dump(conf2));

    // We *don't* need to push: even though we updated, all we did is update to the merged data (and
    // didn't have any sort of merge conflict needed):
    REQUIRE_FALSE(config_needs_push(conf2));

    // Now let's create a conflicting update:

    // Change the name on both clients:
    user_profile_set_name(conf, "Nibbler");
    user_profile_set_name(conf2, "Raz");

    // And, on conf2, we're also going to change some other things:
    strcpy(p.url, "http://new.example.com/pic");
    memcpy(p.key, "qwert\0yuio1234567890123456789012", 32);
    user_profile_set_pic(conf2, p);

    user_profile_set_nts_expiry(conf2, 86400);
    CHECK(user_profile_get_nts_expiry(conf2) == 86400);

    CHECK(user_profile_get_blinded_msgreqs(conf2) == -1);
    user_profile_set_blinded_msgreqs(conf2, 0);
    CHECK(user_profile_get_blinded_msgreqs(conf2) == 0);
    user_profile_set_blinded_msgreqs(conf2, -1);
    CHECK(user_profile_get_blinded_msgreqs(conf2) == -1);
    user_profile_set_blinded_msgreqs(conf2, 1);
    CHECK(user_profile_get_blinded_msgreqs(conf2) == 1);

    // Both have changes, so push need a push
    CHECK(config_needs_push(conf));
    CHECK(config_needs_push(conf2));
    to_push = config_push(conf);
    CHECK(to_push->seqno == 2);  // incremented, since we made a field change
    config_confirm_pushed(conf2, to_push->seqno, "fakehash2");

    config_push_data* to_push2 = config_push(conf2);
    CHECK(to_push2->seqno == 2);  // incremented, since we made a field change
    config_confirm_pushed(conf2, to_push2->seqno, "fakehash3");

    config_dump(conf, &dump1, &dump1len);
    config_dump(conf2, &dump2, &dump2len);
    // (store in db)
    free(dump1);
    free(dump2);

    // Since we set different things, we're going to get back different serialized data to be
    // pushed:
    CHECK(printable(to_push->config, to_push->config_len) !=
          printable(to_push2->config, to_push2->config_len));

    // Now imagine that each client pushed its `seqno=2` config to the swarm, but then each client
    // also fetches new messages and pulls down the other client's `seqno=2` value.

    // Feed the new config into each other.  (This array could hold multiple configs if we pulled
    // down more than one).
    merge_hash[0] = "fakehash2";
    merge_data[0] = to_push->config;
    merge_size[0] = to_push->config_len;
    accepted = config_merge(conf2, merge_hash, merge_data, merge_size, 1);
    free(to_push);
    REQUIRE(accepted->len == 1);
    CHECK(accepted->value[0] == "fakehash2"sv);
    free(accepted);
    merge_hash[0] = "fakehash3";
    merge_data[0] = to_push2->config;
    merge_size[0] = to_push2->config_len;
    accepted = config_merge(conf, merge_hash, merge_data, merge_size, 1);
    REQUIRE(accepted->len == 1);
    CHECK(accepted->value[0] == "fakehash3"sv);
    free(accepted);
    free(to_push2);

    // Now after the merge we *will* want to push from both client, since both will have generated a
    // merge conflict update (with seqno = 3).
    to_push = config_push(conf);
    to_push2 = config_push(conf2);

    REQUIRE(to_push->seqno == 3);
    REQUIRE(to_push2->seqno == 3);
    REQUIRE(config_needs_push(conf));
    REQUIRE(config_needs_push(conf2));

    // They should have resolved the conflict to the same thing:
    CHECK(user_profile_get_name(conf) == "Nibbler"sv);
    CHECK(user_profile_get_name(conf2) == "Nibbler"sv);
    // (Note that they could have also both resolved to "Raz" here, but the hash of the serialized
    // message just happens to have a higher hash -- and thus gets priority -- for this particular
    // test).

    // Since only one of them set a profile pic there should be no conflict there:
    pic = user_profile_get_pic(conf);
    REQUIRE(pic.url);
    CHECK(pic.url == "http://new.example.com/pic"sv);
    REQUIRE(pic.key);
    CHECK(to_hex(ustring_view{pic.key, 32}) ==
          "7177657274007975696f31323334353637383930313233343536373839303132");
    pic = user_profile_get_pic(conf2);
    REQUIRE(pic.url);
    CHECK(pic.url == "http://new.example.com/pic"sv);
    REQUIRE(pic.key);
    CHECK(to_hex(ustring_view{pic.key, 32}) ==
          "7177657274007975696f31323334353637383930313233343536373839303132");

    CHECK(user_profile_get_nts_priority(conf) == 9);
    CHECK(user_profile_get_nts_priority(conf2) == 9);
    CHECK(user_profile_get_nts_expiry(conf) == 86400);
    CHECK(user_profile_get_nts_expiry(conf2) == 86400);
    CHECK(user_profile_get_blinded_msgreqs(conf) == 1);
    CHECK(user_profile_get_blinded_msgreqs(conf2) == 1);

    config_confirm_pushed(conf, to_push->seqno, "fakehash4");
    config_confirm_pushed(conf2, to_push2->seqno, "fakehash4");

    config_dump(conf, &dump1, &dump1len);
    config_dump(conf2, &dump2, &dump2len);
    // (store in db)
    free(dump1);
    free(dump2);

    CHECK_FALSE(config_needs_dump(conf));
    CHECK_FALSE(config_needs_dump(conf2));
    CHECK_FALSE(config_needs_push(conf));
    CHECK_FALSE(config_needs_push(conf2));
}
