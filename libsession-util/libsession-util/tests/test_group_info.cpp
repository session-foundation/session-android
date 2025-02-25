#include <oxenc/endian.h>
#include <oxenc/hex.h>
#include <session/config/contacts.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers.hpp>
#include <session/config/groups/info.hpp>
#include <string_view>

#include "utils.hpp"

using namespace std::literals;
using namespace oxenc::literals;

static constexpr int64_t created_ts = 1680064059;

using namespace session::config;

TEST_CASE("Group Info settings", "[config][groups][info]") {

    const auto seed = "0123456789abcdef0123456789abcdeffedcba9876543210fedcba9876543210"_hexbytes;
    std::array<unsigned char, 32> ed_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_seed_keypair(
            ed_pk.data(), ed_sk.data(), reinterpret_cast<const unsigned char*>(seed.data()));

    REQUIRE(oxenc::to_hex(ed_pk.begin(), ed_pk.end()) ==
            "cbd569f56fb13ea95a3f0c05c331cc24139c0090feb412069dc49fab34406ece");
    CHECK(oxenc::to_hex(seed.begin(), seed.end()) ==
          oxenc::to_hex(ed_sk.begin(), ed_sk.begin() + 32));

    std::vector<ustring> enc_keys{
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"_hexbytes};

    groups::Info ginfo1{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    // This is just for testing: normally you don't load keys manually but just make a groups::Keys
    // object that loads the keys into the Members object for you.
    for (const auto& k : enc_keys)
        ginfo1.add_key(k, false);

    enc_keys.insert(
            enc_keys.begin(),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"_hexbytes);
    enc_keys.push_back("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"_hexbytes);
    enc_keys.push_back("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"_hexbytes);
    groups::Info ginfo2{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys)  // Just for testing, as above.
        ginfo2.add_key(k, false);

    ginfo1.set_name("GROUP Name");
    CHECK(ginfo1.is_dirty());
    CHECK(ginfo1.needs_push());
    CHECK(ginfo1.needs_dump());

    auto [s1, p1, o1] = ginfo1.push();

    CHECK(s1 == 1);
    CHECK(p1.size() == 256);
    CHECK(o1.empty());

    ginfo1.confirm_pushed(s1, "fakehash1");
    CHECK(ginfo1.needs_dump());
    CHECK_FALSE(ginfo1.needs_push());

    std::vector<std::pair<std::string, ustring_view>> merge_configs;
    merge_configs.emplace_back("fakehash1", p1);
    CHECK(ginfo2.merge(merge_configs) == std::vector{{"fakehash1"s}});
    CHECK_FALSE(ginfo2.needs_push());

    CHECK(ginfo2.get_name() == "GROUP Name");

    ginfo2.set_profile_pic(
            "http://example.com/12345",
            "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes);
    ginfo2.set_expiry_timer(1h);
    constexpr int64_t create_time{1682529839};
    ginfo2.set_created(create_time);
    ginfo2.set_delete_before(create_time + 50 * 86400);
    ginfo2.set_delete_attach_before(create_time + 70 * 86400);
    ginfo2.destroy_group();

    auto [s2, p2, o2] = ginfo2.push();
    CHECK(s2 == 2);
    CHECK(p2.size() == 512);
    CHECK(o2 == std::vector{"fakehash1"s});

    ginfo2.confirm_pushed(s2, "fakehash2");

    ginfo1.set_name("Better name!");

    merge_configs.clear();
    merge_configs.emplace_back("fakehash2", p2);

    // This fails because ginfo1 doesn't yet have the new key that ginfo2 used (bbb...)
    CHECK(ginfo1.merge(merge_configs) == std::vector<std::string>{});

    ginfo1.add_key("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"_hexbytes);
    ginfo1.add_key(
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"_hexbytes,
            /*prepend=*/false);

    CHECK(ginfo1.merge(merge_configs) == std::vector{{"fakehash2"s}});

    CHECK(ginfo1.needs_push());
    auto [s3, p3, o3] = ginfo1.push();

    CHECK(ginfo1.get_name() == "Better name!");
    CHECK(ginfo1.get_profile_pic().url == "http://example.com/12345");
    CHECK(ginfo1.get_profile_pic().key ==
          "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes);
    CHECK(ginfo1.get_expiry_timer() == 1h);
    CHECK(ginfo1.get_created() == create_time);
    CHECK(ginfo1.get_delete_before() == create_time + 50 * 86400);
    CHECK(ginfo1.get_delete_attach_before() == create_time + 70 * 86400);
    CHECK(ginfo1.is_destroyed());

    ginfo1.confirm_pushed(s3, "fakehash3");

    merge_configs.clear();
    merge_configs.emplace_back("fakehash3", p3);
    CHECK(ginfo2.merge(merge_configs) == std::vector{{"fakehash3"s}});
    CHECK(ginfo2.get_name() == "Better name!");
    CHECK(ginfo2.get_profile_pic().url == "http://example.com/12345");
    CHECK(ginfo2.get_profile_pic().key ==
          "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes);
    CHECK(ginfo2.get_expiry_timer() == 1h);
    CHECK(ginfo2.get_created() == create_time);
    CHECK(ginfo2.get_delete_before() == create_time + 50 * 86400);
    CHECK(ginfo2.get_delete_attach_before() == create_time + 70 * 86400);
    CHECK(ginfo2.is_destroyed());

    CHECK_THROWS(
            ginfo1.set_name("1234567890123456789012345678901234567890123456789012345678901234567890"
                            "123456789012345678901234567890A"));
    CHECK_NOTHROW(
            ginfo1.set_name_truncated("123456789012345678901234567890123456789012345678901234567890"
                                      "1234567890123456789012345678901234567890A"));
    CHECK(ginfo1.get_name() ==
          "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
          "901234567890");
}

TEST_CASE("Verify-only Group Info", "[config][groups][verify-only]") {

    const auto seed = "0123456789abcdef0123456789abcdeffedcba9876543210fedcba9876543210"_hexbytes;
    std::array<unsigned char, 32> ed_pk;
    std::array<unsigned char, 64> ed_sk;
    crypto_sign_ed25519_seed_keypair(
            ed_pk.data(), ed_sk.data(), reinterpret_cast<const unsigned char*>(seed.data()));

    REQUIRE(oxenc::to_hex(ed_pk.begin(), ed_pk.end()) ==
            "cbd569f56fb13ea95a3f0c05c331cc24139c0090feb412069dc49fab34406ece");
    CHECK(oxenc::to_hex(seed.begin(), seed.end()) ==
          oxenc::to_hex(ed_sk.begin(), ed_sk.begin() + 32));

    std::vector<ustring> enc_keys1;
    enc_keys1.push_back(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"_hexbytes);
    std::vector<ustring> enc_keys2;
    enc_keys2.push_back(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"_hexbytes);
    enc_keys2.push_back(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"_hexbytes);

    // This Info object has only the public key, not the priv key, and so cannot modify things:
    groups::Info ginfo{to_usv(ed_pk), std::nullopt, std::nullopt};

    for (const auto& k : enc_keys1)  // Just for testing, as above.
        ginfo.add_key(k, false);

    REQUIRE_THROWS_WITH(
            ginfo.set_name("Super Group!"), "Unable to make changes to a read-only config object");
    REQUIRE_THROWS_WITH(
            ginfo.set_name("Super Group!"), "Unable to make changes to a read-only config object");
    CHECK(!ginfo.is_dirty());

    // This one is good and has the right signature:
    groups::Info ginfo_rw{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys1)  // Just for testing, as above.
        ginfo_rw.add_key(k, false);

    ginfo_rw.set_name("Super Group!!");
    CHECK(ginfo_rw.is_dirty());
    CHECK(ginfo_rw.needs_push());
    CHECK(ginfo_rw.needs_dump());

    auto [seqno, to_push, obs] = ginfo_rw.push();

    CHECK(seqno == 1);

    ginfo_rw.confirm_pushed(seqno, "fakehash1");
    CHECK(ginfo_rw.needs_dump());
    CHECK_FALSE(ginfo_rw.needs_push());

    std::vector<std::pair<std::string, ustring_view>> merge_configs;
    merge_configs.emplace_back("fakehash1", to_push);
    CHECK(ginfo.merge(merge_configs) == std::vector{{"fakehash1"s}});
    CHECK_FALSE(ginfo.needs_push());

    groups::Info ginfo_rw2{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys1)  // Just for testing, as above.
        ginfo_rw2.add_key(k, false);

    CHECK(ginfo_rw2.merge(merge_configs) == std::vector{{"fakehash1"s}});
    CHECK_FALSE(ginfo.needs_push());

    CHECK(ginfo.get_name() == "Super Group!!");

    REQUIRE_THROWS_WITH(
            ginfo.set_name("Super Group11"), "Unable to make changes to a read-only config object");
    // This shouldn't throw because it isn't *actually* changing a config value (i.e. re-setting the
    // same value does not dirty the config).  It isn't clear why you'd need to do this, but still.
    ginfo.set_name("Super Group!!");

    // Deliberately use the wrong signing key so that what we produce encrypts successfully but
    // doesn't verify
    const auto seed_bad1 =
            "0023456789abcdef0123456789abcdeffedcba9876543210fedcba9876543210"_hexbytes;
    std::array<unsigned char, 32> ed_pk_bad1;
    std::array<unsigned char, 64> ed_sk_bad1;
    crypto_sign_ed25519_seed_keypair(
            ed_pk_bad1.data(),
            ed_sk_bad1.data(),
            reinterpret_cast<const unsigned char*>(seed_bad1.data()));

    groups::Info ginfo_bad1{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys1)  // Just for testing, as above.
        ginfo_bad1.add_key(k, false);

    ginfo_bad1.merge(merge_configs);
    ginfo_bad1.set_sig_keys(to_usv(ed_sk_bad1));
    ginfo_bad1.set_name("Bad name, BAD!");
    auto [s_bad, p_bad, o_bad] = ginfo_bad1.push();

    merge_configs.clear();
    merge_configs.emplace_back("badhash1", p_bad);

    CHECK(ginfo.merge(merge_configs) == std::vector<std::string>{});
    CHECK_FALSE(ginfo.needs_push());

    // Now let's get more complicated: we will have *two* valid signers who submit competing updates
    ginfo_rw2.set_name("Super Group 2");
    ginfo_rw2.set_created(12345);
    ginfo_rw.set_name("Super Group 3");
    ginfo_rw.set_expiry_timer(365 * 24h);

    CHECK(ginfo_rw.needs_push());
    CHECK(ginfo_rw2.needs_push());

    auto [s2, tp2, o2] = ginfo_rw2.push();
    auto [s3, tp3, o3] = ginfo_rw.push();

    merge_configs.clear();
    merge_configs.emplace_back("fakehash2", tp2);
    merge_configs.emplace_back("fakehash3", tp3);

    CHECK(ginfo.merge(merge_configs) == std::vector{{"fakehash2"s, "fakehash3"s}});
    CHECK(ginfo.is_clean());

    CHECK(s2 == 2);
    CHECK(s3 == 2);
    CHECK_FALSE(ginfo.needs_push());

    CHECK(ginfo_rw.merge(merge_configs) == std::vector{{"fakehash2"s, "fakehash3"s}});
    CHECK(ginfo_rw2.merge(merge_configs) == std::vector{{"fakehash2"s, "fakehash3"s}});

    CHECK(ginfo_rw.needs_push());
    CHECK(ginfo_rw2.needs_push());

    auto [s23, t23, o23] = ginfo_rw.push();
    auto [s32, t32, o32] = ginfo_rw2.push();

    CHECK(s23 == s32);
    CHECK(t23 == t32);
    CHECK(o23 == o32);

    ginfo_rw.confirm_pushed(s23, "fakehash23");
    ginfo_rw2.confirm_pushed(s32, "fakehash23");

    merge_configs.clear();
    merge_configs.emplace_back("fakehash23", t23);

    CHECK(ginfo.merge(merge_configs) == std::vector{{"fakehash23"s}});
    CHECK(ginfo_rw.merge(merge_configs) == std::vector{{"fakehash23"s}});
    CHECK(ginfo_rw2.merge(merge_configs) == std::vector{{"fakehash23"s}});

    CHECK_FALSE(ginfo.needs_push());
    CHECK_FALSE(ginfo_rw.needs_push());
    CHECK_FALSE(ginfo_rw2.needs_push());

    auto test = [](groups::Info& g) {
        auto n = g.get_name();
        REQUIRE(n);
        CHECK(*n == "Super Group 2");
        auto c = g.get_created();
        REQUIRE(c);
        CHECK(*c == 12345);
        auto et = g.get_expiry_timer();
        REQUIRE(et);
        CHECK(*et == 365 * 24h);
    };
    test(ginfo);
    test(ginfo_rw);
    test(ginfo_rw2);

    CHECK(ginfo.needs_dump());
    auto dump = ginfo.dump();
    groups::Info ginfo2{to_usv(ed_pk), std::nullopt, dump};

    for (const auto& k : enc_keys1)  // Just for testing, as above.
        ginfo2.add_key(k, false);

    CHECK(!ginfo.needs_dump());
    CHECK(!ginfo2.needs_dump());

    auto [s4, t4, o4] = ginfo.push();
    auto [s5, t5, o5] = ginfo.push();
    CHECK(s4 == s23);
    CHECK(s4 == s5);
    CHECK(t4 == t23);
    CHECK(t4 == t5);
    CHECK(o4.empty());
    CHECK(o5.empty());

    // This account has a different primary decryption key
    groups::Info ginfo_rw3{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys2)  // Just for testing, as above.
        ginfo_rw3.add_key(k, false);

    CHECK(ginfo_rw3.merge(merge_configs) == std::vector{{"fakehash23"s}});
    CHECK(ginfo_rw3.get_name() == "Super Group 2");

    auto [s6, t6, o6] = ginfo_rw3.push();
    CHECK(to_hex(ginfo_rw3.key(0)) ==
          "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    REQUIRE(ginfo_rw3.key_count() == 2);
    CHECK(to_hex(ginfo_rw3.key(1)) ==
          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    CHECK(s6 == s5);
    CHECK(t6.size() == t23.size());
    CHECK(t6 != t23);

    ginfo_rw3.set_profile_pic(
            "http://example.com/12345",
            "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes);
    CHECK(ginfo_rw3.needs_push());
    auto [s7, t7, o7] = ginfo_rw3.push();
    CHECK(s7 == s6 + 1);
    CHECK(t7 != t6);
    CHECK(o7 == std::vector{{"fakehash23"s}});

    merge_configs.clear();
    merge_configs.emplace_back("fakehash7", t7);
    // If we don't have the new "bbb" key loaded yet, this will fail:
    CHECK(ginfo.merge(merge_configs) == std::vector<std::string>{});

    ginfo.add_key(enc_keys2.front());
    CHECK(ginfo.merge(merge_configs) == std::vector{{"fakehash7"s}});

    auto pic = ginfo.get_profile_pic();
    CHECK_FALSE(pic.empty());
    CHECK(pic.url == "http://example.com/12345");
    CHECK(pic.key == "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes);
}
