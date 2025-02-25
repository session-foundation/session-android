#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#include "../export.h"

// Maximum length of the profile pic URL (not including the null terminator)
LIBSESSION_EXPORT extern const size_t PROFILE_PIC_MAX_URL_LENGTH;

typedef struct user_profile_pic {
    // Null-terminated C string containing the uploaded URL of the pic.  Will be length 0 if there
    // is no profile pic.
    char url[224];
    // The profile pic decryption key, in bytes.  This is a byte buffer of length 32, *not* a
    // null-terminated C string.  This is only valid when there is a url (i.e. url has strlen > 0).
    unsigned char key[32];
} user_profile_pic;

#ifdef __cplusplus
}
#endif
