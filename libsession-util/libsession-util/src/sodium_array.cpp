#include <sodium/utils.h>

#include <session/sodium_array.hpp>

namespace session {

void* sodium_buffer_allocate(size_t length) {
    if (auto* p = sodium_malloc(length))
        return p;
    throw std::bad_alloc{};
}

void sodium_buffer_deallocate(void* p) {
    if (p)
        sodium_free(p);
}

void sodium_zero_buffer(void* ptr, size_t size) {
    if (ptr)
        sodium_memzero(ptr, size);
}

}  // namespace session
