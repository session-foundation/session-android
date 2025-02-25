#pragma once

#include <string>
#include <string_view>

#include "builder.hpp"
#include "key_types.hpp"

namespace session::onionreq {

// Encryption/decription class for encryption/decrypting outgoing/incoming messages.
class HopEncryption {
  public:
    HopEncryption(x25519_seckey private_key, x25519_pubkey public_key, bool server = true) :
            private_key_{std::move(private_key)},
            public_key_{std::move(public_key)},
            server_{server} {}

    // Encrypts `plaintext` message using encryption `type`. `pubkey` is the recipients public key.
    // `reply` should be false for a client-to-snode message, and true on a returning
    // snode-to-client message.
    ustring encrypt(EncryptType type, ustring plaintext, const x25519_pubkey& pubkey) const;
    ustring decrypt(EncryptType type, ustring ciphertext, const x25519_pubkey& pubkey) const;

    // AES-GCM encryption.
    ustring encrypt_aesgcm(ustring plainText, const x25519_pubkey& pubKey) const;
    ustring decrypt_aesgcm(ustring cipherText, const x25519_pubkey& pubKey) const;

    // xchacha20-poly1305 encryption; for a message sent from client Alice to server Bob we use a
    // shared key of a Blake2B 32-byte (i.e. crypto_aead_xchacha20poly1305_ietf_KEYBYTES) hash of
    // H(aB || A || B), which Bob can compute when receiving as H(bA || A || B).  The returned value
    // always has the crypto_aead_xchacha20poly1305_ietf_NPUBBYTES nonce prepended to the beginning.
    //
    // When Bob (the server) encrypts a method for Alice (the client), he uses shared key
    // H(bA || A || B) (note that this is *different* that what would result if Bob was a client
    // sending to Alice the client).
    ustring encrypt_xchacha20(ustring plaintext, const x25519_pubkey& pubKey) const;
    ustring decrypt_xchacha20(ustring ciphertext, const x25519_pubkey& pubKey) const;

  private:
    const x25519_seckey private_key_;
    const x25519_pubkey public_key_;
    bool server_;  // True if we are the server (i.e. the snode).
};

}  // namespace session::onionreq
