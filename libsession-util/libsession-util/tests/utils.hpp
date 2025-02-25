#pragma once

#include <oxenc/hex.h>

#include <array>
#include <chrono>
#include <cstddef>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "session/config/base.h"

using ustring = std::basic_string<unsigned char>;
using ustring_view = std::basic_string_view<unsigned char>;

inline ustring operator""_bytes(const char* x, size_t n) {
    return {reinterpret_cast<const unsigned char*>(x), n};
}
inline ustring operator""_hexbytes(const char* x, size_t n) {
    ustring bytes;
    oxenc::from_hex(x, x + n, std::back_inserter(bytes));
    return bytes;
}

inline std::string to_hex(ustring_view bytes) {
    std::string hex;
    oxenc::to_hex(bytes.begin(), bytes.end(), std::back_inserter(hex));
    return hex;
}

inline constexpr auto operator""_kiB(unsigned long long kiB) {
    return kiB * 1024;
}

inline int64_t get_timestamp_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::system_clock::now().time_since_epoch())
            .count();
}

inline std::string_view to_sv(ustring_view x) {
    return {reinterpret_cast<const char*>(x.data()), x.size()};
}
inline ustring_view to_usv(std::string_view x) {
    return {reinterpret_cast<const unsigned char*>(x.data()), x.size()};
}
template <size_t N>
ustring_view to_usv(const std::array<unsigned char, N>& data) {
    return {data.data(), N};
}

inline std::string printable(ustring_view x) {
    std::string p;
    for (auto c : x) {
        if (c >= 0x20 && c <= 0x7e)
            p += c;
        else
            p += "\\x" + oxenc::to_hex(&c, &c + 1);
    }
    return p;
}
inline std::string printable(std::string_view x) {
    return printable(to_usv(x));
}
std::string printable(const unsigned char* x) = delete;
inline std::string printable(const unsigned char* x, size_t n) {
    return printable({x, n});
}

template <typename Container>
std::set<typename Container::value_type> as_set(const Container& c) {
    return {c.begin(), c.end()};
}

template <typename... T>
std::set<std::common_type_t<T...>> make_set(T&&... args) {
    return {std::forward<T>(args)...};
}

template <typename C>
std::vector<std::basic_string_view<C>> view_vec(std::vector<std::basic_string<C>>&& v) = delete;
template <typename C>
std::vector<std::basic_string_view<C>> view_vec(const std::vector<std::basic_string<C>>& v) {
    std::vector<std::basic_string_view<C>> vv;
    vv.reserve(v.size());
    std::copy(v.begin(), v.end(), std::back_inserter(vv));
    return vv;
}
