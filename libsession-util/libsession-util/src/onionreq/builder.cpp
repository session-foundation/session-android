#include "session/onionreq/builder.hpp"

#include <nettle/gcm.h>
#include <oxenc/endian.h>
#include <oxenc/hex.h>
#include <sodium/crypto_aead_xchacha20poly1305.h>
#include <sodium/crypto_auth_hmacsha256.h>
#include <sodium/crypto_box.h>
#include <sodium/crypto_generichash.h>
#include <sodium/crypto_scalarmult.h>
#include <sodium/randombytes.h>
#include <sodium/utils.h>

#include <exception>
#include <iostream>
#include <memory>
#include <nlohmann/json.hpp>

#include "session/export.h"
#include "session/onionreq/builder.h"
#include "session/onionreq/hop_encryption.hpp"
#include "session/onionreq/key_types.hpp"
#include "session/util.hpp"
#include "session/xed25519.hpp"

namespace session::onionreq {

namespace {

    ustring encode_size(uint32_t s) {
        ustring result;
        result.resize(4);
        oxenc::write_host_as_little(s, result.data());
        return result;
    }
}  // namespace

EncryptType parse_enc_type(std::string_view enc_type) {
    if (enc_type == "xchacha20" || enc_type == "xchacha20-poly1305")
        return EncryptType::xchacha20;
    if (enc_type == "aes-gcm" || enc_type == "gcm")
        return EncryptType::aes_gcm;
    throw std::runtime_error{"Invalid encryption type " + std::string{enc_type}};
}

ustring Builder::build(ustring payload) {
    ustring blob;

    // First hop:
    //
    // [N][ENCRYPTED]{json}
    //
    // where json has the ephemeral_key indicating how we encrypted ENCRYPTED for this first hop.
    // The first hop decrypts ENCRYPTED into:
    //
    // [N][BLOB]{json}
    //
    // where [N] is the length of the blob and {json} now contains either:
    // - a "headers" key with an empty value.  This is how we indicate that the request is for this
    //   node as the final hop, and means that the BLOB is actually JSON it should parse to get the
    //   request info (which has "method", "params", etc. in it).
    // - "host"/"target"/"port"/"protocol" asking for an HTTP or HTTPS proxy request to be made
    //   (though "target" must start with /loki/ or /oxen/ and end with /lsrpc).  (There is still a
    //   blob here, but it is not used and typically empty).
    // - "destination" and "ephemeral_key" to forward the request to the next hop.
    //
    // This later case continues onion routing by giving us something like:
    //
    //      {"destination":"ed25519pubkey","ephemeral_key":"x25519-eph-pubkey-for-decryption","enc_type":"xchacha20"}
    //
    // (enc_type can also be aes-gcm, and defaults to that if not specified).  We forward this via
    // oxenmq to the given ed25519pubkey (but since oxenmq uses x25519 pubkeys we first have to go
    // look it up), sending an oxenmq request to sn.onion_req_v2 of the following (but bencoded, not
    // json):
    //
    //  { "d": "BLOB", "ek": "ephemeral-key-in-binary", "et": "xchacha20", "nh": N }
    //
    // where BLOB is the opaque data received from the previous hop and N is the hop number which
    // gets incremented at each hop (and terminates if it exceeds 15).  That next hop decrypts BLOB,
    // giving it a value interpreted as the same [N][BLOB]{json} as above, and we recurse.
    //
    // On the *return* trip, the message gets encrypted (once!) at the final destination using the
    // derived key from the pubkey given to the final hop, base64-encoded, then passed back without
    // any onion encryption at all all the way back to the client.

    // Ephemeral keypair:
    x25519_pubkey A;
    x25519_seckey a;
    nlohmann::json final_route;

    {
        crypto_box_keypair(A.data(), a.data());
        HopEncryption e{a, A, false};

        // The data we send to the destination differs depending on whether the destination is a
        // server or a service node
        if (host_ && target_ && protocol_ && destination_x25519_public_key) {
            final_route = {
                    {"host", host_.value()},
                    {"target", target_.value()},
                    {"method", "POST"},
                    {"protocol", protocol_.value()},
                    {"port", port_.value_or(protocol_.value() == "https" ? 443 : 80)},
                    {"ephemeral_key", A.hex()},  // The x25519 ephemeral_key here is the key for the
                                                 // *next* hop to use
                    {"enc_type", to_string(enc_type)},
            };

            blob = e.encrypt(enc_type, payload.data(), *destination_x25519_public_key);
        } else if (ed25519_public_key_ && destination_x25519_public_key) {
            nlohmann::json control{{"headers", ""}};
            final_route = {
                    {"destination", ed25519_public_key_.value().hex()},  // Next hop's ed25519 key
                    {"ephemeral_key", A.hex()},  // The x25519 ephemeral_key here is the key for the
                                                 // *next* hop to use
                    {"enc_type", to_string(enc_type)},
            };

            auto data = encode_size(payload.size());
            data += payload;
            data += to_unsigned_sv(control.dump());
            blob = e.encrypt(enc_type, data, *destination_x25519_public_key);
        } else {
            throw std::runtime_error{"Destination not set"};
        }

        // Save these because we need them again to decrypt the final response:
        final_hop_x25519_keypair.reset();
        final_hop_x25519_keypair.emplace(A, a);
    }

    for (auto it = hops_.rbegin(); it != hops_.rend(); ++it) {
        // Routing data for this hop:
        nlohmann::json routing;

        if (it == hops_.rbegin()) {
            routing = final_route;
        } else {
            routing = {
                    {"destination", std::prev(it)->first.hex()},  // Next hop's ed25519 key
                    {"ephemeral_key", A.hex()},  // The x25519 ephemeral_key here is the key for the
                                                 // *next* hop to use
                    {"enc_type", to_string(enc_type)},
            };
        }

        auto data = encode_size(blob.size());
        data += blob;
        data += to_unsigned_sv(routing.dump());

        // Generate eph key for *this* request and encrypt it:
        crypto_box_keypair(A.data(), a.data());
        HopEncryption e{a, A, false};
        blob = e.encrypt(enc_type, data, it->second);
    }

    // The data going to the first hop needs to be wrapped in one more layer to tell the first hop
    // how to decrypt the initial payload:
    auto result = encode_size(blob.size());
    result += blob;
    result += to_unsigned_sv(
            nlohmann::json{{"ephemeral_key", A.hex()}, {"enc_type", to_string(enc_type)}}.dump());

    return result;
}
}  // namespace session::onionreq

namespace {

session::onionreq::Builder& unbox(onion_request_builder_object* builder) {
    assert(builder && builder->internals);
    return *static_cast<session::onionreq::Builder*>(builder->internals);
}

}  // namespace

extern "C" {

using session::ustring;

LIBSESSION_C_API void onion_request_builder_init(onion_request_builder_object** builder) {
    auto c_builder = std::make_unique<onion_request_builder_object>();
    c_builder->internals = new session::onionreq::Builder{};
    *builder = c_builder.release();
}

LIBSESSION_C_API void onion_request_builder_free(onion_request_builder_object* builder) {
    delete static_cast<session::onionreq::Builder*>(builder->internals);
    delete builder;
}

LIBSESSION_C_API void onion_request_builder_set_enc_type(
        onion_request_builder_object* builder, ENCRYPT_TYPE enc_type) {
    assert(builder);

    switch (enc_type) {
        case ENCRYPT_TYPE::ENCRYPT_TYPE_AES_GCM:
            unbox(builder).set_enc_type(session::onionreq::EncryptType::aes_gcm);
            break;

        case ENCRYPT_TYPE::ENCRYPT_TYPE_X_CHA_CHA_20:
            unbox(builder).set_enc_type(session::onionreq::EncryptType::xchacha20);
            break;

        default: throw std::runtime_error{"Invalid encryption type"};
    }
}

LIBSESSION_C_API void onion_request_builder_set_snode_destination(
        onion_request_builder_object* builder,
        const char* ed25519_pubkey,
        const char* x25519_pubkey) {
    assert(builder && ed25519_pubkey && x25519_pubkey);

    unbox(builder).set_snode_destination(
            session::onionreq::ed25519_pubkey::from_hex({ed25519_pubkey, 64}),
            session::onionreq::x25519_pubkey::from_hex({x25519_pubkey, 64}));
}

LIBSESSION_C_API void onion_request_builder_set_server_destination(
        onion_request_builder_object* builder,
        const char* host,
        const char* target,
        const char* protocol,
        uint16_t port,
        const char* x25519_pubkey) {
    assert(builder && host && target && protocol && x25519_pubkey);

    unbox(builder).set_server_destination(
            host,
            target,
            protocol,
            port,
            session::onionreq::x25519_pubkey::from_hex({x25519_pubkey, 64}));
}

LIBSESSION_C_API void onion_request_builder_add_hop(
        onion_request_builder_object* builder,
        const char* ed25519_pubkey,
        const char* x25519_pubkey) {
    assert(builder && ed25519_pubkey && x25519_pubkey);

    unbox(builder).add_hop(
            {session::onionreq::ed25519_pubkey::from_hex({ed25519_pubkey, 64}),
             session::onionreq::x25519_pubkey::from_hex({x25519_pubkey, 64})});
}

LIBSESSION_C_API bool onion_request_builder_build(
        onion_request_builder_object* builder,
        const unsigned char* payload_in,
        size_t payload_in_len,
        unsigned char** payload_out,
        size_t* payload_out_len,
        unsigned char* final_x25519_pubkey_out,
        unsigned char* final_x25519_seckey_out) {
    assert(builder && payload_in);

    try {
        auto& unboxed_builder = unbox(builder);
        auto payload = unboxed_builder.build(ustring{payload_in, payload_in_len});

        if (unboxed_builder.final_hop_x25519_keypair) {
            auto key_pair = unboxed_builder.final_hop_x25519_keypair.value();
            std::memcpy(final_x25519_pubkey_out, key_pair.first.data(), key_pair.first.size());
            std::memcpy(final_x25519_seckey_out, key_pair.second.data(), key_pair.second.size());
        } else {
            throw std::runtime_error{"Final keypair not generated"};
        }

        *payload_out = static_cast<unsigned char*>(malloc(payload.size()));
        *payload_out_len = payload.size();
        std::memcpy(*payload_out, payload.data(), payload.size());

        return true;
    } catch (...) {
        return false;
    }
}
}
