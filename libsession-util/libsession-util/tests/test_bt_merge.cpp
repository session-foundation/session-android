#include <catch2/catch_test_macros.hpp>

#include "catch2_bt_format.hpp"
#include "session/bt_merge.hpp"

using oxenc::bt_dict;
using oxenc::bt_list;
using oxenc::bt_value;

TEST_CASE("bt_dict merging", "[bt_dict][merge]") {
    bt_dict x{{"a", 1}, {"b", 2}, {"c", 3}};
    bt_dict y{{"a", 42}, {"b", -123}, {"x", 12}, {"y", 17}, {"Z", 4}};

    CHECK(session::bt::merge(x, y) ==
          bt_dict{{"a", 1}, {"b", 2}, {"c", 3}, {"x", 12}, {"y", 17}, {"Z", 4}});

    CHECK(session::bt::merge(y, x) ==
          bt_dict{{"a", 42}, {"b", -123}, {"c", 3}, {"x", 12}, {"y", 17}, {"Z", 4}});

    CHECK(session::bt::merge(x, bt_dict{}) == x);
    CHECK(session::bt::merge(bt_dict{}, x) == x);
    CHECK(session::bt::merge(y, bt_dict{}) == y);
    CHECK(session::bt::merge(bt_dict{}, y) == y);
    CHECK(session::bt::merge(bt_dict{}, bt_dict{}) == bt_dict{});
}

TEST_CASE("bt_list sorted merge", "[bt_list][merge]") {
    bt_list x{1, 2, 3, 5, 8, 13, 21};
    bt_list y{2, 4, 8, 16};

    auto compare = [](const auto& a, const auto& b) {
        return var::get<int64_t>(a) < var::get<int64_t>(b);
    };

    CHECK(session::bt::merge_sorted(x, y, compare) == bt_list{1, 2, 3, 4, 5, 8, 13, 16, 21});

    CHECK(session::bt::merge_sorted(x, y, compare, true) ==
          bt_list{1, 2, 2, 3, 4, 5, 8, 8, 13, 16, 21});

    CHECK(session::bt::merge_sorted(bt_list{1, 2}, bt_list{2}, compare) == bt_list{1, 2});
    CHECK(session::bt::merge_sorted(bt_list{1, 2}, bt_list{2}, compare, true) == bt_list{1, 2, 2});
    CHECK(session::bt::merge_sorted(bt_list{2}, bt_list{2}, compare) == bt_list{2});
    CHECK(session::bt::merge_sorted(bt_list{}, bt_list{2}, compare) == bt_list{2});
    CHECK(session::bt::merge_sorted(bt_list{2}, bt_list{}, compare) == bt_list{2});
    CHECK(session::bt::merge_sorted(bt_list{}, bt_list{}, compare) == bt_list{});
    CHECK(session::bt::merge_sorted(bt_list{}, bt_list{}, compare, true) == bt_list{});
}
