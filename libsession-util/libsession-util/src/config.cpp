#include "session/config.hpp"

#include <oxenc/bt_producer.h>
#include <oxenc/bt_serialize.h>
#include <oxenc/bt_value_producer.h>
#include <oxenc/endian.h>
#include <oxenc/variant.h>
#include <sodium/crypto_aead_xchacha20poly1305.h>
#include <sodium/crypto_generichash_blake2b.h>

#include <limits>
#include <optional>
#include <stdexcept>
#include <type_traits>
#include <utility>
#include <variant>

#include "config/internal.hpp"
#include "session/bt_merge.hpp"
#include "session/util.hpp"

using namespace std::literals;

namespace session::config {

namespace {

    // prune functions: return a pair: the first value is true iff, after pruning any subvalues, the
    // value should be removed from its parent.  The second value is true if any nested subkeys
    // anywhere under it were removed.
    std::pair<bool, bool> prune_(dict_value& v);
    std::pair<bool, bool> prune_(dict& d) {
        std::pair<bool, bool> result{false, false};
        auto& [should_remove, removed_subkeys] = result;
        for (auto it = d.begin(); it != d.end();) {
            auto [rm_key, rm_subkeys] = prune_(it->second);
            if (rm_key || rm_subkeys)
                removed_subkeys = true;
            if (rm_key)
                it = d.erase(it);
            else
                ++it;
        }
        should_remove = d.empty();
        return result;
    }
    std::pair<bool, bool> prune_(scalar&) {
        return {false, false};
    }
    std::pair<bool, bool> prune_(set& s) {
        return {s.empty(), false};
    }
    std::pair<bool, bool> prune_(dict_value& v) {
        return var::visit([](auto& x) { return prune_(x); }, unwrap(v));
    }

    // diff helper functions
    std::optional<oxenc::bt_list> diff_impl(const set& old, const set& new_) {
        set added, removed;

        auto oldit = old.begin(), newit = new_.begin();

        while (oldit != old.end() || newit != new_.end()) {
            if (oldit == old.end() || (newit != new_.end() && *newit < *oldit)) {
                added.insert(added.end(), *newit);
                ++newit;
            } else if (newit == new_.end() || (oldit != old.end() && *oldit < *newit)) {
                removed.insert(removed.end(), *oldit);
                ++oldit;
            } else {
                // both are set, and equal
                ++newit;
                ++oldit;
            }
        }

        if (added.empty() && removed.empty())
            return std::nullopt;

        oxenc::bt_list additions, removals;
        for (auto& a : added)
            var::visit([&additions](auto& x) { additions.emplace_back(std::move(x)); }, a);
        for (auto& r : removed)
            var::visit([&removals](auto& x) { removals.emplace_back(std::move(x)); }, r);

        return oxenc::bt_list{{std::move(additions)}, {std::move(removals)}};
    }

    std::optional<oxenc::bt_dict> diff_impl(const dict& old, const dict& new_) {
        auto result = std::make_optional<oxenc::bt_dict>();
        auto& df = *result;

        auto oldit = old.begin(), newit = new_.begin();
        while (oldit != old.end() || newit != new_.end()) {
            bool is_new = false;
            if (oldit == old.end() || (newit != new_.end() && newit->first < oldit->first)) {
                // newit is a new item; fall through to handle below

            } else if (newit == new_.end() || (oldit != old.end() && oldit->first < newit->first)) {

                // oldit got removed
                const auto& key = oldit->first;
                if (auto* d = std::get_if<dict>(&oldit->second))
                    df[key] = *diff_impl(*d, {});
                else if (auto* s = std::get_if<set>(&oldit->second))
                    df[key] = *diff_impl(*s, {});
                else
                    df[key] = "-"sv;

                ++oldit;
                continue;

            } else {
                // same key in old and new
                auto& o = oldit->second;
                auto& n = newit->second;
                if (o.index() != n.index()) {
                    // The fundamental type (scalar, dict, set) changed, so we'll treat this as a
                    // new value (which implicitly deletes a value of a wrong type when merging).
                    is_new = true;
                    ++oldit;
                    // fall through to handler below

                } else {
                    const auto& key = newit->first;
                    if (auto* ov = std::get_if<scalar>(&o)) {
                        if (*ov != var::get<scalar>(n))
                            df[key] = ""sv;
                    } else if (auto* dv = std::get_if<dict>(&o)) {
                        if (auto subdiff = diff_impl(*dv, var::get<dict>(n)))
                            df[key] = std::move(*subdiff);
                    } else if (auto subdiff = diff_impl(var::get<set>(o), var::get<set>(n))) {
                        df[key] = std::move(*subdiff);
                    }
                    ++oldit;
                    ++newit;
                    continue;
                }
            }

            // If we're here then either it's a new key, or we're treating it as a new key because
            // the fundamental type changed.
            const auto& key = newit->first;
            if (auto* d = std::get_if<dict>(&newit->second))
                df[key] = *diff_impl({}, *d);
            else if (auto* s = std::get_if<set>(&newit->second))
                df[key] = *diff_impl({}, *s);
            else
                df[key] = ""sv;
            ++newit;
        }

        if (df.empty())
            result.reset();

        return result;
    }

    // Wrapper around oxenc::get_int that returns nullopt if the type is not an integer.
    std::optional<int64_t> get_bt_int(const oxenc::bt_value& v) {
        if (!(std::holds_alternative<int64_t>(v) || std::holds_alternative<uint64_t>(v)))
            return std::nullopt;
        return oxenc::get_int<int64_t>(v);
    }
    // Gets a string_view if the type is a string or string_view, nullopt otherwise.
    constexpr std::optional<std::string_view> get_bt_str(const oxenc::bt_value& v) {
        if (std::holds_alternative<std::string>(v))
            return var::get<std::string>(v);
        if (std::holds_alternative<std::string_view>(v))
            return var::get<std::string_view>(v);
        return std::nullopt;
    }

    // Gets an int or string if the value contains an int or string.  Nullopt if neither.
    std::optional<scalar> get_bt_scalar(const oxenc::bt_value& v) {
        if (auto i = get_bt_int(v))
            return scalar{*i};
        if (auto s = get_bt_str(v))
            return scalar{std::string{*s}};
        return std::nullopt;
    }

    // Checks to make sure a and b are both scalar (int or string), and that a comes before b our
    // required set ordering (i.e. ints before strings, and ints/strings sorted).  Throws on
    // anything invalid or unordered.
    void check_scalar_order(const oxenc::bt_value& a, const oxenc::bt_value& b) {
        auto a_int = get_bt_int(a), b_int = get_bt_int(b);
        auto a_str = get_bt_str(a), b_str = get_bt_str(b);
        if (!(a_int || a_str) || !(b_int || b_str))
            throw config_parse_error{"invalid config set elements: only ints/strings permitted"};
        assert(!(a_int && a_str) && !(b_int && b_str));
        if (a_int && b_str)
            return;
        if (a_str && b_int)
            throw config_parse_error{"invalid config set elements: string before int"};
        if (a_int) {
            if (*a_int >= *b_int)
                throw config_parse_error{"invalid config set elements: unsorted integers"};
        } else {
            if (*a_str >= *b_str)
                throw config_parse_error{"invalid config set elements: unsorted strings"};
        }
    }

    /// Loads and validates diff data
    oxenc::bt_dict load_diff(oxenc::bt_dict_consumer dict) {
        oxenc::bt_dict result;
        while (!dict.is_finished()) {
            std::string key{dict.key()};
            if (!result.empty() && key <= result.rbegin()->first)
                throw oxenc::bt_deserialize_invalid{"Diff keys are not correctly ordered"};
            if (dict.is_string()) {  // scalar assigned ("") or deleted ("-")
                auto mode = dict.consume_string();
                if (!(mode == "" || mode == "-"))
                    throw config_parse_error{
                            "config diff contains invalid dict pair " + key + "=" + mode};
                result.emplace_hint(result.end(), std::move(key), std::move(mode));
            } else if (dict.is_list()) {
                // A list must be a pair of sub-lists: the first added elements, the second is
                // removed elements.  Within [added] and [removed] elements must be scalar (strings
                // or ints) and sorted: ints before strings, and ints/strings sorted naturally.
                auto changed = dict.consume_list();
                if (changed.size() != 2)
                    throw config_parse_error{
                            "config diff contains invalid set at " + key + ": expected 2 elements"};

                for (const auto& sublist : changed) {
                    auto* elems = std::get_if<oxenc::bt_list>(&sublist);
                    if (!elems)
                        throw config_parse_error{
                                "config diff contains invalid set at " + key +
                                ": expected 2 sub-lists"};
                    if (elems->empty())
                        continue;
                    for (auto i = elems->begin(), j = next(i); j != elems->end(); i = j++)
                        check_scalar_order(*i, *j);
                }
                result.emplace_hint(result.end(), std::move(key), std::move(changed));
            } else if (dict.is_dict()) {
                // Subdict indicates changes within the same subdict
                result.emplace_hint(
                        result.end(), std::move(key), load_diff(dict.consume_dict_consumer()));
            }
        }
        return result;
    }

    void serialize_data(oxenc::bt_list_producer&& out, const set& s);
    void serialize_data(oxenc::bt_dict_producer&& out, const dict& d) {
        for (const auto& pair : d) {
            var::visit(
                    [&](const auto& v) {
                        auto& k = pair.first;
                        using T = std::remove_cv_t<std::remove_reference_t<decltype(v)>>;
                        if constexpr (std::is_same_v<T, dict>)
                            serialize_data(out.append_dict(k), v);
                        else if constexpr (std::is_same_v<T, set>)
                            serialize_data(out.append_list(k), v);
                        else
                            var::visit(
                                    [&](const auto& scalar) { out.append(pair.first, scalar); }, v);
                    },
                    unwrap(pair.second));
        }
    }
    void serialize_data(oxenc::bt_list_producer&& out, const set& s) {
        for (auto& val : s)
            var::visit([&](const auto& scalar) { out.append(scalar); }, val);
    }

    void parse_data(set& s, oxenc::bt_list_consumer in);
    void parse_data(dict& d, oxenc::bt_dict_consumer in, bool top_level = false) {
        if (!top_level && in.is_finished())
            throw oxenc::bt_deserialize_invalid{"Data contains an unpruned, empty dict"};
        while (!in.is_finished()) {
            std::string key{in.key()};
            if (!d.empty() && key <= d.rbegin()->first)
                throw oxenc::bt_deserialize_invalid{"Data keys are not correctly ordered"};
            if (in.is_string())
                d.emplace_hint(d.end(), std::move(key), in.consume_string());
            else if (in.is_integer())
                d.emplace_hint(d.end(), std::move(key), in.consume_integer<int64_t>());
            else if (in.is_dict()) {
                auto it = d.emplace_hint(d.end(), std::move(key), dict{});
                parse_data(var::get<dict>(it->second), in.consume_dict_consumer());
            } else if (in.is_list()) {
                auto it = d.emplace_hint(d.end(), std::move(key), set{});
                parse_data(var::get<set>(it->second), in.consume_list_consumer());
            } else {
                throw oxenc::bt_deserialize_invalid{"Data contains invalid bencoded value type"};
            }
        }
    }

    void parse_data(set& s, oxenc::bt_list_consumer in) {
        if (in.is_finished())
            throw oxenc::bt_deserialize_invalid{"Data contains an unpruned, empty set"};
        while (!in.is_finished()) {
            scalar val;
            if (in.is_integer())
                val = in.consume_integer<int64_t>();
            else if (in.is_string())
                val = in.consume_string();
            else
                throw config_parse_error{"Data contains a set with a non-scalar value"};
            if (!s.empty() && val == *s.rbegin())
                throw config_parse_error{"Data contains a set with duplicates"};
            if (!s.empty() && val < *s.rbegin())
                throw config_parse_error{"Data contains an unsorted set"};
            s.insert(s.end(), std::move(val));
        }
    }

    void parse_lagged_diffs(
            ConfigMessage::lagged_diffs_t& lagged_diffs,
            oxenc::bt_list_consumer in,
            int64_t curr_seqno,
            int lag) {

        while (!in.is_finished()) {
            auto sublist = in.consume_list_consumer();  // Throws if not a list
            seqno_hash_t seqno_hash{};
            auto& [seqno, hash] = seqno_hash;
            seqno = sublist.consume_integer<int64_t>();
            if (seqno >= curr_seqno)
                throw config_parse_error{"Data contains lagged seqno >= current seqno"};
            else if (seqno <= curr_seqno - lag)
                continue;  // Diff too old, so drop it.
            if (auto hash_str = sublist.consume_string_view(); hash_str.size() == hash.size())
                std::memcpy(hash.data(), hash_str.data(), hash.size());
            else
                throw config_parse_error{
                        "Data contains invalid lagged diff data: hash must be 32 bytes"};

            if (!lagged_diffs.empty() && seqno_hash <= lagged_diffs.rbegin()->first)
                throw config_parse_error{"Data contained unsorted or duplicate lagged diff rows"};

            auto diff = load_diff(sublist.consume_dict_consumer());  // Throws if not dict
            if (!sublist.is_finished())
                throw config_parse_error{
                        "Data contains invalid lagged diff tuple: expected 3 elements"};

            lagged_diffs.emplace_hint(lagged_diffs.end(), std::move(seqno_hash), std::move(diff));
        }
    }

    std::string_view view(const hash_t& hash) {
        return std::string_view{reinterpret_cast<const char*>(hash.data()), hash.size()};
    }

    hash_t& hash_msg(hash_t& into, ustring_view serialized) {
        crypto_generichash_blake2b(
                into.data(), into.size(), serialized.data(), serialized.size(), nullptr, 0);
        return into;
    }

    /// Applies a diff update to `data`, getting diff info from `diff` and diff data from `source`.
    /// NB: this doesn't clear empty sets/hashes, which needs to be done after applying all diffs.
    void apply_diff(dict& data, const oxenc::bt_dict& diff, const dict& source) {
        for (const auto& [k, v] : diff) {

            auto source_it = source.find(k);
            auto scalar_diff = get_bt_str(v);
            auto* set_diff = std::get_if<oxenc::bt_list>(&v);
            auto* dict_diff = std::get_if<oxenc::bt_dict>(&v);

            if (source_it == source.end() ||
                (scalar_diff && !std::holds_alternative<scalar>(source_it->second)) ||
                (set_diff && !std::holds_alternative<set>(source_it->second)) ||
                (dict_diff && !std::holds_alternative<dict>(source_it->second))) {
                // Either the referenced value no longer exists in the source data, or it doesn't
                // have a type that matches the type indicated by the diff, which means a later diff
                // must either remove it or change the type (which effectively removes it and
                // replaces with the new type).  In either case we want to remove it from data now:
                // a later diff is either going to remove it (in which case removing it now doesn't
                // hurt) or is going to change its type.  But this later case requires us to delete
                // now (rather than skip it) so that a change sequence such as:
                // {...dict1...} -> 42 -> {...dict2...}
                // ends up with just the dict2 values (because the 42 assignment would have deleted
                // the old dict).  If we skip, we could get dict1+dict2 merged together.
                data.erase(k);
                continue;
            }

            auto& source_val = source_it->second;

            if (scalar_diff) {
                if (*scalar_diff == "-")
                    data.erase(k);
                else if (*scalar_diff == "")
                    data[k] = var::get<scalar>(source_val);
                else
                    throw config_error{
                            "Invalid diff value to apply at key " + k + ": expected '' or '-'"};
            } else if (dict_diff) {
                auto& subdict = data[k];
                if (!std::holds_alternative<dict>(subdict))
                    // Either we autovivified it, or it changed type; in either case we start it out
                    // with an empty dict.
                    subdict = dict{};

                apply_diff(var::get<dict>(subdict), *dict_diff, var::get<dict>(source_val));
            } else if (set_diff) {
                auto& elem = data[k];
                if (!std::holds_alternative<set>(elem))
                    // If not a list (or new) replace with a new empty list
                    elem = set{};

                auto& subset = var::get<set>(elem);

                for (const auto& added : var::get<oxenc::bt_list>(*set_diff->begin())) {
                    if (auto s = get_bt_scalar(added))
                        subset.insert(std::move(*s));
                    else
                        throw config_error{"Invalid set diff added value: expected int or scalar"};
                }
                for (const auto& removed :
                     var::get<oxenc::bt_list>(*std::next(set_diff->begin()))) {
                    if (auto s = get_bt_scalar(removed))
                        subset.erase(*s);
                    else
                        throw config_error{
                                "Invalid set diff removed value: expected int or scalar"};
                }
            } else {
                throw config_error{"Invalid diff value type to apply at key " + k};
            }
        }
    }
}  // namespace

void verify_config_sig(
        oxenc::bt_dict_consumer dict,
        ustring_view config_msg,
        const ConfigMessage::verify_callable& verifier,
        std::optional<std::array<unsigned char, 64>>* verified_signature,
        bool trust_signature) {
    ustring_view to_verify, sig;
    if (dict.skip_until("~")) {
        dict.consume_signature([&](ustring_view to_verify, ustring_view sig) {
            if (sig.size() != 64)
                throw signature_error{"Config signature is invalid (not 64B)"};
            if (verifier && !verifier(to_verify, sig))
                throw signature_error{"Config signature failed verification"};
            if (verified_signature && (verifier || trust_signature)) {
                if (!*verified_signature)
                    verified_signature->emplace();
                std::memcpy((*verified_signature)->data(), sig.data(), 64);
            }
        });
    } else if (verifier) {
        throw missing_signature{"Config signature is missing"};
    }

    if (!dict.is_finished())
        throw config_parse_error{"Invalid config: dict has invalid key(s) after \"~\""};
}

bool MutableConfigMessage::prune() {
    return prune_(data_).second;
}

// Called immediately after being copy-constructed from the source object to do the required
// modifications to increment it.
void MutableConfigMessage::increment_impl() {
    orig_data_ = data_;

    auto& lags = lagged_diffs_;

    // Remove any lagged diffs that are too old for the new message.  E.g. if we are becoming seqno
    // 124 and have lag set to 5 then we will have copied diffs for 119 through 122 from the source
    // object (with seqno 123), but we don't want 119 anymore for the new one: we want 120 through
    // 123, the latter of which we copy from 123's current diff.
    for (auto it = lags.begin(); it != lags.end() && it->first.first <= seqno() - lag;)
        it = lags.erase(it);

    // There shouldn't be any seqnos >= the copied-from seqno in here, but check for and remove them
    // just in case.
    for (auto rit = lags.rbegin(); rit != lags.rend() && rit->first.first >= seqno();) {
        ++rit;  // Looks odd to increment *before* the erase, but rit.base() points at the next
                // element (e.g. for rbegin(), the base == end()), so the ++ first puts .base()
                // at the right place for the deletion.
        lags.erase(rit.base());
    }

    // Append the source config's diff to the new object
    lagged_diffs_.emplace_hint(lagged_diffs_.end(), seqno_hash_, std::move(diff_));
    seqno_hash_.first++;
    seqno_hash_.second.fill(0);  // Not strictly necessary, but makes it obvious if used
    diff_.clear();
}

MutableConfigMessage::MutableConfigMessage(ConfigMessage&& m, const retain_seqno_t&) {
    // We do the derived class cast here rather than using two overloaded constructors so that the
    // *caller* can give us a ConfigMessage reference without worrying about whether it is actually
    // a MutableConfigMessage under the hood.
    if (auto* mut = dynamic_cast<MutableConfigMessage*>(&m)) {
        *this = std::move(*mut);
    } else {
        ConfigMessage::operator=(std::move(m));
    }
}

MutableConfigMessage::MutableConfigMessage(const ConfigMessage& m, const increment_seqno_t&) {
    // We do the derived class cast here rather than using two overloaded constructors so that the
    // *caller* can give us a ConfigMessage reference without worrying about whether it is actually
    // a MutableConfigMessage under the hood.
    if (auto* mut = dynamic_cast<const MutableConfigMessage*>(&m)) {
        *this = *mut;
        hash();
    } else {
        ConfigMessage::operator=(m);
    }
    increment_impl();
}

MutableConfigMessage ConfigMessage::increment() const {
    return MutableConfigMessage{*this, increment_seqno};
}

MutableConfigMessage MutableConfigMessage::increment() const {
    return MutableConfigMessage{*this, increment_seqno};
}

ConfigMessage::ConfigMessage() {
    hash_msg(seqno_hash_.second, serialize());
}

ConfigMessage::ConfigMessage(
        ustring_view serialized,
        verify_callable verifier_,
        sign_callable signer_,
        int lag,
        bool trust_signature) :
        verifier{std::move(verifier_)}, signer{std::move(signer_)}, lag{lag} {

    oxenc::bt_dict_consumer dict{from_unsigned_sv(serialized)};

    try {
        hash_msg(seqno_hash_.second, serialized);

        if (auto [k, v] = dict.next_integer<int64_t>(); k == "#")
            seqno_hash_.first = v;
        else
            throw config_parse_error{"Invalid config: first key must be \"#\""};
        load_unknowns(unknown_, dict, "#", "&");
        if (auto [k, data] = dict.next_dict_consumer(); k == "&")
            parse_data(data_, std::move(data), /*top_level=*/true);
        else
            throw config_parse_error{"Invalid config: \"&\" data dict not found"};
        load_unknowns(unknown_, dict, "&", "<");
        if (dict.key() == "<")
            parse_lagged_diffs(lagged_diffs_, dict.consume_list_consumer(), seqno(), lag);
        load_unknowns(unknown_, dict, "<", "=");

        if (dict.key() == "=")
            diff_ = load_diff(dict.consume_dict_consumer());

        load_unknowns(unknown_, dict, "=", "~");

        verify_config_sig(dict, serialized, verifier, &verified_signature_, trust_signature);
    } catch (const oxenc::bt_deserialize_invalid& err) {
        throw config_parse_error{"Failed to parse config file: "s + err.what()};
    }
}

ConfigMessage::ConfigMessage(
        const std::vector<ustring_view>& serialized_confs,
        verify_callable verifier_,
        sign_callable signer_,
        int lag,
        std::function<void(size_t, const config_error&)> error_handler) :
        verifier{std::move(verifier_)}, signer{std::move(signer_)}, lag{lag} {

    std::vector<std::pair<ConfigMessage, bool>> configs;  // [[config, redundant], ...]
    for (size_t i = 0; i < serialized_confs.size(); i++) {
        const auto& data = serialized_confs[i];
        try {
            ConfigMessage m{data, verifier, signer, lag};
            configs.emplace_back(std::move(m), false);
        } catch (const config_error& e) {
            if (error_handler)
                error_handler(i, e);
            // If we survive the error handler then we just skip it
            continue;
        }
    }
    if (configs.empty())
        throw config_error{"Config initialization failed: no valid config messages given"};

    int64_t max_seqno = std::numeric_limits<int64_t>::min();

    // prune out redundant messages (i.e. messages already included in another message's diff, and
    // duplicates)
    for (int i = 0; i < configs.size(); i++) {
        auto& [conf, redundant] = configs[i];
        if (conf.seqno() > max_seqno)
            max_seqno = conf.seqno();

        for (int j = 0; !redundant && j < configs.size(); j++) {
            if (i == j)
                continue;
            const auto& conf2 = configs[j].first;
            if (conf2.lagged_diffs_.count(conf.seqno_hash_))
                redundant = true;  // conf[j] includes conf[i], so we don't need to keep [i]
            else if (j < i && conf2.seqno_hash_ == conf.seqno_hash_)
                redundant = true;  // Duplicate: some earlier conf[j] has the same seqno/hash
        }
    }

    // prune out any messages that are too old (i.e. `lag` or more behind the top seqno value)
    for (auto& [conf, redundant] : configs)
        if (conf.seqno() + lag <= max_seqno)
            redundant = true;

    size_t curr_confs =
            std::count_if(configs.begin(), configs.end(), [](const auto& c) { return !c.second; });
    assert(curr_confs >= 1);

    if (curr_confs == 1) {
        // We have just one non-redundant config left after all that, so we become it directly as-is
        for (int i = 0; i < configs.size(); i++) {
            if (!configs[i].second) {
                *this = std::move(configs[i].first);
                unmerged_ = i;
                return;
            }
        }
        assert(!"we counted one good config but couldn't find it?!");
    }

    // Otherwise we have more than one valid config, so have to merge them.

    // ... Unless we require signature verification but can't sign, in which case  we can't actually
    // produce a proper merge, so we will just keep the highest (highest seqno, hash) config and use
    // that, dropping the rest.  Someone else (with signing power) will have to merge and push the
    // merge out to us.
    if (verifier && !signer) {
        auto best_it =
                std::max_element(configs.begin(), configs.end(), [](const auto& a, const auto& b) {
                    if (a.second != b.second)  // Exactly one of the two is redundant
                        return a.second;       // a < b iff a is redundant
                    return a.first.seqno_hash_ < b.first.seqno_hash_;
                });
        *this = std::move(best_it->first);
        unmerged_ = std::distance(configs.begin(), best_it);
        return;
    }

    unmerged_ = -1;

    // Clear any redundant messages. (we do it *here* rather than above because, in the
    // single-good-config case, above, we need the index of the good config for `unmerged_`).
    configs.erase(
            std::remove_if(configs.begin(), configs.end(), [](const auto& c) { return c.second; }),
            configs.end());

    // Sort whatever is left by seqno/hash in *descending* order for diff processing (descending
    // order so that higher seqno/hash configs get precedence if multiple merged configs have the
    // same change).
    std::sort(configs.begin(), configs.end(), [](const auto& a, const auto& b) {
        return a.first.seqno_hash_ > b.first.seqno_hash_;
    });

    seqno_hash_.first = max_seqno + 1;

    data_ = configs.front().first.data_;

    std::map<seqno_hash_t, std::pair<const dict*, const oxenc::bt_dict*>> replay;
    // We walk these in reverse order so that the value from the higher seqno/hash message gets
    // precedence if we merge two messages with a common ancestor.
    for (const auto& [conf, _ignored] : configs) {
        replay.emplace(conf.seqno_hash_, std::make_pair(&conf.data_, &conf.diff_));

        for (const auto& [s_h, diff] : conf.lagged_diffs_)
            // We rely on emplace not replacing here (i.e. if something else already set it then it
            // is the one we want to keep).
            replay.emplace(s_h, std::make_pair(&conf.data_, &diff));
    }

    // Now we apply the diffs, in ascending order so that changes from later diffs overwrite earlier
    // ones
    for (const auto& [seqno_hash, ptrs] : replay) {
        const auto& [data, diff] = ptrs;
        apply_diff(data_, *diff, *data);
        lagged_diffs_.emplace_hint(lagged_diffs_.end(), seqno_hash, *diff);
    }

    // remove any sets/dicts that ended up empty after the change:
    prune_(data_);

    // Compute our own hash now that we've loaded everything:
    hash_msg(seqno_hash_.second, serialize_impl(diff_));
}

MutableConfigMessage::MutableConfigMessage(
        const std::vector<ustring_view>& serialized_confs,
        verify_callable verifier,
        sign_callable signer,
        int lag,
        std::function<void(size_t, const config_error&)> error_handler) :
        ConfigMessage{
                serialized_confs,
                std::move(verifier),
                std::move(signer),
                lag,
                std::move(error_handler)} {
    if (!merged())
        increment_impl();
}

MutableConfigMessage::MutableConfigMessage(
        ustring_view config, verify_callable verifier, sign_callable signer, int lag) :
        MutableConfigMessage{
                std::vector{{config}},
                std::move(verifier),
                std::move(signer),
                lag,
                [](size_t, const config_error& e) { throw e; }} {}

const oxenc::bt_dict& ConfigMessage::diff() {
    return diff_;
}

const oxenc::bt_dict& MutableConfigMessage::diff() {
    verified_signature_.reset();
    prune();
    diff_ = diff_impl(orig_data_, data_).value_or(oxenc::bt_dict{});
    return diff_;
}

ustring ConfigMessage::serialize(bool enable_signing) {
    return serialize_impl(
            diff(),  // implicitly prunes (if actually a mutable instance)
            enable_signing);
}

ustring ConfigMessage::serialize_impl(const oxenc::bt_dict& curr_diff, bool enable_signing) {
    oxenc::bt_dict_producer outer{};

    outer.append("#", seqno());

    auto unknown_it = append_unknown(outer, unknown_.begin(), unknown_.end(), "&");

    serialize_data(outer.append_dict("&"), data_);

    unknown_it = append_unknown(outer, unknown_it, unknown_.end(), "<");

    {
        auto lags = outer.append_list("<");
        for (auto& [seqno_hash, lag_data] : lagged_diffs_) {
            const auto& [lag_seqno, lag_hash] = seqno_hash;
            if (lag_seqno <= seqno() - lag || lag_seqno >= seqno())
                continue;
            auto lag = lags.append_list();
            lag.append(lag_seqno);
            lag.append(view(lag_hash));
            lag.append_bt(lag_data);
        }
    }

    unknown_it = append_unknown(outer, unknown_it, unknown_.end(), "=");

    outer.append_bt("=", curr_diff);

    unknown_it = append_unknown(outer, unknown_it, unknown_.end(), "~");
    assert(unknown_it == unknown_.end());

    if (verified_signature_) {
        // We have the signature attached to the current message, so use it.  (This will get cleared
        // if we do anything that changes the config).
        outer.append(
                "~",
                std::string_view{
                        reinterpret_cast<const char*>(verified_signature_->data()),
                        verified_signature_->size()});
    } else if (signer && enable_signing) {
        outer.append_signature("~", [this](ustring_view to_sign) {
            auto sig = signer(to_sign);
            if (sig.size() != 64)
                throw std::logic_error{
                        "Invalid signature: signing function did not return 64 bytes"};
            return sig;
        });
    }
    return ustring{to_unsigned_sv(outer.view())};
}

const hash_t& MutableConfigMessage::hash() {
    return hash(serialize());
}
const hash_t& MutableConfigMessage::hash(ustring_view serialized) {
    return hash_msg(seqno_hash_.second, serialized);
}

}  // namespace session::config
