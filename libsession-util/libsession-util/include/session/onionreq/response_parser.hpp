#pragma once

#include <string>

#include "hop_encryption.hpp"
#include "key_types.hpp"

namespace session::onionreq {

class ResponseParser {
  public:
    /// Constructs a parser, parsing the given request sent to us.  Throws if parsing or decryption
    /// fails.
    ResponseParser(session::onionreq::Builder builder);
    ResponseParser(
            x25519_pubkey destination_x25519_public_key,
            x25519_keypair x25519_keypair,
            EncryptType enc_type = EncryptType::xchacha20) :
            destination_x25519_public_key_{std::move(destination_x25519_public_key)},
            x25519_keypair_{std::move(x25519_keypair)},
            enc_type_{enc_type} {}

    ustring decrypt(ustring ciphertext) const;

  private:
    x25519_pubkey destination_x25519_public_key_;
    x25519_keypair x25519_keypair_;
    EncryptType enc_type_;
};

}  // namespace session::onionreq
