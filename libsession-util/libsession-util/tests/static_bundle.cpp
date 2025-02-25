// This file isn't designed to do anything useful, but just to test that we can compile and link
// against the combined static bundle (when using cmake ... -DSTATIC_BUILD=ON)

#include <random>
#include <session/config/groups/keys.hpp>

int main() {
    if (std::mt19937_64{}() == 123) {
        auto& k = *reinterpret_cast<session::config::groups::Keys*>(12345);
        k.encrypt_message(session::ustring_view{});
    }
}
