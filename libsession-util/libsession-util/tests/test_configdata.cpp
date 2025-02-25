#include <oxenc/bt_serialize.h>
#include <oxenc/hex.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_sign.h>

#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_exception.hpp>
#include <session/config.hpp>

#include "session/bt_merge.hpp"
#include "session/version.h"
#include "utils.hpp"

using namespace session;
using namespace std::literals;
using namespace oxenc::literals;
using config::ConfigMessage;
using config::MutableConfigMessage;
using oxenc::bt_dict;
using oxenc::bt_list;

TEST_CASE("libsession-util version", "[version]") {
    CHECK(LIBSESSION_UTIL_VERSION[0] + LIBSESSION_UTIL_VERSION[1] + LIBSESSION_UTIL_VERSION[2] > 0);
    CHECK(LIBSESSION_UTIL_VERSION_STR[0] == 'v');
    CHECK('0' <= LIBSESSION_UTIL_VERSION_STR[1]);
    CHECK(LIBSESSION_UTIL_VERSION_STR[1] <= '9');
    CHECK(std::string_view{LIBSESSION_UTIL_VERSION_STR}.find(".") != std::string_view::npos);
    CHECK(std::string_view{LIBSESSION_UTIL_VERSION_FULL}.substr(0, 17) == "libsession-util v");
}
TEST_CASE("config data scalar encoding", "[config][data][scalar]") {
    CHECK(oxenc::bt_serialize(config::scalar{3}) == "i3e");
    CHECK(oxenc::bt_serialize(config::scalar{"hi"}) == "2:hi");
    CHECK(oxenc::bt_serialize(config::scalar{-42}) == "i-42e");
}
TEST_CASE("config data set encoding", "[config][data][set]") {
    config::set s;
    s.insert("foo");
    s.insert(23);
    s.insert("Foo");
    s.insert("~");
    s.insert("!");
    s.insert("foo");  // dupe
    s.insert(-123);

    CHECK(oxenc::bt_serialize(s) == "li-123ei23e1:!3:Foo3:foo1:~e");
}

TEST_CASE("config data dict encoding", "[config][data][dict]") {
    config::dict d;
    d["a"] = 23;
    d["B"] = "x";
    d["c"] = config::set{{"2", "1", 4, -3}};
    d["D"] = config::dict{{"x", 1}, {"y", 2}};
    d["d"] = config::dict{{"e", config::dict{{"f", config::dict{{"g", ""}}}}}};

    static_assert(oxenc::detail::is_bt_input_dict_container<config::dict>);

    CHECK(oxenc::bt_serialize(d) ==
          "d1:B1:x1:Dd1:xi1e1:yi2ee1:ai23e1:cli-3ei4e1:11:2e1:dd1:ed1:fd1:g0:eeee");
}

TEST_CASE("config pruning", "[config][prune]") {
    MutableConfigMessage m;
    m.data()["a"] = 123;

    CHECK(m.data() == config::dict{{"a", 123}});

    m.prune();
    CHECK(m.data() == config::dict{{"a", 123}});

    m.data()["b"] = config::dict{};
    m.prune();
    CHECK(m.data().size() == 1);
    CHECK(m.data() == config::dict{{"a", 123}});

    m.data()["b"] = config::dict{};
    m.data()["c"] = config::dict{{"a", 1}};
    m.data()["d"] = config::set{{42}};
    m.data()["e"] = config::set{};
    m.prune();
    CHECK(m.data().size() == 3);
    CHECK(m.data() ==
          config::dict{{"a", 123}, {"c", config::dict{{"a", 1}}}, {"d", config::set{{42}}}});

    m.data()["b"] = config::dict{};
    m.data()["e"] = config::set{};
    m.data()["f"] = config::dict{{"a", 1}, {"b", config::set{}}};
    m.data()["g"] = config::dict{{"a", config::dict{{"a", config::dict{{"a", config::dict{}}}}}}};

    CHECK(m.data().size() == 7);
    m.prune();
    CHECK(m.data().size() == 4);
    CHECK(m.data() == config::dict{
                              {"a", 123},
                              {"c", config::dict{{"a", 1}}},
                              {"d", config::set{{42}}},
                              {"f", config::dict{{"a", 1}}},
                      });
}

// shortcut to access a nested dict
auto& d(config::dict_value& v) {
    return var::get<config::dict>(v);
}
// or set
auto& s(config::dict_value& v) {
    return var::get<config::set>(v);
}

template <typename T, size_t N>
ustring_view view(const std::array<T, N>& data) {
    return ustring_view{data.data(), data.size()};
}
template <typename T, size_t N>
std::string view_hex(const std::array<T, N>& data) {
    return oxenc::to_hex(data.begin(), data.end());
}

ustring blake2b(ustring_view data) {
    ustring result;
    result.resize(32);
    crypto_generichash_blake2b(result.data(), 32, data.data(), data.size(), nullptr, 0);
    return result;
}

TEST_CASE("config diff", "[config][diff]") {
    MutableConfigMessage m;
    m.data()["foo"] = 123;
    m.data()["empty"] = config::set{};
    m.data()["empty2"] = config::dict{};
    m.data()["bar"] =
            config::dict{{"asdf", 123}, {"xyz", "abc"}, {"", config::set{{"a", "b", 42}}}};
    m.data()["z"];  // will autovivify as a dict, but empty so will get pruned

    CHECK(m.seqno() == 0);
    auto diff = m.diff();
    CHECK(diff.size() == 2);
    CHECK(diff == bt_dict{{"foo", ""sv},
                          {"bar",
                           bt_dict{{"asdf", ""sv},
                                   {"xyz", ""sv},
                                   {"", bt_list{{bt_list{{42, "a"s, "b"s}}, bt_list{}}}}}}});

    auto m1 = m.increment();
    CHECK(m1.seqno() == 1);
    CHECK(m1.diff().empty());
    m1.data().erase("foo");

    diff = m1.diff();
    CHECK(diff.size() == 1);
    CHECK(diff == bt_dict{{"foo", "-"sv}});

    auto m2 = m1.increment();
    CHECK(m2.seqno() == 2);
    CHECK(m2.diff().empty());

    m2.data()["foo"] = 123;
    d(m2.data()["bar"])["xyz"] = 42;
    d(m2.data()["bar"])["a"] = 42;
    s(d(m2.data()["bar"])[""]).insert("c");
    s(d(m2.data()["bar"])[""]).insert(99);
    s(d(m2.data()["bar"])[""]).erase("b");
    s(d(m2.data()["bar"])[""]).insert(42);  // already present

    diff = m2.diff();
    CHECK(diff.size() == 2);
    CHECK(diff == bt_dict{{"foo", ""sv},
                          {"bar",
                           bt_dict{{"xyz", ""sv},
                                   {"a", ""sv},
                                   {"", bt_list{{bt_list{{99, "c"s}}, bt_list{{"b"s}}}}}}}});
}

TEST_CASE("config message serialization", "[config][serialization]") {
    MutableConfigMessage m;
    m.seqno(10);
    m.data()["foo"] = 123;
    m.data()["bar"] =
            config::dict{{"asdf", 123}, {"xyz", "abc"}, {"", config::set{{"a", "b", 42}}}};

    // clang-format off
    CHECK(printable(m.serialize()) == printable(
        "d"
          "1:#" "i10e"
          "1:&" "d"
            "3:bar" "d"
              "0:" "li42e1:a1:be"
              "4:asdf" "i123e"
              "3:xyz" "3:abc"
            "e"
            "3:foo" "i123e"
          "e"
          "1:<" "le"
          "1:=" "d"
            "3:bar" "d"
              "0:" "lli42e1:a1:belee"
              "4:asdf" "0:"
              "3:xyz" "0:"
            "e"
            "3:foo" "0:"
          "e"
        "e"));
    // clang-format on

    const auto hash0 = "d65738bba88b0f3455cef20fe09a7b4b10f25f9db82be24a6ce1bd06da197526"_hex;
    CHECK(view_hex(m.hash()) == oxenc::to_hex(hash0));

    auto m1 = m.increment();
    m1.data().erase("foo");
    const auto hash1 = "5b30b4abf4cba71db25dbc0d977cc25df1d0a8a87cad7f561cdec2b8caf65f5e"_hex;
    CHECK(view_hex(m1.hash()) == oxenc::to_hex(hash1));

    auto m2 = m1.increment();

    m2.data()["foo"] = 123;
    d(m2.data()["bar"])["xyz"] = 42;
    d(m2.data()["bar"])["a"] = 42;
    s(d(m2.data()["bar"])[""]).insert("c");
    s(d(m2.data()["bar"])[""]).insert(99);
    s(d(m2.data()["bar"])[""]).erase("b");
    s(d(m2.data()["bar"])[""]).insert(42);  // already present

    const auto hash2 = "027552203cf669070d3ecbeecfa65c65497d59aa4da490e0f68f8131ce081320"_hex;
    CHECK(view_hex(m2.hash()) == oxenc::to_hex(hash2));

    // clang-format off
    CHECK(printable(m2.serialize()) == printable(
        "d"
          "1:#" "i12e"
          "1:&" "d"
            "3:bar" "d"
              "0:" "li42ei99e1:a1:ce"
              "1:a" "i42e"
              "4:asdf" "i123e"
              "3:xyz" "i42e"
            "e"
            "3:foo" "i123e"
          "e"
          "1:<" "l"
            "l"
              "i10e"
              "32:" + hash0 +
              "d"
                "3:bar" "d"
                  "0:" "lli42e1:a1:belee"
                  "4:asdf" "0:"
                  "3:xyz" "0:"
                "e"
                "3:foo" "0:"
              "e"
            "e"
            "l"
              "i11e"
              "32:" + hash1 +
              "d"
                "3:foo" "1:-"
              "e"
            "e"
          "e"
          "1:=" "d"
            "3:bar" "d"
              "0:" "l" "li99e1:ce" "l1:be" "e"
              "1:a" "0:"
              "3:xyz" "0:"
            "e"
            "3:foo" "0:"
          "e"
        "e"));

    auto m5 = m2.increment().increment().increment();
    const auto hash3 = "b83871ea06587f9254cdf2b2af8daff19bd7fb550fb90d5f8f9f546464c08bc5"_hex;
    const auto hash4 = "c30e2cfa7ec93c64a1ab6420c9bccfb63da8e4c2940ed6509ffb64f3f0131860"_hex;
    const auto hash5 = "3234eb7da8cf4b79b9eec2a144247279d10f6f118184f82429a42c5996bea60c"_hex;

    CHECK(view_hex(m2.increment().hash()) == oxenc::to_hex(hash3));
    CHECK(view_hex(m2.increment().increment().hash()) == oxenc::to_hex(hash4));
    CHECK(view_hex(m5.hash()) == oxenc::to_hex(hash5));

    CHECK(printable(m5.serialize()) == printable(
        "d"
          "1:#" "i15e"
          "1:&" "d"
            "3:bar" "d"
              "0:" "li42ei99e1:a1:ce"
              "1:a" "i42e"
              "4:asdf" "i123e"
              "3:xyz" "i42e"
            "e"
            "3:foo" "i123e"
          "e"
          "1:<" "l"
            "l"
              "i11e"
              "32:" + hash1 +
              "d"
                "3:foo" "1:-"
              "e"
            "e"
            "l"
              "i12e"
              "32:" + hash2 +
              "d"
                "3:bar" "d"
                  "0:" "l" "li99e1:ce" "l1:be" "e"
                  "1:a" "0:"
                  "3:xyz" "0:"
                "e"
                "3:foo" "0:"
              "e"
            "e"
            "l"
              "i13e"
              "32:" + hash3 +
              "de"
            "e"
            "l"
              "i14e"
              "32:" + hash4 +
              "de"
            "e"
          "e"
          "1:=" "d"
          "e"
        "e"));

    // clang-format on
}

TEST_CASE("config message signature", "[config][signing]") {
    MutableConfigMessage m;
    m.seqno(10);
    m.data()["foo"] = 123;
    m.data()["bar"] =
            config::dict{{"asdf", 123}, {"xyz", "abc"}, {"", config::set{{"a", "b", 42}}}};
    constexpr auto skey_hex =
            "79f530dbf3d81aecc04072933c1b3e3edc0b7d91f2dcc2f7756f2611886cca5f"
            "4384261cdd338f5820ca9cbbe3fc72ac8944ee60d3b795b797fbbf5597b09f17"sv;
    std::array<unsigned char, 64> secretkey;
    oxenc::from_hex(skey_hex.begin(), skey_hex.end(), secretkey.begin());
    auto signer = [&secretkey](ustring_view data) {
        ustring result;
        result.resize(64);
        crypto_sign_ed25519_detached(
                result.data(), nullptr, data.data(), data.size(), secretkey.data());
        return result;
    };
    auto verifier = [&secretkey](ustring_view data, ustring_view signature) {
        return 0 == crypto_sign_verify_detached(
                            signature.data(), data.data(), data.size(), secretkey.data() + 32);
    };

    m.signer = signer;

    // clang-format off
    auto m_signing_value =
        "d"
          "1:#" "i10e"
          "1:&" "d"
            "3:bar" "d"
              "0:" "li42e1:a1:be"
              "4:asdf" "i123e"
              "3:xyz" "3:abc"
            "e"
            "3:foo" "i123e"
          "e"
          "1:<" "le"
          "1:=" "d"
            "3:bar" "d"
              "0:" "lli42e1:a1:belee"
              "4:asdf" "0:"
              "3:xyz" "0:"
            "e"
            "3:foo" "0:"
          "e"_bytes;
          // Signed value ends here, though the actual value will continue with sig and final e:
          // "1:~" "64:...signature..." +
        //"e";
        ;
    // clang-format on

    auto expected_sig =
            "77267f4de7701ae348eba0ef73175281512ba3f1051cfed22dc3e31b9c699330"
            "2938863e09bc8b33638161071bd8dc397d5c1d3f674120d08fbb9c64dde2e907"_hexbytes;
    ustring sig(64, '\0');
    // Sign it ourselves, and check what we get:
    crypto_sign_ed25519_detached(
            sig.data(), nullptr, m_signing_value.data(), m_signing_value.size(), secretkey.data());
    CHECK(to_hex(sig) == to_hex(expected_sig));
    auto m_expected = m_signing_value;
    m_expected += "1:~64:"_bytes;
    m_expected += expected_sig;
    m_expected += 'e';
    CHECK(printable(m.serialize()) == printable(m_expected));

    ConfigMessage msg{m_expected, verifier, signer};
    CHECK(msg.verified_signature());
    CHECK(msg.hash() == m.hash());
    CHECK(printable(msg.serialize()) == printable(m_expected));

    // Deliberately modify the signature to break it:
    auto m_broken = m_expected;
    REQUIRE(m_broken[m_broken.size() - 2] == 0x07);
    m_broken[m_broken.size() - 2] = 0x17;

    using Catch::Matchers::Message;
    CHECK_THROWS_AS(ConfigMessage(m_broken, verifier), config::signature_error);
    CHECK_THROWS_MATCHES(
            ConfigMessage({m_broken, m_broken}, verifier),
            config::config_error,
            Message("Config initialization failed: no valid config messages given"));

    CHECK_NOTHROW(ConfigMessage({m_broken, m_expected}, verifier));
    CHECK_NOTHROW(ConfigMessage({m_expected, m_broken}, verifier));

    ConfigMessage m2{{m_broken, m_expected}, verifier, signer};
    CHECK_FALSE(m2.merged());
    CHECK(m2.seqno() == 10);
    CHECK(view_hex(m2.hash()) == view_hex(m.hash()));

    CHECK_THROWS_MATCHES(
            ConfigMessage(
                    {m_broken, m_expected},
                    verifier,
                    nullptr,
                    ConfigMessage::DEFAULT_DIFF_LAGS,
                    [](size_t, const auto& exc) { throw exc; }),
            config::config_error,
            Message("Config signature failed verification"));

    auto m_unsigned = m_signing_value + "e"_bytes;
    CHECK_THROWS_MATCHES(
            ConfigMessage(m_unsigned, verifier),
            config::missing_signature,
            Message("Config signature is missing"));
}

const config::dict data118{
        {"dictB", config::dict{{"changed", -1}, {"foo", 123}, {"removed", "x"}, {"removed2", "y"}}},
        {"dictC", config::dict{{"x", config::dict{{"y", 1}}}}},
        {"good", config::set{{99, 456, "bar"}}},
        {"great", config::set{{-42, "omg"}}},
        {"int0", -9999},
        {"int1", 100},
        {"string1", "hello"},
        {"string2", "goodbye"},
};

const auto h119 = "43094f68c1faa37eff79e1c2f3973ffd5f9d6423b00ccda306fc6e7dac5f0c44"_hexbytes;
const auto h120 = "e3a237f91014d31e4d30569c4a8bfcd72157804f99b8732c611c48bf126432b5"_hexbytes;
const auto h121 = "1a7f602055124deaf21175ef3f32983dee7c9de570e5d9c9a0bbc2db71dcb97f"_hexbytes;
const auto h122 = "46560604fe352101bb869435260d7100ccfe007be5f741c7e96303f02f394e8a"_hexbytes;
const auto m123_expected =
        // clang-format off
        "d"
          "1:#" "i123e"
          "1:&" "d"
            "5:dictB" "d"
              "7:changed" "i-1e"
              "3:foo" "i123e"
              "7:removed" "1:x"
              "8:removed2" "1:y"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "i1e"
              "e"
            "e"
            "4:good" "l"
              "i99e" "i456e" "3:bar"
            "e"
            "5:great" "l"
              "i-42e" "3:omg"
            "e"
            "4:int1" "i1e"
            "4:int2" "i2e"
            "7:string1" "5:hello"
            "7:string2" "7:goodbye"
         "e"
         "1:<" "l"
           "l" "i119e" "32:"_bytes+h119+ "de" "e"
           "l" "i120e" "32:"_bytes+h120+ "de" "e"
           "l" "i121e" "32:"_bytes+h121+ "de" "e"
           "l" "i122e" "32:"_bytes+h122+ "de" "e"
         "e"
         "1:=" "d"
           "4:int0" "1:-"
           "4:int1" "0:"
           "4:int2" "0:"
         "e"
       "e"_bytes;
// clang-format on
const auto h123 = "d9398c597b058ac7e28e3febb76ed68eb8c5b6c369610562ab5f2b596775d73c"_hexbytes;

TEST_CASE("config message example 1", "[config][example]") {
    /// This is the "Ordinary update" example described in docs/config-merge-logic.md
    MutableConfigMessage m118{118, 5};
    CHECK(m118.seqno() == 118);
    CHECK(m118.lag == 5);
    m118.data() = data118;

    // clang-format off
    const auto m118_expected =
        "d"
          "1:#" "i118e"
          "1:&" "d"
            "5:dictB" "d"
              "7:changed" "i-1e"
              "3:foo" "i123e"
              "7:removed" "1:x"
              "8:removed2" "1:y"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "i1e"
              "e"
            "e"
            "4:good" "l"
              "i99e" "i456e" "3:bar"
            "e"
            "5:great" "l"
              "i-42e" "3:omg"
            "e"
            "4:int0" "i-9999e"
            "4:int1" "i100e"
            "7:string1" "5:hello"
            "7:string2" "7:goodbye"
          "e"
          "1:<" "le"
          "1:=" "d"
            "5:dictB" "d"
              "7:changed" "0:"
              "3:foo" "0:"
              "7:removed" "0:"
              "8:removed2" "0:"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "0:"
              "e"
            "e"
            "4:good" "l"
              "l" "i99e" "i456e" "3:bar" "e"
              "le"
            "e"
            "5:great" "l"
              "l" "i-42e" "3:omg" "e"
              "le"
            "e"
            "4:int0" "0:"
            "4:int1" "0:"
            "7:string1" "0:"
            "7:string2" "0:"
          "e"
        "e"_bytes;
    // clang-format off

    CHECK(printable(m118.serialize()) == printable(m118_expected));

    CHECK(view_hex(m118.hash()) == to_hex(blake2b(m118_expected)));

    // Increment 5 times so that our diffs will be empty.
    auto m123 = m118.increment();
    CHECK(m123.seqno() == 119);
    CHECK(view_hex(m123.hash()) == to_hex(h119));

    m123 = m123.increment();
    CHECK(m123.seqno() == 120);
    CHECK(view_hex(m123.hash()) == to_hex(h120));

    m123 = m123.increment();
    CHECK(m123.seqno() == 121);
    CHECK(view_hex(m123.hash()) == to_hex(h121));

    m123 = m123.increment();
    CHECK(m123.seqno() == 122);
    CHECK(view_hex(m123.hash()) == to_hex(h122));

    m123 = m123.increment();

    m123.data()["int1"] = 1;
    m123.data()["int2"] = 2;
    m123.data().erase("int0");
    m123.data()["string1"] = "hello"; // not changing the value, shouldn't show up in the diff

    CHECK(printable(m123.serialize()) == printable(m123_expected));
}

TEST_CASE("config message deserialization", "[config][deserialization]") {
    ConfigMessage m{m123_expected};

    CHECK(m.seqno() == 123);
    CHECK(view_hex(m.hash()) == to_hex(h123));
    CHECK(m.diff() == oxenc::bt_dict{
        {"int0"s, "-"s},
        {"int1"s, ""s},
        {"int2"s, ""s}
    });
    CHECK_FALSE(m.verified_signature());

    auto expected_data = data118;
    expected_data["int1"] = 1;
    expected_data.erase("int0");
    expected_data["int2"] = 2;
    CHECK(m.data() == expected_data);

    // This is the same, but because it's mutable, we deserialize and then implicit get a
    // increment()
    MutableConfigMessage mut{m123_expected};
    CHECK(mut.seqno() == 124);
    CHECK(view_hex(mut.hash()) == "3ea36410cf7086ce816eb193b0c94e88632abfb75771d82f8ddb3a909124c580");
    CHECK(mut.diff() == oxenc::bt_dict{});
    CHECK_FALSE(mut.merged());
    CHECK_FALSE(mut.verified_signature());

    // clang-format off
    CHECK(printable(mut.serialize()) == printable(
        "d"
          "1:#" "i124e"
          "1:&" "d"
            "5:dictB" "d"
              "7:changed" "i-1e"
              "3:foo" "i123e"
              "7:removed" "1:x"
              "8:removed2" "1:y"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "i1e"
              "e"
            "e"
            "4:good" "l"
              "i99e" "i456e" "3:bar"
            "e"
            "5:great" "l"
              "i-42e" "3:omg"
            "e"
            "4:int1" "i1e"
            "4:int2" "i2e"
            "7:string1" "5:hello"
            "7:string2" "7:goodbye"
          "e"
          "1:<" "l"
            "l" "i120e" "32:"_bytes+h120+ "de" "e"
            "l" "i121e" "32:"_bytes+h121+ "de" "e"
            "l" "i122e" "32:"_bytes+h122+ "de" "e"
            "l"
              "i123e"
              "32:"_bytes+h123+
              "d"
                "4:int0" "1:-"
                "4:int1" "0:"
                "4:int2" "0:"
              "e"
            "e"
          "e"
          "1:=" "de"
        "e"_bytes));
    // clang-format on
}

TEST_CASE("config message empty set/list deserialization", "[config][deserialization][empty]") {
    // Test that we can properly notice data with an invalid empty set/dict in it.  We were
    // previously not noticing this, allowing it as input, and then segfaulting because we assumed
    // the data was valid (and thus that we would not encounter this case).

    // clang-format off
    auto has_empty_set = (
        "d"
           "1:#" "i0e"
           "1:&" "d"
              "0:" "le"
              "e"
           "1:<" "le"
           "1:=" "de"
        "e"_bytes);

    auto has_empty_dict = (
        "d"
           "1:#" "i0e"
           "1:&" "d"
              "0:" "de"
              "e"
           "1:<" "le"
           "1:=" "de"
        "e"_bytes);
    // clang-format on

    using Catch::Matchers::Message;

    CHECK_THROWS_MATCHES(
            MutableConfigMessage(has_empty_set),
            config::config_error,
            Message("Failed to parse config file: Data contains an unpruned, empty set"));
    CHECK_THROWS_MATCHES(
            MutableConfigMessage(has_empty_dict),
            config::config_error,
            Message("Failed to parse config file: Data contains an unpruned, empty dict"));
}

void updates_124(MutableConfigMessage& m) {
    m.data()["dictA"] = config::dict{
            {"hello", 123},
            {"goodbye", config::set{{123, 456}}},
    };
    auto& dictB = d(m.data()["dictB"]);
    dictB["changed"] = 1;
    dictB["added"] = 9999;
    dictB["nested"] = config::dict{{"a", 1}};
    dictB.erase("removed");
    dictB.erase("removed2");
    m.data().erase("dictC");
    s(m.data()["good"]).insert("Foo");
    s(m.data()["good"]).erase(456);
    s(m.data()["good"]).insert(123);
    m.data()["int1"] = 42;
    m.data().erase("string1");
    m.data()["string2"] = "hello";
    m.data()["string3"] = "omg";
    m.data().erase("great");
}

const auto h124 = "8b73f316178765b9b3b37168e865c84bb5a78610cbb59b84d0fa4d3b4b3c102b"_hexbytes;

TEST_CASE("config message example 2", "[config][example]") {
    /// This is the "Large, but still ordinary, update" example described in
    /// docs/config-merge-logic.md
    MutableConfigMessage m{m123_expected};
    REQUIRE(m.seqno() == 124);

    updates_124(m);

    // clang-format off
    CHECK(printable(m.serialize()) == printable(
        "d"
          "1:#" "i124e"
          "1:&" "d"
            "5:dictA" "d"
              "7:goodbye" "l" "i123e" "i456e" "e"
              "5:hello" "i123e"
            "e"
            "5:dictB" "d"
              "5:added" "i9999e"
              "7:changed" "i1e"
              "3:foo" "i123e"
              "6:nested" "d"
                "1:a" "i1e"
              "e"
            "e"
            "4:good" "l"
              "i99e" "i123e" "3:Foo" "3:bar"
            "e"
            "4:int1" "i42e"
            "4:int2" "i2e"
            "7:string2" "5:hello"
            "7:string3" "3:omg"
          "e"
          "1:<" "l"
            "l" "i120e" "32:"_bytes+h120+ "de" "e"
            "l" "i121e" "32:"_bytes+h121+ "de" "e"
            "l" "i122e" "32:"_bytes+h122+ "de" "e"
            "l"
              "i123e"
              "32:"_bytes+blake2b(m123_expected)+
              "d"
                "4:int0" "1:-"
                "4:int1" "0:"
                "4:int2" "0:"
              "e"
            "e"
          "e"
          "1:=" "d"
            "5:dictA" "d"
              "7:goodbye" "l" "l" "i123e" "i456e" "e" "le" "e"
              "5:hello" "0:"
            "e"
            "5:dictB" "d"
              "5:added" "0:"
              "7:changed" "0:"
              "6:nested" "d"
                "1:a" "0:"
              "e"
              "7:removed" "1:-"
              "8:removed2" "1:-"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "1:-"
              "e"
            "e"
            "4:good" "l"
              "l" "i123e" "3:Foo" "e"
              "l" "i456e" "e"
            "e"
            "5:great" "l"
              "le"
              "l" "i-42e" "3:omg" "e"
            "e"
            "4:int1" "0:"
            "7:string1" "1:-"
            "7:string2" "0:"
            "7:string3" "0:"
          "e"
        "e"_bytes));
    // clang-format on

    CHECK(view_hex(m.hash()) == to_hex(h124));
}

const auto h125a = "80f229c3667de6d0fa6f96b53118e097fbda82db3ca1aea221a3db91ea9c45fb"_hexbytes;
const auto h125b = "ab12f0efe9a9ed00db6b17b44ae0ff36b9f49094077fb114f415522f2a0e98de"_hexbytes;

// clang-format off
const auto m126_expected =
    "d"
      "1:#" "i126e"
      "1:&" "d"
        "5:dictA" "d"
          "7:goodbye" "l" "i123e" "i456e" "e"
          "5:hello" "i123e"
        "e"
        "5:dictB" "d"
          "5:added" "i9999e"
          "7:changed" "i1e"
          "6:nested" "d"
            "1:a" "i1e"
          "e"
        "e"
        "4:good" "l"
          "i99e" "i123e" "3:Foo" "3:bar"
        "e"
        "4:int1" "i5e"
        "4:int2" "i2e"
        "7:string2" "5:hello"
        "7:string3" "3:omg"
      "e"
      "1:<" "l"
        "l" "i122e" "32:"_bytes+h122+ "de" "e"
        "l"
          "i123e"
          "32:"_bytes+h123+
          "d"
            "4:int0" "1:-"
            "4:int1" "0:"
            "4:int2" "0:"
          "e"
        "e"
        "l"
          "i124e"
          "32:"_bytes+h124+
          "d"
            "5:dictA" "d"
              "7:goodbye" "l" "l" "i123e" "i456e" "e" "le" "e"
              "5:hello" "0:"
            "e"
            "5:dictB" "d"
              "5:added" "0:"
              "7:changed" "0:"
              "6:nested" "d"
                "1:a" "0:"
              "e"
              "7:removed" "1:-"
              "8:removed2" "1:-"
            "e"
            "5:dictC" "d"
              "1:x" "d"
                "1:y" "1:-"
              "e"
            "e"
            "4:good" "l"
              "l" "i123e" "3:Foo" "e"
              "l" "i456e" "e"
            "e"
            "5:great" "l"
              "le"
              "l" "i-42e" "3:omg" "e"
            "e"
            "4:int1" "0:"
            "7:string1" "1:-"
            "7:string2" "0:"
            "7:string3" "0:"
          "e"
        "e"
        "l"
          "i125e"
          "32:"_bytes+h125a+
          "d"
            "5:dictB" "d"
              "3:foo" "1:-"
            "e"
          "e"
        "e"
        "l"
          "i125e"
          "32:"_bytes+h125b+
          "d" "4:int1" "0:" "e"
        "e"
      "e"
      "1:=" "de"
    "e"_bytes;
// clang-format on

TEST_CASE("config message example 3 - simple conflict", "[config][example][conflict]") {
    /// This is the "Simple conflict resolution" example described in docs/config-merge-logic.md
    MutableConfigMessage m124{m123_expected};
    REQUIRE(m124.seqno() == 124);

    updates_124(m124);

    REQUIRE(view_hex(m124.hash()) == to_hex(h124));

    auto m125_a = m124.increment();
    REQUIRE(m125_a.seqno() == 125);
    d(m125_a.data()["dictB"]).erase("foo");

    auto m125_b = m124.increment();
    REQUIRE(m125_b.seqno() == 125);
    m125_b.data()["int1"] = 5;

    REQUIRE(view_hex(m125_a.hash()) == to_hex(h125a));
    REQUIRE(view_hex(m125_b.hash()) == to_hex(h125b));
    REQUIRE(m125_a.hash() < m125_b.hash());

    ConfigMessage m{{m125_a.serialize(), m125_b.serialize()}};
    CHECK(m.merged());
    CHECK(m.seqno() == 126);

    CHECK(m.data() ==
          config::dict{
                  {"dictA", config::dict{{"goodbye", config::set{{123, 456}}}, {"hello", 123}}},
                  {"dictB",
                   config::dict{
                           {"added", 9999}, {"changed", 1}, {"nested", config::dict{{"a", 1}}}}},
                  {"good", config::set{{99, 123, "Foo", "bar"}}},
                  {"int1", 5},
                  {"int2", 2},
                  {"string2", "hello"},
                  {"string3", "omg"}});

    CHECK(printable(m.serialize()) == printable(m126_expected));

    // Loading them in the opposite order shouldn't make any difference:
    ConfigMessage m_alt1{{m125_b.serialize(), m125_a.serialize()}};
    CHECK(printable(m_alt1.serialize()) == printable(m.serialize()));

    // Throwing in an already-included message also shouldn't matter:
    ConfigMessage m_alt2{{m124.serialize(), m125_b.serialize(), m125_a.serialize()}};
    ConfigMessage m_alt3{{m125_b.serialize(), m125_a.serialize(), m124.serialize()}};

    CHECK(printable(m_alt2.serialize()) == printable(m.serialize()));
    CHECK(printable(m_alt3.serialize()) == printable(m.serialize()));

    // 120b should get dropped, since it is too far before the top seqno (125).
    auto m120b = MutableConfigMessage{118, 5}.increment().increment();
    m120b.data()["too old"] = "won't see";

    ConfigMessage m_alt4{{m125_b.serialize(), m120b.serialize(), m125_a.serialize()}};
    CHECK(printable(m_alt4.serialize()) == printable(m.serialize()));
}

TEST_CASE("config message example 4 - complex conflict resolution", "[config][example][conflict]") {
    /// This is the "Complex conflict resolution" example described in
    /// docs/config-merge-logic.md

    ConfigMessage m123{m123_expected};

    auto m124a = m123.increment();

    d(m124a.data()["dictB"])["foo"] = 66;
    d(m124a.data()["dictB"])["answer"] = 42;

    auto m124b = m123.increment();
    updates_124(m124b);

    REQUIRE(view_hex(m124b.hash()) == to_hex(h124));

    auto m125a = m124b.increment();
    d(m125a.data()["dictB"]).erase("foo");

    auto m125b = m124b.increment();
    m125b.data()["int1"] = 5;

    ConfigMessage m126a{{m125a.serialize(), m125b.serialize()}};

    auto m120b = MutableConfigMessage{118, 5}.increment().increment();
    m120b.data()["too old"] = "won't see";

    // Throw some irrelevant ones in, which should get ignored (124b, 123, 120b: the first two
    // are already included in the ancestry of 125a and b; and the last is too old to be used).
    ConfigMessage m126b{
            {m125b.serialize(),
             m124a.serialize(),
             m125a.serialize(),
             m123.serialize(),
             m120b.serialize()}};

    REQUIRE(m124a.hash() < m124b.hash());
    REQUIRE(h125a < h125b);
    REQUIRE(m126a.hash() < m126b.hash());

    // Now we merge m126a and m126b together and should end up with the final merged result.
    MutableConfigMessage m127{{m126a.serialize(), m126b.serialize()}};
    s(d(m127.data()["dictA"])["goodbye"]).insert(789);

    REQUIRE(m127.seqno() == 127);

    REQUIRE(oxenc::bt_serialize(m127.data()) ==
            oxenc::bt_serialize(config::dict{
                    {"dictA",
                     config::dict{{"goodbye", config::set{{123, 456, 789}}}, {"hello", 123}}},
                    {"dictB",
                     config::dict{
                             {"added", 9999},
                             {"answer", 42},
                             {"changed", 1},
                             {"nested", config::dict{{"a", 1}}}}},
                    {"good", config::set{{99, 123, "Foo", "bar"}}},
                    {"int1", 5},
                    {"int2", 2},
                    {"string2", "hello"},
                    {"string3", "omg"}}));

    // clang-format off
    CHECK(printable(m127.serialize()) == printable(
        "d"
          "1:#" "i127e"
          "1:&" "d"
            "5:dictA" "d"
              "7:goodbye" "l" "i123e" "i456e" "i789e" "e"
              "5:hello" "i123e"
            "e"
            "5:dictB" "d"
              "5:added" "i9999e"
              "6:answer" "i42e"
              "7:changed" "i1e"
              "6:nested" "d"
                "1:a" "i1e"
              "e"
            "e"
            "4:good" "l"
              "i99e" "i123e" "3:Foo" "3:bar"
            "e"
            "4:int1" "i5e"
            "4:int2" "i2e"
            "7:string2" "5:hello"
            "7:string3" "3:omg"
          "e"
          "1:<" "l"
            "l"
              "i123e"
              "32:"_bytes+h123+
              "d"
                "4:int0" "1:-"
                "4:int1" "0:"
                "4:int2" "0:"
              "e"
            "e"
            "l"
              "i124e"
              "32:"_bytes+ustring{view(m124a.hash())}+
              "d"
                "5:dictB" "d"
                  "6:answer" "0:"
                  "3:foo" "0:"
                "e"
              "e"
            "e"
            "l"
              "i124e"
              "32:"_bytes+h124+
              "d"
                "5:dictA" "d"
                  "7:goodbye" "l" "l" "i123e" "i456e" "e" "le" "e"
                  "5:hello" "0:"
                "e"
                "5:dictB" "d"
                  "5:added" "0:"
                  "7:changed" "0:"
                  "6:nested" "d"
                    "1:a" "0:"
                  "e"
                  "7:removed" "1:-"
                  "8:removed2" "1:-"
                "e"
                "5:dictC" "d"
                  "1:x" "d"
                    "1:y" "1:-"
                  "e"
                "e"
                "4:good" "l"
                  "l" "i123e" "3:Foo" "e"
                  "l" "i456e" "e"
                "e"
                "5:great" "l"
                  "le"
                  "l" "i-42e" "3:omg" "e"
                "e"
                "4:int1" "0:"
                "7:string1" "1:-"
                "7:string2" "0:"
                "7:string3" "0:"
              "e"
            "e"
            "l"
              "i125e"
              "32:"_bytes+h125a+
              "d"
                "5:dictB" "d"
                  "3:foo" "1:-"
                "e"
              "e"
            "e"
            "l"
              "i125e"
              "32:"_bytes+h125b+
              "d" "4:int1" "0:" "e"
            "e"
            "l" "i126e" "32:"_bytes+ustring{view(m126a.hash())}+ "de" "e"
            "l" "i126e" "32:"_bytes+ustring{view(m126b.hash())}+ "de" "e"
          "e"
          "1:=" "d"
            "5:dictA" "d"
              "7:goodbye" "l" "li789ee" "le" "e"
            "e"
          "e"
        "e"_bytes));
    // clang-format on

    ConfigMessage m_alt1{{m127.serialize(), m125a.serialize(), m126b.serialize()}};
    CHECK(m_alt1.seqno() == 127);
    CHECK(m_alt1.hash() == m127.hash());
}
