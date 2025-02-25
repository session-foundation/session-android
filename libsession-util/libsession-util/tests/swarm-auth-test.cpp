#include <bits/chrono.h>
#include <oxenc/base64.h>
#include <oxenc/endian.h>
#include <oxenc/hex.h>
#include <session/config/groups/info.h>
#include <session/config/groups/keys.h>
#include <session/config/groups/members.h>
#include <sodium/crypto_sign_ed25519.h>

#include <algorithm>
#include <cassert>
#include <chrono>
#include <iostream>
#include <iterator>
#include <nlohmann/json.hpp>
#include <session/config/groups/info.hpp>
#include <session/config/groups/keys.hpp>
#include <session/config/groups/members.hpp>
#include <session/config/user_groups.hpp>
#include <string_view>

#include "utils.hpp"

using namespace std::literals;
using namespace oxenc::literals;

static constexpr int64_t created_ts = 1680064059;

using namespace session::config;

static std::array<unsigned char, 64> sk_from_seed(ustring_view seed) {
    std::array<unsigned char, 32> ignore;
    std::array<unsigned char, 64> sk;
    crypto_sign_ed25519_seed_keypair(ignore.data(), sk.data(), seed.data());
    return sk;
}

static std::string session_id_from_ed(ustring_view ed_pk) {
    std::string sid;
    std::array<unsigned char, 32> xpk;
    int rc = crypto_sign_ed25519_pk_to_curve25519(xpk.data(), ed_pk.data());
    assert(rc == 0);
    sid.reserve(66);
    sid += "05";
    oxenc::to_hex(xpk.begin(), xpk.end(), std::back_inserter(sid));
    return sid;
}

struct pseudo_client {
    std::array<unsigned char, 64> secret_key;
    const ustring_view public_key{secret_key.data() + 32, 32};
    std::string session_id{session_id_from_ed(public_key)};

    groups::Info info;
    groups::Members members;
    groups::Keys keys;

    pseudo_client(
            ustring_view seed,
            bool admin,
            const unsigned char* gpk,
            std::optional<const unsigned char*> gsk) :
            secret_key{sk_from_seed(seed)},
            info{ustring_view{gpk, 32},
                 admin ? std::make_optional<ustring_view>({*gsk, 64}) : std::nullopt,
                 std::nullopt},
            members{ustring_view{gpk, 32},
                    admin ? std::make_optional<ustring_view>({*gsk, 64}) : std::nullopt,
                    std::nullopt},
            keys{to_usv(secret_key),
                 ustring_view{gpk, 32},
                 admin ? std::make_optional<ustring_view>({*gsk, 64}) : std::nullopt,
                 std::nullopt,
                 info,
                 members} {}
};

int main() {

    const ustring group_seed =
            "0123456789abcdeffedcba98765432100123456789abcdeffedcba9876543210"_hexbytes;
    const ustring admin_seed =
            "0123456789abcdef0123456789abcdeffedcba9876543210fedcba9876543210"_hexbytes;
    const ustring member_seed =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"_hexbytes;

    std::array<unsigned char, 32> group_pk;
    std::array<unsigned char, 64> group_sk;

    crypto_sign_ed25519_seed_keypair(group_pk.data(), group_sk.data(), group_seed.data());

    pseudo_client admin{admin_seed, true, group_pk.data(), group_sk.data()};
    pseudo_client member{member_seed, false, group_pk.data(), std::nullopt};
    session::config::UserGroups member_groups{member_seed, std::nullopt};

    auto auth_data = admin.keys.swarm_make_subaccount(member.session_id);
    {
        auto g = member_groups.get_or_construct_group(member.info.id);
        g.auth_data = auth_data;
        member_groups.set(g);
    }

    session::config::UserGroups member_gr2{member_seed, std::nullopt};
    auto [seqno, push, obs] = member_groups.push();

    std::vector<std::pair<std::string, ustring_view>> gr_conf;
    gr_conf.emplace_back("fakehash1", push);

    member_gr2.merge(gr_conf);

    auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::system_clock::now().time_since_epoch())
                       .count();

    auto msg = to_usv("hello world");
    std::array<unsigned char, 64> store_sig;
    ustring store_to_sign;
    store_to_sign += to_usv("store999");
    store_to_sign += to_usv(std::to_string(now));
    crypto_sign_ed25519_detached(
            store_sig.data(), nullptr, store_to_sign.data(), store_to_sign.size(), group_sk.data());

    nlohmann::json store{
            {"method", "store"},
            {"params",
             {{"pubkey", member.info.id},
              {"namespace", 999},
              {"timestamp", now},
              {"ttl", 3600'000},
              {"data", oxenc::to_base64(msg)},
              {"signature", oxenc::to_base64(store_sig.begin(), store_sig.end())}}}};

    std::cout << "STORE:\n\n" << store.dump() << "\n\n";

    ustring retrieve_to_sign;
    retrieve_to_sign += to_usv("retrieve999");
    retrieve_to_sign += to_usv(std::to_string(now));
    auto subauth = member.keys.swarm_subaccount_sign(retrieve_to_sign, auth_data);

    nlohmann::json retrieve{
            {"method", "retrieve"},
            {"params",
             {
                     {"pubkey", member.info.id},
                     {"namespace", 999},
                     {"timestamp", now},
                     {"subaccount", subauth.subaccount},
                     {"subaccount_sig", subauth.subaccount_sig},
                     {"signature", subauth.signature},
             }}};

    std::cout << "RETRIEVE:\n\n" << retrieve.dump() << "\n\n";
}
