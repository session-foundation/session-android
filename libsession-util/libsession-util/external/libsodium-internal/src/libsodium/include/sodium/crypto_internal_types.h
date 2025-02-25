
#ifndef sodium_oxen_internal_types_H
#define sodium_oxen_internal_types_H

#include <stdint.h>

#ifdef HAVE_TI_MODE
typedef uint64_t fe25519[5];
#else
typedef int32_t fe25519[10];
#endif

#endif
