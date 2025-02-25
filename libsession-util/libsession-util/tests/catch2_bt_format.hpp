#pragma once

#include <oxenc/bt_value.h>
#include <oxenc/variant.h>

#include <catch2/catch_tostring.hpp>

namespace Catch {

template <>
struct StringMaker<oxenc::bt_value> {
    static std::string convert(const oxenc::bt_value& value);
};

template <>
struct StringMaker<oxenc::bt_list> {
    static std::string convert(const oxenc::bt_list& value);
};
template <>
struct StringMaker<oxenc::bt_dict> {
    static std::string convert(const oxenc::bt_dict& value);
};

inline std::string StringMaker<oxenc::bt_value>::convert(const oxenc::bt_value& value) {
    return var::visit(
            [](const auto& x) {
                return StringMaker<oxenc::remove_cvref_t<decltype(x)>>{}.convert(x);
            },
            static_cast<const oxenc::bt_variant&>(value));
}
inline std::string StringMaker<oxenc::bt_list>::convert(const oxenc::bt_list& value) {
    std::string r = "[";
    for (auto& el : value) {
        r += StringMaker<oxenc::bt_value>{}.convert(el);
        r += ", ";
    }
    if (!value.empty()) {
        r.pop_back();
        r.back() = ']';
    }
    return r;
}

inline std::string StringMaker<oxenc::bt_dict>::convert(const oxenc::bt_dict& value) {
    std::string r = "{ ";
    for (auto& [key, val] : value) {
        r += key;
        r += ": ";
        r += StringMaker<oxenc::bt_value>{}.convert(val);
        r += ", ";
    }
    if (!value.empty()) {
        *(r.rbegin() + 1) = ' ';
    }
    r.back() = '}';

    return r;
}

}  // namespace Catch
