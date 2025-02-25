#include "session/bt_merge.hpp"

namespace session::bt {
bt_dict merge(const bt_dict& a, const bt_dict& b) {
    bt_dict result;
    auto it_a = a.begin();
    auto it_b = b.begin();
    while (it_a != a.end() || it_b != b.end()) {
        auto c = it_b == b.end() ? -1 : it_a == a.end() ? 1 : it_a->first.compare(it_b->first);
        if (c <= 0) {
            result.insert(result.end(), *it_a++);
            if (c == 0)
                ++it_b;  // equal keys: ignore value from b
        } else {
            result.insert(result.end(), *it_b++);
        }
    }
    return result;
}

}  // namespace session::bt
