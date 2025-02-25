#include "session/random.hpp"

#include <sodium/randombytes.h>

#include "session/export.h"
#include "session/util.hpp"

namespace session::random {

ustring random(size_t size) {
    ustring result;
    result.resize(size);
    randombytes_buf(result.data(), size);

    return result;
}

}  // namespace session::random

extern "C" {

LIBSESSION_C_API unsigned char* session_random(size_t size) {
    auto result = session::random::random(size);
    auto* ret = static_cast<unsigned char*>(malloc(size));
    std::memcpy(ret, result.data(), result.size());
    return ret;
}

}  // extern "C"
