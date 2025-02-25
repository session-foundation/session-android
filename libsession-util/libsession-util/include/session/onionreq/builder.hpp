#pragma once

#include <string>
#include <string_view>

#include "key_types.hpp"

namespace session::onionreq {

enum class EncryptType {
    aes_gcm,
    xchacha20,
};

// Takes the encryption type as a string, returns the EncryptType value (or throws if invalid).
// Supported values: aes-gcm and xchacha20.  gcm is accepted as an aliases for aes-gcm.
EncryptType parse_enc_type(std::string_view enc_type);

inline constexpr std::string_view to_string(EncryptType type) {
    switch (type) {
        case EncryptType::xchacha20: return "xchacha20"sv;
        case EncryptType::aes_gcm: return "aes-gcm"sv;
    }
    return ""sv;
}

// Builder class for preparing onion request payloads.
class Builder {
  public:
    EncryptType enc_type;
    std::optional<x25519_pubkey> destination_x25519_public_key = std::nullopt;
    std::optional<x25519_keypair> final_hop_x25519_keypair = std::nullopt;

    Builder(EncryptType enc_type_ = EncryptType::xchacha20) : enc_type{enc_type_} {}

    void set_enc_type(EncryptType enc_type_) { enc_type = enc_type_; }

    void set_snode_destination(ed25519_pubkey ed25519_public_key, x25519_pubkey x25519_public_key) {
        destination_x25519_public_key.reset();
        ed25519_public_key_.reset();
        destination_x25519_public_key.emplace(x25519_public_key);
        ed25519_public_key_.emplace(ed25519_public_key);
    }

    void set_server_destination(
            std::string host,
            std::string target,
            std::string protocol,
            std::optional<uint16_t> port,
            x25519_pubkey x25519_public_key) {
        destination_x25519_public_key.reset();

        host_.emplace(host);
        target_.emplace(target);
        protocol_.emplace(protocol);

        if (port)
            port_.emplace(*port);

        destination_x25519_public_key.emplace(x25519_public_key);
    }

    void add_hop(std::pair<ed25519_pubkey, x25519_pubkey> keys) { hops_.push_back(keys); }

    ustring build(ustring payload);

  private:
    std::vector<std::pair<ed25519_pubkey, x25519_pubkey>> hops_ = {};

    // Snode request values

    std::optional<ed25519_pubkey> ed25519_public_key_ = std::nullopt;

    // Proxied request values

    std::optional<std::string> host_ = std::nullopt;
    std::optional<std::string> target_ = std::nullopt;
    std::optional<std::string> protocol_ = std::nullopt;
    std::optional<uint16_t> port_ = std::nullopt;
};

}  // namespace session::onionreq
