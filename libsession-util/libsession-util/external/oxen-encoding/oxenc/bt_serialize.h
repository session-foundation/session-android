#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <optional>
#include <ostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>
#include <vector>

#include "bt_value.h"
#include "variant.h"

namespace oxenc {

using namespace std::literals;

/** \file
 * Oxenc serialization for internal commands is very simple: we support two primitive types,
 * strings and integers, and two container types, lists and dicts with string keys.  On the wire
 * these go in BitTorrent byte encoding as described in BEP-0003
 * (https://www.bittorrent.org/beps/bep_0003.html#bencoding).
 *
 * On the C++ side, on input we allow strings, integral types, STL-like containers of these types,
 * and STL-like containers of pairs with a string first value and any of these types as second
 * value.  We also accept std::variants of these.
 *
 * One minor deviation from BEP-0003 is that we don't support serializing values that don't fit in a
 * 64-bit integer (BEP-0003 specifies arbitrary precision integers).
 *
 * On deserialization we can either deserialize into a special bt_value type supports everything
 * (with arbitrary nesting), or we can fill a container of your given type (though this fails if the
 * container isn't compatible with the deserialized data).
 *
 * There is also a stream deserialization that allows you to deserialize without needing heap
 * allocations (as long as you know the precise data structure layout).
 */

/// Exception throw if deserialization fails
class bt_deserialize_invalid : public std::invalid_argument {
    using std::invalid_argument::invalid_argument;
};

/// A more specific subclass that is thown if the serialization type is an initial mismatch: for
/// example, trying deserializing an int but the next thing in input is a list.  This is not,
/// however, thrown if the type initially looks fine but, say, a nested serialization fails.  This
/// error will only be thrown when the input stream has not been advanced (and so can be tried for a
/// different type).
class bt_deserialize_invalid_type : public bt_deserialize_invalid {
    using bt_deserialize_invalid::bt_deserialize_invalid;
};

namespace detail {

    /// Reads digits into an unsigned 64-bit int.
    uint64_t extract_unsigned(std::string_view& s);
    // (Provide non-constant lvalue and rvalue ref functions so that we only accept explicit
    // string_views but not implicitly converted ones)
    inline uint64_t extract_unsigned(std::string_view&& s) {
        return extract_unsigned(s);
    }

    // Fallback base case; we only get here if none of the partial specializations below work
    template <typename T, typename SFINAE = void>
    struct bt_serialize {
        static_assert(
                !std::is_same_v<T, T>, "Cannot serialize T: unsupported type for bt serialization");
    };

    template <typename T, typename SFINAE = void>
    struct bt_deserialize {
        static_assert(
                !std::is_same_v<T, T>,
                "Cannot deserialize T: unsupported type for bt deserialization");
    };

    /// Checks that we aren't at the end of a string view and throws if we are.
    inline void bt_need_more(const std::string_view& s) {
        if (s.empty())
            throw bt_deserialize_invalid{"Unexpected end of string while deserializing"};
    }

    using some64 = union {
        int64_t i64;
        uint64_t u64;
    };

    /// Deserializes a signed or unsigned 64-bit integer from a string.  Sets the second bool to
    /// true iff the value read was negative, false if positive; in either case the unsigned value
    /// is return in .first.  Throws an exception if the read value doesn't fit in a int64_t (if
    /// negative) or a uint64_t (if positive).  Removes consumed characters from the string_view.
    std::pair<some64, bool> bt_deserialize_integer(std::string_view& s);

    /// Integer specializations
    template <typename T>
    struct bt_serialize<T, std::enable_if_t<std::is_integral_v<T>>> {
        static_assert(
                sizeof(T) <= sizeof(uint64_t),
                "Serialization of integers larger than uint64_t is not supported");
        void operator()(std::ostream& os, const T& val) {
            // Cast 1-byte types to a larger type to avoid iostream interpreting them as single
            // characters
            using output_type = std::conditional_t<
                    (sizeof(T) > 1),
                    T,
                    std::conditional_t<std::is_signed_v<T>, int, unsigned>>;
            os << 'i' << static_cast<output_type>(val) << 'e';
        }
    };

    template <typename T>
    struct bt_deserialize<T, std::enable_if_t<std::is_integral_v<T>>> {
        void operator()(std::string_view& s, T& val) {
            constexpr uint64_t umax = static_cast<uint64_t>(std::numeric_limits<T>::max());
            constexpr int64_t smin = static_cast<int64_t>(std::numeric_limits<T>::min());

            auto [v, neg] = bt_deserialize_integer(s);

            if (std::is_signed_v<T>) {
                if (!neg) {
                    if (v.u64 > umax)
                        throw bt_deserialize_invalid(
                                "Integer deserialization failed: found too-large value " +
                                std::to_string(v.u64) + " > " + std::to_string(umax));
                    val = static_cast<T>(v.u64);
                } else {
                    auto& sval = v.i64;
                    if (!std::is_same_v<T, int64_t> && sval < smin)
                        throw bt_deserialize_invalid(
                                "Integer deserialization failed: found too-low value " +
                                std::to_string(sval) + " < " + std::to_string(smin));
                    val = static_cast<T>(sval);
                }
            } else {
                if (neg)
                    throw bt_deserialize_invalid(
                            "Integer deserialization failed: found negative value -" +
                            std::to_string(v.i64) + " but type is unsigned");
                if (!std::is_same_v<T, uint64_t> && v.u64 > umax)
                    throw bt_deserialize_invalid(
                            "Integer deserialization failed: found too-large value " +
                            std::to_string(v.u64) + " > " + std::to_string(umax));
                val = static_cast<T>(v.u64);
            }
        }
    };

    extern template struct bt_deserialize<int64_t>;
    extern template struct bt_deserialize<uint64_t>;

    template <>
    struct bt_serialize<std::string_view> {
        void operator()(std::ostream& os, const std::string_view& val) {
            os << val.size();
            os.put(':');
            os.write(val.data(), val.size());
        }
    };
    template <>
    struct bt_deserialize<std::string_view> {
        void operator()(std::string_view& s, std::string_view& val);
    };

    /// String specialization
    template <>
    struct bt_serialize<std::string> {
        void operator()(std::ostream& os, const std::string& val) {
            bt_serialize<std::string_view>{}(os, val);
        }
    };
    template <>
    struct bt_deserialize<std::string> {
        void operator()(std::string_view& s, std::string& val) {
            std::string_view view;
            bt_deserialize<std::string_view>{}(s, view);
            val = {view.data(), view.size()};
        }
    };

    /// char * and string literals -- we allow serialization for convenience, but not
    /// deserialization
    template <>
    struct bt_serialize<char*> {
        void operator()(std::ostream& os, const char* str) {
            bt_serialize<std::string_view>{}(os, {str, std::strlen(str)});
        }
    };
    template <size_t N>
    struct bt_serialize<char[N]> {
        void operator()(std::ostream& os, const char* str) {
            bt_serialize<std::string_view>{}(os, {str, N - 1});
        }
    };

    /// Partial dict validity; we don't check the second type for serializability, that will be
    /// handled via the base case static_assert if invalid.
    template <typename T, typename = void>
    struct is_bt_input_dict_container_impl : std::false_type {};
    template <typename T>
    struct is_bt_input_dict_container_impl<
            T,
            std::enable_if_t<
                    std::is_same_v<
                            std::string,
                            std::remove_cv_t<typename T::value_type::first_type>> ||
                            std::is_same_v<
                                    std::string_view,
                                    std::remove_cv_t<typename T::value_type::first_type>>,
                    std::void_t<
                            typename T::const_iterator /* is const iterable */,
                            typename T::value_type::second_type /* has a second type */>>>
            : std::true_type {};

    /// Determines whether the type looks like something we can insert into (using
    /// `v.insert(v.end(), x)`)
    template <typename T, typename = void>
    struct is_bt_insertable_impl : std::false_type {};
    template <typename T>
    struct is_bt_insertable_impl<
            T,
            std::void_t<decltype(std::declval<T>().insert(
                    std::declval<T>().end(), std::declval<typename T::value_type>()))>>
            : std::true_type {};
    template <typename T>
    constexpr bool is_bt_insertable = is_bt_insertable_impl<T>::value;

    /// Determines whether the given type looks like a compatible map (i.e. has std::string keys)
    /// that we can insert into.
    template <typename T, typename = void>
    struct is_bt_output_dict_container_impl : std::false_type {};
    template <typename T>
    struct is_bt_output_dict_container_impl<
            T,
            std::enable_if_t<
                    std::is_same_v<
                            std::string,
                            std::remove_cv_t<typename T::value_type::first_type>> &&
                            is_bt_insertable<T>,
                    std::void_t<typename T::value_type::second_type /* has a second type */>>>
            : std::true_type {};

    template <typename T>
    constexpr bool is_bt_output_dict_container = is_bt_output_dict_container_impl<T>::value;
    template <typename T>
    constexpr bool is_bt_input_dict_container = is_bt_output_dict_container_impl<T>::value;

    // Sanity checks:
    static_assert(is_bt_input_dict_container<bt_dict>);
    static_assert(is_bt_output_dict_container<bt_dict>);

    /// Specialization for a dict-like container (such as an unordered_map).  We accept anything for
    /// a dict that is const iterable over something that looks like a pair with std::string for
    /// first value type.  The value (i.e. second element of the pair) also must be serializable.
    template <typename T>
    struct bt_serialize<T, std::enable_if_t<is_bt_input_dict_container<T>>> {
        using second_type = typename T::value_type::second_type;
        using ref_pair = std::reference_wrapper<const typename T::value_type>;
        void operator()(std::ostream& os, const T& dict) {
            os << 'd';
            std::vector<ref_pair> pairs;
            pairs.reserve(dict.size());
            for (const auto& pair : dict)
                pairs.emplace(pairs.end(), pair);
            std::sort(pairs.begin(), pairs.end(), [](ref_pair a, ref_pair b) {
                return a.get().first < b.get().first;
            });
            for (auto& ref : pairs) {
                bt_serialize<std::string>{}(os, ref.get().first);
                bt_serialize<second_type>{}(os, ref.get().second);
            }
            os << 'e';
        }
    };

    template <typename T>
    struct bt_deserialize<T, std::enable_if_t<is_bt_output_dict_container<T>>> {
        using second_type = typename T::value_type::second_type;
        void operator()(std::string_view& s, T& dict) {
            // Smallest dict is 2 bytes "de", for an empty dict.
            if (s.size() < 2)
                throw bt_deserialize_invalid(
                        "Deserialization failed: end of string found where dict expected");
            if (s[0] != 'd')
                throw bt_deserialize_invalid_type(
                        "Deserialization failed: expected 'd', found '"s + s[0] + "'"s);
            s.remove_prefix(1);
            dict.clear();
            bt_deserialize<std::string> key_deserializer;
            bt_deserialize<second_type> val_deserializer;

            while (!s.empty() && s[0] != 'e') {
                std::string key;
                second_type val;
                key_deserializer(s, key);
                val_deserializer(s, val);
                dict.insert(dict.end(), typename T::value_type{std::move(key), std::move(val)});
            }
            if (s.empty())
                throw bt_deserialize_invalid(
                        "Deserialization failed: encountered end of string before dict was "
                        "finished");
            s.remove_prefix(1);  // Consume the 'e'
        }
    };

    /// Accept anything that looks iterable; value serialization validity isn't checked here (it
    /// fails via the base case static assert).
    template <typename T, typename = void>
    struct is_bt_input_list_container_impl : std::false_type {};
    template <typename T>
    struct is_bt_input_list_container_impl<
            T,
            std::enable_if_t<
                    !std::is_same_v<T, std::string> && !std::is_same_v<T, std::string_view> &&
                            !is_bt_input_dict_container<T>,
                    std::void_t<typename T::const_iterator, typename T::value_type>>>
            : std::true_type {};

    template <typename T, typename = void>
    struct is_bt_output_list_container_impl : std::false_type {};
    template <typename T>
    struct is_bt_output_list_container_impl<
            T,
            std::enable_if_t<
                    !std::is_same_v<T, std::string> && !is_bt_output_dict_container<T> &&
                    is_bt_insertable<T>>> : std::true_type {};

    template <typename T>
    constexpr bool is_bt_output_list_container = is_bt_output_list_container_impl<T>::value;
    template <typename T>
    constexpr bool is_bt_input_list_container = is_bt_input_list_container_impl<T>::value;

    // Sanity checks:
    static_assert(is_bt_input_list_container<bt_list>);
    static_assert(is_bt_output_list_container<bt_list>);

    /// List specialization
    template <typename T>
    struct bt_serialize<T, std::enable_if_t<is_bt_input_list_container<T>>> {
        void operator()(std::ostream& os, const T& list) {
            os << 'l';
            for (const auto& v : list)
                bt_serialize<std::remove_cv_t<typename T::value_type>>{}(os, v);
            os << 'e';
        }
    };
    template <typename T>
    struct bt_deserialize<T, std::enable_if_t<is_bt_output_list_container<T>>> {
        using value_type = typename T::value_type;
        void operator()(std::string_view& s, T& list) {
            // Smallest list is 2 bytes "le", for an empty list.
            if (s.size() < 2)
                throw bt_deserialize_invalid(
                        "Deserialization failed: end of string found where list expected");
            if (s[0] != 'l')
                throw bt_deserialize_invalid_type(
                        "Deserialization failed: expected 'l', found '"s + s[0] + "'"s);
            s.remove_prefix(1);
            list.clear();
            bt_deserialize<value_type> deserializer;
            while (!s.empty() && s[0] != 'e') {
                value_type v;
                deserializer(s, v);
                list.insert(list.end(), std::move(v));
            }
            if (s.empty())
                throw bt_deserialize_invalid(
                        "Deserialization failed: encountered end of string before list was "
                        "finished");
            s.remove_prefix(1);  // Consume the 'e'
        }
    };

    /// Serializes a tuple or pair of serializable values (as a list on the wire)

    /// Common implementation for both tuple and pair:
    template <template <typename...> typename Tuple, typename... T>
    struct bt_serialize_tuple {
      private:
        template <size_t... Is>
        void operator()(std::ostream& os, const Tuple<T...>& elems, std::index_sequence<Is...>) {
            os << 'l';
            (bt_serialize<T>{}(os, std::get<Is>(elems)), ...);
            os << 'e';
        }

      public:
        void operator()(std::ostream& os, const Tuple<T...>& elems) {
            operator()(os, elems, std::index_sequence_for<T...>{});
        }
    };
    template <template <typename...> typename Tuple, typename... T>
    struct bt_deserialize_tuple {
      private:
        template <size_t... Is>
        void operator()(std::string_view& s, Tuple<T...>& elems, std::index_sequence<Is...>) {
            // Smallest list is 2 bytes "le", for an empty list.
            if (s.size() < 2)
                throw bt_deserialize_invalid(
                        "Deserialization failed: end of string found where tuple expected");
            if (s[0] != 'l')
                throw bt_deserialize_invalid_type(
                        "Deserialization of tuple failed: expected 'l', found '"s + s[0] + "'"s);
            s.remove_prefix(1);
            (bt_deserialize<T>{}(s, std::get<Is>(elems)), ...);
            if (s.empty())
                throw bt_deserialize_invalid(
                        "Deserialization failed: encountered end of string before tuple was "
                        "finished");
            if (s[0] != 'e')
                throw bt_deserialize_invalid(
                        "Deserialization failed: expected end of tuple but found something else");
            s.remove_prefix(1);  // Consume the 'e'
        }

      public:
        void operator()(std::string_view& s, Tuple<T...>& elems) {
            operator()(s, elems, std::index_sequence_for<T...>{});
        }
    };
    template <typename... T>
    struct bt_serialize<std::tuple<T...>> : bt_serialize_tuple<std::tuple, T...> {};
    template <typename... T>
    struct bt_deserialize<std::tuple<T...>> : bt_deserialize_tuple<std::tuple, T...> {};
    template <typename S, typename T>
    struct bt_serialize<std::pair<S, T>> : bt_serialize_tuple<std::pair, S, T> {};
    template <typename S, typename T>
    struct bt_deserialize<std::pair<S, T>> : bt_deserialize_tuple<std::pair, S, T> {};

    template <typename T>
    inline constexpr bool is_bt_tuple = false;
    template <typename... T>
    inline constexpr bool is_bt_tuple<std::tuple<T...>> = true;
    template <typename S, typename T>
    inline constexpr bool is_bt_tuple<std::pair<S, T>> = true;

    template <typename T>
    constexpr bool is_bt_deserializable =
            std::is_same_v<T, std::string> || std::is_integral_v<T> ||
            is_bt_output_dict_container<T> || is_bt_output_list_container<T> || is_bt_tuple<T>;

    // General template and base case; this base will only actually be invoked when Ts... is empty,
    // which means we reached the end without finding any variant type capable of holding the value.
    template <typename SFINAE, typename Variant, typename... Ts>
    struct bt_deserialize_try_variant_impl {
        void operator()(std::string_view&, Variant&) {
            throw bt_deserialize_invalid(
                    "Deserialization failed: could not deserialize value into any variant type");
        }
    };

    template <typename... Ts, typename Variant>
    void bt_deserialize_try_variant(std::string_view& s, Variant& variant) {
        bt_deserialize_try_variant_impl<void, Variant, Ts...>{}(s, variant);
    }

    template <typename Variant, typename T, typename... Ts>
    struct bt_deserialize_try_variant_impl<
            std::enable_if_t<is_bt_deserializable<T>>,
            Variant,
            T,
            Ts...> {
        void operator()(std::string_view& s, Variant& variant) {
            if (is_bt_output_list_container<T>   ? s[0] == 'l'
                : is_bt_tuple<T>                 ? s[0] == 'l'
                : is_bt_output_dict_container<T> ? s[0] == 'd'
                : std::is_integral_v<T>          ? s[0] == 'i'
                : std::is_same_v<T, std::string> ? s[0] >= '0' && s[0] <= '9'
                                                 : false) {
                T val;
                bt_deserialize<T>{}(s, val);
                variant = std::move(val);
            } else {
                bt_deserialize_try_variant<Ts...>(s, variant);
            }
        }
    };

    template <typename Variant, typename T, typename... Ts>
    struct bt_deserialize_try_variant_impl<
            std::enable_if_t<!is_bt_deserializable<T>>,
            Variant,
            T,
            Ts...> {
        void operator()(std::string_view& s, Variant& variant) {
            // Unsupported deserialization type, skip it
            bt_deserialize_try_variant<Ts...>(s, variant);
        }
    };

    // Serialization of a variant; all variant types must be bt-serializable.
    template <typename... Ts>
    struct bt_serialize<std::variant<Ts...>, std::void_t<bt_serialize<Ts>...>> {
        void operator()(std::ostream& os, const std::variant<Ts...>& val) {
            var::visit(
                    [&os](const auto& val) {
                        using T = std::remove_cv_t<std::remove_reference_t<decltype(val)>>;
                        bt_serialize<T>{}(os, val);
                    },
                    val);
        }
    };

    // Deserialization to a variant; at least one variant type must be bt-deserializble.
    template <typename... Ts>
    struct bt_deserialize<
            std::variant<Ts...>,
            std::enable_if_t<(is_bt_deserializable<Ts> || ...)>> {
        void operator()(std::string_view& s, std::variant<Ts...>& val) {
            bt_deserialize_try_variant<Ts...>(s, val);
        }
    };

    template <>
    struct bt_serialize<bt_value> : bt_serialize<bt_variant> {};

    template <>
    struct bt_deserialize<bt_value> {
        void operator()(std::string_view& s, bt_value& val);
    };

    template <typename T>
    struct bt_stream_serializer {
        const T& val;
        explicit bt_stream_serializer(const T& val) : val{val} {}
        operator std::string() const {
            std::ostringstream oss;
            oss << *this;
            return oss.str();
        }
    };
    template <typename T>
    std::ostream& operator<<(std::ostream& os, const bt_stream_serializer<T>& s) {
        bt_serialize<T>{}(os, s.val);
        return os;
    }

    // True if the type is a std::string, std::string_view, or some a basic_string<Char> for some
    // single-byte type Char.
    template <typename T>
    constexpr bool is_string_like = false;
    template <typename Char>
    inline constexpr bool is_string_like<std::basic_string<Char>> = sizeof(Char) == 1;
    template <typename Char>
    inline constexpr bool is_string_like<std::basic_string_view<Char>> = sizeof(Char) == 1;

}  // namespace detail

/// Returns a wrapper around a value reference that can serialize the value directly to an output
/// stream.  This class is intended to be used inline (i.e. without being stored) as in:
///
///     std::list<int> my_list{{1,2,3}};
///     std::cout << bt_serializer(my_list);
///
/// While it is possible to store the returned object and use it, such as:
///
///     auto encoded = bt_serializer(42);
///     std::cout << encoded;
///
/// this approach is not generally recommended: the returned object stores a reference to the
/// passed-in type, which may not survive.  If doing this note that it is the caller's
/// responsibility to ensure the serializer is not used past the end of the lifetime of the value
/// being serialized.
///
/// Also note that serializing directly to an output stream is more efficient as no intermediate
/// string containing the entire serialization has to be constructed.
///
template <typename T>
detail::bt_stream_serializer<T> bt_serializer(const T& val) {
    return detail::bt_stream_serializer<T>{val};
}

/// Serializes the given value into a std::string.
///
///     int number = 42;
///     std::string encoded = bt_serialize(number);
///     // Equivalent:
///     //auto encoded = (std::string) bt_serialize(number);
///
/// This takes any serializable type: integral types, strings, lists of serializable types, and
/// string->value maps of serializable types.
template <typename T>
std::string bt_serialize(const T& val) {
    return bt_serializer(val);
}

/// Deserializes the given string view directly into `val`.  Usage:
///
///     std::string encoded = "i42e";
///     int value;
///     bt_deserialize(encoded, value); // Sets value to 42
///
template <typename T, std::enable_if_t<!std::is_const_v<T>, int> = 0>
void bt_deserialize(std::string_view s, T& val) {
    return detail::bt_deserialize<T>{}(s, val);
}

/// Deserializes the given string_view into a `T`, which is returned.
///
///     std::string encoded = "li1ei2ei3ee"; // bt-encoded list of ints: [1,2,3]
///     auto mylist = bt_deserialize<std::list<int>>(encoded);
///
template <typename T>
T bt_deserialize(std::string_view s) {
    T val;
    bt_deserialize(s, val);
    return val;
}

/// Deserializes the given value into a generic `bt_value` type (wrapped std::variant) which is
/// capable of holding all possible BT-encoded values (including recursion).
///
/// Example:
///
///     std::string encoded = "i42e";
///     auto val = bt_get(encoded);
///     int v = get_int<int>(val); // fails unless the encoded value was actually an integer that
///                                // fits into an `int`
///
inline bt_value bt_get(std::string_view s) {
    return bt_deserialize<bt_value>(s);
}

/// Helper functions to extract a value of some integral type from a bt_value which contains either
/// a int64_t or uint64_t.  Does range checking, throwing std::overflow_error if the stored value is
/// outside the range of the target type.
///
/// Example:
///
///     std::string encoded = "i123456789e";
///     auto val = bt_get(encoded);
///     auto v = get_int<uint32_t>(val); // throws if the decoded value doesn't fit in a uint32_t
template <typename IntType, std::enable_if_t<std::is_integral_v<IntType>, int> = 0>
IntType get_int(const bt_value& v) {
    if (auto* value = std::get_if<uint64_t>(&v)) {
        if constexpr (!std::is_same_v<IntType, uint64_t>)
            if (*value > static_cast<uint64_t>(std::numeric_limits<IntType>::max()))
                throw std::overflow_error(
                        "Unable to extract integer value: stored value is too large for the "
                        "requested type");
        return static_cast<IntType>(*value);
    }

    int64_t value = var::get<int64_t>(v);  // throws if no int contained
    if constexpr (!std::is_same_v<IntType, int64_t>)
        if (value > static_cast<int64_t>(std::numeric_limits<IntType>::max()) ||
            value < static_cast<int64_t>(std::numeric_limits<IntType>::min()))
            throw std::overflow_error(
                    "Unable to extract integer value: stored value is outside the range of the "
                    "requested type");
    return static_cast<IntType>(value);
}

namespace detail {
    template <typename Tuple, size_t... Is>
    void get_tuple_impl(Tuple& t, const bt_list& l, std::index_sequence<Is...>);
}

/// Converts a bt_list into the given template std::tuple or std::pair.  Throws a
/// std::invalid_argument if the list has the wrong size or wrong element types.  Supports recursion
/// (i.e. if the tuple itself contains tuples or pairs).  The tuple (or nested tuples) may only
/// contain integral types, strings, string_views, bt_list, bt_dict, and tuples/pairs of those.
template <typename Tuple>
Tuple get_tuple(const bt_list& x) {
    Tuple t;
    detail::get_tuple_impl(t, x, std::make_index_sequence<std::tuple_size_v<Tuple>>{});
    return t;
}
template <typename Tuple>
Tuple get_tuple(const bt_value& x) {
    return get_tuple<Tuple>(var::get<bt_list>(static_cast<const bt_variant&>(x)));
}

class bt_dict_consumer;
class bt_list_consumer;

namespace detail {
    template <typename T, typename It>
    void get_tuple_impl_one(T& t, It& it) {
        const bt_variant& v = *it++;
        if constexpr (std::is_integral_v<T>) {
            t = oxenc::get_int<T>(v);
        } else if constexpr (is_bt_tuple<T>) {
            if (std::holds_alternative<bt_list>(v))
                throw std::invalid_argument{
                        "Unable to convert tuple: cannot create sub-tuple from non-bt_list"};
            t = get_tuple<T>(var::get<bt_list>(v));
        } else if constexpr (
                std::is_same_v<std::string, T> || std::is_same_v<std::string_view, T>) {
            // If we request a string/string_view, we might have the other one and need to copy/view
            // it.
            if (std::holds_alternative<std::string_view>(v))
                t = var::get<std::string_view>(v);
            else
                t = var::get<std::string>(v);
        } else {
            t = var::get<T>(v);
        }
    }
    template <typename Tuple, size_t... Is>
    void get_tuple_impl(Tuple& t, const bt_list& l, std::index_sequence<Is...>) {
        if (l.size() != sizeof...(Is))
            throw std::invalid_argument{"Unable to convert tuple: bt_list has wrong size"};
        auto it = l.begin();
        (get_tuple_impl_one(std::get<Is>(t), it), ...);
    }

    template <typename T, typename Consumer>
    T consume_impl(Consumer& c) {
        if constexpr (std::is_integral_v<T>)
            return c.template consume_integer<T>();
        else if constexpr (detail::is_string_like<T>)
            return T{c.template consume_string_view<typename T::value_type>()};
        else if constexpr (std::is_same_v<T, bt_dict>)
            return c.consume_dict();
        else if constexpr (std::is_same_v<T, bt_list>)
            return c.consume_list();
        else if constexpr (std::is_same_v<T, bt_dict_consumer>)
            return c.consume_dict_consumer();
        else {
            static_assert(std::is_same_v<T, bt_list_consumer>, "Unsupported consume type");
            return c.consume_list_consumer();
        }
    }

}  // namespace detail

/// Class that allows you to walk through a bt-encoded list in memory without copying or allocating
/// memory.  It accesses existing memory directly and so the caller must ensure that the referenced
/// memory stays valid for the lifetime of the bt_list_consumer object.
class bt_list_consumer {
  protected:
    std::string_view data;            // Remaining data; this gets prefix-removed as we go
    const char* start = data.data();  // Pointer to the start of the initial data, so that we can
                                      // get the entire data when needed (e.g. for signatures)
    bt_list_consumer() = default;

    struct load_tag {};
    bt_list_consumer(std::string_view data, load_tag) : data{data} {}

  public:
    bt_list_consumer(std::string_view data_) : bt_list_consumer{data_, load_tag{}} {
        if (data.empty())
            throw std::runtime_error{"Cannot create a bt_list_consumer with an empty string_view"};
        if (data[0] != 'l')
            throw std::runtime_error{"Cannot create a bt_list_consumer with non-list data"};
        data.remove_prefix(1);
    }
    bt_list_consumer(std::basic_string_view<unsigned char> data) :
            bt_list_consumer{
                    std::string_view{reinterpret_cast<const char*>(data.data()), data.size()}} {}
    bt_list_consumer(std::basic_string_view<std::byte> data) :
            bt_list_consumer{
                    std::string_view{reinterpret_cast<const char*>(data.data()), data.size()}} {}

    /// Copy constructor.  Making a copy copies the current position so can be used for multipass
    /// iteration through a list.
    bt_list_consumer(const bt_list_consumer&) = default;
    bt_list_consumer& operator=(const bt_list_consumer&) = default;

    /// Returns true if the next value indicates the end of the list
    bool is_finished() const { return data.front() == 'e'; }
    /// Returns true if the next element looks like an encoded string
    bool is_string() const { return data.front() >= '0' && data.front() <= '9'; }
    /// Returns true if the next element looks like an encoded integer
    bool is_integer() const { return data.front() == 'i'; }
    /// Returns true if the next element looks like an encoded negative integer
    bool is_negative_integer() const { return is_integer() && data.size() >= 2 && data[1] == '-'; }
    /// Returns true if the next element looks like an encoded non-negative integer
    bool is_unsigned_integer() const {
        return is_integer() && data.size() >= 2 && data[1] >= '0' && data[1] <= '9';
    }
    /// Returns true if the next element looks like an encoded list
    bool is_list() const { return data.front() == 'l'; }
    /// Returns true if the next element looks like an encoded dict
    bool is_dict() const { return data.front() == 'd'; }

    /// Consumes a value into the given type (string_view, string, integer, bt_dict_consumer, etc.).
    /// This is a shortcut for calling consume_string, consume_integer, etc. based on the templated
    /// type.
    template <typename T>
    T consume() {
        return detail::consume_impl<T>(*this);
    }

    /// Attempt to parse the next value as a string (and advance just past it).  Throws if the next
    /// value is not a string.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string<Char> consume_string() {
        return std::basic_string<Char>{consume_string_view<Char>()};
    }
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string_view<Char> consume_string_view() {
        if (data.empty())
            throw bt_deserialize_invalid{"expected a string, but reached end of data"};
        else if (!is_string())
            throw bt_deserialize_invalid_type{"expected a string, but found "s + data.front()};
        std::string_view next{data}, result;
        detail::bt_deserialize<std::string_view>{}(next, result);
        data = next;
        return {reinterpret_cast<const Char*>(result.data()), result.size()};
    }

    /// Attempts to parse the next value as an integer (and advance just past it).  Throws if the
    /// next value is not an integer.
    template <typename IntType>
    IntType consume_integer() {
        if (!is_integer())
            throw bt_deserialize_invalid_type{"next value is not an integer"};
        std::string_view next{data};
        IntType ret;
        detail::bt_deserialize<IntType>{}(next, ret);
        data = next;
        return ret;
    }

    /// Consumes a list, return it as a list-like type.  Can also be used for tuples/pairs.  This
    /// typically requires dynamic allocation, but only has to parse the data once.  Compare with
    /// consume_list_data() which allows alloc-free traversal, but requires parsing twice (if the
    /// contents are to be used).
    template <typename T = bt_list>
    T consume_list() {
        T list;
        consume_list(list);
        return list;
    }

    /// Same as above, but takes a pre-existing list-like data type.
    template <typename T>
    void consume_list(T& list) {
        if (!is_list())
            throw bt_deserialize_invalid_type{"next bt value is not a list"};
        std::string_view n{data};
        detail::bt_deserialize<T>{}(n, list);
        data = n;
    }

    /// Consumes a dict, return it as a dict-like type.  This typically requires dynamic allocation,
    /// but only has to parse the data once.  Compare with consume_dict_data() which allows
    /// alloc-free traversal, but requires parsing twice (if the contents are to be used).
    template <typename T = bt_dict>
    T consume_dict() {
        T dict;
        consume_dict(dict);
        return dict;
    }

    /// Same as above, but takes a pre-existing dict-like data type.
    template <typename T>
    void consume_dict(T& dict) {
        if (!is_dict())
            throw bt_deserialize_invalid_type{"next bt value is not a dict"};
        std::string_view n{data};
        detail::bt_deserialize<T>{}(n, dict);
        data = n;
    }

    /// Attempts to parse the next value as a list and returns the string_view that contains the
    /// entire thing.  This is recursive into both lists and dicts and likely to be quite
    /// inefficient for large, nested structures (unless the values only need to be skipped but
    /// aren't separately needed).  This, however, does not require dynamic memory allocation.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string_view<Char> consume_list_data() {
        std::basic_string_view<Char> orig{reinterpret_cast<const Char*>(data.data()), data.size()};
        if (data.size() < 2 || !is_list())
            throw bt_deserialize_invalid_type{"next bt value is not a list"};
        data.remove_prefix(1);  // Descend into the sublist, consume the "l"
        while (!is_finished()) {
            skip_value();
            if (data.empty())
                throw bt_deserialize_invalid{
                        "bt list consumption failed: hit the end of string before the list was "
                        "done"};
        }
        data.remove_prefix(1);  // Back out from the sublist, consume the "e"
        orig.remove_suffix(data.size());
        return orig;
    }

    /// Attempts to parse the next value as a dict and returns the string_view that contains the
    /// entire thing.  This is recursive into both lists and dicts and likely to be quite
    /// inefficient for large, nested structures (unless the values only need to be skipped but
    /// aren't separately needed).  This, however, does not require dynamic memory allocation.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string_view<Char> consume_dict_data() {
        std::basic_string_view<Char> orig{reinterpret_cast<const Char*>(data.data()), data.size()};
        if (data.size() < 2 || !is_dict())
            throw bt_deserialize_invalid_type{"next bt value is not a dict"};
        data.remove_prefix(1);  // Descent into the dict, consumer the "d"
        while (!is_finished()) {
            consume_string_view();  // Key is always a string
            if (!data.empty())
                skip_value();
            if (data.empty())
                throw bt_deserialize_invalid{
                        "bt dict consumption failed: hit the end of string before the dict was "
                        "done"};
        }
        data.remove_prefix(1);  // Back out of the dict, consume the "e"
        orig.remove_suffix(data.size());
        return orig;
    }

    /// Shortcut for wrapping `consume_list_data()` in a new list consumer
    bt_list_consumer consume_list_consumer() { return consume_list_data(); }
    /// Shortcut for wrapping `consume_dict_data()` in a new dict consumer
    inline bt_dict_consumer consume_dict_consumer();

    /// Consumes a string as a signature value, as added via bt_list_producer::append_signature.
    /// The expected signed message (i.e. the data parsed up to the current point) and the signature
    /// itself (the next element string value) are passed to the given VerifyFunc.  The VerifyFunc
    /// must take two std::string_views or two std::basic_string_view<C> for C of either `unsigned
    /// char` (aka `uint8_t`) or `std::byte`.  The first argument is the allegedly signed message
    /// (i.e. already-consumed list data), and the second argument is the signature.
    ///
    /// The VerifyFunc must not return a value (to prevent against accidentally passing a
    /// bool-returning verification function); it should (typically) be a function that throws on
    /// signature verification rather than returning a success value.  Any such exception is not
    /// caught here (and so propagates back to the consume_signature() caller).
    ///
    /// Does not return a value (if the signature is needed then the callback can copy/store it).
    template <typename VerifyFunc>
    void consume_signature(VerifyFunc verify) {
        using Char = std::conditional_t<
                std::is_invocable_v<VerifyFunc, std::string_view&&, std::string_view&&>,
                char,
                std::conditional_t<
                        std::is_invocable_v<
                                VerifyFunc,
                                std::basic_string_view<unsigned char>&&,
                                std::basic_string_view<unsigned char>&&>,
                        unsigned char,
                        std::conditional_t<
                                std::is_invocable_v<
                                        VerifyFunc,
                                        std::basic_string_view<std::byte>&&,
                                        std::basic_string_view<std::byte>&&>,
                                std::byte,
                                void>>>;
        static_assert(
                !std::is_void_v<Char>,
                "consume_signature verify function must take two string_views (or unsigned "
                "char/std::byte variants)");
        using SV = std::basic_string_view<Char>;

        SV message{reinterpret_cast<const Char*>(start), static_cast<size_t>(data.data() - start)};

        static_assert(
                std::is_void_v<decltype(verify(SV{}, SV{}))>,
                "consume_signature verify function must not return a value");

        verify(std::move(message), consume_string_view<Char>());
    }

    /// Consumes a value without returning it.
    void skip_value() {
        if (is_string())
            consume_string_view();
        else if (is_integer())
            detail::bt_deserialize_integer(data);
        else if (is_list())
            consume_list_data();
        else if (is_dict())
            consume_dict_data();
        else
            throw bt_deserialize_invalid_type{"next bt value has unknown type"};
    }
};

/// Class that allows you to walk through key-value pairs of a bt-encoded dict in memory without
/// copying or allocating memory.  It accesses existing memory directly and so the caller must
/// ensure that the referenced memory stays valid for the lifetime of the bt_dict_consumer object.
class bt_dict_consumer : private bt_list_consumer {
    std::string_view key_;

    /// Consume the key if not already consumed and there is a key present (rather than 'e').
    /// Throws exception if what should be a key isn't a string, or if the key consumes the entire
    /// data (i.e. requires that it be followed by something).  Returns true if the key was consumed
    /// (either now or previously and cached).
    bool consume_key() {
        if (key_.data())
            return true;
        if (data.empty())
            throw bt_deserialize_invalid_type{"expected a key or dict end, found end of string"};
        if (data[0] == 'e')
            return false;
        key_ = bt_list_consumer::consume_string_view();
        if (data.empty() || data[0] == 'e')
            throw bt_deserialize_invalid{"dict key isn't followed by a value"};
        return true;
    }

    /// Clears the cached key and returns it.  Must have already called consume_key directly or
    /// indirectly via one of the `is_{...}` methods.
    std::string_view flush_key() {
        std::string_view k;
        k.swap(key_);
        return k;
    }

  public:
    bt_dict_consumer(std::string_view data_) : bt_list_consumer{data_, load_tag{}} {
        if (data.empty())
            throw std::runtime_error{"Cannot create a bt_dict_consumer with an empty string_view"};
        if (data.size() < 2 || data[0] != 'd')
            throw std::runtime_error{"Cannot create a bt_dict_consumer with non-dict data"};
        data.remove_prefix(1);
    }
    bt_dict_consumer(std::basic_string_view<unsigned char> data) :
            bt_dict_consumer{
                    std::string_view{reinterpret_cast<const char*>(data.data()), data.size()}} {}
    bt_dict_consumer(std::basic_string_view<std::byte> data) :
            bt_dict_consumer{
                    std::string_view{reinterpret_cast<const char*>(data.data()), data.size()}} {}

    /// Copy constructor.  Making a copy copies the current position so can be used for multipass
    /// iteration through a list.
    bt_dict_consumer(const bt_dict_consumer&) = default;
    bt_dict_consumer& operator=(const bt_dict_consumer&) = default;

    /// Returns true if the next value indicates the end of the dict
    bool is_finished() { return !consume_key() && data.front() == 'e'; }
    /// Operator bool is an alias for `!is_finished()`
    operator bool() { return !is_finished(); }
    /// Returns true if the next value looks like an encoded string
    bool is_string() { return consume_key() && data.front() >= '0' && data.front() <= '9'; }
    /// Returns true if the next element looks like an encoded integer
    bool is_integer() { return consume_key() && data.front() == 'i'; }
    /// Returns true if the next element looks like an encoded negative integer
    bool is_negative_integer() { return is_integer() && data.size() >= 2 && data[1] == '-'; }
    /// Returns true if the next element looks like an encoded non-negative integer
    bool is_unsigned_integer() {
        return is_integer() && data.size() >= 2 && data[1] >= '0' && data[1] <= '9';
    }
    /// Returns true if the next element looks like an encoded list
    bool is_list() { return consume_key() && data.front() == 'l'; }
    /// Returns true if the next element looks like an encoded dict
    bool is_dict() { return consume_key() && data.front() == 'd'; }
    /// Returns the key of the next pair.  This does not have to be called; it is also returned by
    /// all of the other consume_* methods.  The value is cached whether called here or by some
    /// other method; accessing it multiple times simple accesses the cache until the next value is
    /// consumed.
    std::string_view key() {
        if (!consume_key())
            throw bt_deserialize_invalid{"Cannot access next key: at the end of the dict"};
        return key_;
    }

    /// Attempt to parse the next value as a string->string pair (and advance just past it).  Throws
    /// if the next value is not a string.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::pair<std::string_view, std::basic_string_view<Char>> next_string() {
        if (!is_string())
            throw bt_deserialize_invalid_type{"expected a string, but found "s + data.front()};
        std::pair<std::string_view, std::basic_string_view<Char>> ret;
        ret.second = bt_list_consumer::consume_string_view<Char>();
        ret.first = flush_key();
        return ret;
    }

    /// Attempts to parse the next value as an string->integer pair (and advance just past it).
    /// Throws if the next value is not an integer.
    template <typename IntType>
    std::pair<std::string_view, IntType> next_integer() {
        if (!is_integer())
            throw bt_deserialize_invalid_type{"next bt dict value is not an integer"};
        std::pair<std::string_view, IntType> ret;
        ret.second = bt_list_consumer::consume_integer<IntType>();
        ret.first = flush_key();
        return ret;
    }

    /// Consumes a string->list pair, return it as a list-like type.  This typically requires
    /// dynamic allocation, but only has to parse the data once.  Compare with consume_list_data()
    /// which allows alloc-free traversal, but requires parsing twice (if the contents are to be
    /// used).
    template <typename T = bt_list>
    std::pair<std::string_view, T> next_list() {
        std::pair<std::string_view, T> pair;
        pair.first = next_list(pair.second);
        return pair;
    }

    /// Same as above, but takes a pre-existing list-like data type.  Returns the key.
    template <typename T>
    std::string_view next_list(T& list) {
        if (!is_list())
            throw bt_deserialize_invalid_type{"next bt value is not a list"};
        bt_list_consumer::consume_list(list);
        return flush_key();
    }

    /// Consumes a string->dict pair, return it as a dict-like type.  This typically requires
    /// dynamic allocation, but only has to parse the data once.  Compare with consume_dict_data()
    /// which allows alloc-free traversal, but requires parsing twice (if the contents are to be
    /// used).
    template <typename T = bt_dict>
    std::pair<std::string_view, T> next_dict() {
        std::pair<std::string_view, T> pair;
        pair.first = next_dict(pair.second);
        return pair;
    }

    /// Same as above, but takes a pre-existing dict-like data type.  Returns the key.
    template <typename T>
    std::string_view next_dict(T& dict) {
        if (!is_dict())
            throw bt_deserialize_invalid_type{"next bt value is not a dict"};
        bt_list_consumer::consume_dict(dict);
        return flush_key();
    }

    /// Attempts to parse the next value as a string->list pair and returns the string_view that
    /// contains the entire thing.  This is recursive into both lists and dicts and likely to be
    /// quite inefficient for large, nested structures (unless the values only need to be skipped
    /// but aren't separately needed).  This, however, does not require dynamic memory allocation.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::pair<std::string_view, std::basic_string_view<Char>> next_list_data() {
        if (data.size() < 2 || !is_list())
            throw bt_deserialize_invalid_type{"next bt dict value is not a list"};
        return {flush_key(), bt_list_consumer::consume_list_data<Char>()};
    }

    /// Same as next_list_data(), but wraps the value in a bt_list_consumer for convenience
    std::pair<std::string_view, bt_list_consumer> next_list_consumer() { return next_list_data(); }

    /// Attempts to parse the next value as a string->dict pair and returns the string_view that
    /// contains the entire thing.  This is recursive into both lists and dicts and likely to be
    /// quite inefficient for large, nested structures (unless the values only need to be skipped
    /// but aren't separately needed).  This, however, does not require dynamic memory allocation.
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::pair<std::string_view, std::basic_string_view<Char>> next_dict_data() {
        if (data.size() < 2 || !is_dict())
            throw bt_deserialize_invalid_type{"next bt dict value is not a dict"};
        return {flush_key(), bt_list_consumer::consume_dict_data<Char>()};
    }

    /// Same as next_dict_data(), but wraps the value in a bt_dict_consumer for convenience
    std::pair<std::string_view, bt_dict_consumer> next_dict_consumer() { return next_dict_data(); }

    /// Parses the next value as a string->string pair that has been constructed to contain a
    /// signature produced via bt_dict_producer::append_signature.  Returns a tuple of three
    /// values:
    ///
    /// - the key (std::string_view)
    /// - the message that is allegedly signed, consisting of all (so-far) consumed data from the
    /// dict
    /// - the signature value
    ///
    /// Verification of the signature is up to the caller.  See also consume_signature().
    ///
    /// The latter two are std::string_views by default, but a `Char` template type can be provided
    /// to return them as some other basic_string_view<Char>.
    template <typename Char = char>
    std::tuple<std::string_view, std::basic_string_view<Char>, std::basic_string_view<Char>>
    next_signature() {
        std::tuple<std::string_view, std::basic_string_view<Char>, std::basic_string_view<Char>>
                ret;
        auto& [k, msg, sig] = ret;
        // Figuring out `msg` gets a little complicated.
        //
        // It would be easier if we knew that the key hadn't yet been consumed, because then we
        // could just get the message from `start` to our current position, but we have no guarantee
        // of that (because the user might have called something like is_string() that has already
        // consumed the key), so we can't rely on it and we have to back up over what the encoded
        // key size would have been.
        //
        // So start out by always getting the key so we have only one case to deal with:
        k = key();

        // What we need for msg is from `start` and ending at the beginning of `LMN:somekey`, where
        // LMN is some base-10 integer, but `data.data()` currently points at the *end* of that
        // value.  So first we can back up over the key value itself, the `:`, and the last digit
        // (`N`):
        const char* msgend = data.data() - k.size() - 2;

        // But in case this is a long key, we might need to back up over more digits (L, M):
        for (size_t x = k.size(); x >= 10; x /= 10)
            msgend--;

        msg = {reinterpret_cast<const Char*>(start), static_cast<size_t>(msgend - start)};
        sig = consume_string_view<Char>();

        return ret;
    }

    /// Skips ahead until we find the first key >= the given key or reach the end of the dict.
    /// Returns true if we found an exact match, false if we reached some greater value or the end.
    /// If we didn't hit the end, the next `consumer_*()` call will return the key-value pair we
    /// found (either the exact match or the first key greater than the requested key).
    ///
    /// Two important notes:
    ///
    /// - properly encoded bt dicts must have lexicographically sorted keys, and this method assumes
    ///   that the input is correctly sorted (and thus if we find a greater value then your key does
    ///   not exist).
    /// - this is irreversible; you cannot returned to skipped values without reparsing.  (You *can*
    ///   however, make a copy of the bt_dict_consumer before calling and use the copy to return to
    ///   the pre-skipped position).
    bool skip_until(std::string_view find) {
        while (consume_key() && key_ < find) {
            flush_key();
            skip_value();
        }
        return key_ == find;
    }

    /// This functions nearly identically to skip_until; it will return if we found an exact match
    /// but will throw if the key is not found. If we didn't throw, the next `consumer_*()` call
    /// will return the key-value pair we found.
    ///
    /// Two important notes:
    ///
    /// - properly encoded bt dicts must have lexicographically sorted keys, and this method assumes
    ///   that the input is correctly sorted (and thus if we find a greater value then your key does
    ///   not exist).
    /// - this is irreversible; you cannot returned to skipped values without reparsing.  (You *can*
    ///   however, make a copy of the bt_dict_consumer before calling and use the copy to return to
    ///   the pre-skipped position).
    void required(std::string_view find) {
        if (!skip_until(find))
            throw std::out_of_range{"Key " + std::string{find} + " not found!"};
    }

    /// The `consume_*` functions are wrappers around next_whatever that discard the returned key.
    ///
    /// Intended for use with skip_until such as:
    ///
    ///     std::string value;
    ///     if (d.skip_until("key"))
    ///         value = d.consume_string();
    ///

    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    auto consume_string_view() {
        return next_string<Char>().second;
    }
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    auto consume_string() {
        return std::basic_string<Char>{consume_string_view<Char>()};
    }

    template <typename IntType>
    auto consume_integer() {
        return next_integer<IntType>().second;
    }

    template <typename T = bt_list>
    auto consume_list() {
        return next_list<T>().second;
    }

    template <typename T>
    void consume_list(T& list) {
        next_list(list);
    }

    template <typename T = bt_dict>
    auto consume_dict() {
        return next_dict<T>().second;
    }

    template <typename T>
    void consume_dict(T& dict) {
        next_dict(dict);
    }

    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string_view<Char> consume_list_data() {
        return next_list_data<Char>().second;
    }
    template <typename Char = char, typename = std::enable_if_t<sizeof(Char) == 1>>
    std::basic_string_view<Char> consume_dict_data() {
        return next_dict_data<Char>().second;
    }

    /// Shortcut for wrapping `consume_list_data()` in a new list consumer
    bt_list_consumer consume_list_consumer() { return consume_list_data(); }
    /// Shortcut for wrapping `consume_dict_data()` in a new dict consumer
    bt_dict_consumer consume_dict_consumer() { return consume_dict_data(); }

    /// Consumes and verifies a signature.  This method, unlike the above consume_ functions, is a
    /// little different from its `next_signature` counterpart: it returns nothing, but takes a
    /// verification function to call with the expected message data and signature.  The VerifyFunc
    /// should take two arguments, such as:
    ///
    ///     void verifier(std::string_view msg, std::string_view sig);
    ///     void verifier(std::basic_string_view<Char> msg, std::basic_string_view<Char> sig);
    ///
    /// with allowed `Char` types of `char`, `unsigned char` or `std::byte`.
    ///
    /// The first paramter is the allegedly signed message (i.e. already-consumed dict data), and
    /// the second argument is the signature.
    ///
    /// The VerifyFunc return value must be void; non-void returning functions are explicitly
    /// disallowed to prevent accidentally passing a bool-return verification function.  The verify
    /// function may throw if desired (the exception is not caught and will propagate back to the
    /// consume_signature() caller).
    ///
    /// Does not return a value (if the signature needs to be stored then the callback can
    /// copy/store it).
    template <typename VerifyFunc>
    void consume_signature(VerifyFunc verify) {
        using Char = std::conditional_t<
                std::is_invocable_v<VerifyFunc, std::string_view, std::string_view>,
                char,
                std::conditional_t<
                        std::is_invocable_v<
                                VerifyFunc,
                                std::basic_string_view<unsigned char>,
                                std::basic_string_view<unsigned char>>,
                        unsigned char,
                        std::conditional_t<
                                std::is_invocable_v<
                                        VerifyFunc,
                                        std::basic_string_view<std::byte>,
                                        std::basic_string_view<std::byte>>,
                                std::byte,
                                void>>>;
        static_assert(
                !std::is_void_v<Char>,
                "consume_signature verify function must take two string_views (or unsigned "
                "char/std::byte variants)");
        auto [key, msg, sig] = next_signature<Char>();
        static_assert(
                std::is_void_v<decltype(verify(msg, sig))>,
                "consume_signature verify function must not return a value");
        verify(msg, sig);
    }

    /// Consumes a value into the given type (string_view, string, integer, bt_dict_consumer, etc.).
    /// This is a shortcut for calling consume_string, consume_integer, etc. based on the templated
    /// type.
    template <typename T>
    T consume() {
        return detail::consume_impl<T>(*this);
    }

    /// Advances to and requires the given key (as if by calling `required()`) and then throws if
    /// the key was not found; otherwise returns the value parsed into the given type.
    template <typename T>
    T require(std::string_view key) {
        required(key);
        return consume<T>();
    }

    /// Advances to and requires the given key (as if by calling `required()`) and then throws if
    /// the key was not found; otherwise calls consume_signature() with the given verification
    /// function to verify the signature value against the prior dict data.
    template <typename VerifyFunc>
    void require_signature(std::string_view key, VerifyFunc&& verify) {
        required(key);
        return consume_signature(std::forward<VerifyFunc>(verify));
    }

    /// Advances to a given key (as if by calling `skip_until`) and then returns std::nullopt if the
    /// key was not found; otherwise returns the value parsed into the given type.  Note that this
    /// will still throw if the key exists but has an incompatible value (e.g. calling
    /// `d.maybe<int>("x")` when the value at "x" is a string).
    template <typename T>
    std::optional<T> maybe(std::string_view key) {
        if (!skip_until(key))
            return std::nullopt;
        return consume<T>();
    }
};

inline bt_dict_consumer bt_list_consumer::consume_dict_consumer() {
    return consume_dict_data();
}

namespace detail {

    /// Reads digits into an unsigned 64-bit int.
    inline uint64_t extract_unsigned(std::string_view& s) {
        uint64_t uval = 0;
        bool once = false;
        while (!s.empty() && (s[0] >= '0' && s[0] <= '9')) {
            once = true;
            uint64_t bigger = uval * 10 + (s[0] - '0');
            s.remove_prefix(1);
            if (bigger < uval)  // overflow
                throw bt_deserialize_invalid(
                        "Integer deserialization failed: value is too large for a 64-bit int");
            uval = bigger;
        }
        if (!once)
            throw bt_deserialize_invalid{"Expected 0-9 was not found"};
        return uval;
    }

    inline void bt_deserialize<std::string_view>::operator()(
            std::string_view& s, std::string_view& val) {
        if (s.size() < 2)
            throw bt_deserialize_invalid{
                    "Deserialize failed: given data is not an bt-encoded string"};
        if (s[0] < '0' || s[0] > '9')
            throw bt_deserialize_invalid_type{"Expected 0-9 but found '"s + s[0] + "'"};
        auto len = static_cast<size_t>(extract_unsigned(s));
        if (s.empty() || s[0] != ':')
            throw bt_deserialize_invalid{"Did not find expected ':' during string deserialization"};
        s.remove_prefix(1);

        if (len > s.size())
            throw bt_deserialize_invalid{
                    "String deserialization failed: encoded string length is longer than the "
                    "serialized data"};

        val = {s.data(), len};
        s.remove_prefix(len);
    }

    // Check that we are on a 2's complement architecture.  It's highly unlikely that this code ever
    // runs on a non-2s-complement architecture (especially since C++20 requires a two's complement
    // signed value behaviour), but check at compile time anyway because we rely on these relations
    // below.
    static_assert(
            std::numeric_limits<int64_t>::min() + std::numeric_limits<int64_t>::max() == -1 &&
                    static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) + uint64_t{1} ==
                            (uint64_t{1} << 63),
            "Non 2s-complement architecture not supported!");

    inline std::pair<some64, bool> bt_deserialize_integer(std::string_view& s) {
        // Smallest possible encoded integer is 3 chars: "i0e"
        if (s.size() < 3)
            throw bt_deserialize_invalid(
                    "Deserialization failed: end of string found where integer expected");
        if (s[0] != 'i')
            throw bt_deserialize_invalid_type(
                    "Deserialization failed: expected 'i', found '"s + s[0] + '\'');
        s.remove_prefix(1);
        std::pair<some64, bool> result;
        auto& [val, negative] = result;
        if (s[0] == '-') {
            negative = true;
            s.remove_prefix(1);
            val.u64 = extract_unsigned(s);
            if (val.u64 > (uint64_t{1} << 63))
                throw bt_deserialize_invalid(
                        "Deserialization of integer failed: negative integer value is too large "
                        "for a 64-bit signed int");
            val.i64 = -static_cast<int64_t>(val.u64);
        } else {
            val.u64 = extract_unsigned(s);
        }

        if (s.empty())
            throw bt_deserialize_invalid(
                    "Integer deserialization failed: encountered end of string before integer was "
                    "finished");
        if (s[0] != 'e')
            throw bt_deserialize_invalid(
                    "Integer deserialization failed: expected digit or 'e', found '"s + s[0] +
                    '\'');
        s.remove_prefix(1);

        return result;
    }

    template struct bt_deserialize<int64_t>;
    template struct bt_deserialize<uint64_t>;

    inline void bt_deserialize<bt_value, void>::operator()(std::string_view& s, bt_value& val) {
        if (s.size() < 2)
            throw bt_deserialize_invalid(
                    "Deserialization failed: end of string found where bt-encoded value expected");

        switch (s[0]) {
            case 'd': {
                bt_dict dict;
                bt_deserialize<bt_dict>{}(s, dict);
                val = std::move(dict);
                break;
            }
            case 'l': {
                bt_list list;
                bt_deserialize<bt_list>{}(s, list);
                val = std::move(list);
                break;
            }
            case 'i': {
                auto [v, negative] = bt_deserialize_integer(s);
                if (negative)
                    val = v.i64;
                else
                    val = v.u64;
                break;
            }
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9': {
                std::string str;
                bt_deserialize<std::string>{}(s, str);
                val = std::move(str);
                break;
            }
            default:
                throw bt_deserialize_invalid(
                        "Deserialize failed: encountered invalid value '"s + s[0] +
                        "'; expected one of [0-9idl]");
        }
    }

}  // namespace detail

}  // namespace oxenc
