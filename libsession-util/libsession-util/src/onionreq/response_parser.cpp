#include "session/onionreq/response_parser.hpp"

#include <oxenc/endian.h>
#include <sodium/core.h>

#include <stdexcept>

#include "session/export.h"
#include "session/onionreq/builder.h"
#include "session/onionreq/builder.hpp"
#include "session/onionreq/hop_encryption.hpp"

namespace session::onionreq {

ResponseParser::ResponseParser(session::onionreq::Builder builder) {
    if (!builder.destination_x25519_public_key.has_value())
        throw std::runtime_error{"Builder does not contain destination x25519 public key"};
    if (!builder.final_hop_x25519_keypair.has_value())
        throw std::runtime_error{"Builder does not contain final keypair"};

    enc_type_ = builder.enc_type;
    destination_x25519_public_key_ = builder.destination_x25519_public_key.value();
    x25519_keypair_ = builder.final_hop_x25519_keypair.value();
}

ustring ResponseParser::decrypt(ustring ciphertext) const {
    HopEncryption d{x25519_keypair_.second, x25519_keypair_.first, false};

    // FIXME: The legacy PN server doesn't support 'xchacha20' onion requests so would return an
    // error encrypted with 'aes_gcm' so try to decrypt in case that is what happened - this
    // workaround can be removed once the legacy PN server is removed
    try {
        return d.decrypt(enc_type_, ciphertext, destination_x25519_public_key_);
    } catch (const std::exception& e) {
        if (enc_type_ == session::onionreq::EncryptType::xchacha20)
            return d.decrypt(
                    session::onionreq::EncryptType::aes_gcm,
                    ciphertext,
                    destination_x25519_public_key_);
        else
            throw e;
    }
}

}  // namespace session::onionreq

extern "C" {

using session::ustring;

LIBSESSION_C_API bool onion_request_decrypt(
        const unsigned char* ciphertext,
        size_t ciphertext_len,
        ENCRYPT_TYPE enc_type_,
        unsigned char* destination_x25519_pubkey,
        unsigned char* final_x25519_pubkey,
        unsigned char* final_x25519_seckey,
        unsigned char** plaintext_out,
        size_t* plaintext_out_len) {
    assert(ciphertext && destination_x25519_pubkey && final_x25519_pubkey && final_x25519_seckey &&
           ciphertext_len > 0);

    try {
        auto enc_type = session::onionreq::EncryptType::xchacha20;

        switch (enc_type_) {
            case ENCRYPT_TYPE::ENCRYPT_TYPE_AES_GCM:
                enc_type = session::onionreq::EncryptType::aes_gcm;
                break;

            case ENCRYPT_TYPE::ENCRYPT_TYPE_X_CHA_CHA_20:
                enc_type = session::onionreq::EncryptType::xchacha20;
                break;

            default:
                throw std::runtime_error{"Invalid decryption type " + std::to_string(enc_type_)};
        }

        session::onionreq::HopEncryption d{
                session::onionreq::x25519_seckey::from_bytes({final_x25519_seckey, 32}),
                session::onionreq::x25519_pubkey::from_bytes({final_x25519_pubkey, 32}),
                false};

        ustring result;

        // FIXME: The legacy PN server doesn't support 'xchacha20' onion requests so would return an
        // error encrypted with 'aes_gcm' so try to decrypt in case that is what happened - this
        // workaround can be removed once the legacy PN server is removed
        try {
            result = d.decrypt(
                    enc_type,
                    ustring{ciphertext, ciphertext_len},
                    session::onionreq::x25519_pubkey::from_bytes({destination_x25519_pubkey, 32}));
        } catch (...) {
            if (enc_type == session::onionreq::EncryptType::xchacha20)
                result = d.decrypt(
                        session::onionreq::EncryptType::aes_gcm,
                        ustring{ciphertext, ciphertext_len},
                        session::onionreq::x25519_pubkey::from_bytes(
                                {destination_x25519_pubkey, 32}));
            else
                return false;
        }

        *plaintext_out = static_cast<unsigned char*>(malloc(result.size()));
        *plaintext_out_len = result.size();
        std::memcpy(*plaintext_out, result.data(), result.size());
        return true;
    } catch (...) {
        return false;
    }
}
}
