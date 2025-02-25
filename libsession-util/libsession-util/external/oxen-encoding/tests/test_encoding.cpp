#include <iterator>

#include "common.h"

using namespace std::literals;
using namespace oxenc::literals;

const std::string pk =
        "\xf1\x6b\xa5\x59\x10\x39\xf0\x89\xb4\x2a\x83\x41\x75\x09\x30\x94\x07\x4d\x0d\x93\x7a\x79"
        "\xe5\x3e\x5c\xe7\x30\xf9\x46\xe1\x4b\x88";
const std::string pk_hex = "f16ba5591039f089b42a834175093094074d0d937a79e53e5ce730f946e14b88";
const std::string pk_b32z = "6fi4kseo88aeupbkopyzknjo1odw4dcuxjh6kx1hhhax1tzbjqry";
const std::string pk_b64 = "8WulWRA58Im0KoNBdQkwlAdNDZN6eeU+XOcw+UbhS4g=";

TEST_CASE("hex encoding/decoding", "[encoding][decoding][hex]") {
    REQUIRE(oxenc::to_hex("\xff\x42\x12\x34") == "ff421234"s);
    std::vector<uint8_t> chars{{1, 10, 100, 254}};
    std::array<uint8_t, 8> out;
    std::array<uint8_t, 8> expected{{'0', '1', '0', 'a', '6', '4', 'f', 'e'}};
    oxenc::to_hex(chars.begin(), chars.end(), out.begin());
    REQUIRE(out == expected);

    REQUIRE(oxenc::to_hex(chars.begin(), chars.end()) == "010a64fe");

    REQUIRE(oxenc::from_hex("12345678ffEDbca9") == "\x12\x34\x56\x78\xff\xed\xbc\xa9"s);
    REQUIRE("12345678ffEDbca9"_hex == "\x12\x34\x56\x78\xff\xed\xbc\xa9"s);
    REQUIRE_THROWS_AS("abc"_hex, std::invalid_argument);
    REQUIRE_THROWS_AS("abcg"_hex, std::invalid_argument);

    REQUIRE(oxenc::is_hex("1234567890abcdefABCDEF1234567890abcdefABCDEF"));
    REQUIRE_FALSE(oxenc::is_hex("1234567890abcdefABCDEF1234567890aGcdefABCDEF"));
    //                                                              ^
    REQUIRE_FALSE(oxenc::is_hex("1234567890abcdefABCDEF1234567890agcdefABCDEF"));
    //                                                              ^
    REQUIRE_FALSE(oxenc::is_hex("\x11\xff"));
    constexpr auto odd_hex = "1234567890abcdefABCDEF1234567890abcdefABCDE"sv;
    REQUIRE_FALSE(oxenc::is_hex(odd_hex));
    REQUIRE_FALSE(oxenc::is_hex("0"));

    REQUIRE(std::all_of(odd_hex.begin(), odd_hex.end(), oxenc::is_hex_digit<char>));

    REQUIRE(oxenc::from_hex(pk_hex) == pk);
    REQUIRE(oxenc::to_hex(pk) == pk_hex);

    REQUIRE(oxenc::from_hex(pk_hex.begin(), pk_hex.end()) == pk);

    std::vector<std::byte> bytes{
            {std::byte{0xff}, std::byte{0x42}, std::byte{0x12}, std::byte{0x34}}};
    std::basic_string_view<std::byte> b{bytes.data(), bytes.size()};
    REQUIRE(oxenc::to_hex(b) == "ff421234"s);

    // In-place decoding and truncation via to_hex's returned iterator:
    std::string some_hex = "48656c6c6f";
    some_hex.erase(
            oxenc::from_hex(some_hex.begin(), some_hex.end(), some_hex.begin()), some_hex.end());
    REQUIRE(some_hex == "Hello");

    // Test the returned iterator from encoding
    std::string hellohex;
    *oxenc::to_hex(some_hex.begin(), some_hex.end(), std::back_inserter(hellohex))++ = '!';
    REQUIRE(hellohex == "48656c6c6f!");

    bytes.resize(8);
    bytes[0] = std::byte{'f'};
    bytes[1] = std::byte{'f'};
    bytes[2] = std::byte{'4'};
    bytes[3] = std::byte{'2'};
    bytes[4] = std::byte{'1'};
    bytes[5] = std::byte{'2'};
    bytes[6] = std::byte{'3'};
    bytes[7] = std::byte{'4'};
    std::basic_string_view<std::byte> hex_bytes{bytes.data(), bytes.size()};
    REQUIRE(oxenc::is_hex(hex_bytes));
    REQUIRE(oxenc::from_hex(hex_bytes) == "\xff\x42\x12\x34");

    REQUIRE(oxenc::to_hex_size(1) == 2);
    REQUIRE(oxenc::to_hex_size(2) == 4);
    REQUIRE(oxenc::to_hex_size(3) == 6);
    REQUIRE(oxenc::to_hex_size(4) == 8);
    REQUIRE(oxenc::to_hex_size(100) == 200);
    REQUIRE(oxenc::from_hex_size(2) == 1);
    REQUIRE(oxenc::from_hex_size(4) == 2);
    REQUIRE(oxenc::from_hex_size(6) == 3);
    REQUIRE(oxenc::from_hex_size(98) == 49);
}

TEST_CASE("base32z encoding/decoding", "[encoding][decoding][base32z]") {
    REQUIRE(oxenc::to_base32z("\0\0\0\0\0"s) == "yyyyyyyy");
    REQUIRE(oxenc::to_base32z(
                    "\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef"sv) ==
            "yrtwk3hjixg66yjdeiuauk6p7hy1gtm8tgih55abrpnsxnpm3zzo");

    REQUIRE(oxenc::from_base32z("yrtwk3hjixg66yjdeiuauk6p7hy1gtm8tgih55abrpnsxnpm3zzo") ==
            "\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef"sv);

    REQUIRE(oxenc::from_base32z("YRTWK3HJIXG66YJDEIUAUK6P7HY1GTM8TGIH55ABRPNSXNPM3ZZO") ==
            "\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef"sv);

    REQUIRE("yrtwk3hjixg66yjdeiuauk6p7hy1gtm8tgih55abrpnsxnpm3zzo"_b32z ==
            "\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef"sv);
    REQUIRE("YRTWK3HJIXG66YJDEIUAUK6P7HY1GTM8TGIH55ABRPNSXNPM3ZZO"_b32z ==
            "\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef\x01\x23\x45\x67\x89\xab\xcd\xef"sv);
    REQUIRE_THROWS_AS("abcl"_b32z, std::invalid_argument);

    auto five_nulls = oxenc::from_base32z("yyyyyyyy");
    REQUIRE(five_nulls.size() == 5);
    REQUIRE(five_nulls == "\0\0\0\0\0"s);

    // 00000 00001 00010 00011 00100 00101 00110 00111
    // ==
    // 00000000 01000100 00110010 00010100 11000111
    REQUIRE(oxenc::from_base32z("ybndrfg8") == "\x00\x44\x32\x14\xc7"s);

    // Special case 1: 7 base32z digits with 3 trailing 0 bits -> 4 bytes (the trailing 0s are
    // dropped) 00000 00001 00010 00011 00100 00101 11000
    // ==
    // 00000000 01000100 00110010 00010111
    REQUIRE(oxenc::from_base32z("ybndrfa") == "\x00\x44\x32\x17"s);

    // Round-trip it:
    REQUIRE(oxenc::from_base32z(oxenc::to_base32z("\x00\x44\x32\x17"sv)) == "\x00\x44\x32\x17"sv);
    REQUIRE(oxenc::to_base32z(oxenc::from_base32z("ybndrfa")) == "ybndrfa");

    // Special case 2: 7 base32z digits with 3 trailing bits 010; we just ignore the trailing stuff,
    // as if it was specified as 0.  (The last digit here is 11010 instead of 11000).
    REQUIRE(oxenc::from_base32z("ybndrf4") == "\x00\x44\x32\x17"s);
    // This one won't round-trip to the same value since it has ignored garbage bytes at the end
    REQUIRE(oxenc::to_base32z(oxenc::from_base32z("ybndrf4"s)) == "ybndrfa");

    REQUIRE(oxenc::to_base32z(pk) == pk_b32z);
    REQUIRE(oxenc::to_base32z(pk.begin(), pk.end()) == pk_b32z);
    REQUIRE(oxenc::from_base32z(pk_b32z) == pk);
    REQUIRE(oxenc::from_base32z(pk_b32z.begin(), pk_b32z.end()) == pk);

    std::string pk_b32z_again, pk_again;
    oxenc::to_base32z(pk.begin(), pk.end(), std::back_inserter(pk_b32z_again));
    oxenc::from_base32z(pk_b32z.begin(), pk_b32z.end(), std::back_inserter(pk_again));
    REQUIRE(pk_b32z_again == pk_b32z);
    REQUIRE(pk_again == pk);

    // In-place decoding and truncation via returned iterator:
    std::string some_b32z = "jb1sa5dx";
    some_b32z.erase(
            oxenc::from_base32z(some_b32z.begin(), some_b32z.end(), some_b32z.begin()),
            some_b32z.end());
    REQUIRE(some_b32z == "Hello");

    // Test the returned iterator from encoding
    std::string hellob32z;
    *oxenc::to_base32z(some_b32z.begin(), some_b32z.end(), std::back_inserter(hellob32z))++ = '!';
    REQUIRE(hellob32z == "jb1sa5dx!");

    std::vector<std::byte> bytes{{std::byte{0}, std::byte{255}}};
    std::basic_string_view<std::byte> b{bytes.data(), bytes.size()};
    REQUIRE(oxenc::to_base32z(b) == "yd9o");

    bytes.resize(4);
    bytes[0] = std::byte{'y'};
    bytes[1] = std::byte{'d'};
    bytes[2] = std::byte{'9'};
    bytes[3] = std::byte{'o'};
    std::basic_string_view<std::byte> b32_bytes{bytes.data(), bytes.size()};
    REQUIRE(oxenc::is_base32z(b32_bytes));
    REQUIRE(oxenc::from_base32z(b32_bytes) == "\x00\xff"sv);

    REQUIRE(oxenc::is_base32z(""));
    REQUIRE_FALSE(oxenc::is_base32z("y"));
    REQUIRE(oxenc::is_base32z("yy"));
    REQUIRE_FALSE(oxenc::is_base32z("yyy"));
    REQUIRE(oxenc::is_base32z("yyyy"));
    REQUIRE(oxenc::is_base32z("yyyyy"));
    REQUIRE_FALSE(oxenc::is_base32z("yyyyyy"));
    REQUIRE(oxenc::is_base32z("yyyyyyy"));
    REQUIRE(oxenc::is_base32z("yyyyyyyy"));

    REQUIRE(oxenc::to_base32z_size(1) == 2);
    REQUIRE(oxenc::to_base32z_size(2) == 4);
    REQUIRE(oxenc::to_base32z_size(3) == 5);
    REQUIRE(oxenc::to_base32z_size(4) == 7);
    REQUIRE(oxenc::to_base32z_size(5) == 8);
    REQUIRE(oxenc::to_base32z_size(30) == 48);
    REQUIRE(oxenc::to_base32z_size(31) == 50);
    REQUIRE(oxenc::to_base32z_size(32) == 52);
    REQUIRE(oxenc::to_base32z_size(33) == 53);
    REQUIRE(oxenc::to_base32z_size(100) == 160);
    REQUIRE(oxenc::from_base32z_size(160) == 100);
    REQUIRE(oxenc::from_base32z_size(53) == 33);
    REQUIRE(oxenc::from_base32z_size(52) == 32);
    REQUIRE(oxenc::from_base32z_size(50) == 31);
    REQUIRE(oxenc::from_base32z_size(48) == 30);
    REQUIRE(oxenc::from_base32z_size(8) == 5);
    REQUIRE(oxenc::from_base32z_size(7) == 4);
    REQUIRE(oxenc::from_base32z_size(5) == 3);
    REQUIRE(oxenc::from_base32z_size(4) == 2);
    REQUIRE(oxenc::from_base32z_size(2) == 1);
}

TEST_CASE("base64 encoding/decoding", "[encoding][decoding][base64]") {
    // 00000000 00000000 00000000 -> 000000 000000 000000 000000
    REQUIRE(oxenc::to_base64("\0\0\0"s) == "AAAA");
    // 00000001 00000002 00000003 -> 000000 010000 000200 000003
    REQUIRE(oxenc::to_base64("\x01\x02\x03"s) == "AQID");
    REQUIRE(oxenc::to_base64("\0\0\0\0"s) == "AAAAAA==");
    // 00000000 00000000 00000000  11111111 ->
    // 000000 000000 000000 000000 111111 110000 (pad) (pad)
    REQUIRE(oxenc::to_base64("a") == "YQ==");
    REQUIRE(oxenc::to_base64("ab") == "YWI=");
    REQUIRE(oxenc::to_base64("abc") == "YWJj");
    REQUIRE(oxenc::to_base64("abcd") == "YWJjZA==");
    REQUIRE(oxenc::to_base64("abcde") == "YWJjZGU=");
    REQUIRE(oxenc::to_base64("abcdef") == "YWJjZGVm");

    REQUIRE(oxenc::to_base64_unpadded("a") == "YQ");
    REQUIRE(oxenc::to_base64_unpadded("ab") == "YWI");
    REQUIRE(oxenc::to_base64_unpadded("abc") == "YWJj");
    REQUIRE(oxenc::to_base64_unpadded("abcd") == "YWJjZA");
    REQUIRE(oxenc::to_base64_unpadded("abcde") == "YWJjZGU");
    REQUIRE(oxenc::to_base64_unpadded("abcdef") == "YWJjZGVm");

    REQUIRE(oxenc::to_base64("\0\0\0\xff"s) == "AAAA/w==");
    REQUIRE(oxenc::to_base64("\0\0\0\xff\xff"s) == "AAAA//8=");
    REQUIRE(oxenc::to_base64("\0\0\0\xff\xff\xff"s) == "AAAA////");
    REQUIRE(oxenc::to_base64("Man is distinguished, not only by his reason, but by this singular "
                             "passion from other "
                             "animals, which is a lust of the mind, that by a perseverance of "
                             "delight in the "
                             "continued and indefatigable generation of knowledge, exceeds the "
                             "short vehemence of "
                             "any carnal pleasure.") ==
            "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlz"
            "IHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx1c3Qgb2Yg"
            "dGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGlu"
            "dWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRo"
            "ZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=");

    REQUIRE(oxenc::from_base64("A+/A") == "\x03\xef\xc0");
    REQUIRE(oxenc::from_base64("YWJj") == "abc");
    REQUIRE(oxenc::from_base64("YWJjZA==") == "abcd");
    REQUIRE(oxenc::from_base64("YWJjZA") == "abcd");
    REQUIRE(oxenc::from_base64("YWJjZB") == "abcd");  // ignore superfluous bits
    REQUIRE(oxenc::from_base64("YWJjZB") == "abcd");  // ignore superfluous bits
    REQUIRE(oxenc::from_base64("YWJj+") == "abc");    // ignore superfluous bits
    REQUIRE(oxenc::from_base64("YWJjZGU=") == "abcde");
    REQUIRE(oxenc::from_base64("YWJjZGU") == "abcde");
    REQUIRE(oxenc::from_base64("YWJjZGVm") == "abcdef");

    REQUIRE(oxenc::is_base64("YWJjZGVm"));
    REQUIRE(oxenc::is_base64("YWJjZGU"));
    REQUIRE(oxenc::is_base64("YWJjZGU="));
    REQUIRE(oxenc::is_base64("YWJjZA=="));
    REQUIRE(oxenc::is_base64("YWJjZA"));
    REQUIRE(oxenc::is_base64("YWJjZB"));  // not really valid, but we explicitly accept it

    REQUIRE_FALSE(oxenc::is_base64(
            "YWJjZ="));  // invalid padding (padding can only be 4th or 3rd+4th of a 4-char block)
    REQUIRE_FALSE(oxenc::is_base64("YYYYA"));  // invalid: base64 can never be length 4n+1
    REQUIRE_FALSE(oxenc::is_base64("YWJj="));
    REQUIRE_FALSE(oxenc::is_base64("YWJj=A"));
    REQUIRE_FALSE(oxenc::is_base64("YWJjA==="));
    REQUIRE_FALSE(oxenc::is_base64("YWJ["));
    REQUIRE_FALSE(oxenc::is_base64("YWJ."));
    REQUIRE_FALSE(oxenc::is_base64("_YWJ"));

    REQUIRE(oxenc::from_base64("TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCB"
                               "ieSB0aGlz"
                               "IHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx"
                               "1c3Qgb2Yg"
                               "dGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGU"
                               "gY29udGlu"
                               "dWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGN"
                               "lZWRzIHRo"
                               "ZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=") ==
            "Man is distinguished, not only by his reason, but by this singular passion from other "
            "animals, which is a lust of the mind, that by a perseverance of delight in the "
            "continued and indefatigable generation of knowledge, exceeds the short vehemence of "
            "any carnal pleasure.");

    REQUIRE("SGVsbG8="_b64 == "Hello");
    REQUIRE("SGVsbG8"_b64 == "Hello");
    REQUIRE_THROWS_AS("SGVsbG8$"_b64, std::invalid_argument);

    REQUIRE(oxenc::to_base64(pk) == pk_b64);
    REQUIRE(oxenc::to_base64(pk.begin(), pk.end()) == pk_b64);
    REQUIRE(oxenc::from_base64(pk_b64) == pk);
    REQUIRE(oxenc::from_base64(pk_b64.begin(), pk_b64.end()) == pk);

    std::string pk_b64_again, pk_again;
    oxenc::to_base64(pk.begin(), pk.end(), std::back_inserter(pk_b64_again));
    oxenc::from_base64(pk_b64.begin(), pk_b64.end(), std::back_inserter(pk_again));
    REQUIRE(pk_b64_again == pk_b64);
    REQUIRE(pk_again == pk);

    // In-place decoding and truncation via returned iterator:
    std::string some_b64 = "SGVsbG8=";
    some_b64.erase(
            oxenc::from_base64(some_b64.begin(), some_b64.end(), some_b64.begin()), some_b64.end());
    REQUIRE(some_b64 == "Hello");

    // Test the returned iterator from encoding
    std::string hellob64;
    *oxenc::to_base64(some_b64.begin(), some_b64.end(), std::back_inserter(hellob64))++ = '!';
    REQUIRE(hellob64 == "SGVsbG8=!");

    std::vector<std::byte> bytes{{std::byte{0}, std::byte{255}}};
    std::basic_string_view<std::byte> b{bytes.data(), bytes.size()};
    REQUIRE(oxenc::to_base64(b) == "AP8=");

    bytes.resize(4);
    bytes[0] = std::byte{'/'};
    bytes[1] = std::byte{'w'};
    bytes[2] = std::byte{'A'};
    bytes[3] = std::byte{'='};
    std::basic_string_view<std::byte> b64_bytes{bytes.data(), bytes.size()};
    REQUIRE(oxenc::is_base64(b64_bytes));
    REQUIRE(oxenc::from_base64(b64_bytes) == "\xff\x00"sv);

    REQUIRE(oxenc::to_base64_size(1) == 4);
    REQUIRE(oxenc::to_base64_size(2) == 4);
    REQUIRE(oxenc::to_base64_size(3) == 4);
    REQUIRE(oxenc::to_base64_size(4) == 8);
    REQUIRE(oxenc::to_base64_size(5) == 8);
    REQUIRE(oxenc::to_base64_size(6) == 8);
    REQUIRE(oxenc::to_base64_size(30) == 40);
    REQUIRE(oxenc::to_base64_size(31) == 44);
    REQUIRE(oxenc::to_base64_size(32) == 44);
    REQUIRE(oxenc::to_base64_size(33) == 44);
    REQUIRE(oxenc::to_base64_size(100) == 136);
    REQUIRE(oxenc::from_base64_size(136) ==
            102);  // Not symmetric because we don't know the last two are padding
    REQUIRE(oxenc::from_base64_size(134) == 100);  // Unpadded
    REQUIRE(oxenc::from_base64_size(44) == 33);
    REQUIRE(oxenc::from_base64_size(43) == 32);
    REQUIRE(oxenc::from_base64_size(42) == 31);
    REQUIRE(oxenc::from_base64_size(40) == 30);
    REQUIRE(oxenc::from_base64_size(8) == 6);
    REQUIRE(oxenc::from_base64_size(7) == 5);
    REQUIRE(oxenc::from_base64_size(6) == 4);
    REQUIRE(oxenc::from_base64_size(4) == 3);
    REQUIRE(oxenc::from_base64_size(3) == 2);
    REQUIRE(oxenc::from_base64_size(2) == 1);
}

TEST_CASE("transcoding", "[decoding][encoding][base32z][hex][base64]") {
    // Decoders:
    oxenc::base64_decoder in64{pk_b64.begin(), pk_b64.end()};
    oxenc::base32z_decoder in32z{pk_b32z.begin(), pk_b32z.end()};
    oxenc::hex_decoder in16{pk_hex.begin(), pk_hex.end()};

    // Transcoders:
    oxenc::base32z_encoder b64_to_b32z{in64, in64.end()};
    oxenc::base32z_encoder hex_to_b32z{in16, in16.end()};
    oxenc::hex_encoder b64_to_hex{in64, in64.end()};
    oxenc::hex_encoder b32z_to_hex{in32z, in32z.end()};
    oxenc::base64_encoder hex_to_b64{in16, in16.end()};
    oxenc::base64_encoder b32z_to_b64{in32z, in32z.end()};
    // These ones are stupid, but should work anyway:
    oxenc::base64_encoder b64_to_b64{in64, in64.end()};
    oxenc::base32z_encoder b32z_to_b32z{in32z, in32z.end()};
    oxenc::hex_encoder hex_to_hex{in16, in16.end()};

    // Decoding to bytes:
    std::string x;
    auto xx = std::back_inserter(x);
    std::copy(in64, in64.end(), xx);
    REQUIRE(x == pk);
    x.clear();
    std::copy(in32z, in32z.end(), xx);
    REQUIRE(x == pk);
    x.clear();
    std::copy(in16, in16.end(), xx);
    REQUIRE(x == pk);

    // Transcoding
    x.clear();
    std::copy(b64_to_hex, b64_to_hex.end(), xx);
    CHECK(x == pk_hex);

    x.clear();
    std::copy(b64_to_b32z, b64_to_b32z.end(), xx);
    CHECK(x == pk_b32z);

    x.clear();
    std::copy(b64_to_b64, b64_to_b64.end(), xx);
    CHECK(x == pk_b64);

    x.clear();
    std::copy(b32z_to_hex, b32z_to_hex.end(), xx);
    CHECK(x == pk_hex);

    x.clear();
    std::copy(b32z_to_b32z, b32z_to_b32z.end(), xx);
    CHECK(x == pk_b32z);

    x.clear();
    std::copy(b32z_to_b64, b32z_to_b64.end(), xx);
    CHECK(x == pk_b64);

    x.clear();
    std::copy(hex_to_hex, hex_to_hex.end(), xx);
    CHECK(x == pk_hex);

    x.clear();
    std::copy(hex_to_b32z, hex_to_b32z.end(), xx);
    CHECK(x == pk_b32z);

    x.clear();
    std::copy(hex_to_b64, hex_to_b64.end(), xx);
    CHECK(x == pk_b64);

    // Make a big chain of conversions
    oxenc::base32z_encoder it1{in64, in64.end()};
    oxenc::base32z_decoder it2{it1, it1.end()};
    oxenc::base64_encoder it3{it2, it2.end()};
    oxenc::base64_decoder it4{it3, it3.end()};
    oxenc::hex_encoder it5{it4, it4.end()};
    x.clear();
    std::copy(it5, it5.end(), xx);
    CHECK(x == pk_hex);

    // No-padding b64 encoding:
    oxenc::base64_encoder b64_nopad{pk.begin(), pk.end(), false};
    x.clear();
    std::copy(b64_nopad, b64_nopad.end(), xx);
    CHECK(x == pk_b64.substr(0, pk_b64.size() - 1));
}

TEST_CASE("std::byte decoding", "[decoding][hex][base32z][base64]") {
    // Decoding to std::byte is a little trickier because you can't assign to a byte without an
    // explicit cast, which means we have to properly detect that output is going to a std::byte
    // output.

    // hex
    auto b_in = "ff42"s;
    std::vector<std::byte> b_out;
    oxenc::from_hex(b_in.begin(), b_in.end(), std::back_inserter(b_out));
    REQUIRE(b_out == std::vector{std::byte{0xff}, std::byte{0x42}});
    b_out.emplace_back();
    oxenc::from_hex(b_in.begin(), b_in.end(), b_out.begin() + 1);
    REQUIRE(b_out == std::vector{std::byte{0xff}, std::byte{0xff}, std::byte{0x42}});
    oxenc::from_hex(b_in.begin(), b_in.end(), b_out.data());
    REQUIRE(b_out == std::vector{std::byte{0xff}, std::byte{0x42}, std::byte{0x42}});

    // base32z
    b_in = "yojky"s;
    b_out.clear();
    oxenc::from_base32z(b_in.begin(), b_in.end(), std::back_inserter(b_out));
    REQUIRE(b_out == std::vector{std::byte{0x04}, std::byte{0x12}, std::byte{0xa0}});
    b_out.emplace_back();
    oxenc::from_base32z(b_in.begin(), b_in.end(), b_out.begin() + 1);
    REQUIRE(b_out ==
            std::vector{std::byte{0x04}, std::byte{0x04}, std::byte{0x12}, std::byte{0xa0}});
    oxenc::from_base32z(b_in.begin(), b_in.end(), b_out.data());
    REQUIRE(b_out ==
            std::vector{std::byte{0x04}, std::byte{0x12}, std::byte{0xa0}, std::byte{0xa0}});

    // base64
    b_in = "yojk"s;
    b_out.clear();
    oxenc::from_base64(b_in.begin(), b_in.end(), std::back_inserter(b_out));
    REQUIRE(b_out == std::vector{std::byte{0xca}, std::byte{0x88}, std::byte{0xe4}});
    b_out.emplace_back();
    oxenc::from_base64(b_in.begin(), b_in.end(), b_out.begin() + 1);
    REQUIRE(b_out ==
            std::vector{std::byte{0xca}, std::byte{0xca}, std::byte{0x88}, std::byte{0xe4}});
    oxenc::from_base64(b_in.begin(), b_in.end(), b_out.data());
    REQUIRE(b_out ==
            std::vector{std::byte{0xca}, std::byte{0x88}, std::byte{0xe4}, std::byte{0xe4}});
}
