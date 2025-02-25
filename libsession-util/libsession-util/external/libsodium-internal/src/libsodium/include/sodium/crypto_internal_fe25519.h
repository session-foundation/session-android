#ifndef sodium_oxen_internals_H
#define sodium_oxen_internals_H

#include <stddef.h>
#include "export.h"
#include "crypto_internal_types.h"

#ifdef __cplusplus
extern "C" {
#endif


SODIUM_EXPORT
void crypto_internal_fe25519_invert(fe25519 out, const fe25519 z)
            __attribute__ ((nonnull));

SODIUM_EXPORT
void crypto_internal_fe25519_frombytes(fe25519 h, const unsigned char *s)
            __attribute__ ((nonnull));

SODIUM_EXPORT
void crypto_internal_fe25519_tobytes(unsigned char *s, const fe25519 h)
            __attribute__ ((nonnull));

SODIUM_EXPORT
void crypto_internal_fe25519_0(fe25519 h)
            __attribute__ ((nonnull));

/*
 h = 1
 */
SODIUM_EXPORT
void crypto_internal_fe25519_1(fe25519 h)
            __attribute__ ((nonnull));

/*
 h = f + g
 Can overlap h with f or g.
 */

SODIUM_EXPORT
void crypto_internal_fe25519_add(fe25519 h, const fe25519 f, const fe25519 g)
            __attribute__ ((nonnull));

/*
 h = f - g
 */

SODIUM_EXPORT
void crypto_internal_fe25519_sub(fe25519 h, const fe25519 f, const fe25519 g)
            __attribute__ ((nonnull));

/*
 h = -f
 */

SODIUM_EXPORT
void crypto_internal_fe25519_neg(fe25519 h, const fe25519 f)
            __attribute__ ((nonnull));

/*
 Replace (f,g) with (g,g) if b == 1;
 replace (f,g) with (f,g) if b == 0.
 *
 Preconditions: b in {0,1}.
 */

SODIUM_EXPORT
void crypto_internal_fe25519_cmov(fe25519 f, const fe25519 g, unsigned int b)
            __attribute__ ((nonnull));

/*
Replace (f,g) with (g,f) if b == 1;
replace (f,g) with (f,g) if b == 0.

Preconditions: b in {0,1}.
*/

SODIUM_EXPORT
void crypto_internal_fe25519_cswap(fe25519 f, fe25519 g, unsigned int b)
            __attribute__ ((nonnull));

/*
 h = f
 */

SODIUM_EXPORT
void crypto_internal_fe25519_copy(fe25519 h, const fe25519 f)
            __attribute__ ((nonnull));

/*
 return 1 if f is in {1,3,5,...,q-2}
 return 0 if f is in {0,2,4,...,q-1}
 */

SODIUM_EXPORT
int crypto_internal_fe25519_isnegative(const fe25519 f)
            __attribute__ ((nonnull));

/*
 return 1 if f == 0
 return 0 if f != 0
 */

SODIUM_EXPORT
int crypto_internal_fe25519_iszero(const fe25519 f)
            __attribute__ ((nonnull));

/*
 h = f * g
 Can overlap h with f or g.
 */

SODIUM_EXPORT
void crypto_internal_fe25519_mul(fe25519 h, const fe25519 f, const fe25519 g)
            __attribute__ ((nonnull));

/*
 h = f * f
 Can overlap h with f.
 */

void crypto_internal_fe25519_sq(fe25519 h, const fe25519 f)
            __attribute__ ((nonnull));


/*
 h = 2 * f * f
 Can overlap h with f.
*/

SODIUM_EXPORT
void crypto_internal_fe25519_sq2(fe25519 h, const fe25519 f)
            __attribute__ ((nonnull));


SODIUM_EXPORT
void crypto_internal_fe25519_mul32(fe25519 h, const fe25519 f, uint32_t n)
            __attribute__ ((nonnull));


#ifdef __cplusplus
}
#endif

#endif
