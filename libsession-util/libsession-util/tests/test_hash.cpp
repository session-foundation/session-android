#include <catch2/catch_test_macros.hpp>

#include "session/hash.h"
#include "session/hash.hpp"
#include "utils.hpp"

TEST_CASE("Hash generation", "[hash][hash]") {
    auto hash1 = session::hash::hash(32, to_usv("TestMessage"), std::nullopt);
    auto hash2 = session::hash::hash(32, to_usv("TestMessage"), std::nullopt);
    auto hash3 = session::hash::hash(32, to_usv("TestMessage"), to_usv("TestKey"));
    auto hash4 = session::hash::hash(32, to_usv("TestMessage"), to_usv("TestKey"));
    auto hash5 = session::hash::hash(64, to_usv("TestMessage"), std::nullopt);
    auto hash6 = session::hash::hash(64, to_usv("TestMessage"), to_usv("TestKey"));
    CHECK_THROWS(session::hash::hash(10, to_usv("TestMessage"), std::nullopt));
    CHECK_THROWS(session::hash::hash(100, to_usv("TestMessage"), std::nullopt));
    CHECK_THROWS(session::hash::hash(
            32,
            to_usv("TestMessage"),
            to_usv("KeyThatIsTooLongKeyThatIsTooLongKeyThatIsTooLongKeyThatIsTooLongKeyThatIsTooLon"
                   "g")));

    CHECK(hash1.size() == 32);
    CHECK(hash2.size() == 32);
    CHECK(hash3.size() == 32);
    CHECK(hash4.size() == 32);
    CHECK(hash5.size() == 64);
    CHECK(hash6.size() == 64);
    CHECK(hash1 == hash2);
    CHECK(hash1 != hash3);
    CHECK(hash3 == hash4);
    CHECK(hash1 != hash5);
    CHECK(hash3 != hash6);
    CHECK(oxenc::to_hex(hash1) ==
          "2a48a12262e4548afb97fe2b04a912a02297d451169ee7ef2d01a28ea20286ab");
    CHECK(oxenc::to_hex(hash2) ==
          "2a48a12262e4548afb97fe2b04a912a02297d451169ee7ef2d01a28ea20286ab");
    CHECK(oxenc::to_hex(hash3) ==
          "3d643e479b626bb2907476e32ccf7bdbd1ac3efa0da6e2c335255c48dcc216b6");
    CHECK(oxenc::to_hex(hash4) ==
          "3d643e479b626bb2907476e32ccf7bdbd1ac3efa0da6e2c335255c48dcc216b6");

    auto expected_hash5 =
            "9d9085ac026fe3542abbeb2ea2ec05f5c37aecd7695f6cc41e9ccf39014196a39c02db69c44"
            "16d5c45acc2e9469b7f274992b2858f3bb2746becb48c8b56ce4b";
    auto expected_hash6 =
            "6a2faad89cf9010a4270cba07cc96cfb36688106e080b15fef66bb03c68e877874c9059edf5"
            "3d03c1330b2655efdad6e4aa259118b6ea88698ea038efb9d52ce";
    CHECK(oxenc::to_hex(hash5) == expected_hash5);
    CHECK(oxenc::to_hex(hash6) == expected_hash6);
}
