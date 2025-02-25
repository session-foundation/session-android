#pragma once

#include "namespaces.hpp"
#include "session/util.hpp"

namespace session::config::protos {

/// API: config/protos::wrap_config
///
/// Wraps a config message in endless layers of protobuf and unnecessary extra encryption and
/// then more protobuf as required by older clients for older config message types.
///
/// Inputs:
/// - `ed25519_sk` a 32- or 64-byte, libsodium-style secret key value (if 32 then it is just the
///   seed).
/// - `data` the config data to wrap
/// - `seqno` the seqno value of the data
/// - `ns` the namespace of the config data
///
/// Outputs:
/// Returns the wrapped config.  Will throw on serious errors (e.g. `ed25519_sk` or `ns` are
/// invalid).
ustring wrap_config(
        ustring_view ed25519_sk, ustring_view data, int64_t seqno, config::Namespace ns);

/// API: config/protos::unwrap_config
///
/// Unwraps a config message from endless layers of protobuf, extra encryption and then more
/// protobuf as required by older clients for older config message types.
///
/// Inputs:
/// - `ed25519_sk` a 32- or 64-byte, libsodium-style secret key value (if 32 then it is just the
///   seed).
/// - `data` the incoming data that might be protobuf-wrapped
///
/// Outputs:
///
/// Returns the unwrapped, inner config value if this is a proper protobuf-wrapped message; throws
/// std::runtime_error if it is not (thus most likely indicating that this is a raw config value).
/// Throws a std::invalid_argument if the given ed25519_sk is invalid.  (It is recommended that only
/// the std::runtime_error is caught for detecting non-wrapped input as the invalid secret key is
/// more serious).
ustring unwrap_config(ustring_view ed25519_sk, ustring_view data, config::Namespace ns);

}  // namespace session::config::protos
