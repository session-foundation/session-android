#pragma once

#include <optional>

#include "types.hpp"

namespace session::hash {

/// API: hash/hash
///
/// Wrapper around the crypto_generichash_blake2b function.
///
/// Inputs:
/// - `size` -- length of the hash to be generated.
/// - `msg` -- the message to generate a hash for.
/// - `key` -- an optional key to be used when generating the hash.  Can be omitted or an empty
///   string for an unkeyed hash.
///
/// Outputs:
/// - a `size` byte hash.
ustring hash(const size_t size, ustring_view msg, std::optional<ustring_view> key = std::nullopt);

}  // namespace session::hash
