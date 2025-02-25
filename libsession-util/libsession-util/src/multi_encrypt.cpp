
#include <oxenc/bt_producer.h>
#include <oxenc/bt_serialize.h>
#include <session/multi_encrypt.h>
#include <sodium/crypto_aead_xchacha20poly1305.h>
#include <sodium/crypto_generichash_blake2b.h>
#include <sodium/crypto_scalarmult_curve25519.h>
#include <sodium/crypto_sign_ed25519.h>
#include <sodium/randombytes.h>

#include <session/multi_encrypt.hpp>
#include <stdexcept>

namespace session {

const size_t encrypt_multiple_message_overhead = crypto_aead_xchacha20poly1305_ietf_ABYTES;

namespace detail {

    void encrypt_multi_key(
            std::array<unsigned char, 32>& key,
            const unsigned char* a,
            const unsigned char* A,
            const unsigned char* B,
            bool encrypting,
            std::string_view domain) {

        std::array<unsigned char, 32> buf;
        if (0 != crypto_scalarmult_curve25519(buf.data(), a, B))
            throw std::invalid_argument{"Unable to compute shared encrypted key: invalid pubkey?"};

        static_assert(crypto_aead_xchacha20poly1305_ietf_KEYBYTES == 32);

        crypto_generichash_blake2b_state st;
        crypto_generichash_blake2b_init(
                &st,
                reinterpret_cast<const unsigned char*>(domain.data()),
                std::min<size_t>(domain.size(), crypto_generichash_blake2b_KEYBYTES_MAX),
                32);

        crypto_generichash_blake2b_update(&st, buf.data(), buf.size());

        // If we're encrypting then a/A == sender, B = recipient
        // If we're decrypting then a/A = recipient, B = sender
        // We always need the same sR || S || R or rS || S || R, so if we're decrypting we need to
        // put B before A in the hash;
        const auto* S = encrypting ? A : B;
        const auto* R = encrypting ? B : A;
        crypto_generichash_blake2b_update(&st, S, 32);
        crypto_generichash_blake2b_update(&st, R, 32);
        crypto_generichash_blake2b_final(&st, key.data(), 32);
    }

    void encrypt_multi_impl(
            ustring& out, ustring_view msg, const unsigned char* key, const unsigned char* nonce) {

        //        auto key = encrypt_multi_key(a, A, B, true, domain);

        out.resize(msg.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES);
        if (0 !=
            crypto_aead_xchacha20poly1305_ietf_encrypt(
                    out.data(), nullptr, msg.data(), msg.size(), nullptr, 0, nullptr, nonce, key))
            throw std::runtime_error{"XChaCha20 encryption failed!"};
    }

    bool decrypt_multi_impl(
            ustring& out,
            ustring_view ciphertext,
            const unsigned char* key,
            const unsigned char* nonce) {

        if (ciphertext.size() < crypto_aead_xchacha20poly1305_ietf_ABYTES)
            return false;

        out.resize(ciphertext.size() - crypto_aead_xchacha20poly1305_ietf_ABYTES);
        return 0 == crypto_aead_xchacha20poly1305_ietf_decrypt(
                            out.data(),
                            nullptr,
                            nullptr,
                            ciphertext.data(),
                            ciphertext.size(),
                            nullptr,
                            0,
                            nonce,
                            key);
    }

    std::pair<sodium_cleared<std::array<unsigned char, 32>>, std::array<unsigned char, 32>> x_keys(
            ustring_view ed25519_secret_key) {
        if (ed25519_secret_key.size() != 64)
            throw std::invalid_argument{"Ed25519 secret key is not the expected 64 bytes"};

        std::pair<sodium_cleared<std::array<unsigned char, 32>>, std::array<unsigned char, 32>> ret;
        auto& [x_priv, x_pub] = ret;

        crypto_sign_ed25519_sk_to_curve25519(x_priv.data(), ed25519_secret_key.data());
        if (0 != crypto_sign_ed25519_pk_to_curve25519(x_pub.data(), ed25519_secret_key.data() + 32))
            throw std::runtime_error{"Failed to convert Ed25519 key to X25519: invalid secret key"};

        return ret;
    }

}  // namespace detail

std::optional<ustring> decrypt_for_multiple(
        const std::vector<ustring_view>& ciphertexts,
        ustring_view nonce,
        ustring_view privkey,
        ustring_view pubkey,
        ustring_view sender_pubkey,
        std::string_view domain) {

    auto it = ciphertexts.begin();
    return decrypt_for_multiple(
            [&]() -> std::optional<ustring_view> {
                if (it == ciphertexts.end())
                    return std::nullopt;
                return *it++;
            },
            nonce,
            privkey,
            pubkey,
            sender_pubkey,
            domain);
}

ustring encrypt_for_multiple_simple(
        const std::vector<ustring_view>& messages,
        const std::vector<ustring_view>& recipients,
        ustring_view privkey,
        ustring_view pubkey,
        std::string_view domain,
        std::optional<ustring_view> nonce,
        int pad) {

    oxenc::bt_dict_producer d;

    std::array<unsigned char, 24> random_nonce;
    if (!nonce) {
        randombytes_buf(random_nonce.data(), random_nonce.size());
        nonce.emplace(random_nonce.data(), random_nonce.size());
    } else if (nonce->size() != 24) {
        throw std::invalid_argument{"Invalid nonce: nonce must be 24 bytes"};
    }

    d.append("#", *nonce);
    {
        auto enc_list = d.append_list("e");

        int msg_count = 0;
        encrypt_for_multiple(
                messages, recipients, *nonce, privkey, pubkey, domain, [&](ustring_view encrypted) {
                    enc_list.append(encrypted);
                    msg_count++;
                });

        if (int pad_size = pad > 1 && !messages.empty() ? messages.front().size() : 0) {
            ustring junk;
            junk.resize(pad_size);
            for (; msg_count % pad != 0; msg_count++) {
                randombytes_buf(junk.data(), pad_size);
                enc_list.append(junk);
            }
        }
    }

    return ustring{d.view<unsigned char>()};
}

ustring encrypt_for_multiple_simple(
        const std::vector<ustring_view>& messages,
        const std::vector<ustring_view>& recipients,
        ustring_view ed25519_secret_key,
        std::string_view domain,
        ustring_view nonce,
        int pad) {

    auto [x_privkey, x_pubkey] = detail::x_keys(ed25519_secret_key);

    return encrypt_for_multiple_simple(
            messages, recipients, to_sv(x_privkey), to_sv(x_pubkey), domain, nonce, pad);
}

std::optional<ustring> decrypt_for_multiple_simple(
        ustring_view encoded,
        ustring_view privkey,
        ustring_view pubkey,
        ustring_view sender_pubkey,
        std::string_view domain) {
    try {
        oxenc::bt_dict_consumer d{encoded};
        auto nonce = d.require<ustring_view>("#");
        if (nonce.size() != 24)
            return std::nullopt;
        auto enc_list = d.require<oxenc::bt_list_consumer>("e");

        return decrypt_for_multiple(
                [&]() -> std::optional<ustring_view> {
                    if (enc_list.is_finished())
                        return std::nullopt;
                    return enc_list.consume<ustring_view>();
                },
                nonce,
                privkey,
                pubkey,
                sender_pubkey,
                domain);
    } catch (...) {
        return std::nullopt;
    }
}

std::optional<ustring> decrypt_for_multiple_simple(
        ustring_view encoded,
        ustring_view ed25519_secret_key,
        ustring_view sender_pubkey,
        std::string_view domain) {

    auto [x_privkey, x_pubkey] = detail::x_keys(ed25519_secret_key);

    return decrypt_for_multiple_simple(
            encoded, to_sv(x_privkey), to_sv(x_pubkey), sender_pubkey, domain);
}

std::optional<ustring> decrypt_for_multiple_simple_ed25519(
        ustring_view encoded,
        ustring_view ed25519_secret_key,
        ustring_view sender_ed25519_pubkey,
        std::string_view domain) {

    std::array<unsigned char, 32> sender_pub;
    if (sender_ed25519_pubkey.size() != 32)
        throw std::invalid_argument{"Invalid sender Ed25519 pubkey: expected 32 bytes"};
    if (0 != crypto_sign_ed25519_pk_to_curve25519(sender_pub.data(), sender_ed25519_pubkey.data()))
        throw std::runtime_error{"Failed to convert Ed25519 key to X25519: invalid secret key"};

    return decrypt_for_multiple_simple(encoded, ed25519_secret_key, to_sv(sender_pub), domain);
}

}  // namespace session

using namespace session;

static unsigned char* to_c_buffer(ustring_view x, size_t* out_len) {
    auto* ret = static_cast<unsigned char*>(malloc(x.size()));
    *out_len = x.size();
    std::memcpy(ret, x.data(), x.size());
    return ret;
}

LIBSESSION_C_API unsigned char* session_encrypt_for_multiple_simple(
        size_t* out_len,
        const unsigned char** messages,
        const size_t* message_lengths,
        size_t n_messages,
        const unsigned char** recipients,
        size_t n_recipients,
        const unsigned char* x25519_privkey,
        const unsigned char* x25519_pubkey,
        const char* domain,
        const unsigned char* nonce,
        int pad) {

    std::vector<ustring_view> msgs, recips;
    msgs.reserve(n_messages);
    recips.reserve(n_recipients);
    for (size_t i = 0; i < n_messages; i++)
        msgs.emplace_back(messages[i], message_lengths[i]);
    for (size_t i = 0; i < n_recipients; i++)
        recips.emplace_back(recipients[i], 32);
    std::optional<ustring_view> maybe_nonce;
    if (nonce)
        maybe_nonce.emplace(nonce, 24);

    try {
        auto encoded = session::encrypt_for_multiple_simple(
                msgs,
                recips,
                ustring_view{x25519_privkey, 32},
                ustring_view{x25519_pubkey, 32},
                domain,
                std::move(maybe_nonce),
                pad);
        return to_c_buffer(encoded, out_len);
    } catch (...) {
        return nullptr;
    }
}

LIBSESSION_C_API unsigned char* session_encrypt_for_multiple_simple_ed25519(
        size_t* out_len,
        const unsigned char** messages,
        const size_t* message_lengths,
        size_t n_messages,
        const unsigned char** recipients,
        size_t n_recipients,
        const unsigned char* ed25519_secret_key,
        const char* domain,
        const unsigned char* nonce,
        int pad) {

    try {
        auto [priv, pub] = session::detail::x_keys(ustring_view{ed25519_secret_key, 64});
        return session_encrypt_for_multiple_simple(
                out_len,
                messages,
                message_lengths,
                n_messages,
                recipients,
                n_recipients,
                priv.data(),
                pub.data(),
                domain,
                nonce,
                pad);
    } catch (...) {
        return nullptr;
    }
}

LIBSESSION_C_API unsigned char* session_decrypt_for_multiple_simple(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* x25519_privkey,
        const unsigned char* x25519_pubkey,
        const unsigned char* sender_x25519_pubkey,
        const char* domain) {

    try {
        if (auto decrypted = session::decrypt_for_multiple_simple(
                    ustring_view{encoded, encoded_len},
                    ustring_view{x25519_privkey, 32},
                    ustring_view{x25519_pubkey, 32},
                    ustring_view{sender_x25519_pubkey, 32},
                    domain)) {
            return to_c_buffer(*decrypted, out_len);
        }
    } catch (...) {
    }

    return nullptr;
}

LIBSESSION_C_API unsigned char* session_decrypt_for_multiple_simple_ed25519_from_x25519(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* ed25519_secret,
        const unsigned char* sender_x25519_pubkey,
        const char* domain) {

    try {
        if (auto decrypted = session::decrypt_for_multiple_simple(
                    ustring_view{encoded, encoded_len},
                    ustring_view{ed25519_secret, 64},
                    ustring_view{sender_x25519_pubkey, 32},
                    domain)) {
            return to_c_buffer(*decrypted, out_len);
        }
    } catch (...) {
    }

    return nullptr;
}

LIBSESSION_C_API unsigned char* session_decrypt_for_multiple_simple_ed25519(
        size_t* out_len,
        const unsigned char* encoded,
        size_t encoded_len,
        const unsigned char* ed25519_secret,
        const unsigned char* sender_ed25519_pubkey,
        const char* domain) {

    try {
        if (auto decrypted = session::decrypt_for_multiple_simple_ed25519(
                    ustring_view{encoded, encoded_len},
                    ustring_view{ed25519_secret, 64},
                    ustring_view{sender_ed25519_pubkey, 32},
                    domain)) {
            return to_c_buffer(*decrypted, out_len);
        }
    } catch (...) {
    }

    return nullptr;
}
