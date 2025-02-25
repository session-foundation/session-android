#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

/// libsession-util version triplet (major, minor, patch)
extern const uint16_t LIBSESSION_UTIL_VERSION[3];

/// Printable full libsession-util name and version string, such as `libsession-util v0.1.2-release`
/// for a tagged release or `libsession-util v0.1.2-7f144eb5` for an untagged build.
extern const char* LIBSESSION_UTIL_VERSION_FULL;

/// Just the version component as a string, e.g. `v0.1.2-release`.
extern const char* LIBSESSION_UTIL_VERSION_STR;

#ifdef __cplusplus
}  // extern "C"
#endif
