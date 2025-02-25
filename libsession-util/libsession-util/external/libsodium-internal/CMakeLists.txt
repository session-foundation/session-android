# This CMakeLists.txt build script is from https://github.com/robinlinden/libsodium-cmake, with the
# following copyright:
#
# ISC License
# 
# Copyright (c) 2019, Robin Linden
# 
# Permission to use, copy, modify, and/or distribute this software for any
# purpose with or without fee is hereby granted, provided that the above
# copyright notice and this permission notice appear in all copies.
# 
# THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
# WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
# MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
# ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
# WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
# ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
# OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

cmake_minimum_required(VERSION 3.11)

if(POLICY CMP0077)
    cmake_policy(SET CMP0077 NEW)
endif()

project(libsodium-internal LANGUAGES C ASM)

set(LIB_NAME sodium-internal)

option(SODIUM_MINIMAL "Only compile the minimum set of functions required for the high-level API" OFF)
option(SODIUM_ENABLE_BLOCKING_RANDOM "Enable this switch only if /dev/urandom is totally broken on the target platform" OFF)

add_library(${LIB_NAME}
    src/libsodium/crypto_aead/aes256gcm/aesni/aead_aes256gcm_aesni.c
    src/libsodium/crypto_aead/chacha20poly1305/sodium/aead_chacha20poly1305.c
    src/libsodium/crypto_aead/xchacha20poly1305/sodium/aead_xchacha20poly1305.c
    src/libsodium/crypto_auth/crypto_auth.c
    src/libsodium/crypto_auth/hmacsha256/auth_hmacsha256.c
    src/libsodium/crypto_auth/hmacsha512/auth_hmacsha512.c
    src/libsodium/crypto_auth/hmacsha512256/auth_hmacsha512256.c
    src/libsodium/crypto_box/crypto_box.c
    src/libsodium/crypto_box/crypto_box_easy.c
    src/libsodium/crypto_box/crypto_box_seal.c
    src/libsodium/crypto_box/curve25519xsalsa20poly1305/box_curve25519xsalsa20poly1305.c
    src/libsodium/crypto_core/ed25519/ref10/ed25519_ref10.c
    src/libsodium/crypto_core/ed25519/ref10/fe_25_5/base.h
    src/libsodium/crypto_core/ed25519/ref10/fe_25_5/base2.h
    src/libsodium/crypto_core/ed25519/ref10/fe_25_5/constants.h
    src/libsodium/crypto_core/ed25519/ref10/fe_25_5/fe.h
    src/libsodium/crypto_core/ed25519/ref10/fe_51/base.h
    src/libsodium/crypto_core/ed25519/ref10/fe_51/base2.h
    src/libsodium/crypto_core/ed25519/ref10/fe_51/constants.h
    src/libsodium/crypto_core/ed25519/ref10/fe_51/fe.h
    src/libsodium/crypto_core/hchacha20/core_hchacha20.c
    src/libsodium/crypto_core/hsalsa20/core_hsalsa20.c
    src/libsodium/crypto_core/hsalsa20/ref2/core_hsalsa20_ref2.c
    src/libsodium/crypto_core/salsa/ref/core_salsa_ref.c
    src/libsodium/crypto_generichash/blake2b/generichash_blake2.c
    src/libsodium/crypto_generichash/blake2b/ref/blake2.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-avx2.c
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-avx2.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-ref.c
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-sse41.c
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-sse41.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-ssse3.c
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-compress-ssse3.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-load-avx2.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-load-sse2.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-load-sse41.h
    src/libsodium/crypto_generichash/blake2b/ref/blake2b-ref.c
    src/libsodium/crypto_generichash/blake2b/ref/generichash_blake2b.c
    src/libsodium/crypto_generichash/crypto_generichash.c
    src/libsodium/crypto_hash/crypto_hash.c
    src/libsodium/crypto_hash/sha256/cp/hash_sha256_cp.c
    src/libsodium/crypto_hash/sha256/hash_sha256.c
    src/libsodium/crypto_hash/sha512/cp/hash_sha512_cp.c
    src/libsodium/crypto_hash/sha512/hash_sha512.c
    src/libsodium/crypto_internal/fe25519.c
    src/libsodium/crypto_kdf/blake2b/kdf_blake2b.c
    src/libsodium/crypto_kdf/crypto_kdf.c
    src/libsodium/crypto_kx/crypto_kx.c
    src/libsodium/crypto_onetimeauth/crypto_onetimeauth.c
    src/libsodium/crypto_onetimeauth/poly1305/donna/poly1305_donna.c
    src/libsodium/crypto_onetimeauth/poly1305/donna/poly1305_donna.h
    src/libsodium/crypto_onetimeauth/poly1305/donna/poly1305_donna32.h
    src/libsodium/crypto_onetimeauth/poly1305/donna/poly1305_donna64.h
    src/libsodium/crypto_onetimeauth/poly1305/onetimeauth_poly1305.c
    src/libsodium/crypto_onetimeauth/poly1305/onetimeauth_poly1305.h
    src/libsodium/crypto_onetimeauth/poly1305/sse2/poly1305_sse2.c
    src/libsodium/crypto_onetimeauth/poly1305/sse2/poly1305_sse2.h
    src/libsodium/crypto_pwhash/argon2/argon2-core.c
    src/libsodium/crypto_pwhash/argon2/argon2-core.h
    src/libsodium/crypto_pwhash/argon2/argon2-encoding.c
    src/libsodium/crypto_pwhash/argon2/argon2-encoding.h
    src/libsodium/crypto_pwhash/argon2/argon2-fill-block-avx2.c
    src/libsodium/crypto_pwhash/argon2/argon2-fill-block-avx512f.c
    src/libsodium/crypto_pwhash/argon2/argon2-fill-block-ref.c
    src/libsodium/crypto_pwhash/argon2/argon2-fill-block-ssse3.c
    src/libsodium/crypto_pwhash/argon2/argon2.c
    src/libsodium/crypto_pwhash/argon2/argon2.h
    src/libsodium/crypto_pwhash/argon2/blake2b-long.c
    src/libsodium/crypto_pwhash/argon2/blake2b-long.h
    src/libsodium/crypto_pwhash/argon2/blamka-round-avx2.h
    src/libsodium/crypto_pwhash/argon2/blamka-round-avx512f.h
    src/libsodium/crypto_pwhash/argon2/blamka-round-ref.h
    src/libsodium/crypto_pwhash/argon2/blamka-round-ssse3.h
    src/libsodium/crypto_pwhash/argon2/pwhash_argon2i.c
    src/libsodium/crypto_pwhash/argon2/pwhash_argon2id.c
    src/libsodium/crypto_pwhash/crypto_pwhash.c
    src/libsodium/crypto_scalarmult/crypto_scalarmult.c
    src/libsodium/crypto_scalarmult/curve25519/ref10/x25519_ref10.c
    src/libsodium/crypto_scalarmult/curve25519/ref10/x25519_ref10.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/consts_namespace.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/curve25519_sandy2x.c
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/curve25519_sandy2x.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/fe.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/fe51.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/fe51_invert.c
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/fe51_namespace.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/fe_frombytes_sandy2x.c
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/ladder.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/ladder_namespace.h
    src/libsodium/crypto_scalarmult/curve25519/sandy2x/sandy2x.S
    src/libsodium/crypto_scalarmult/curve25519/scalarmult_curve25519.c
    src/libsodium/crypto_scalarmult/curve25519/scalarmult_curve25519.h
    src/libsodium/crypto_secretbox/crypto_secretbox.c
    src/libsodium/crypto_secretbox/crypto_secretbox_easy.c
    src/libsodium/crypto_secretbox/xsalsa20poly1305/secretbox_xsalsa20poly1305.c
    src/libsodium/crypto_secretstream/xchacha20poly1305/secretstream_xchacha20poly1305.c
    src/libsodium/crypto_shorthash/crypto_shorthash.c
    src/libsodium/crypto_shorthash/siphash24/ref/shorthash_siphash24_ref.c
    src/libsodium/crypto_shorthash/siphash24/ref/shorthash_siphash_ref.h
    src/libsodium/crypto_shorthash/siphash24/shorthash_siphash24.c
    src/libsodium/crypto_sign/crypto_sign.c
    src/libsodium/crypto_sign/ed25519/ref10/keypair.c
    src/libsodium/crypto_sign/ed25519/ref10/open.c
    src/libsodium/crypto_sign/ed25519/ref10/sign.c
    src/libsodium/crypto_sign/ed25519/ref10/sign_ed25519_ref10.h
    src/libsodium/crypto_sign/ed25519/sign_ed25519.c
    src/libsodium/crypto_stream/chacha20/dolbeau/chacha20_dolbeau-avx2.c
    src/libsodium/crypto_stream/chacha20/dolbeau/chacha20_dolbeau-avx2.h
    src/libsodium/crypto_stream/chacha20/dolbeau/chacha20_dolbeau-ssse3.c
    src/libsodium/crypto_stream/chacha20/dolbeau/chacha20_dolbeau-ssse3.h
    src/libsodium/crypto_stream/chacha20/dolbeau/u0.h
    src/libsodium/crypto_stream/chacha20/dolbeau/u1.h
    src/libsodium/crypto_stream/chacha20/dolbeau/u4.h
    src/libsodium/crypto_stream/chacha20/dolbeau/u8.h
    src/libsodium/crypto_stream/chacha20/ref/chacha20_ref.c
    src/libsodium/crypto_stream/chacha20/ref/chacha20_ref.h
    src/libsodium/crypto_stream/chacha20/stream_chacha20.c
    src/libsodium/crypto_stream/chacha20/stream_chacha20.h
    src/libsodium/crypto_stream/crypto_stream.c
    src/libsodium/crypto_stream/salsa20/ref/salsa20_ref.c
    src/libsodium/crypto_stream/salsa20/ref/salsa20_ref.h
    src/libsodium/crypto_stream/salsa20/stream_salsa20.c
    src/libsodium/crypto_stream/salsa20/stream_salsa20.h
    src/libsodium/crypto_stream/salsa20/xmm6/salsa20_xmm6.c
    src/libsodium/crypto_stream/salsa20/xmm6/salsa20_xmm6.h
    src/libsodium/crypto_stream/salsa20/xmm6/salsa20_xmm6-asm.S
    src/libsodium/crypto_stream/salsa20/xmm6int/salsa20_xmm6int-avx2.c
    src/libsodium/crypto_stream/salsa20/xmm6int/salsa20_xmm6int-avx2.h
    src/libsodium/crypto_stream/salsa20/xmm6int/salsa20_xmm6int-sse2.c
    src/libsodium/crypto_stream/salsa20/xmm6int/salsa20_xmm6int-sse2.h
    src/libsodium/crypto_stream/salsa20/xmm6int/u0.h
    src/libsodium/crypto_stream/salsa20/xmm6int/u1.h
    src/libsodium/crypto_stream/salsa20/xmm6int/u4.h
    src/libsodium/crypto_stream/salsa20/xmm6int/u8.h
    src/libsodium/crypto_stream/xsalsa20/stream_xsalsa20.c
    src/libsodium/crypto_verify/sodium/verify.c
    src/libsodium/include/sodium.h
    src/libsodium/include/sodium/core.h
    src/libsodium/include/sodium/crypto_aead_aes256gcm.h
    src/libsodium/include/sodium/crypto_aead_chacha20poly1305.h
    src/libsodium/include/sodium/crypto_aead_xchacha20poly1305.h
    src/libsodium/include/sodium/crypto_auth.h
    src/libsodium/include/sodium/crypto_auth_hmacsha256.h
    src/libsodium/include/sodium/crypto_auth_hmacsha512.h
    src/libsodium/include/sodium/crypto_auth_hmacsha512256.h
    src/libsodium/include/sodium/crypto_box.h
    src/libsodium/include/sodium/crypto_box_curve25519xchacha20poly1305.h
    src/libsodium/include/sodium/crypto_box_curve25519xsalsa20poly1305.h
    src/libsodium/include/sodium/crypto_core_ed25519.h
    src/libsodium/include/sodium/crypto_core_hchacha20.h
    src/libsodium/include/sodium/crypto_core_hsalsa20.h
    src/libsodium/include/sodium/crypto_core_ristretto255.h
    src/libsodium/include/sodium/crypto_core_salsa20.h
    src/libsodium/include/sodium/crypto_core_salsa2012.h
    src/libsodium/include/sodium/crypto_core_salsa208.h
    src/libsodium/include/sodium/crypto_generichash.h
    src/libsodium/include/sodium/crypto_generichash_blake2b.h
    src/libsodium/include/sodium/crypto_hash.h
    src/libsodium/include/sodium/crypto_hash_sha256.h
    src/libsodium/include/sodium/crypto_hash_sha512.h
    src/libsodium/include/sodium/crypto_internal_types.h
    src/libsodium/include/sodium/crypto_kdf.h
    src/libsodium/include/sodium/crypto_kdf_blake2b.h
    src/libsodium/include/sodium/crypto_kx.h
    src/libsodium/include/sodium/crypto_onetimeauth.h
    src/libsodium/include/sodium/crypto_onetimeauth_poly1305.h
    src/libsodium/include/sodium/crypto_pwhash.h
    src/libsodium/include/sodium/crypto_pwhash_argon2i.h
    src/libsodium/include/sodium/crypto_pwhash_argon2id.h
    src/libsodium/include/sodium/crypto_pwhash_scryptsalsa208sha256.h
    src/libsodium/include/sodium/crypto_scalarmult.h
    src/libsodium/include/sodium/crypto_scalarmult_curve25519.h
    src/libsodium/include/sodium/crypto_scalarmult_ed25519.h
    src/libsodium/include/sodium/crypto_scalarmult_ristretto255.h
    src/libsodium/include/sodium/crypto_secretbox.h
    src/libsodium/include/sodium/crypto_secretbox_xchacha20poly1305.h
    src/libsodium/include/sodium/crypto_secretbox_xsalsa20poly1305.h
    src/libsodium/include/sodium/crypto_secretstream_xchacha20poly1305.h
    src/libsodium/include/sodium/crypto_shorthash.h
    src/libsodium/include/sodium/crypto_shorthash_siphash24.h
    src/libsodium/include/sodium/crypto_sign.h
    src/libsodium/include/sodium/crypto_sign_ed25519.h
    src/libsodium/include/sodium/crypto_sign_edwards25519sha512batch.h
    src/libsodium/include/sodium/crypto_stream.h
    src/libsodium/include/sodium/crypto_stream_chacha20.h
    src/libsodium/include/sodium/crypto_stream_salsa20.h
    src/libsodium/include/sodium/crypto_stream_salsa2012.h
    src/libsodium/include/sodium/crypto_stream_salsa208.h
    src/libsodium/include/sodium/crypto_stream_xchacha20.h
    src/libsodium/include/sodium/crypto_stream_xsalsa20.h
    src/libsodium/include/sodium/crypto_verify_16.h
    src/libsodium/include/sodium/crypto_verify_32.h
    src/libsodium/include/sodium/crypto_verify_64.h
    src/libsodium/include/sodium/export.h
    src/libsodium/include/sodium/private/chacha20_ietf_ext.h
    src/libsodium/include/sodium/private/common.h
    src/libsodium/include/sodium/private/ed25519_ref10.h
    src/libsodium/include/sodium/private/ed25519_ref10_fe_25_5.h
    src/libsodium/include/sodium/private/ed25519_ref10_fe_51.h
    src/libsodium/include/sodium/private/implementations.h
    src/libsodium/include/sodium/private/mutex.h
    src/libsodium/include/sodium/private/sse2_64_32.h
    src/libsodium/include/sodium/randombytes.h
    src/libsodium/include/sodium/randombytes_internal_random.h
    src/libsodium/include/sodium/randombytes_sysrandom.h
    src/libsodium/include/sodium/runtime.h
    src/libsodium/include/sodium/utils.h
    src/libsodium/include/sodium/version.h
    src/libsodium/randombytes/internal/randombytes_internal_random.c
    src/libsodium/randombytes/randombytes.c
    src/libsodium/randombytes/sysrandom/randombytes_sysrandom.c
    src/libsodium/sodium/codecs.c
    src/libsodium/sodium/core.c
    src/libsodium/sodium/runtime.c
    src/libsodium/sodium/utils.c
    src/libsodium/sodium/version.c
)

if(NOT SODIUM_MINIMAL)
    target_sources(${LIB_NAME}
        PRIVATE
            src/libsodium/crypto_box/curve25519xchacha20poly1305/box_curve25519xchacha20poly1305.c
            src/libsodium/crypto_box/curve25519xchacha20poly1305/box_seal_curve25519xchacha20poly1305.c
            src/libsodium/crypto_core/ed25519/core_ed25519.c
            src/libsodium/crypto_core/ed25519/core_ristretto255.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/crypto_scrypt-common.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/crypto_scrypt.h
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/nosse/pwhash_scryptsalsa208sha256_nosse.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/pbkdf2-sha256.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/pbkdf2-sha256.h
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/pwhash_scryptsalsa208sha256.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/scrypt_platform.c
            src/libsodium/crypto_pwhash/scryptsalsa208sha256/sse/pwhash_scryptsalsa208sha256_sse.c
            src/libsodium/crypto_scalarmult/ed25519/ref10/scalarmult_ed25519_ref10.c
            src/libsodium/crypto_scalarmult/ristretto255/ref10/scalarmult_ristretto255_ref10.c
            src/libsodium/crypto_secretbox/xchacha20poly1305/secretbox_xchacha20poly1305.c
            src/libsodium/crypto_shorthash/siphash24/ref/shorthash_siphashx24_ref.c
            src/libsodium/crypto_shorthash/siphash24/shorthash_siphashx24.c
            src/libsodium/crypto_sign/ed25519/ref10/obsolete.c
            src/libsodium/crypto_stream/salsa2012/ref/stream_salsa2012_ref.c
            src/libsodium/crypto_stream/salsa2012/stream_salsa2012.c
            src/libsodium/crypto_stream/salsa208/ref/stream_salsa208_ref.c
            src/libsodium/crypto_stream/salsa208/stream_salsa208.c
            src/libsodium/crypto_stream/xchacha20/stream_xchacha20.c
    )
endif()

set_target_properties(${LIB_NAME}
    PROPERTIES
        C_STANDARD 99
)

target_include_directories(${LIB_NAME}
    PUBLIC
        src/libsodium/include
    PRIVATE
        src/libsodium/include/sodium
)

target_compile_definitions(${LIB_NAME}
    PUBLIC
        $<$<NOT:$<BOOL:${BUILD_SHARED_LIBS}>>:SODIUM_STATIC>
        $<$<BOOL:${SODIUM_MINIMAL}>:SODIUM_LIBRARY_MINIMAL>
    PRIVATE
        CONFIGURED
        $<$<BOOL:${BUILD_SHARED_LIBS}>:SODIUM_DLL_EXPORT>
        $<$<BOOL:${SODIUM_ENABLE_BLOCKING_RANDOM}>:USE_BLOCKING_RANDOM>
        $<$<BOOL:${SODIUM_MINIMAL}>:MINIMAL>
        $<$<C_COMPILER_ID:MSVC>:_CRT_SECURE_NO_WARNINGS>
)

include(./features.cmake)


# Variables that need to be exported to version.h.in
set(VERSION 1.0.18)
set(SODIUM_LIBRARY_VERSION_MAJOR 10)
set(SODIUM_LIBRARY_VERSION_MINOR 3)
if(SODIUM_MINIMAL)
    set(SODIUM_LIBRARY_MINIMAL_DEF "#define SODIUM_LIBRARY_MINIMAL 1")
endif()

configure_file(
    src/libsodium/include/sodium/version.h.in
    ${CMAKE_CURRENT_SOURCE_DIR}/src/libsodium/include/sodium/version.h
)

add_library(libsodium::${LIB_NAME} ALIAS ${LIB_NAME})
export(
    TARGETS ${LIB_NAME}
    NAMESPACE libsodium::
    FILE libsodiumInternalTargets.cmake
)
