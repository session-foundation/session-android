#pragma once

#include <optional>
#include <string>
#include <string_view>

#include "platform.hpp"
#include "sodium_array.hpp"

namespace session {

/// Helper functions for community 25xxx blinded ID from a Session pubkey and the server pubkey.
/// This is a verifiable Ed25519 pubkey (prefixed with 25) and is fully deterministic.
///
/// Details: we first compute a blinding factor:
///
///     k = H(SESSION_ID || SERVER_PK) mod L,  where H = 64-byte BLAKE2b
///
/// Next we convert the Session ID (an X25519 pubkey) to Ed25519, and also clear the sign bit
/// (i.e. take the positive).  Thus we give up one bit of the pubkey (because the initial X25519
/// -> Ed25519 conversion lost it).
///
///     X = session id x25519 pubkey
///     A = |Ed(X)| -- i.e. + of the two possible Ed25519 pubkey alternatives for X
///
/// then the blinded pubkey is prefix "25" followed by `kA`.
///
/// To create such a signature, starting from the underlying Ed25519 keypair from seed `s`, with
/// private scalar `z` (NOT the seed) and public key point `S`, we use the same blinding factor
/// to compute private scalar `a` associated with `A`:
///
///     a = z if S is positive (i.e. sign bit is 0)
///     a = -z if S is negative (sign bit is 1)
///
/// which yields our blinded private scalar `ka`.
///
/// From here we generate a signature very similarly to EdDSA, but adapted to this different
/// private signing mechanism.  For a message `M`:
///
///     r = H64(H32(seed, key="SessCommBlind25_seed") || kA || M, key="SessCommBlind25_sig") mod L
///
/// analagously to Ed25519's
///
///     r = SHA512(SHA512(seed)[32:64] || M) mod L
///
/// but using BLAKE2b 64-byte and 32-byte keyed hashes instead of SHA512.  (We also include the
/// `A` in the hash so that the same message with different server_pks will result in different
/// `r` values).
///
/// From there we follow the standard EdDSA construction:
///
///     R = rG
///     S = r + H(R || kA || M) ka   (mod L)
///
/// (using the standard Ed25519 SHA-512 here for H, so that this is verifiable as a standard
/// Ed25519 signature).
///
/// This (R, S) signature is then Ed25519-verifiable using pubkey kA.

/// Returns the blinding factor for 15 blinding.  Typically this isn't used directly, but is
/// exposed for debugging/testing.  Takes server pk in bytes, not hex.
uc32 blind15_factor(ustring_view server_pk);

/// Returns the blinding factor for 25 blinding.  Typically this isn't used directly, but is
/// exposed for debugging/testing.  Takes session id and server pk in bytes, not hex.  session
/// id can be 05-prefixed (33 bytes) or unprefixed (32 bytes).
uc32 blind25_factor(ustring_view session_id, ustring_view server_pk);

/// Computes the two possible 15-blinded ids from a session id and server pubkey.  Values accepted
/// and returned are hex-encoded.
std::array<std::string, 2> blind15_id(std::string_view session_id, std::string_view server_pk);

/// Similar to the above, but takes the session id and pubkey as byte values instead of hex, and
/// returns a single 33-byte value (instead of a 66-digit hex value).  Unlike the string version,
/// session_id here may be passed unprefixed (i.e. 32 bytes instead of 33 with the 05 prefix).  Only
/// the *positive* possible ID is returned: the alternative can be computed by flipping the highest
/// bit of byte 32, i.e.: `result[32] ^= 0x80`.
ustring blind15_id(ustring_view session_id, ustring_view server_pk);

/// Computes the 25-blinded id from a session id and server pubkey.  Values accepted and
/// returned are hex-encoded.
std::string blind25_id(std::string_view session_id, std::string_view server_pk);

/// Same as above, but takes the session id and pubkey as byte values instead of hex, and returns a
/// 33-byte value (instead of a 66-digit hex value).  Unlike the string version, session_id here may
/// be passed unprefixed (i.e. 32 bytes instead of 33 with the 05 prefix).
ustring blind25_id(ustring_view session_id, ustring_view server_pk);

/// Computes the 15-blinded id from a 32-byte Ed25519 pubkey, i.e. from the known underlying Ed25519
/// pubkey behind a (X25519) Session ID.  Unlike blind15_id, knowing the true Ed25519 pubkey allows
/// thie method to compute the correct sign and so using this does not require considering that the
/// resulting blinded ID might need to have a sign flipped.
///
/// If the `session_id` is a non-null pointer then it must point at an empty string to be populated
/// with the session_id associated with `ed_pubkey`.  This is here for consistency with
/// `blinded25_id_from_ed`, but unlike the 25 version, this value is not read if non-empty, and is
/// not an optimization (that is: it is purely for convenience and is no more efficient to use this
/// than it is to compute it yourself).
ustring blinded15_id_from_ed(
        ustring_view ed_pubkey, ustring_view server_pk, ustring* session_id = nullptr);

/// Computes the 25-blinded id from a 32-byte Ed25519 pubkey, i.e. from the known underlying Ed25519
/// pubkey behind a (X25519) Session ID.  This will be the same as blind25_id (if given the X25519
/// pubkey that the Ed25519 converts to), but is more efficient when the Ed25519 pubkey is already
/// known.
///
/// The session_id argument is provided to optimize input or output of the session ID derived from
/// the Ed25519 pubkey: if already computed, this argument can be a pointer to a 33-byte string
/// containing the precomputed value (to avoid needing to compute it again).  If unknown but needed
/// then a pointer to an empty string can be given to computed and stored the value here.  Otherwise
/// (if omitted or nullptr) then the value will temporarily computed within the function.
ustring blinded25_id_from_ed(
        ustring_view ed_pubkey, ustring_view server_pk, ustring* session_id = nullptr);

/// Computes a 15-blinded key pair.
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed) and the server pubkey (in hex (64
/// digits) or bytes (32 bytes)).  Returns the blinded public key and private key (NOT a seed).
///
/// Can optionally also return the blinding factor, k, by providing a pointer to a uc32 (or
/// cleared_uc32); if non-nullptr then k will be written to it.
///
/// It is recommended to pass the full 64-byte libsodium-style secret key for `ed25519_sk` (i.e.
/// seed + appended pubkey) as with just the 32-byte seed the public key has to be recomputed.
std::pair<uc32, cleared_uc32> blind15_key_pair(
        ustring_view ed25519_sk, ustring_view server_pk, uc32* k = nullptr);

/// Computes a 25-blinded key pair.
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed) and the server pubkey (in hex (64
/// digits) or bytes (32 bytes)).  Returns the blinded public key and private key (NOT a seed).
///
/// Can optionally also return the blinding factor, k', by providing a pointer to a uc32 (or
/// cleared_uc32); if non-nullptr then k' will be written to it, where k' = ±k.  Here, `k'` can be
/// negative to cancel out a negative in the true pubkey, which the remote client will always assume
/// is not present when it does a Session ID -> Ed25519 conversion for blinding purposes.
///
/// It is recommended to pass the full 64-byte libsodium-style secret key for `ed25519_sk` (i.e.
/// seed + appended pubkey) as with just the 32-byte seed the public key has to be recomputed.
std::pair<uc32, cleared_uc32> blind25_key_pair(
        ustring_view ed25519_sk, ustring_view server_pk, uc32* k_prime = nullptr);

/// Computes a version-blinded key pair.
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed).  Returns the blinded public key and
/// blinded libsodium seed value.
///
/// It is recommended to pass the full 64-byte libsodium-style secret key for `ed25519_sk` (i.e.
/// seed + appended pubkey) as with just the 32-byte seed the public key has to be recomputed.
std::pair<uc32, cleared_uc64> blind_version_key_pair(ustring_view ed25519_sk);

/// Computes a verifiable 15-blinded signature that validates with the blinded pubkey that would
/// be returned from blind15_key_pair().
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed) and the server pubkey (in hex (64
/// digits) or bytes (32 bytes)).  Returns the 64-byte signature.
///
/// It is recommended to pass the full 64-byte libsodium-style secret key for `ed25519_sk` (i.e.
/// seed + appended pubkey) as with just the 32-byte seed the public key has to be recomputed.
ustring blind15_sign(ustring_view ed25519_sk, std::string_view server_pk_in, ustring_view message);

/// Computes a verifiable 25-blinded signature that validates with the blinded pubkey that would
/// be returned from blind25_id().
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed) and the server pubkey (in hex (64
/// digits) or bytes (32 bytes)).  Returns the 64-byte signature.
///
/// It is recommended to pass the full 64-byte libsodium-style secret key for `ed25519_sk` (i.e.
/// seed + appended pubkey) as with just the 32-byte seed the public key has to be recomputed.
ustring blind25_sign(ustring_view ed25519_sk, std::string_view server_pk, ustring_view message);

/// Computes a verifiable version-blinded signature that validates with the version-blinded pubkey
/// that would be returned from blind_version_key_pair.
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed), unix timestamp, method, path, and
/// optional body.
/// Returns the version-blinded signature.
ustring blind_version_sign_request(
        ustring_view ed25519_sk,
        uint64_t timestamp,
        std::string_view method,
        std::string_view path,
        std::optional<ustring_view> body);

/// Computes a verifiable version-blinded signature that validates with the version-blinded pubkey
/// that would be returned from blind_version_key_pair.
///
/// Takes the Ed25519 secret key (64 bytes, or 32-byte seed), current platform and unix timestamp.
/// Returns the version-blinded signature.
ustring blind_version_sign(ustring_view ed25519_sk, Platform platform, uint64_t timestamp);

/// Takes in a standard session_id and returns a flag indicating whether it matches the given
/// blinded_id for a given server_pk.
///
/// Takes either a 15 or 25 blinded_id (66 bytes) and the server pubkey (64 bytes).
///
/// Returns a flag indicating whether the session_id matches the blinded_id.
bool session_id_matches_blinded_id(
        std::string_view session_id, std::string_view blinded_id, std::string_view server_pk);

}  // namespace session
