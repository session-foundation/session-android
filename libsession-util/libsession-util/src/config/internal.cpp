#include "internal.hpp"

#include <oxenc/base32z.h>
#include <oxenc/base64.h>
#include <oxenc/bt_value_producer.h>
#include <oxenc/hex.h>
#include <zstd.h>

#include <iterator>
#include <optional>

namespace session::config {

void check_session_id(std::string_view session_id, std::string_view prefix) {
    if (!(session_id.size() == 64 + prefix.size() && oxenc::is_hex(session_id) &&
          session_id.substr(0, prefix.size()) == prefix))
        throw std::invalid_argument{
                "Invalid session ID: expected 66 hex digits starting with " + std::string{prefix} +
                "; got " + std::string{session_id}};
}

std::string session_id_to_bytes(std::string_view session_id, std::string_view prefix) {
    check_session_id(session_id, prefix);
    return oxenc::from_hex(session_id);
}

std::array<unsigned char, 32> session_id_pk(std::string_view session_id, std::string_view prefix) {
    check_session_id(session_id, prefix);
    std::array<unsigned char, 32> pk;
    session_id.remove_prefix(2);
    oxenc::from_hex(session_id.begin(), session_id.end(), pk.begin());
    return pk;
}

void check_encoded_pubkey(std::string_view pk) {
    if (!((pk.size() == 64 && oxenc::is_hex(pk)) ||
          ((pk.size() == 43 || (pk.size() == 44 && pk.back() == '=')) && oxenc::is_base64(pk)) ||
          (pk.size() == 52 && oxenc::is_base32z(pk))))
        throw std::invalid_argument{"Invalid encoded pubkey: expected hex, base32z or base64"};
}

ustring decode_pubkey(std::string_view pk) {
    session::ustring pubkey;
    pubkey.reserve(32);
    if (pk.size() == 64 && oxenc::is_hex(pk))
        oxenc::from_hex(pk.begin(), pk.end(), std::back_inserter(pubkey));
    else if ((pk.size() == 43 || (pk.size() == 44 && pk.back() == '=')) && oxenc::is_base64(pk))
        oxenc::from_base64(pk.begin(), pk.end(), std::back_inserter(pubkey));
    else if (pk.size() == 52 && oxenc::is_base32z(pk))
        oxenc::from_base32z(pk.begin(), pk.end(), std::back_inserter(pubkey));
    else
        throw std::invalid_argument{"Invalid encoded pubkey: expected hex, base32z or base64"};
    return pubkey;
}

void make_lc(std::string& s) {
    for (auto& c : s)
        if (c >= 'A' && c <= 'Z')
            c += ('a' - 'A');
}

template <typename Scalar>
const Scalar* maybe_scalar(const session::config::dict& d, const char* key) {
    if (auto it = d.find(key); it != d.end())
        if (auto* sc = std::get_if<session::config::scalar>(&it->second))
            if (auto* i = std::get_if<Scalar>(sc))
                return i;
    return nullptr;
}

const session::config::set* maybe_set(const session::config::dict& d, const char* key) {
    if (auto it = d.find(key); it != d.end())
        if (auto* s = std::get_if<session::config::set>(&it->second))
            return s;
    return nullptr;
}

std::optional<int64_t> maybe_int(const session::config::dict& d, const char* key) {
    if (auto* i = maybe_scalar<int64_t>(d, key))
        return *i;
    return std::nullopt;
}

std::optional<std::string> maybe_string(const session::config::dict& d, const char* key) {
    if (auto* s = maybe_scalar<std::string>(d, key))
        return *s;
    return std::nullopt;
}

std::optional<std::string_view> maybe_sv(const session::config::dict& d, const char* key) {
    if (auto* s = maybe_scalar<std::string>(d, key))
        return *s;
    return std::nullopt;
}

std::optional<ustring> maybe_ustring(const session::config::dict& d, const char* key) {
    std::optional<ustring> result;
    if (auto* s = maybe_scalar<std::string>(d, key))
        result.emplace(reinterpret_cast<const unsigned char*>(s->data()), s->size());
    return result;
}

void set_flag(ConfigBase::DictFieldProxy&& field, bool val) {
    if (val)
        field = 1;
    else
        field.erase();
}

void set_positive_int(ConfigBase::DictFieldProxy&& field, int64_t val) {
    if (val > 0)
        field = val;
    else
        field.erase();
}

void set_nonzero_int(ConfigBase::DictFieldProxy&& field, int64_t val) {
    if (val != 0)
        field = val;
    else
        field.erase();
}

void set_nonempty_str(ConfigBase::DictFieldProxy&& field, std::string val) {
    if (!val.empty())
        field = std::move(val);
    else
        field.erase();
}

void set_nonempty_str(ConfigBase::DictFieldProxy&& field, std::string_view val) {
    if (!val.empty())
        field = val;
    else
        field.erase();
}

/// Writes all the dict elements in `[it, E)` into `out`; E is whichever of `end` or an element with
/// a key >= `until` comes first.
oxenc::bt_dict::iterator append_unknown(
        oxenc::bt_dict_producer& out,
        oxenc::bt_dict::iterator it,
        oxenc::bt_dict::iterator end,
        std::string_view until) {
    for (; it != end && it->first < until; ++it)
        out.append_bt(it->first, it->second);

    assert(!(it != end && it->first == until));
    return it;
}

/// Extracts and unknown keys in the top-level dict into `unknown` that have keys (strictly)
/// between previous and until.
void load_unknowns(
        oxenc::bt_dict& unknown,
        oxenc::bt_dict_consumer& in,
        std::string_view previous,
        std::string_view until) {
    while (!in.is_finished() && in.key() < until) {
        std::string key{in.key()};
        if (key <= previous || (!unknown.empty() && key <= unknown.rbegin()->first))
            throw oxenc::bt_deserialize_invalid{"top-level keys are out of order"};
        if (in.is_string())
            unknown.emplace_hint(unknown.end(), std::move(key), in.consume_string());
        else if (in.is_negative_integer())
            unknown.emplace_hint(unknown.end(), std::move(key), in.consume_integer<int64_t>());
        else if (in.is_integer())
            unknown.emplace_hint(unknown.end(), std::move(key), in.consume_integer<uint64_t>());
        else if (in.is_list())
            unknown.emplace_hint(unknown.end(), std::move(key), in.consume_list());
        else if (in.is_dict())
            unknown.emplace_hint(unknown.end(), std::move(key), in.consume_dict());
        else
            throw oxenc::bt_deserialize_invalid{"invalid bencoded value type"};
    }
}

namespace {
    struct zstd_decomp_freer {
        void operator()(ZSTD_DStream* z) const { ZSTD_freeDStream(z); }
    };

    using zstd_decomp_ptr = std::unique_ptr<ZSTD_DStream, zstd_decomp_freer>;
}  // namespace

ustring zstd_compress(ustring_view data, int level, ustring_view prefix) {
    ustring compressed;
    if (prefix.empty())
        compressed.resize(ZSTD_compressBound(data.size()));
    else {
        compressed.resize(prefix.size() + ZSTD_compressBound(data.size()));
        compressed.replace(0, prefix.size(), prefix);
    }
    auto size = ZSTD_compress(
            compressed.data() + prefix.size(),
            compressed.size() - prefix.size(),
            data.data(),
            data.size(),
            level);
    if (ZSTD_isError(size))
        throw std::runtime_error{"Compression failed: " + std::string{ZSTD_getErrorName(size)}};

    compressed.resize(prefix.size() + size);
    return compressed;
}

std::optional<ustring> zstd_decompress(ustring_view data, size_t max_size) {
    zstd_decomp_ptr z_decompressor{ZSTD_createDStream()};
    auto* zds = z_decompressor.get();

    ZSTD_initDStream(zds);
    ZSTD_inBuffer input{/*.src=*/data.data(), /*.size=*/data.size(), /*.pos=*/0};
    std::array<unsigned char, 4096> out_buf;
    ZSTD_outBuffer output{/*.dst=*/out_buf.data(), /*.size=*/out_buf.size()};

    ustring decompressed;

    size_t ret;
    do {
        output.pos = 0;
        if (ret = ZSTD_decompressStream(zds, &output, &input); ZSTD_isError(ret))
            return std::nullopt;

        if (max_size > 0 && decompressed.size() + output.pos > max_size)
            return std::nullopt;

        decompressed.append(out_buf.data(), output.pos);
    } while (ret > 0 || input.pos < input.size);

    return decompressed;
}

}  // namespace session::config
