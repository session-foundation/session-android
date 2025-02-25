#include "common.h"
#include "oxenc/endian.h"

using namespace oxenc;

TEST_CASE("endian swapping", "[endian]") {
    uint8_t u8 = 0x12;
    uint16_t u16 = 0x1234;
    uint32_t u32 = 0x12345678;
    uint64_t u64 = 0x0123456789abcdef;

    byteswap_inplace(u8);
    CHECK(u8 == 0x12);
    byteswap_inplace(u16);
    CHECK(u16 == 0x3412);
    byteswap_inplace(u32);
    CHECK(u32 == 0x78563412);
    byteswap_inplace(u64);
    CHECK(u64 == 0xefcdab8967452301);
}

TEST_CASE("native to little", "[endian][little]") {
    uint8_t u8 = 0x01;
    uint16_t u16 = 0x0123;
    uint32_t u32 = 0x01234567;
    uint64_t u64 = 0x0123456789abcdef;

    constexpr uint8_t u8_little = 0x01;
    constexpr uint16_t u16_little = little_endian ? 0x0123 : 0x2301;
    constexpr uint32_t u32_little = little_endian ? 0x01234567 : 0x67452301;
    constexpr uint64_t u64_little = little_endian ? 0x0123456789abcdef : 0xefcdab8967452301;

    CHECK(host_to_little(u8) == u8_little);
    CHECK(host_to_little(u16) == u16_little);
    CHECK(host_to_little(u32) == u32_little);
    CHECK(host_to_little(u64) == u64_little);

    // The above should not have mutated:
    REQUIRE(u8 == 0x01);
    REQUIRE(u16 == 0x0123);
    REQUIRE(u32 == 0x01234567);
    REQUIRE(u64 == 0x0123456789abcdef);

    host_to_little_inplace(u8);
    host_to_little_inplace(u16);
    host_to_little_inplace(u32);
    host_to_little_inplace(u64);
    CHECK(u8 == u8_little);
    CHECK(u16 == u16_little);
    CHECK(u32 == u32_little);
    CHECK(u64 == u64_little);

    CHECK(little_to_host(u8) == 0x01);
    CHECK(little_to_host(u16) == 0x0123);
    CHECK(little_to_host(u32) == 0x01234567);
    CHECK(little_to_host(u64) == 0x0123456789abcdef);

    little_to_host_inplace(u8);
    little_to_host_inplace(u16);
    little_to_host_inplace(u32);
    little_to_host_inplace(u64);
    CHECK(u8 == 0x01);
    CHECK(u16 == 0x0123);
    CHECK(u32 == 0x01234567);
    CHECK(u64 == 0x0123456789abcdef);

    const char* data = "\xef\xcd\xab\x89\x67\x45\x23\x01";
    CHECK(load_little_to_host<uint8_t>(data) == 0xef);
    CHECK(load_little_to_host<uint16_t>(data) == 0xcdef);
    CHECK(load_little_to_host<uint32_t>(data) == 0x89abcdef);
    CHECK(load_little_to_host<uint64_t>(data) == 0x0123456789abcdef);

    CHECK(load_host_to_little<uint8_t>(data) == 0xef);
    CHECK(load_host_to_little<uint16_t>(data) == 0xcdef);
    CHECK(load_host_to_little<uint32_t>(data) == 0x89abcdef);
    CHECK(load_host_to_little<uint64_t>(data) == 0x0123456789abcdef);

    char buf[8] = {0};
    std::string_view buffer{buf, 8};
    write_host_as_little(u8, buf);
    CHECK(buffer.substr(0, 1) == "\x01");
    write_host_as_little(u16, buf);
    CHECK(buffer.substr(0, 2) == "\x23\x01");
    write_host_as_little(u32, buf);
    CHECK(buffer.substr(0, 4) == "\x67\x45\x23\x01");
    write_host_as_little(u64, buf);
    CHECK(buffer.substr(0, 8) == "\xef\xcd\xab\x89\x67\x45\x23\x01");
}

TEST_CASE("native to big", "[endian][big]") {
    uint8_t u8 = 0x01;
    uint16_t u16 = 0x0123;
    uint32_t u32 = 0x01234567;
    uint64_t u64 = 0x0123456789abcdef;

#ifdef __BIG_ENDIAN__
    constexpr uint8_t u8_big = 0x01;
    constexpr uint16_t u16_big = 0x0123;
    constexpr uint32_t u32_big = 0x01234567;
    constexpr uint64_t u64_big = 0x0123456789abcdef;
#else
    constexpr uint8_t u8_big = 0x01;
    constexpr uint16_t u16_big = 0x2301;
    constexpr uint32_t u32_big = 0x67452301;
    constexpr uint64_t u64_big = 0xefcdab8967452301;
#endif

    CHECK(host_to_big(u8) == u8_big);
    CHECK(host_to_big(u16) == u16_big);
    CHECK(host_to_big(u32) == u32_big);
    CHECK(host_to_big(u64) == u64_big);

    // The above should not have mutated:
    REQUIRE(u8 == 0x01);
    REQUIRE(u16 == 0x0123);
    REQUIRE(u32 == 0x01234567);
    REQUIRE(u64 == 0x0123456789abcdef);

    host_to_big_inplace(u8);
    host_to_big_inplace(u16);
    host_to_big_inplace(u32);
    host_to_big_inplace(u64);
    CHECK(u8 == u8_big);
    CHECK(u16 == u16_big);
    CHECK(u32 == u32_big);
    CHECK(u64 == u64_big);

    CHECK(big_to_host(u8) == 0x01);
    CHECK(big_to_host(u16) == 0x0123);
    CHECK(big_to_host(u32) == 0x01234567);
    CHECK(big_to_host(u64) == 0x0123456789abcdef);

    big_to_host_inplace(u8);
    big_to_host_inplace(u16);
    big_to_host_inplace(u32);
    big_to_host_inplace(u64);
    CHECK(u8 == 0x01);
    CHECK(u16 == 0x0123);
    CHECK(u32 == 0x01234567);
    CHECK(u64 == 0x0123456789abcdef);

    const char* data = "\xef\xcd\xab\x89\x67\x45\x23\x01";
    CHECK(load_big_to_host<uint8_t>(data) == 0xef);
    CHECK(load_big_to_host<uint16_t>(data) == 0xefcd);
    CHECK(load_big_to_host<uint32_t>(data) == 0xefcdab89);
    CHECK(load_big_to_host<uint64_t>(data) == 0xefcdab8967452301);

    CHECK(load_host_to_big<uint8_t>(data) == 0xef);
    CHECK(load_host_to_big<uint16_t>(data) == 0xefcd);
    CHECK(load_host_to_big<uint32_t>(data) == 0xefcdab89);
    CHECK(load_host_to_big<uint64_t>(data) == 0xefcdab8967452301);

    char buf[8] = {0};
    std::string_view buffer{buf, 8};
    write_host_as_big(u8, buf);
    CHECK(buffer.substr(0, 1) == "\x01");
    write_host_as_big(u16, buf);
    CHECK(buffer.substr(0, 2) == "\x01\x23");
    write_host_as_big(u32, buf);
    CHECK(buffer.substr(0, 4) == "\x01\x23\x45\x67");
    write_host_as_big(u64, buf);
    CHECK(buffer.substr(0, 8) == "\x01\x23\x45\x67\x89\xab\xcd\xef");
}

TEST_CASE("signed values", "[endian][signed]") {
    int8_t i8 = 0x01;
    int16_t i16 = 0x0123;
    int32_t i32 = 0x01234567;
    int64_t i64 = 0x0123456789abcdef;

    constexpr int8_t i8_little = 0x01;
    constexpr int16_t i16_little = little_endian ? 0x0123 : 0x2301;
    constexpr int32_t i32_little = little_endian ? 0x01234567 : 0x67452301;
    constexpr int64_t i64_little = little_endian ? 0x0123456789abcdef : -0x1032547698badcff;
    constexpr int8_t i8_big = 0x01;
    constexpr int16_t i16_big = little_endian ? 0x2301 : 0x0123;
    constexpr int32_t i32_big = little_endian ? 0x67452301 : 0x01234567;
    constexpr int64_t i64_big = little_endian ? -0x1032547698badcff : 0x0123456789abcdef;

    CHECK(host_to_little(i8) == i8_little);
    CHECK(host_to_little(i16) == i16_little);
    CHECK(host_to_little(i32) == i32_little);
    CHECK(host_to_little(i64) == i64_little);
    CHECK(host_to_big(i8) == i8_big);
    CHECK(host_to_big(i16) == i16_big);
    CHECK(host_to_big(i32) == i32_big);
    CHECK(host_to_big(i64) == i64_big);
}
