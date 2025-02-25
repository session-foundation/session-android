#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>

#include "../export.h"

typedef enum ENCRYPT_TYPE {
    ENCRYPT_TYPE_AES_GCM = 0,
    ENCRYPT_TYPE_X_CHA_CHA_20 = 1,
} ENCRYPT_TYPE;

typedef struct onion_request_builder_object {
    // Internal opaque object pointer; calling code should leave this alone.
    void* internals;

    ENCRYPT_TYPE enc_type;
} onion_request_builder_object;

/// API: groups/onion_request_builder_init
///
/// Constructs an onion request builder and sets a pointer to it in `builder`.
///
/// When done with the object the `builder` must be destroyed by passing the pointer to
/// onion_request_builder_free().
///
/// Inputs:
/// - `builder` -- [out] Pointer to the builder object
LIBSESSION_EXPORT void onion_request_builder_init(onion_request_builder_object** builder);

/// API: groups/onion_request_builder_free
///
/// Properly destroys an onion request builder instance.
///
/// Inputs:
/// - `builder` -- [out] Pointer to the builder object to be freed
LIBSESSION_EXPORT void onion_request_builder_free(onion_request_builder_object* builder);

/// API: onion_request_builder_set_enc_type
///
/// Wrapper around session::onionreq::Builder::onion_request_builder_set_enc_type.
///
/// Declaration:
/// ```cpp
/// void onion_request_builder_set_enc_type(
///     [in]    onion_request_builder_object*  builder
///     [in]    ENCRYPT_TYPE                   enc_type
/// );
/// ```
///
/// Inputs:
/// - `builder` -- [in] Pointer to the builder object
/// - `enc_type` -- [in] The encryption type to use in the onion request
LIBSESSION_EXPORT void onion_request_builder_set_enc_type(
        onion_request_builder_object* builder, ENCRYPT_TYPE enc_type);

/// API: onion_request_builder_set_snode_destination
///
/// Wrapper around session::onionreq::Builder::set_snode_destination.  ed25519_pubkey and
/// x25519_pubkey are both hex strings and must both be exactly 64 characters.
///
/// Declaration:
/// ```cpp
/// void onion_request_builder_set_snode_destination(
///     [in]    onion_request_builder_object*  builder
///     [in]    const char*                    ed25519_pubkey,
///     [in]    const char*                    x25519_pubkey
/// );
/// ```
///
/// Inputs:
/// - `builder` -- [in] Pointer to the builder object
/// - `ed25519_pubkey` -- [in] The ed25519 public key for the snode destination
/// - `x25519_pubkey` -- [in] The x25519 public key for the snode destination
LIBSESSION_EXPORT void onion_request_builder_set_snode_destination(
        onion_request_builder_object* builder,
        const char* ed25519_pubkey,
        const char* x25519_pubkey);

/// API: onion_request_builder_set_server_destination
///
/// Wrapper around session::onionreq::Builder::set_server_destination.  x25519_pubkey
/// is a hex string and must both be exactly 64 characters.
///
/// Declaration:
/// ```cpp
/// void onion_request_builder_set_server_destination(
///     [in]    onion_request_builder_object*  builder
///     [in]    const char*                    host,
///     [in]    const char*                    target,
///     [in]    const char*                    protocol,
///     [in]    uint16_t                       port,
///     [in]    const char*                    x25519_pubkey
/// );
/// ```
///
/// Inputs:
/// - `builder` -- [in] Pointer to the builder object
/// - `host` -- [in] The host for the server destination
/// - `target` -- [in] The target (endpoint) for the server destination
/// - `protocol` -- [in] The protocol to use for the
/// - `port` -- [in] The host for the server destination
/// - `x25519_pubkey` -- [in] The x25519 public key for the snode destination
LIBSESSION_EXPORT void onion_request_builder_set_server_destination(
        onion_request_builder_object* builder,
        const char* host,
        const char* target,
        const char* protocol,
        uint16_t port,
        const char* x25519_pubkey);

/// API: onion_request_builder_add_hop
///
/// Wrapper around session::onionreq::Builder::add_hop.  ed25519_pubkey and
/// x25519_pubkey are both hex strings and must both be exactly 64 characters.
///
/// Declaration:
/// ```cpp
/// void onion_request_builder_add_hop(
///     [in]    onion_request_builder_object*  builder
///     [in]    const char*                    ed25519_pubkey,
///     [in]    const char*                    x25519_pubkey
/// );
/// ```
///
/// Inputs:
/// - `builder` -- [in] Pointer to the builder object
/// - `ed25519_pubkey` -- [in] The ed25519 public key for the snode hop
/// - `x25519_pubkey` -- [in] The x25519 public key for the snode hop
LIBSESSION_EXPORT void onion_request_builder_add_hop(
        onion_request_builder_object* builder,
        const char* ed25519_pubkey,
        const char* x25519_pubkey);

/// API: onion_request_builder_build
///
/// Wrapper around session::onionreq::Builder::build.  payload_in is binary: payload_in
/// has the length provided, destination_ed25519_pubkey and destination_x25519_pubkey
/// are both hex strings and must both be exactly 64 characters. Returns a flag indicating
/// success or failure.
///
/// Declaration:
/// ```cpp
/// bool onion_request_builder_build(
///     [in]    onion_request_builder_object*  builder
///     [in]    const unsigned char*           payload_in,
///     [in]    size_t                         payload_in_len,
///     [out]   unsigned char**                payload_out,
///     [out]   size_t*                        payload_out_len,
///     [out]   unsigned char*                 final_x25519_pubkey_out,
///     [out]   unsigned char*                 final_x25519_seckey_out
/// );
/// ```
///
/// Inputs:
/// - `builder` -- [in] Pointer to the builder object
/// - `payload_in` -- [in] The payload to be sent in the onion request
/// - `payload_in_len` -- [in] The length of the payload_in
/// - `payload_out` -- [out] payload to be sent through the network, will be nullptr on error
/// - `payload_out_len` -- [out] length of payload_out if not null
/// - `final_x25519_pubkey_out` -- [out] pointer to a buffer of exactly 32 bytes where the final
/// x25519 public key used for the onion request will be written if successful
/// - `final_x25519_seckey_out` -- [out] pointer to a buffer of exactly 32 bytes where the final
/// x25519 secret key used for the onion request will be written if successful
///
/// Outputs:
/// - `bool` -- True if the onion request payload was successfully constructed, false if it failed.
///   If (and only if) true is returned then `payload_out` must be freed when done with it.
LIBSESSION_EXPORT bool onion_request_builder_build(
        onion_request_builder_object* builder,
        const unsigned char* payload_in,
        size_t payload_in_len,
        unsigned char** payload_out,
        size_t* payload_out_len,
        unsigned char* final_x25519_pubkey_out,
        unsigned char* final_x25519_seckey_out);

#ifdef __cplusplus
}
#endif
