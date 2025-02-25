#pragma once

#include <stdexcept>

#include "session/types.hpp"

namespace session::config {

// Profile pic info.
struct profile_pic {
    static constexpr size_t MAX_URL_LENGTH = 223;

    std::string url;
    ustring key;

    static void check_key(ustring_view key) {
        if (!(key.empty() || key.size() == 32))
            throw std::invalid_argument{"Invalid profile pic key: 32 bytes required"};
    }

    // Default constructor, makes an empty profile pic
    profile_pic() = default;

    // Constructs from a URL and key.  Key must be empty or 32 bytes.
    profile_pic(std::string_view url, ustring_view key) : url{url}, key{key} {
        check_key(this->key);
    }

    // Constructs from a string/ustring pair moved into the constructor
    profile_pic(std::string&& url, ustring&& key) : url{std::move(url)}, key{std::move(key)} {
        check_key(this->key);
    }

    /// API: profile_pic/profile_pic::empty
    ///
    /// Returns true if either url or key are empty (or invalid)
    ///
    /// Inputs: None
    ///
    /// Outputs:
    ///  - `bool` -- Returns true if either url or key are empty
    bool empty() const { return url.empty() || key.size() != 32; }

    /// API: profile_pic/profile_pic::clear
    ///
    /// Clears the current url/key, if set.  This is just a shortcut for calling `.clear()` on each
    /// of them.
    ///
    /// Inputs: None
    void clear() {
        url.clear();
        key.clear();
    }

    // The object in boolean context is true if url and key are both set, i.e. the opposite of
    // `empty()`.
    explicit operator bool() const { return !empty(); }

    /// API: profile_pic/profile_pic::set_key
    ///
    /// Sets and validates the key.  The key can be empty, or 32 bytes.  This is almost the same as
    /// just setting `.key` directly, except that it will throw if the provided key is invalid (i.e.
    /// neither empty nor 32 bytes).
    ///
    /// Inputs:
    /// - `new_key` -- binary data of a new key to be set. Must be 32 bytes
    void set_key(ustring new_key) {
        check_key(new_key);
        key = std::move(new_key);
    }
};

}  // namespace session::config
