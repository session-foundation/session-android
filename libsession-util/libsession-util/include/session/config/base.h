#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "../config.h"
#include "../export.h"

// Config object base type: this type holds the internal object and is initialized by the various
// config-dependent settings (e.g. config_user_profile_init) then passed to the various functions.
typedef struct config_object {
    // Internal opaque object pointer; calling code should leave this alone.
    void* internals;
    // When an error occurs in the C API this string will be set to the specific error message.  May
    // be empty.
    const char* last_error;

    // Sometimes used as the backing buffer for `last_error`.  Should not be touched externally.
    char _error_buf[256];
} config_object;

// Common functions callable on any config instance:

/// API: base/config_free
///
/// Frees a config object created with one of the config-dependent ..._init functions (e.g.
/// user_profile_init).
///
/// Declaration:
/// ```cpp
/// VOID config_free(
///     [in, out]   config_object*      conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
LIBSESSION_EXPORT void config_free(config_object* conf);

typedef enum config_log_level {
    LOG_LEVEL_DEBUG = 0,
    LOG_LEVEL_INFO,
    LOG_LEVEL_WARNING,
    LOG_LEVEL_ERROR
} config_log_level;

/// API: base/config_set_logger
///
/// Sets a logging function; takes the log function pointer and a context pointer (which can be NULL
/// if not needed).  The given function pointer will be invoked with one of the above values, a
/// null-terminated c string containing the log message, and the void* context object given when
/// setting the logger (this is for caller-specific state data and won't be touched).
///
/// The logging function must have signature:
///
/// void log(config_log_level lvl, const char* msg, void* ctx);
///
/// Can be called with callback set to NULL to clear an existing logger.
///
/// The config object itself has no log level: the caller should filter by level as needed.
///
/// Declaration:
/// ```cpp
/// VOID config_set_logger(
///     [in, out]   config_object*                                  conf,
///     [in]        void(*)(config_log_level, const char*, void*)   callback,
///     [in]        void*                                           ctx
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `callback` -- [in] Callback function
/// - `ctx` --- [in, optional] Pointer to an optional context. Set to NULL if unused
LIBSESSION_EXPORT void config_set_logger(
        config_object* conf, void (*callback)(config_log_level, const char*, void*), void* ctx);

/// API: base/config_storage_namespace
///
/// Returns the numeric namespace in which config messages of this type should be stored.
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `int16_t` -- integer of the namespace
LIBSESSION_EXPORT int16_t config_storage_namespace(const config_object* conf);

/// Struct containing a list of C strings.  Typically where this is returned by this API it must be
/// freed (via `free()`) when done with it.
///
/// When returned as a pointer by a libsession-util function this is allocated in such a way that
/// just the outer config_string_list can be free()d to free both the list *and* the inner `value`
/// and pointed-at values.
typedef struct config_string_list {
    char** value;  // array of null-terminated C strings
    size_t len;    // length of `value`
} config_string_list;

/// API: base/config_merge
///
/// Merges the config object with one or more remotely obtained config strings.  After this call the
/// config object may be unchanged, complete replaced, or updated and needing a push, depending on
/// the messages that are merged; the caller should check config_needs_push().
///
/// Declaration:
/// ```cpp
/// INT config_merge(
///     [in, out]   config_object*          conf,
///     [in]        const char**            msg_hashes,
///     [in]        const unsigned char**   configs,
///     [in]        const size_t*           lengths,
///     [in]        size_t                  count
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to config_object object
/// - `msg_hashes` -- [in] is an array of null-terminated C strings containing the hashes of the
/// configs being provided.
/// - `configs` -- [in] is an array of pointers to the start of the (binary) data.
/// - `lengths` -- [in] is an array of lengths of the binary data
/// - `count` -- [in] is the length of all three arrays.
///
/// Outputs:
/// - `config_string_list*` -- pointer to the list of successfully parsed hashes; the pointer
///   belongs to the caller and must be freed when done with it.

LIBSESSION_EXPORT config_string_list* config_merge(
        config_object* conf,
        const char** msg_hashes,
        const unsigned char** configs,
        const size_t* lengths,
        size_t count) LIBSESSION_WARN_UNUSED;

/// API: base/config_needs_push
///
/// Returns true if this config object contains updated data that has not yet been confirmed stored
/// on the server.
///
/// Declaration:
/// ```cpp
/// BOOL config_needs_push(
///     [in]   const config_object*      conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `bool` -- returns true if object contains updated data
LIBSESSION_EXPORT bool config_needs_push(const config_object* conf);

/// Returned struct of config push data.
typedef struct config_push_data {
    // The config seqno (to be provided later in `config_confirm_pushed`).
    seqno_t seqno;
    // The config message to push (binary data, not null-terminated).
    unsigned char* config;
    // The length of `config`
    size_t config_len;
    // Array of obsolete message hashes to delete; each element is a null-terminated C string
    char** obsolete;
    // length of `obsolete`
    size_t obsolete_len;
} config_push_data;

/// API: base/config_push
///
/// Obtains the configuration data that needs to be pushed to the server.
///
/// Generally this call should be guarded by a call to `config_needs_push`, however it can be used
/// to re-obtain the current serialized config even if no push is needed (for example, if the client
/// wants to re-submit it after a network error).
///
/// NB: The returned pointer belongs to the caller: that is, the caller *MUST* free() it when
/// done with it.
///
/// Declaration:
/// ```cpp
/// CONFIG_PUSH_DATA* config_push(
///     [in, out]   config_object*      conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `config_push_data*` -- pointer to the config object. Pointer belongs to the caller.
LIBSESSION_EXPORT config_push_data* config_push(config_object* conf);

/// API: base/config_confirm_pushed
///
/// Reports that data obtained from `config_push` has been successfully stored on the server with
/// message hash `msg_hash`.  The seqno value is the one returned by the config_push call that
/// yielded the config data.
///
/// Declaration:
/// ```cpp
/// VOID config_confirm_pushed(
///     [in, out]   config_object*      conf,
///     [out]       seqno_t             seqno,
///     [out]       const char*         msg_hash
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `seqno` -- [out] Value returned by config_push call
/// - `msg_hash` -- [out] Value returned by config_push call
LIBSESSION_EXPORT void config_confirm_pushed(
        config_object* conf, seqno_t seqno, const char* msg_hash);

/// API: base/config_dump
///
/// Returns a binary dump of the current state of the config object.  This dump can be used to
/// resurrect the object at a later point (e.g. after a restart).  Allocates a new buffer and sets
/// it in `out` and the length in `outlen`.  Note that this is binary data, *not* a null-terminated
/// C string.
///
/// NB: It is the caller's responsibility to `free()` the buffer when done with it.
///
/// Immediately after this is called `config_needs_dump` will start returning true (until the
/// configuration is next modified).
///
/// Declaration:
/// ```cpp
/// VOID config_dump(
///     [in]    config_object*          conf
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `out` -- [out] Pointer to the output location
/// - `outlen` -- [out] Length of output
LIBSESSION_EXPORT void config_dump(config_object* conf, unsigned char** out, size_t* outlen);

/// API: base/config_needs_dump
///
/// Returns true if something has changed since the last call to `dump()` that requires calling
/// and saving the `config_dump()` data again.
///
/// Declaration:
/// ```cpp
/// BOOL config_needs_dump(
///     [in]    const config_object*          conf
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `bool` -- True if config has changed since last call to `dump()`
LIBSESSION_EXPORT bool config_needs_dump(const config_object* conf);

/// API: base/config_current_hashes
///
/// Obtains the current active hashes.  Note that this will be empty if the current hash is unknown
/// or not yet determined (for example, because the current state is dirty or because the most
/// recent push is still pending and we don't know the hash yet).
///
/// The returned pointer belongs to the caller and must be freed via `free()` when done with it.
///
/// Declaration:
/// ```cpp
/// CONFIG_STRING_LIST* config_current_hashes(
///     [in]    const config_object*          conf
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `config_string_list*` -- pointer to the list of hashes; the pointer belongs to the caller
LIBSESSION_EXPORT config_string_list* config_current_hashes(const config_object* conf)
        LIBSESSION_WARN_UNUSED;

/// API: base/config_get_keys
///
/// Obtains the current group decryption keys.
///
/// Returns a buffer where each consecutive 32 bytes is an encryption key for the object, in
/// priority order (i.e. the key at 0 is the encryption key, and the first decryption key).
///
/// This function is mainly for debugging/diagnostics purposes; most config types have one single
/// key (based on the secret key), and multi-keyed configs such as groups have their own methods for
/// encryption/decryption that are already aware of the multiple keys.
///
/// Inputs:
/// - `conf` -- [in] Pointer to the config_object object
/// - `len` -- [out] Pointer where the number of keys will be written (that is: the returned pointer
///   will be to a buffer which has a size of of this value times 32).
///
/// Outputs:
/// - `unsigned char*` -- pointer to newly malloced key data (a multiple of 32 bytes); the pointer
///   belongs to the caller and must be `free()`d when done with it.
LIBSESSION_EXPORT unsigned char* config_get_keys(const config_object* conf, size_t* len);

/// Config key management; see the corresponding method docs in base.hpp.  All `key` arguments here
/// are 32-byte binary buffers (and since fixed-length, there is no keylen argument).

/// API: base/config_add_key
///
/// Adds an encryption/decryption key, without removing existing keys.  They key must be exactly
/// 32 bytes long.  The newly added key becomes the highest priority key: it will be used for
/// encryption of config pushes after the call, and will be tried first when decrypting, followed by
/// keys present (if any) before this call.  If the given key is already present in the key list
/// then this call moves it to the front of the list (if not already at the front).
///
/// Declaration:
/// ```cpp
/// VOID config_add_key(
///     [in, out]       config_object*          conf,
///     [in]            const unsigned char*    key
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to config_object object
/// - `key` -- [in] Pointer to the binary key object, must be 32 bytes
LIBSESSION_EXPORT void config_add_key(config_object* conf, const unsigned char* key);

/// API: base/config_add_key_low_prio
///
/// Adds an encryption/decryption key, without removing existing keys.  They key must be exactly
/// 32 bytes long.  The newly added key becomes the lowest priority key
///
/// Declaration:
/// ```cpp
/// VOID config_add_key_low_prio(
///     [in, out]       config_object*          conf,
///     [in]            const unsigned char*    key
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in, out] Pointer to config_object object
/// - `key` -- [in] Pointer to the binary key object, must be 32 bytes
LIBSESSION_EXPORT void config_add_key_low_prio(config_object* conf, const unsigned char* key);

/// API: base/config_clear_keys
///
/// Clears all stored encryption/decryption keys.  This is typically immediately followed with
/// one or more `add_key` call to replace existing keys.  Returns the number of keys removed.
///
/// Declaration:
/// ```cpp
/// INT config_clear_keys(
///     [in]    config_object*          conf
/// );
///
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `int` -- Number of keys removed
LIBSESSION_EXPORT int config_clear_keys(config_object* conf);

/// API: base/config_remove_key
///
/// Removes the given encryption/decryption key, if present.  Returns true if it was found and
/// removed, false if it was not in the key list.
///
/// Declaration:
/// ```cpp
/// BOOL config_remove_key(
///     [in]    const config_object*    conf,
///     [in]    const unsigned char*    key
/// ),
///
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `key` -- [in] Pointer to the binary key object, must be 32 bytes
///
/// Outputs:
/// - `bool` -- True if key successfully removed
LIBSESSION_EXPORT bool config_remove_key(config_object* conf, const unsigned char* key);

/// API: base/config_key_count
///
/// Returns the number of encryption keys.
///
/// Declaration:
/// ```cpp
/// INT config_key_count(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `int` -- Number of encryption keys
LIBSESSION_EXPORT int config_key_count(const config_object* conf);

/// API: base/config_key_count
///
/// Returns true if the given key is already in the keys list.
///
/// Declaration:
/// ```cpp
/// BOOL config_has_key(
///     [in]    const config_object*    conf,
///     [in]    const unsigned char*    key
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `key` -- [in] Pointer to the binary key object, must be 32 bytes
///
/// Outputs:
/// - `bool` -- True if key exists
LIBSESSION_EXPORT bool config_has_key(const config_object* conf, const unsigned char* key);

/// API: base/config_has_key
///
/// Returns a pointer to the 32-byte binary key at position i.  This is *not* null terminated (and
/// is exactly 32 bytes long).  `i < config_key_count(conf)` must be satisfied.  Ownership of the
/// data remains in the object (that is: the caller must not attempt to free it).
///
/// Declaration:
/// ```cpp
/// CONST UNSIGNED CHAR* config_key(
///     [in]    const config_object*    conf,
///     [in]    size_t                  i
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
/// - `i` -- [in] Position of key in config object
///
/// Outputs:
/// - `unsigned char*` -- binary data of the key, exactly 32 bytes and is not null terminated
LIBSESSION_EXPORT const unsigned char* config_key(const config_object* conf, size_t i);

/// API: base/config_encryption_domain
///
/// Returns the encryption domain C-str used to encrypt values for this config object.  (This is
/// here only for debugging/testing).
///
/// Declaration:
/// ```cpp
/// CONST CHAR* config_encryption_domain(
///     [in]    const config_object*    conf
/// );
/// ```
///
/// Inputs:
/// - `conf` -- [in] Pointer to config_object object
///
/// Outputs:
/// - `char*` -- encryption domain C-str used to encrypt values
LIBSESSION_EXPORT const char* config_encryption_domain(const config_object* conf);

/// API: base/config_set_sig_keys
///
/// Sets an Ed25519 keypair pair for signing and verifying config messages.  When set, this adds an
/// additional signature for verification into the config message (*after* decryption) that
/// validates a config message.
///
/// This is used in config contexts where the encryption/decryption keys are insufficient for
/// permission verification to produce new messages, such as in groups where non-admins need to be
/// able to decrypt group data, but are not permitted to push new group data.  In such a case only
/// the admins have the secret key with which messages can be signed; regular users can only read,
/// but cannot write, config messages.
///
/// When a signature public key (with or without a secret key) is set the config object enters a
/// "signing-required" mode, which has some implications worth noting:
/// - incoming messages must contain a signature that verifies with the public key; messages
///   without such a signature will be dropped as invalid.
/// - because of the above, a config object cannot push config updates without the secret key:
///   thus any attempt to modify the config message with a pubkey-only config object will raise
///   an exception.
///
/// Inputs:
/// - `secret` -- pointer to a 64-byte sodium-style Ed25519 "secret key" buffer (technically the
///   seed+precomputed pubkey concatenated together) that sets both the secret key and public key.
LIBSESSION_EXPORT void config_set_sig_keys(config_object* conf, const unsigned char* secret);

/// API: base/config_set_sig_pubkey
///
/// Sets a Ed25519 signing pubkey which incoming messages must be signed by to be acceptable.  This
/// is intended for use when the secret key is not known (see `config_set_sig_keys()` to set both
/// secret and pubkey keys together).
///
/// Inputs:
/// - `pubkey` -- pointer to the 32-byte Ed25519 pubkey that must have signed incoming messages.
LIBSESSION_EXPORT void config_set_sig_pubkey(config_object* conf, const unsigned char* pubkey);

/// API: base/config_get_sig_pubkey
///
/// Returns a pointer to the 32-byte Ed25519 signing pubkey, if set.  Returns nullptr if there is no
/// current signing pubkey.
///
/// Inputs: none.
///
/// Outputs:
/// - pointer to the 32-byte pubkey, or NULL if not set.
LIBSESSION_EXPORT const unsigned char* config_get_sig_pubkey(const config_object* conf);

/// API: base/config_clear_sig_keys
///
/// Drops the signature pubkey and/or secret key, if the object has them.
///
/// Inputs: none.
LIBSESSION_EXPORT void config_clear_sig_keys(config_object* conf);

#ifdef __cplusplus
}  // extern "C"
#endif
