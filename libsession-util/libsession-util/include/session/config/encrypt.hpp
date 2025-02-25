#pragma once

#include <stdexcept>

#include "../types.hpp"

namespace session::config {

/// API: encrypt/encrypt
///
/// Encrypts a config message using XChaCha20-Poly1305, using a blake2b keyed hash of the message
/// for the nonce (rather than pure random) so that different clients will encrypt the same data to
/// the same encrypted value (thus allowing for server-side deduplication of identical messages).
///
/// `key_base` must be 32 bytes.  This value is a fixed key that all clients that might receive this
/// message can calculate independently (for instance a value derived from a secret key, or a shared
/// random key).  This key will be hashed with the message size and domain suffix (see below) to
/// determine the actual encryption key.
///
/// `domain` is a short string (1-24 chars) used for the keyed hash.  Typically this is the type of
/// config, e.g. "closed-group" or "contacts".  The full key will be
/// "session-config-encrypted-message-[domain]".  This value is also used for the encrypted key (see
/// above).
///
/// The returned result will consist of encrypted data with authentication tag and appended nonce,
/// suitable for being passed to decrypt() to authenticate and decrypt.
///
/// Throw std::invalid_argument on bad input (i.e. from invalid key_base or domain).
///
/// Inputs:
/// - `message` -- message to encrypt
/// - `key_base` -- Fixed key that all clients, must be 32 bytes.
/// - `domain` -- short string for the keyed hash
///
/// Outputs:
/// - `ustring` -- Returns the encrypted message bytes
ustring encrypt(ustring_view message, ustring_view key_base, std::string_view domain);

/// API: encrypt/encrypt_inplace
///
/// Same as above `encrypt`, but modifies `message` in place.  `message` gets encrypted plus has the
/// extra data and nonce appended.
///
/// Inputs:
/// - `message` -- message to encrypt
/// - `key_base` -- Fixed key that all clients, must be 32 bytes.
/// - `domain` -- short string for the keyed hash
void encrypt_inplace(ustring& message, ustring_view key_base, std::string_view domain);

/// Constant amount of extra bytes required to be appended when encrypting.
constexpr size_t ENCRYPT_DATA_OVERHEAD = 40;  // ABYTES + NPUBBYTES

/// Thrown if decrypt() fails.
struct decrypt_error : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/// API: encrypt/decrypt
///
/// Takes a value produced by `encrypt()` and decrypts it.  `key_base` and `domain` must be the same
/// given to encrypt or else decryption fails.  Upon decryption failure a `decrypt_error` exception
/// is thrown.
///
/// Inputs:
/// - `ciphertext` -- message to decrypt
/// - `key_base` -- Fixed key that all clients, must be 32 bytes.
/// - `domain` -- short string for the keyed hash
///
/// Outputs:
/// - `ustring` -- Returns the decrypt message bytes
ustring decrypt(ustring_view ciphertext, ustring_view key_base, std::string_view domain);

/// API: encrypt/decrypt_inplace
///
/// Same as above `decrypt()`, but does in in-place.  The string gets shortend to the plaintext
/// after this call.
///
/// Inputs:
/// - `ciphertext` -- message to decrypt
/// - `key_base` -- Fixed key that all clients, must be 32 bytes.
/// - `domain` -- short string for the keyed hash
void decrypt_inplace(ustring& ciphertext, ustring_view key_base, std::string_view domain);

/// Returns the target size of the message with padding, assuming an additional `overhead` bytes of
/// overhead (e.g. from encrypt() overhead) will be appended.  Will always return a value >= s +
/// overhead.
///
/// Padding increments we use: 256 byte increments up to 5120; 1024 byte increments up to 20480,
/// 2048 increments up to 40960, then 5120 from there up.
inline constexpr size_t padded_size(size_t s, size_t overhead = ENCRYPT_DATA_OVERHEAD) {
    size_t s2 = s + overhead;
    size_t chunk = s2 < 5120 ? 256 : s2 < 20480 ? 1024 : s2 < 40960 ? 2048 : 5120;
    return (s2 + chunk - 1) / chunk * chunk - overhead;
}

/// API: encrypt/pad_message
///
/// Inserts null byte padding to the beginning of a message to make the final message size granular.
/// See the above function for the sizes.
///
/// \param data - the data; this is modified in place.
/// \param overhead -
///
/// Inputs:
/// - `data` -- the data; this is modified in place
/// - `overhead` -- encryption overhead to account for to reach the desired padded size.  The
/// default, if omitted, is the space used by the `encrypt()` function defined above.
void pad_message(ustring& data, size_t overhead = ENCRYPT_DATA_OVERHEAD);

}  // namespace session::config
