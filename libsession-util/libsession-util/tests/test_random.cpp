#include <catch2/catch_test_macros.hpp>

#include "session/random.h"
#include "session/random.hpp"
#include "utils.hpp"

TEST_CASE("Random generation", "[random][random]") {
    auto rand1 = session::random::random(10);
    auto rand2 = session::random::random(10);
    auto rand3 = session::random::random(20);

    CHECK(rand1.size() == 10);
    CHECK(rand2.size() == 10);
    CHECK(rand3.size() == 20);
    CHECK(rand1 != rand2);
}
