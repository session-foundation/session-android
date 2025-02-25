#pragma once

#include <array>

#include "types.hpp"

namespace session::curve25519 {

/// Generates a random curve25519 key pair
std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> curve25519_key_pair();

/// API: curve25519/to_curve25519_pubkey
///
/// Generates a curve25519 public key for an ed25519 public key.
///
/// Inputs:
/// - `ed25519_pubkey` -- the ed25519 public key.
///
/// Outputs:
/// - The curve25519 public key
std::array<unsigned char, 32> to_curve25519_pubkey(ustring_view ed25519_pubkey);

/// API: curve25519/to_curve25519_seckey
///
/// Generates a curve25519 secret key given given a libsodium-style secret key, 64
/// bytes.
///
/// Inputs:
/// - `ed25519_seckey` -- the libsodium-style secret key, 64 bytes.
///
/// Outputs:
/// - The curve25519 secret key
std::array<unsigned char, 32> to_curve25519_seckey(ustring_view ed25519_seckey);

}  // namespace session::curve25519
