#include "session/config/encrypt.hpp"

#include <oxenc/endian.h>
#include <sodium/crypto_aead_xchacha20poly1305.h>
#include <sodium/crypto_generichash_blake2b.h>

#include <array>
#include <cassert>

#include "session/export.h"

using namespace std::literals;

namespace session::config {

namespace {

    // Helper function to go from char pointers to the unsigned char pointers sodium needs:
    const unsigned char* to_unsigned(const char* x) {
        return reinterpret_cast<const unsigned char*>(x);
    }

    ustring_view to_unsigned_sv(std::string_view v) {
        return {to_unsigned(v.data()), v.size()};
    }

}  // namespace

static constexpr size_t DOMAIN_MAX_SIZE = 24;
static constexpr auto NONCE_KEY_PREFIX = "libsessionutil-config-encrypted-"sv;
static_assert(NONCE_KEY_PREFIX.size() + DOMAIN_MAX_SIZE < crypto_generichash_blake2b_KEYBYTES_MAX);

static std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_KEYBYTES> make_encrypt_key(
        ustring_view key_base, uint64_t message_size, std::string_view domain) {
    if (key_base.size() != 32)
        throw std::invalid_argument{"encrypt called with key_base != 32 bytes"};
    if (domain.size() < 1 || domain.size() > DOMAIN_MAX_SIZE)
        throw std::invalid_argument{"encrypt called with domain size not in [1, 24]"};

    // We hash the key because we're using a deterministic nonce: the `key_base` value is expected
    // to be a long-term value for which nonce reuse (via hash collision) would be bad: by
    // incorporating the domain and message size we at least vary the key to further restrict the
    // nonce reuse concern so that you would not only have to hash collide but also have it happen
    // on messages of identical sizes and identical domain.
    std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_KEYBYTES> key{0};
    crypto_generichash_blake2b_state state;
    crypto_generichash_blake2b_init(&state, nullptr, 0, key.size());
    crypto_generichash_blake2b_update(&state, key_base.data(), key_base.size());
    oxenc::host_to_big_inplace(message_size);
    crypto_generichash_blake2b_update(
            &state, reinterpret_cast<const unsigned char*>(&message_size), sizeof(message_size));
    crypto_generichash_blake2b_update(&state, to_unsigned(domain.data()), domain.size());
    crypto_generichash_blake2b_final(&state, key.data(), key.size());
    return key;
}

ustring encrypt(ustring_view message, ustring_view key_base, std::string_view domain) {
    ustring msg;
    msg.reserve(
            message.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES +
            crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    msg.assign(message);
    encrypt_inplace(msg, key_base, domain);
    return msg;
}
void encrypt_inplace(ustring& message, ustring_view key_base, std::string_view domain) {
    auto key = make_encrypt_key(key_base, message.size(), domain);

    std::string nonce_key{NONCE_KEY_PREFIX};
    nonce_key += domain;

    std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce;
    crypto_generichash_blake2b(
            nonce.data(),
            nonce.size(),
            message.data(),
            message.size(),
            to_unsigned(nonce_key.data()),
            nonce_key.size());

    size_t plaintext_len = message.size();
    message.resize(
            plaintext_len + crypto_aead_xchacha20poly1305_ietf_ABYTES +
            crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);

    unsigned long long outlen = 0;
    crypto_aead_xchacha20poly1305_ietf_encrypt(
            message.data(),
            &outlen,
            message.data(),
            plaintext_len,
            nullptr,
            0,
            nullptr,
            nonce.data(),
            key.data());

    assert(outlen == message.size() - crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    std::memcpy(message.data() + outlen, nonce.data(), nonce.size());
}

static_assert(
        ENCRYPT_DATA_OVERHEAD ==
        crypto_aead_xchacha20poly1305_IETF_ABYTES + crypto_aead_xchacha20poly1305_IETF_NPUBBYTES);

ustring decrypt(ustring_view ciphertext, ustring_view key_base, std::string_view domain) {
    ustring x{ciphertext};
    decrypt_inplace(x, key_base, domain);
    return x;
}
void decrypt_inplace(ustring& ciphertext, ustring_view key_base, std::string_view domain) {
    size_t message_len = ciphertext.size() - crypto_aead_xchacha20poly1305_ietf_ABYTES -
                         crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
    if (message_len > ciphertext.size())  // overflow
        throw decrypt_error{"Decryption failed: ciphertext is too short"};

    ustring_view nonce = ustring_view{ciphertext}.substr(
            ciphertext.size() - crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    auto key = make_encrypt_key(key_base, message_len, domain);

    unsigned long long mlen_wrote = 0;
    if (0 != crypto_aead_xchacha20poly1305_ietf_decrypt(
                     ciphertext.data(),
                     &mlen_wrote,
                     nullptr,
                     ciphertext.data(),
                     ciphertext.size() - crypto_aead_xchacha20poly1305_ietf_NPUBBYTES,
                     nullptr,
                     0,
                     nonce.data(),
                     key.data()))
        throw decrypt_error{"Message decryption failed"};

    assert(mlen_wrote == message_len);
    ciphertext.resize(mlen_wrote);
}

void pad_message(ustring& data, size_t overhead) {
    size_t target_size = padded_size(data.size(), overhead);
    if (target_size > data.size())
        data.insert(0, target_size - data.size(), '\0');
}

}  // namespace session::config

extern "C" {

using session::ustring;

LIBSESSION_EXPORT unsigned char* config_encrypt(
        const unsigned char* plaintext,
        size_t len,
        const unsigned char* key_base,
        const char* domain,
        size_t* ciphertext_size) {

    ustring ciphertext;
    try {
        ciphertext = session::config::encrypt({plaintext, len}, {key_base, 32}, domain);
    } catch (...) {
        return nullptr;
    }

    auto* data = static_cast<unsigned char*>(std::malloc(ciphertext.size()));
    std::memcpy(data, ciphertext.data(), ciphertext.size());
    *ciphertext_size = ciphertext.size();
    return data;
}

LIBSESSION_EXPORT unsigned char* config_decrypt(
        const unsigned char* ciphertext,
        size_t clen,
        const unsigned char* key_base,
        const char* domain,
        size_t* plaintext_size) {

    ustring plaintext;
    try {
        plaintext = session::config::decrypt({ciphertext, clen}, {key_base, 32}, domain);
    } catch (const std::exception& e) {
        return nullptr;
    }

    auto* data = static_cast<unsigned char*>(std::malloc(plaintext.size()));
    std::memcpy(data, plaintext.data(), plaintext.size());
    *plaintext_size = plaintext.size();
    return data;
}

LIBSESSION_EXPORT size_t config_padded_size(size_t s, size_t overhead) {
    return session::config::padded_size(s, overhead);
}
}
