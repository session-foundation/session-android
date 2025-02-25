#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "crypto_internal_fe25519.h"
#include "private/common.h"
#include "private/ed25519_ref10.h"

void crypto_internal_fe25519_invert(fe25519 out, const fe25519 z) {
    fe25519_invert(out, z);
}

void crypto_internal_fe25519_frombytes(fe25519 h, const unsigned char *s) {
    fe25519_frombytes(h, s);
}

void crypto_internal_fe25519_tobytes(unsigned char *s, const fe25519 h) {
    fe25519_tobytes(s, h);
}

void crypto_internal_fe25519_0(fe25519 h) {
    fe25519_0(h);
}

void crypto_internal_fe25519_1(fe25519 h) {
    fe25519_1(h);
}

void crypto_internal_fe25519_add(fe25519 h, const fe25519 f, const fe25519 g) {
    fe25519_add(h, f, g);
}

void crypto_internal_fe25519_sub(fe25519 h, const fe25519 f, const fe25519 g) {
    fe25519_sub(h, f, g);
}

void crypto_internal_fe25519_neg(fe25519 h, const fe25519 f) {
    fe25519_neg(h, f);
}

void crypto_internal_fe25519_cmov(fe25519 f, const fe25519 g, unsigned int b) {
    fe25519_cmov(f, g, b);
}

void crypto_internal_fe25519_cswap(fe25519 f, fe25519 g, unsigned int b) {
    fe25519_cswap(f, g, b);
}

void crypto_internal_fe25519_copy(fe25519 h, const fe25519 f) {
    fe25519_copy(h, f);
}

int crypto_internal_fe25519_isnegative(const fe25519 f) {
    return fe25519_isnegative(f);
}

int crypto_internal_fe25519_iszero(const fe25519 f) {
    return fe25519_iszero(f);
}

void crypto_internal_fe25519_mul(fe25519 h, const fe25519 f, const fe25519 g) {
    fe25519_mul(h, f, g);
}

void crypto_internal_fe25519_sq(fe25519 h, const fe25519 f) {
    fe25519_sq(h, f);
}

void crypto_internal_fe25519_sq2(fe25519 h, const fe25519 f) {
    fe25519_sq2(h, f);
}

void crypto_internal_fe25519_mul32(fe25519 h, const fe25519 f, uint32_t n) {
    fe25519_mul32(h, f, n);
}
