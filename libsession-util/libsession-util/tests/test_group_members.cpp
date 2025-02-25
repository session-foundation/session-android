#include <oxenc/endian.h>
#include <oxenc/hex.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers.hpp>
#include <iostream>
#include <session/config/groups/members.hpp>
#include <string_view>

#include "utils.hpp"

using namespace std::literals;
using namespace oxenc::literals;

static constexpr int64_t created_ts = 1680064059;

using namespace session::config;

constexpr bool is_prime100(int i) {
    constexpr std::array p100 = {2,  3,  5,  7,  11, 13, 17, 19, 23, 29, 31, 37, 41,
                                 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97};
    for (auto p : p100)
        if (p >= i)
            return p == i;
    return false;
}

TEST_CASE("Group Members", "[config][groups][members]") {

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

    groups::Members gmem1{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    // This is just for testing: normally you don't load keys manually but just make a groups::Keys
    // object that loads the keys into the Members object for you.
    for (const auto& k : enc_keys)
        gmem1.add_key(k, false);

    enc_keys.insert(
            enc_keys.begin(),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"_hexbytes);
    enc_keys.push_back("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"_hexbytes);
    enc_keys.push_back("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"_hexbytes);
    groups::Members gmem2{to_usv(ed_pk), to_usv(ed_sk), std::nullopt};

    for (const auto& k : enc_keys)  // Just for testing, as above.
        gmem2.add_key(k, false);

    std::vector<std::string> sids;
    while (sids.size() < 256) {
        std::array<unsigned char, 33> sid;
        for (auto& s : sid)
            s = sids.size();
        sid[0] = 0x05;
        sids.push_back(oxenc::to_hex(sid.begin(), sid.end()));
    }

    // 10 admins:
    for (int i = 0; i < 10; i++) {
        auto m = gmem1.get_or_construct(sids[i]);
        m.set_promotion_accepted();
        m.name = "Admin " + std::to_string(i);
        m.profile_picture.url = "http://example.com/" + std::to_string(i);
        m.profile_picture.key =
                "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes;
        gmem1.set(m);
    }
    // 10 members:
    for (int i = 10; i < 20; i++) {
        auto m = gmem1.get_or_construct(sids[i]);
        m.set_name("Member " + std::to_string(i));
        m.profile_picture.url = "http://example.com/" + std::to_string(i);
        m.profile_picture.key =
                "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes;
        gmem1.set(m);
    }
    // 5 members with no attributes (not even a name):
    for (int i = 20; i < 25; i++) {
        auto m = gmem1.get_or_construct(sids[i]);
        gmem1.set(m);
    }

    REQUIRE_THROWS(gmem1.get(sids[14])->set_name(std::string(200, 'c')));

    CHECK(gmem1.needs_push());
    auto [s1, p1, o1] = gmem1.push();
    CHECK(p1.size() == 768);

    gmem1.confirm_pushed(s1, "fakehash1");
    CHECK(gmem1.needs_dump());
    CHECK_FALSE(gmem1.needs_push());

    std::vector<std::pair<std::string, ustring_view>> merge_configs;
    merge_configs.emplace_back("fakehash1", p1);
    CHECK(gmem2.merge(merge_configs) == std::vector<std::string>{{"fakehash1"}});
    CHECK_FALSE(gmem2.needs_push());

    for (int i = 0; i < 25; i++)
        CHECK(gmem2.get(sids[i]).has_value());

    {
        int i = 0;
        for (auto& m : gmem2) {
            CHECK(m.session_id == sids[i]);
            CHECK_FALSE(m.status() == session::config::groups::member::Status::invite_failed);
            CHECK_FALSE(m.status() == session::config::groups::member::Status::promotion_not_sent);
            CHECK_FALSE(m.status() == session::config::groups::member::Status::promotion_failed);
            CHECK_FALSE(m.status() == session::config::groups::member::Status::removed);
            CHECK_FALSE(
                    m.status() ==
                    session::config::groups::member::Status::removed_including_messages);
            CHECK_FALSE(m.supplement);
            if (i < 10) {
                CHECK_FALSE(m.status() == session::config::groups::member::Status::invite_not_sent);
                CHECK(m.admin);
                CHECK(m.name == "Admin " + std::to_string(i));
                CHECK_FALSE(m.profile_picture.empty());
                CHECK(m.status() == session::config::groups::member::Status::promotion_accepted);
            } else {
                CHECK(m.status() == session::config::groups::member::Status::invite_not_sent);
                CHECK_FALSE(m.admin);
                if (i < 20) {
                    CHECK(m.name == "Member " + std::to_string(i));
                    CHECK_FALSE(m.profile_picture.empty());
                } else {
                    CHECK(m.name.empty());
                    CHECK(m.profile_picture.empty());
                }
            }
            i++;
        }
        CHECK(i == 25);
    }

    for (int i = 22; i < 50; i++) {
        auto m = gmem2.get_or_construct(sids[i]);
        m.name = "Member " + std::to_string(i);
        gmem2.set(m);
    }
    for (int i = 50; i < 55; i++) {
        auto m = gmem2.get_or_construct(sids[i]);
        m.set_invite_sent();
        if (i % 2)
            m.supplement = true;
        gmem2.set(m);
    }
    for (int i = 55; i < 58; i++) {
        auto m = gmem2.get_or_construct(sids[i]);
        m.set_invite_failed();
        if (i % 2)
            m.supplement = true;
        gmem2.set(m);
    }
    for (int i = 58; i < 62; i++) {
        auto m = gmem2.get_or_construct(sids[i]);
        if (i >= 60)
            m.set_promotion_failed();
        else
            m.set_promotion_sent();
        gmem2.set(m);
    }
    for (int i = 62; i < 66; i++) {
        auto m = gmem2.get_or_construct(sids[i]);
        m.set_removed(i >= 64);
        gmem2.set(m);
    }

    CHECK(gmem2.get(sids[23]).value().name == "Member 23");

    auto [s2, p2, o2] = gmem2.push();
    gmem2.confirm_pushed(s2, "fakehash2");
    merge_configs.emplace_back("fakehash2", p2);  // not clearing it first!
    CHECK(gmem1.merge(merge_configs) == std::vector{{"fakehash1"s}});
    gmem1.add_key("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"_hexbytes);
    CHECK(gmem1.merge(merge_configs) == std::vector{{"fakehash1"s, "fakehash2"s}});

    CHECK(gmem1.get(sids[23]).value().name == "Member 23");

    {
        int i = 0;
        for (auto& m : gmem1) {
            CHECK(m.session_id == sids[i]);
            CHECK(m.admin == (i < 10 || (i >= 58 && i < 62)));
            CHECK(m.name == ((i == 20 || i == 21 || i >= 50) ? ""
                             : i < 10                        ? "Admin " + std::to_string(i)
                                                             : "Member " + std::to_string(i)));
            CHECK(m.profile_picture.key ==
                  (i < 20 ? "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes
                          : ""_hexbytes));
            CHECK(m.profile_picture.url ==
                  (i < 20 ? "http://example.com/" + std::to_string(i) : ""));
            if (i >= 10 && i < 50)
                CHECK(m.status() == session::config::groups::member::Status::invite_not_sent);
            if (50 <= i && i < 55)
                CHECK(m.status() == session::config::groups::member::Status::invite_sent);
            if (55 <= i && i < 58)
                CHECK(m.status() == session::config::groups::member::Status::invite_failed);
            if (i < 10)
                CHECK(m.status() == session::config::groups::member::Status::promotion_accepted);
            if (i >= 58 && i < 60)
                CHECK(m.status() == session::config::groups::member::Status::promotion_sent);
            if (i >= 60 && i < 62)
                CHECK(m.status() == session::config::groups::member::Status::promotion_failed);
            if (i >= 62 && i < 64)
                CHECK(m.status() == session::config::groups::member::Status::removed);
            if (i >= 64 && i < 66)
                CHECK(m.status() ==
                      session::config::groups::member::Status::removed_including_messages);
            CHECK(m.supplement == (i % 2 && 50 < i && i < 58));
            i++;
        }
        CHECK(i == 66);
    }

    for (int i = 0; i < 100; i++) {
        if (is_prime100(i))
            gmem1.erase(sids[i]);
        else if (i >= 50 && i <= 56) {
            auto m = gmem1.get(sids[i]).value();
            if (i >= 55)
                m.set_invite_sent();
            else
                m.set_invite_accepted();
            gmem1.set(m);
        } else if (i == 58) {
            auto m = gmem1.get(sids[i]).value();
            m.set_promotion_accepted();
            gmem1.set(m);
        } else if (i == 59) {
            auto m = gmem1.get(sids[i]).value();
            m.set_promotion_sent();
            gmem1.set(m);
        }
    }

    auto [s3, p3, o3] = gmem1.push();
    gmem1.confirm_pushed(s3, "fakehash3");
    merge_configs.clear();
    merge_configs.emplace_back("fakehash3", p3);
    CHECK(gmem2.merge(merge_configs) == std::vector{{"fakehash3"s}});

    {
        int i = 0;
        for (auto& m : gmem2) {
            CHECK(m.session_id == sids[i]);
            CHECK(m.admin == (i < 10 || (i >= 58 && i < 62)));
            CHECK(m.name == ((i == 20 || i == 21 || i >= 50) ? ""
                             : i < 10                        ? "Admin " + std::to_string(i)
                                                             : "Member " + std::to_string(i)));
            CHECK(m.profile_picture.key ==
                  (i < 20 ? "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"_hexbytes
                          : ""_hexbytes));
            CHECK(m.profile_picture.url ==
                  (i < 20 ? "http://example.com/" + std::to_string(i) : ""));
            if (i >= 10 && i < 50)
                CHECK(m.status() == session::config::groups::member::Status::invite_not_sent);
            if (i >= 50 && i < 54)
                CHECK(m.status() == session::config::groups::member::Status::invite_accepted);
            if (i == 53 || (i >= 55 && i < 57))
                CHECK(m.status() == session::config::groups::member::Status::invite_sent);
            if (i == 57)
                CHECK(m.status() == session::config::groups::member::Status::invite_failed);
            if (i < 10 || i == 58)
                CHECK(m.status() == session::config::groups::member::Status::promotion_accepted);
            if (i == 59)
                CHECK(m.status() == session::config::groups::member::Status::promotion_sent);
            if (i >= 60 && i < 62)
                CHECK(m.status() == session::config::groups::member::Status::promotion_failed);
            if (i >= 62 && i < 64)
                CHECK(m.status() == session::config::groups::member::Status::removed);
            if (i >= 64 && i < 66)
                CHECK(m.status() ==
                      session::config::groups::member::Status::removed_including_messages);
            CHECK(m.supplement == (i == 55 || i == 57));

            do
                i++;
            while (is_prime100(i));
        }
        CHECK(i == 66);
    }

    auto m = gmem1.get_or_construct(sids[0]);
    CHECK_THROWS(
            m.set_name("123456789012345678901234567890123456789012345678901234567890123456789012345"
                       "6789012345678901234567890A"));
    CHECK_NOTHROW(
            m.set_name_truncated("12345678901234567890123456789012345678901234567890123456789012345"
                                 "67890123456789012345678901234567890A"));
    CHECK(m.name ==
          "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
          "901234567890");
}
