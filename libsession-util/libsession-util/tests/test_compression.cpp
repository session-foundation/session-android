
#include <oxenc/hex.h>
#include <session/config/encrypt.h>
#include <session/config/user_profile.h>
#include <sodium/crypto_sign_ed25519.h>

#include <catch2/catch_test_macros.hpp>
#include <string_view>

#include "utils.hpp"

namespace session::config {
void compress_message(ustring& msg, int level);
}

using namespace std::literals;
using namespace oxenc::literals;

TEST_CASE("compression", "[config][compression]") {

    auto data =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"_hexbytes;

    CHECK(data.size() == 81);

    auto d = data;
    session::config::compress_message(d, 1);

    CHECK(d[0] == 'z');
    CHECK(d.size() == 18);
    CHECK(to_hex(d) == "7a28b52ffd205145000010aaaa01008c022c");

    d = data;
    session::config::compress_message(d, 5);
    CHECK(d[0] == 'z');
    CHECK(d.size() == 17);
    CHECK(to_hex(d) == "7a28b52ffd20513d000008aa01000dea84");

    // This message (from the user profile test case) doesn't compress any better than plaintext
    // with zstd compression, so the compress_message call shouldn't change it.
    // clang-format off
    data =
        "d"
          "1:#" "i1e"
          "1:&" "d"
            "1:n" "6:Kallie"
            "1:p" "34:http://example.org/omg-pic-123.bmp"
            "1:q" "6:secret"
          "e"
          "1:<" "l"
            "l"
              "i0e"
              "32:"_bytes +
                "ea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c965"_hexbytes +
              "de"
            "e"
          "e"
          "1:=" "d"
            "1:n" "0:"
            "1:p" "0:"
            "1:q" "0:"
          "e"
        "e"_bytes;
    //
    // If we add some more repetition in it, though, it will:
    auto data2 =
        "d"
          "1:#" "i1e"
          "1:&" "d"
            "1:n" "12:KallieKallie"
            "1:p" "29:http://kallie.example.org/Kallie.bmp"
            "1:q" "24:KallieKalliesecretKallie"
          "e"
          "1:<" "l"
            "l"
              "i0e"
              "32:"_bytes +
                "ea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c965"_hexbytes +
              "de"
            "e"
          "e"
          "1:=" "d"
            "1:n" "0:"
            "1:p" "0:"
            "1:q" "0:"
          "e"
        "e"_bytes;
    // clang-format on

    d = data;
    intptr_t dptr = reinterpret_cast<intptr_t>(d.data());

    // Doesn't compress, so shouldn't change:
    CHECK(d.size() == 142);
    session::config::compress_message(d, 1);
    CHECK(d[0] == 'd');
    CHECK(d.size() == 142);
    CHECK(reinterpret_cast<intptr_t>(d.data()) == dptr);

    // Test some compression levels with exact compression values.  (Note that this will change if
    // we change the version of external/zstd, but should be constant otherwise for any given
    // version of zstd).
    d = data2;
    session::config::compress_message(d, 1);
    CHECK(d[0] == 'z');
    CHECK(d.size() == 161);
    CHECK(d.size() < data2.size());
    CHECK(to_hex(d) ==
          "7a28b52ffd20aabd0400640864313a23693165313a2664313a6e31323a4b616c6c6965313a7032393a68"
          "7474703a2f2f6b2e6578616d706c652e6f72672f4b626d70313a71323473656372657465313a3c6c6c69"
          "306533323aea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c9656465656531"
          "3a3d64313a6e303a313a70303a313a71303a656505003587f2d5e1c02836af9aa13401");

    d = data2;
    session::config::compress_message(d, 5);
    CHECK(d[0] == 'z');
    CHECK(d.size() == 156);
    CHECK(d.size() < data2.size());
    CHECK(to_hex(d) ==
          "7a28b52ffd20aa950400a40764313a23693165313a2664313a6e31323a4b616c6c6965313a7032393a68"
          "7474703a2f2f6b2e6578616d706c652e6f72672f4b626d70313a71323473656372657465313a3c6c6c69"
          "306533323aea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c96564653d303a"
          "313a7071303a65650800a8d0880966a9827e19e0572706a3d8bc6a86d204");

    d = data2;
    session::config::compress_message(d, 19);
    CHECK(d[0] == 'z');
    CHECK(d.size() == 157);  // Yeah, it actually gets *bigger* with supposedly "higher" compression
    CHECK(d.size() < data2.size());
    CHECK(to_hex(d) ==
          "7a28b52ffd20aa9d0400e40764313a23693165313a2664313a6e31323a4b616c6c6965313a7032393a68"
          "7474703a2f2f6b2e6578616d706c652e6f72672f4b626d70313a71323473656372657465313a3c6c6c69"
          "306533323aea173b57beca8af18c3519a7bbf69c3e7a05d1c049fa9558341d8ebb48b0c96564653d6431"
          "3a6e303a313a7071303a6565070028812c55282f03fceac460149b57cd509a");
}
