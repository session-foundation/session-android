#include "session/config/protos.hpp"

#include <sodium/crypto_scalarmult.h>
#include <sodium/crypto_sign_ed25519.h>

#include <array>
#include <stdexcept>

#include "SessionProtos.pb.h"
#include "WebSocketResources.pb.h"
#include "session/session_encrypt.hpp"

namespace session::config::protos {

namespace {

    SessionProtos::SharedConfigMessage_Kind encode_namespace(session::config::Namespace t) {
        switch (t) {
            case session::config::Namespace::UserProfile:
                return SessionProtos::SharedConfigMessage_Kind_USER_PROFILE;
            case session::config::Namespace::Contacts:
                return SessionProtos::SharedConfigMessage_Kind_CONTACTS;
            case session::config::Namespace::ConvoInfoVolatile:
                return SessionProtos::SharedConfigMessage_Kind_CONVO_INFO_VOLATILE;
            case session::config::Namespace::UserGroups:
                return SessionProtos::SharedConfigMessage_Kind_USER_GROUPS;
            default:
                throw std::invalid_argument{
                        "Error: cannot encode invalid SharedConfigMessage type"};
        }
    }

}  // namespace

ustring wrap_config(
        ustring_view ed25519_sk, ustring_view data, int64_t seqno, config::Namespace t) {
    std::array<unsigned char, 64> tmp_sk;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> ignore_pk;
        crypto_sign_ed25519_seed_keypair(ignore_pk.data(), tmp_sk.data(), ed25519_sk.data());
        ed25519_sk = {tmp_sk.data(), 64};
    } else if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "Error: ed25519_sk is not the expected 64-byte Ed25519 secret key"};

    std::array<unsigned char, 32> my_xpk;
    if (0 != crypto_sign_ed25519_pk_to_curve25519(my_xpk.data(), ed25519_sk.data() + 32))
        throw std::invalid_argument{
                "Failed to convert Ed25519 pubkey to X25519; invalid secret key?"};

    if (static_cast<int16_t>(t) > 5)
        throw std::invalid_argument{"Error: received invalid outgoing SharedConfigMessage type"};

    // Wrap in a SharedConfigMessage inside a Content
    SessionProtos::Content config{};
    auto& shconf = *config.mutable_sharedconfigmessage();
    shconf.set_kind(encode_namespace(t));
    shconf.set_seqno(seqno);
    *shconf.mutable_data() = from_unsigned_sv(data);

    // Then we serialize that, pad it, and encrypt it.  Copying this relevant comment from the
    // Session codebase (the comment itself git blames to Signal):
    // NOTE: This is dumb.
    auto shared_conf = config.SerializeAsString();
    // Okay now let's talk about padding.  Remember, though:
    // NOTE: This is dumb.
    // Okay so to be more specific, padding adds a 0x80 byte followed by any number (including 0) of
    // 0x00 bytes.  The 0x80 byte, however, is always required (so there is always at least one
    // padding byte); for DMs, this gets pushed up to the next multiple of 160, hence the final
    // padded length we want, mathematically, is ⌈(x+1)/160⌉ * 160.  With integer division,
    // ceil(a/b) is (a+b-1)/b, so for a=x+1 we get: (x+1+b-1)/b*b = (x+b)/b*b = (x/b + 1)*b.
    //
#if 0
    const size_t unpadded_size = shared_conf.size();
    const size_t padded_size = (unpadded_size / 160 + 1) * 160;
    assert(padded_size > shared_conf.size());
    shared_conf.resize(padded_size);
    shared_conf[unpadded_size] = 0x80;
#else
    // But this is all moot for a config message which is *already* padded to a multiple of 256, so
    // just tack on the 0x80 and no 0x00s rather than making it bigger still.
    shared_conf += '\x80';
#endif

    // Now we encrypt using the session protocol encryption, but with sender == recipient ==
    // ourself.  This is unnecessary because the inner content is already encrypted with a value
    // derived from our private key, but old Session clients expect this.
    // NOTE: This is dumb.
    auto enc_shared_conf = encrypt_for_recipient_deterministic(
            ed25519_sk, {my_xpk.data(), my_xpk.size()}, to_unsigned_sv(shared_conf));

    // This is the point in session client code where this value got base64-encoded, passed to
    // another function, which then base64-decoded that value to put into the envelope.  We're going
    // to skip that step here: fingers crossed!!!
    // enc_shared_conf = oxenc::from_base64(oxenc::to_base64(enc_shared_conf));
    // NOTE: This is dumb.

    // Now we just keep on trucking with more protobuf:
    auto envelope = SessionProtos::Envelope();
    *envelope.mutable_content() = from_unsigned_sv(enc_shared_conf);
    envelope.set_timestamp(1);  // Old session clients with their own unwrapping require this > 0
    envelope.set_type(SessionProtos::Envelope_Type::Envelope_Type_SESSION_MESSAGE);

    // And more protobuf (even though this no one cares about anything other than the body in this
    // one):
    // NOTE: This is dumb.
    auto webreq = WebSocketProtos::WebSocketRequestMessage();
    webreq.set_verb("");
    webreq.set_path("");
    webreq.set_requestid(0);
    *webreq.mutable_body() = envelope.SerializeAsString();

    // And then yet more protobuf (even though this no one cares about anything other than the body
    // in this one, again):
    // NOTE: This is dumb.
    auto msg = WebSocketProtos::WebSocketMessage();
    msg.set_type(WebSocketProtos::WebSocketMessage_Type_REQUEST);
    *msg.mutable_request() = webreq;

    return ustring{to_unsigned_sv(msg.SerializeAsString())};
}

ustring unwrap_config(ustring_view ed25519_sk, ustring_view data, config::Namespace ns) {
    // Hurray, we get to undo everything from the above!

    std::array<unsigned char, 64> tmp_sk;
    if (ed25519_sk.size() == 32) {
        std::array<unsigned char, 32> ignore_pk;
        crypto_sign_ed25519_seed_keypair(ignore_pk.data(), tmp_sk.data(), ed25519_sk.data());
        ed25519_sk = {tmp_sk.data(), 64};
    } else if (ed25519_sk.size() != 64)
        throw std::invalid_argument{
                "Error: ed25519_sk is not the expected 64-byte Ed25519 secret key"};
    auto ed25519_pk = ed25519_sk.substr(32);

    WebSocketProtos::WebSocketMessage req{};

    if (!req.ParseFromArray(data.data(), data.size()))
        throw std::runtime_error{"Failed to parse WebSocketMessage"};

    const auto& msg_type = req.type();
    if (req.type() != WebSocketProtos::WebSocketMessage_Type_REQUEST)
        throw std::runtime_error{"Error: received invalid WebSocketRequest"};

    SessionProtos::Envelope envelope{};
    if (!envelope.ParseFromString(req.request().body()))
        throw std::runtime_error{"Failed to parse Envelope"};

    auto [content, sender] = decrypt_incoming(ed25519_sk, to_unsigned_sv(envelope.content()));
    if (sender != ed25519_pk)
        throw std::runtime_error{"Incoming config data was not from us; ignoring"};

    if (content.empty())
        throw std::runtime_error{"Incoming config data decrypted to empty string"};

    if (!(content.back() == 0x00 || content.back() == 0x80))
        throw std::runtime_error{"Incoming config data doesn't have required padding"};

    if (auto pos = content.find_last_not_of((unsigned char)0);
        pos != std::string::npos && content[pos] == 0x80)
        content.resize(pos);
    else
        throw std::runtime_error{"Incoming config data has invalid padding"};

    SessionProtos::Content config{};
    if (!config.ParseFromArray(content.data(), content.size()))
        throw std::runtime_error{"Failed to parse SharedConfig"};

    if (!config.has_sharedconfigmessage())
        throw std::runtime_error{"Content is missing a SharedConfigMessage"};
    auto& shconf = config.sharedconfigmessage();
    if (shconf.kind() != encode_namespace(ns))
        throw std::runtime_error{"SharedConfig has wrong kind for config namespace"};

    // if ParseFromString fails, we have a raw (not protobuf encoded) message
    return ustring{to_unsigned_sv(shconf.data())};
}

}  // namespace session::config::protos
