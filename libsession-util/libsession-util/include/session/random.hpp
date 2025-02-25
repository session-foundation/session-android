#pragma once

#include "types.hpp"

namespace session::random {

/// API: random/random
///
/// Wrapper around the randombytes_buf function.
///
/// Inputs:
/// - `size` -- the number of random bytes to be generated.
///
/// Outputs:
/// - random bytes of the specified length.
ustring random(size_t size);

}  // namespace session::random
