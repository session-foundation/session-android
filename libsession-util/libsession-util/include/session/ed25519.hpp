#pragma once

#include <array>

#include "types.hpp"

namespace session::ed25519 {

/// Generates a random Ed25519 key pair
std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> ed25519_key_pair();

/// Given an Ed25519 seed this returns the associated Ed25519 key pair
std::pair<std::array<unsigned char, 32>, std::array<unsigned char, 64>> ed25519_key_pair(
        ustring_view ed25519_seed);

/// API: ed25519/seed_for_ed_privkey
///
/// Returns the seed for an ed25519 key pair given either the libsodium-style secret key, 64
/// bytes.  If a 32-byte value is provided it is assumed to be the seed and the value will just
/// be returned directly.
///
/// Inputs:
/// - `ed25519_privkey` -- the libsodium-style secret key of the sender, 64 bytes.  Can also be
///   passed as a 32-byte seed.
///
/// Outputs:
/// - The ed25519 seed
std::array<unsigned char, 32> seed_for_ed_privkey(ustring_view ed25519_privkey);

/// API: ed25519/sign
///
/// Generates a signature for the message using the libsodium-style ed25519 secret key, 64 bytes.
///
/// Inputs:
/// - `ed25519_privkey` -- the libsodium-style secret key, 64 bytes.
/// - `msg` -- the data to generate a signature for.
///
/// Outputs:
/// - The ed25519 signature
ustring sign(ustring_view ed25519_privkey, ustring_view msg);

/// API: ed25519/verify
///
/// Verify a message and signature for a given pubkey.
///
/// Inputs:
/// - `sig` -- the signature to verify, 64 bytes.
/// - `pubkey` -- the pubkey for the secret key that was used to generate the signature, 32 bytes.
/// - `msg` -- the data to verify the signature for.
///
/// Outputs:
/// - A flag indicating whether the signature is valid
bool verify(ustring_view sig, ustring_view pubkey, ustring_view msg);

}  // namespace session::ed25519
