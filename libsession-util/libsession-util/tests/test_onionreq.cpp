#include <catch2/catch_test_macros.hpp>
#include <session/onionreq/hop_encryption.hpp>
#include <session/onionreq/parser.hpp>

#include "utils.hpp"

using namespace session;
using namespace session::onionreq;

TEST_CASE("Onion request encryption", "[encryption][onionreq]") {

    auto A = "bbdfc83022d0aff084a6f0c529a93d1c4d728bf7e41199afed0e01ae70d20540"_hexbytes;
    auto a = "ccc335912da8939e2b44816728a5a4773063efa82bf7ae2d42a0abfa2caa452d"_hexbytes;
    auto B = "caea52c5b0c316d85ffb53ea536826618b13dee40685f166f632653114526a78"_hexbytes;
    auto b = "8fcd8ad3a15c76f76f1c56dff0c529999f8c59b4acda79e05666e54d5727dca1"_hexbytes;

    auto enc_gcm =
            "1eb6ae1cd72f60999486365749bd5dc15cc0b6a2a44d7d063daa5e93722f0c025fd00306403b61"
            ""_hexbytes;
    auto enc_gcm_broken1 =
            "1eb6ae1cd72f60999486365759bd5dc15cc0b6a2a44d7d063daa5e93722f0c025fd00306403b61"
            ""_hexbytes;
    auto enc_gcm_broken2 =
            "1eb6ae1cd72f60999486365749bd5dc15cc0b6a2a44d7d063daa5e93722f0c025fd00306403b69"
            ""_hexbytes;
    auto enc_xchacha20 =
            "9e1a3abe60eff3ea5c23556cc7e225b6f94355315f7281f66ecf4dbb06e7899a52b863e03cde3b28"
            "7d1638d765db75de02b032"_hexbytes;
    auto enc_xchacha20_broken1 =
            "9e1a3abe60eff3ea5c23556cc7e225b6f94355315f7281f66ecf4dbb06e7899a52b863e03cde3b28"
            "7d1638d765db75de02b033"_hexbytes;
    auto enc_xchacha20_broken2 =
            "9e1a3abe60eff3ea5c23556ccfe225b6f94355315f7281f66ecf4dbb06e7899a52b863e03cde3b28"
            "7d1638d765db75de02b032"_hexbytes;

    HopEncryption e{x25519_seckey::from_bytes(b), x25519_pubkey::from_bytes(B), true};

    CHECK(from_unsigned_sv(e.decrypt_aesgcm(enc_gcm, x25519_pubkey::from_bytes(A))) ==
          "Hello world");
    CHECK(from_unsigned_sv(e.decrypt_xchacha20(enc_xchacha20, x25519_pubkey::from_bytes(A))) ==
          "Hello world");
    CHECK_THROWS(e.decrypt_aesgcm(enc_xchacha20_broken1, x25519_pubkey::from_bytes(A)));
    CHECK_THROWS(e.decrypt_aesgcm(enc_xchacha20_broken2, x25519_pubkey::from_bytes(A)));
    CHECK_THROWS(e.decrypt_xchacha20(enc_xchacha20_broken1, x25519_pubkey::from_bytes(A)));
    CHECK_THROWS(e.decrypt_xchacha20(enc_xchacha20_broken2, x25519_pubkey::from_bytes(A)));
}

TEST_CASE("Onion request parser", "[onionreq][parser]") {

    auto A = "8167e97672005c669a48858c69895f395ca235219ac3f7a4210022b1f910e652"_hexbytes;
    auto a = "d2ee09e1a557a077d385fcb69a11ffb6909ecdcc8348def3e0e4172c8a1431c1"_hexbytes;
    auto B = "8388de69bc0d4b6196133233ad9a46ba0473474bc67718aad96a3a33c257f726"_hexbytes;
    auto b = "2f4d1c0d28e137777ec0a316e9f4f763e3e66662a6c51994c6315c9ef34b6deb"_hexbytes;

    auto enc_gcm =
            "270000009525d587d188c92a966eef0e7162bef99a6171a124575b998072a8ee7eb265e0b6f0930ed96504"
            "7b22656e635f74797065223a20226165732d67636d222c2022657068656d6572616c5f6b6579223a202238"
            "31363765393736373230303563363639613438383538633639383935663339356361323335323139616333"
            "6637613432313030323262316639313065363532227d"_hexbytes;
    auto enc_gcm_broken1 = ""_hexbytes;
    auto enc_gcm_broken2 = ""_hexbytes;
    auto enc_xchacha20 =
            "33000000e440bc244ddcafd947b86fc5a964aa58de54a6d75cc0f0f3840db14b6c1176a8e2e0a04d5fbdf9"
            "8f23adee1edc8362ab99b10b7b22656e635f74797065223a2022786368616368613230222c202265706865"
            "6d6572616c5f6b6579223a2022383136376539373637323030356336363961343838353863363938393566"
            "33393563613233353231396163336637613432313030323262316639313065363532227d"_hexbytes;
    auto enc_xchacha20_broken1 = ""_hexbytes;
    auto enc_xchacha20_broken2 = ""_hexbytes;

    OnionReqParser parser_gcm{B, b, enc_gcm};
    CHECK(from_unsigned_sv(parser_gcm.payload()) == "Hello world");
    CHECK(parser_gcm.remote_pubkey() == A);
    auto aes_reply = parser_gcm.encrypt_reply(to_unsigned_sv("Goodbye world"));
    CHECK(aes_reply.size() == 12 + 13 + 16);

    HopEncryption e{x25519_seckey::from_bytes(a), x25519_pubkey::from_bytes(A), false};
    CHECK(from_unsigned_sv(e.decrypt_aesgcm(aes_reply, x25519_pubkey::from_bytes(B))) ==
          "Goodbye world");

    OnionReqParser parser_xchacha20{B, b, enc_xchacha20};
    CHECK(from_unsigned_sv(parser_xchacha20.payload()) == "Hello world");
    CHECK(parser_xchacha20.remote_pubkey() == A);
    auto xcha_reply = parser_xchacha20.encrypt_reply(to_unsigned_sv("Goodbye world"));
    CHECK(xcha_reply.size() == 16 + 13 + 24);
    CHECK(from_unsigned_sv(e.decrypt_xchacha20(xcha_reply, x25519_pubkey::from_bytes(B))) ==
          "Goodbye world");
}
