
#include <oxenc/hex.h>
#include <sodium.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <iterator>

#include "session/blinding.hpp"
#include "session/util.hpp"
#include "utils.hpp"

using namespace std::literals;
using namespace session;

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

const std::string session_id1 = "05" + oxenc::to_hex(xpub1.begin(), xpub1.end());
const std::string session_id2 = "05" + oxenc::to_hex(xpub2.begin(), xpub2.end());

TEST_CASE("Communities 25xxx-blinded pubkey derivation", "[blinding25][pubkey]") {
    REQUIRE(sodium_init() >= 0);

    CHECK(blind25_id(
                  session_id1,
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789") ==
          "253b991dcbba44cfdb45d5b38880d95cff723309e3ece6fd01415ad5fa1dccc7ac");
    CHECK(blind25_id(
                  session_id1,
                  "00cdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789") ==
          "2598589c7885b56cbeae6ab7b4224f202815520a54995872cb1833b44db6401c8d");
    CHECK(blind25_id(
                  session_id2,
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789") ==
          "25a69cc6884530bf8498d22892e563716c4742f2845a7eb608de2aecbe7b6b5996");

    ustring session_id1_raw;
    oxenc::from_hex(session_id1.begin(), session_id1.end(), std::back_inserter(session_id1_raw));
    CHECK(oxenc::to_hex(blind25_id(
                  session_id1_raw,
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes)) ==
          "253b991dcbba44cfdb45d5b38880d95cff723309e3ece6fd01415ad5fa1dccc7ac");
    CHECK(oxenc::to_hex(blind25_id(
                  session_id1_raw.substr(1),
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes)) ==
          "253b991dcbba44cfdb45d5b38880d95cff723309e3ece6fd01415ad5fa1dccc7ac");
}

TEST_CASE("Communities 25xxx-blinded signing", "[blinding25][sign]") {

    std::array server_pks = {
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "00cdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "999def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "888def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "777def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv};
    auto b25_1 = blind25_id(session_id1, server_pks[0]);
    auto b25_2 = blind25_id(session_id1, server_pks[1]);
    auto b25_3 = blind25_id(session_id2, server_pks[2]);
    auto b25_4 = blind25_id(session_id2, server_pks[3]);
    auto b25_5 = blind25_id(session_id2, server_pks[4]);
    auto b25_6 = blind25_id(session_id1, server_pks[5]);

    auto sig1 = blind25_sign(to_usv(seed1), server_pks[0], to_unsigned_sv("hello"));
    CHECK(oxenc::to_hex(sig1) ==
          "e6c57de4ac0cd278abbeef815bd88b163a037085deae789ecaaf4805884c4c3d3db25f3afa856241366cb341"
          "a3a4c9bbaa2cda81d028079c956fab16a7fe6206");
    CHECK(0 == crypto_sign_verify_detached(
                       sig1.data(),
                       to_unsigned("hello"),
                       5,
                       to_unsigned(oxenc::from_hex(b25_1).data()) + 1));

    auto sig2 = blind25_sign(to_usv(seed1), server_pks[1], to_unsigned_sv("world"));
    CHECK(oxenc::to_hex(sig2) ==
          "4460b606e9f55a7cba0bbe24207fe2859c3422783373788b6b070b2fa62ceba4f2a50749a6cee68e095747a3"
          "69927f9f4afa86edaf055cad68110e35e8b06607");
    CHECK(0 == crypto_sign_verify_detached(
                       sig2.data(),
                       to_unsigned("world"),
                       5,
                       to_unsigned(oxenc::from_hex(b25_2).data()) + 1));

    auto sig3 = blind25_sign(to_usv(seed2), server_pks[2], to_unsigned_sv("this"));
    CHECK(oxenc::to_hex(sig3) ==
          "57bb2f80c88ce2f677902ee58e02cbd83e4e1ec9e06e1c72a34b4ab76d0f5219cfd141ac5ce7016c73c8382d"
          "b99df9f317f2bc0af6ca68edac2a9a7670938902");
    CHECK(0 == crypto_sign_verify_detached(
                       sig3.data(),
                       to_unsigned("this"),
                       4,
                       to_unsigned(oxenc::from_hex(b25_3).data()) + 1));

    auto sig4 = blind25_sign(to_usv(seed2), server_pks[3], to_unsigned_sv("is"));
    CHECK(oxenc::to_hex(sig4) ==
          "ecce032b27b09d2d3d6df4ebab8cae86656c64fd1e3e70d6f020cd7e1a8058c57e3df7b6b01e90ccd592ac4a"
          "845dde7a2fdceb1a328a6690686851583133ea0c");
    CHECK(0 == crypto_sign_verify_detached(
                       sig4.data(),
                       to_unsigned("is"),
                       2,
                       to_unsigned(oxenc::from_hex(b25_4).data()) + 1));

    auto sig5 = blind25_sign(to_usv(seed2), server_pks[4], to_unsigned_sv(""));
    CHECK(oxenc::to_hex(sig5) ==
          "bf2fb9a511adbf5827e2e3bcf09f0a1cff80f85556fb76d8001aa8483b5f22e14539b170eaa0dbfa1489d1b8"
          "618ce8b48d7512cb5602c7eb8a05ce330a68350b");
    CHECK(0 ==
          crypto_sign_verify_detached(
                  sig5.data(), to_unsigned(""), 0, to_unsigned(oxenc::from_hex(b25_5).data()) + 1));

    auto sig6 = blind25_sign(to_usv(seed1), server_pks[5], to_unsigned_sv("omg!"));
    CHECK(oxenc::to_hex(sig6) ==
          "322e280fbc3547c6b6512dbea4d60563d32acaa2df10d665c40a336c99fc3b8e4b13a7109dfdeadab2ab58b2"
          "cb314eb0510b947f43e5dfb6e0ce5bf1499d240f");
    CHECK(0 == crypto_sign_verify_detached(
                       sig6.data(),
                       to_unsigned("omg!"),
                       4,
                       to_unsigned(oxenc::from_hex(b25_6).data()) + 1));

    // Test that it works when given just the seed instead of the whole sk:
    auto sig6b = blind25_sign(to_usv(seed1).substr(0, 32), server_pks[5], to_unsigned_sv("omg!"));
    CHECK(oxenc::to_hex(sig6b) ==
          "322e280fbc3547c6b6512dbea4d60563d32acaa2df10d665c40a336c99fc3b8e4b13a7109dfdeadab2ab58b2"
          "cb314eb0510b947f43e5dfb6e0ce5bf1499d240f");
    CHECK(0 == crypto_sign_verify_detached(
                       sig6b.data(),
                       to_unsigned("omg!"),
                       4,
                       to_unsigned(oxenc::from_hex(b25_6).data()) + 1));
}

TEST_CASE("Communities 15xxx-blinded pubkey derivation", "[blinding15][pubkey]") {
    REQUIRE(sodium_init() >= 0);

    ustring session_id1_raw, session_id2_raw;
    oxenc::from_hex(session_id1.begin(), session_id1.end(), std::back_inserter(session_id1_raw));
    oxenc::from_hex(session_id2.begin(), session_id2.end(), std::back_inserter(session_id2_raw));
    CHECK(oxenc::to_hex(blind15_id(
                  session_id1_raw,
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes)) ==
          "15b74ed205f1f931e1bb1291183778a9456b835937d923b0f2e248aa3a44c07844");
    CHECK(oxenc::to_hex(blind15_id(
                  session_id2_raw,
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes)) ==
          "1561e070286ff7a71f167e92b18c709882b148d8238c8872caf414b301ba0564fd");
    CHECK(oxenc::to_hex(blind15_id(
                  session_id1_raw.substr(1),
                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"_hexbytes)) ==
          "15b74ed205f1f931e1bb1291183778a9456b835937d923b0f2e248aa3a44c07844");
}

TEST_CASE("Communities 15xxx-blinded signing", "[blinding15][sign]") {
    REQUIRE(sodium_init() >= 0);

    std::array server_pks = {
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "00cdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "999def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "888def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "777def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv};
    auto b15_1 = blind15_id(session_id1, server_pks[0])[0];
    auto b15_2 = blind15_id(session_id1, server_pks[1])[0];
    // session_id2 has a negative pubkey, so these next three need the negative [1] instead:
    auto b15_3 = blind15_id(session_id2, server_pks[2])[1];
    auto b15_4 = blind15_id(session_id2, server_pks[3])[1];
    auto b15_5 = blind15_id(session_id2, server_pks[4])[1];
    auto b15_6 = blind15_id(session_id1, server_pks[5])[0];

    auto sig1 = blind15_sign(to_usv(seed1), server_pks[0], to_unsigned_sv("hello"));
    CHECK(oxenc::to_hex(sig1) ==
          "1a5ade20b43af0e16b3e591d6f86303938d7557c0ac54469dd4f5aea759f82d22cafa42587251756e133acdd"
          "dd8cbec2f707a9ce09a49f2193f46a91502c5006");
    CHECK(0 == crypto_sign_verify_detached(
                       sig1.data(),
                       to_unsigned("hello"),
                       5,
                       to_unsigned(oxenc::from_hex(b15_1).data()) + 1));

    auto sig2 = blind15_sign(to_usv(seed1), server_pks[1], to_unsigned_sv("world"));
    CHECK(oxenc::to_hex(sig2) ==
          "d357f74c5ec5536840aec575051f71fdb22d70f35ef31db1715f5f694842de3b39aa647c84aa8e28ec56eb76"
          "2d237c9e030639c83f429826d419ac719cd4df03");
    CHECK(0 == crypto_sign_verify_detached(
                       sig2.data(),
                       to_unsigned("world"),
                       5,
                       to_unsigned(oxenc::from_hex(b15_2).data()) + 1));

    auto sig3 = blind15_sign(to_usv(seed2), server_pks[2], to_unsigned_sv("this"));
    CHECK(oxenc::to_hex(sig3) ==
          "dacf91dfb411e99cd8ef4cb07b195b49289cf1a724fef122c73462818560bc29832a98d870ec4feb79dedca5"
          "b59aba6a466d3ce8f3e35adf25a1813f6989fd0a");
    CHECK(0 == crypto_sign_verify_detached(
                       sig3.data(),
                       to_unsigned("this"),
                       4,
                       to_unsigned(oxenc::from_hex(b15_3).data()) + 1));

    auto sig4 = blind15_sign(to_usv(seed2), server_pks[3], to_unsigned_sv("is"));
    CHECK(oxenc::to_hex(sig4) ==
          "8339ea9887d3e44131e33403df160539cdc7a0a8107772172c311e95773660a0d39ed0a6c2b2c794dde1fdc6"
          "40943e403497aa02c4d1a21a7d9030742beabb05");
    CHECK(0 == crypto_sign_verify_detached(
                       sig4.data(),
                       to_unsigned("is"),
                       2,
                       to_unsigned(oxenc::from_hex(b15_4).data()) + 1));

    auto sig5 = blind15_sign(to_usv(seed2), server_pks[4], to_unsigned_sv(""));
    CHECK(oxenc::to_hex(sig5) ==
          "8b0d6447decff3a21ec1809141580139c4a51e24977b0605fe7984439993f5377ebc9681e4962593108d03cc"
          "8b6873c5c5ba8c30287188137d2dee9ab10afd0f");
    CHECK(0 ==
          crypto_sign_verify_detached(
                  sig5.data(), to_unsigned(""), 0, to_unsigned(oxenc::from_hex(b15_5).data()) + 1));

    auto sig6 = blind15_sign(to_usv(seed1), server_pks[5], to_unsigned_sv("omg!"));
    CHECK(oxenc::to_hex(sig6) ==
          "946725055399376ecebb605c79f845fbf689a47f98507c2a1f239516fd9c9104e19fe533631c27ba4e744457"
          "4f0e4f0f0d422b7256ed63681a3ab2fe7e040601");
    CHECK(0 == crypto_sign_verify_detached(
                       sig6.data(),
                       to_unsigned("omg!"),
                       4,
                       to_unsigned(oxenc::from_hex(b15_6).data()) + 1));

    // Test that it works when given just the seed instead of the whole sk:
    auto sig6b = blind15_sign(to_usv(seed1).substr(0, 32), server_pks[5], to_unsigned_sv("omg!"));
    CHECK(oxenc::to_hex(sig6b) ==
          "946725055399376ecebb605c79f845fbf689a47f98507c2a1f239516fd9c9104e19fe533631c27ba4e744457"
          "4f0e4f0f0d422b7256ed63681a3ab2fe7e040601");
    CHECK(0 == crypto_sign_verify_detached(
                       sig6b.data(),
                       to_unsigned("omg!"),
                       4,
                       to_unsigned(oxenc::from_hex(b15_6).data()) + 1));
}

TEST_CASE("Version 07xxx-blinded pubkey derivation", "[blinding07][key_pair]") {
    REQUIRE(sodium_init() >= 0);

    auto [pubkey, seckey] = blind_version_key_pair(to_usv(seed1));
    CHECK(oxenc::to_hex(pubkey.begin(), pubkey.end()) ==
          "88e8adb27e7b8ce776fcc25bc1501fb2888fcac0308e52fb10044f789ae1a8fa");

    CHECK(oxenc::to_hex(seckey.begin() + 32, seckey.end()) ==
          oxenc::to_hex(pubkey.begin(), pubkey.end()));

    // Hash ourselves just to make sure we get what we expect for the seed part of the secret key:
    cleared_uc32 expect_seed;
    static const auto hash_key = to_unsigned_sv("VersionCheckKey_sig"sv);
    crypto_generichash_blake2b(
            expect_seed.data(), 32, seed1.data(), 32, hash_key.data(), hash_key.size());

    CHECK(oxenc::to_hex(seckey.begin(), seckey.begin() + 32) ==
          oxenc::to_hex(expect_seed.begin(), expect_seed.end()));

    CHECK(oxenc::to_hex(seckey.begin(), seckey.end()) ==
          "91faddc2c36da4f7bcf24fd977d9ca5346ae7489cfd43c58cad9eaaa6ed60f69"
          "88e8adb27e7b8ce776fcc25bc1501fb2888fcac0308e52fb10044f789ae1a8fa");
}

TEST_CASE("Version 07xxx-blinded signing", "[blinding07][sign]") {
    REQUIRE(sodium_init() >= 0);

    auto signature = blind_version_sign(to_usv(seed1), Platform::desktop, 1234567890);
    CHECK(oxenc::to_hex(signature.begin(), signature.end()) ==
          "143c2c9828f7680ee81e6247bc7aa4777c4991add87cd724149b00452bed4e92"
          "0fa57daf4627c68f43fcbddb2d465d5ea11def523f3befb2bbee39c769676305");

    auto [pk, sk] = blind_version_key_pair(to_usv(seed1));
    auto method = "GET"sv;
    auto path = "/path/to/somewhere"sv;
    auto body = to_unsigned_sv("some body (once told me)");

    uint64_t timestamp = 1234567890;
    ustring full_message;
    full_message += to_unsigned_sv(std::to_string(timestamp));
    full_message += to_unsigned_sv(method);
    full_message += to_unsigned_sv(path);
    auto req_sig_no_body =
            blind_version_sign_request(to_usv(seed1), timestamp, method, path, std::nullopt);
    CHECK(crypto_sign_verify_detached(
                  req_sig_no_body.data(), full_message.data(), full_message.size(), pk.data()) ==
          0);

    full_message += body;
    auto req_sig = blind_version_sign_request(to_usv(seed1), timestamp, method, path, body);
    CHECK(crypto_sign_verify_detached(
                  req_sig.data(), full_message.data(), full_message.size(), pk.data()) == 0);
}

TEST_CASE("Communities session id blinded id matching", "[blinding][matching]") {
    std::array server_pks = {
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "00cdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "999def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "888def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv,
            "777def0123456789abcdef0123456789abcdef0123456789abcdef0123456789"sv};
    auto b15_1 = blind15_id(session_id1, server_pks[0])[0];
    auto b15_2 = blind15_id(session_id1, server_pks[1])[0];
    auto b15_3 = blind15_id(session_id2, server_pks[2])[1];
    auto b15_4 = blind15_id(session_id2, server_pks[3])[1];
    auto b15_5 = blind15_id(session_id2, server_pks[4])[1];
    auto b15_6 = blind15_id(session_id1, server_pks[5])[0];
    auto b25_1 = blind25_id(session_id1, server_pks[0]);
    auto b25_2 = blind25_id(session_id1, server_pks[1]);
    auto b25_3 = blind25_id(session_id2, server_pks[2]);
    auto b25_4 = blind25_id(session_id2, server_pks[3]);
    auto b25_5 = blind25_id(session_id2, server_pks[4]);
    auto b25_6 = blind25_id(session_id1, server_pks[5]);

    CHECK(session_id_matches_blinded_id(session_id1, b15_1, server_pks[0]));
    CHECK(session_id_matches_blinded_id(session_id1, b15_2, server_pks[1]));
    CHECK(session_id_matches_blinded_id(session_id2, b15_3, server_pks[2]));
    CHECK(session_id_matches_blinded_id(session_id2, b15_4, server_pks[3]));
    CHECK(session_id_matches_blinded_id(session_id2, b15_5, server_pks[4]));
    CHECK(session_id_matches_blinded_id(session_id1, b15_6, server_pks[5]));
    CHECK(session_id_matches_blinded_id(session_id1, b25_1, server_pks[0]));
    CHECK(session_id_matches_blinded_id(session_id1, b25_2, server_pks[1]));
    CHECK(session_id_matches_blinded_id(session_id2, b25_3, server_pks[2]));
    CHECK(session_id_matches_blinded_id(session_id2, b25_4, server_pks[3]));
    CHECK(session_id_matches_blinded_id(session_id2, b25_5, server_pks[4]));
    CHECK(session_id_matches_blinded_id(session_id1, b25_6, server_pks[5]));

    auto invalid_session_id = "9" + session_id1.substr(1, 65);
    auto invalid_blinded_id = "9" + b15_1.substr(1, 65);
    auto invalid_server_pk = server_pks[0].substr(0, 60);
    CHECK_THROWS(session_id_matches_blinded_id(invalid_session_id, b15_1, server_pks[0]));
    CHECK_THROWS(session_id_matches_blinded_id(session_id1, invalid_blinded_id, server_pks[0]));
    CHECK_THROWS(session_id_matches_blinded_id(session_id1, invalid_blinded_id, invalid_server_pk));
}
