#include <string>

#include "session/onionreq/hop_encryption.hpp"
#include "session/types.hpp"

namespace session::onionreq {

/// The default maximum size of an onion request accepted by the OnionReqParser constructor.
constexpr size_t DEFAULT_MAX_SIZE = 10'485'760;  // 10 MiB

class OnionReqParser {
  private:
    x25519_keypair keys;
    HopEncryption enc;
    EncryptType enc_type = EncryptType::aes_gcm;
    x25519_pubkey remote_pk;
    ustring payload_;

  public:
    /// Constructs a parser, parsing the given request sent to us.  Throws if parsing or decryption
    /// fails.
    OnionReqParser(
            ustring_view x25519_pubkey,
            ustring_view x25519_privkey,
            ustring_view req,
            size_t max_size = DEFAULT_MAX_SIZE);

    /// plaintext payload, decrypted from the incoming request during construction.
    ustring_view payload() const { return payload_; }

    /// Extracts payload from this object (via a std::move); after the call the object's payload
    /// will be empty.
    ustring move_payload() {
        ustring ret{std::move(payload_)};
        payload_.clear();  // Guarantee empty, even if SSO active
        return ret;
    }

    ustring_view remote_pubkey() const { return to_unsigned_sv(remote_pk.view()); }

    /// Encrypts a reply using the appropriate encryption as determined when parsing the
    /// request.
    ustring encrypt_reply(ustring_view reply) const;
};

}  // namespace session::onionreq
