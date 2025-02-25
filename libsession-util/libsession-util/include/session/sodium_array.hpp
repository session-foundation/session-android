#pragma once

#include "util.hpp"

namespace session {

// Calls sodium_malloc for secure allocation; throws a std::bad_alloc on allocation failure
void* sodium_buffer_allocate(size_t size);
// Frees a pointer constructed with sodium_buffer_allocate.  Does nothing if `p` is nullptr.
void sodium_buffer_deallocate(void* p);
// Calls sodium_memzero to zero a buffer
void sodium_zero_buffer(void* ptr, size_t size);

// Works similarly to a unique_ptr, but allocations and free go via libsodium (which is slower, but
// more secure for sensitive data).
template <typename T>
struct sodium_ptr {
  private:
    T* x;

  public:
    sodium_ptr() : x{nullptr} {}
    sodium_ptr(std::nullptr_t) : sodium_ptr{} {}
    ~sodium_ptr() { reset(x); }

    // Allocates and constructs a new `T` in-place, forwarding any given arguments to the `T`
    // constructor.  If this sodium_ptr already has an object, `reset()` is first called implicitly
    // to destruct and deallocate the existing object.
    template <typename... Args>
    T& emplace(Args&&... args) {
        if (x)
            reset();
        x = static_cast<T>(sodium_buffer_allocate(sizeof(T)));
        new (x) T(std::forward<Args>(args)...);
        return *x;
    }

    void reset() {
        if (x) {
            x->~T();
            sodium_buffer_deallocate(x);
            x = nullptr;
        }
    }
    void operator=(std::nullptr_t) { reset(); }

    T& operator*() { return *x; }
    const T& operator*() const { return *x; }

    T* operator->() { return x; }
    const T* operator->() const { return x; }

    explicit operator bool() const { return x != nullptr; }
};

// Wrapper around a type that uses `sodium_memzero` to zero the container on destruction; may only
// be used with trivially destructible types.
template <typename T, typename = std::enable_if_t<std::is_trivially_destructible_v<T>>>
struct sodium_cleared : T {
    using T::T;

    ~sodium_cleared() { sodium_zero_buffer(this, sizeof(*this)); }
};

template <size_t N>
using cleared_array = sodium_cleared<std::array<unsigned char, N>>;

using cleared_uc32 = cleared_array<32>;
using cleared_uc64 = cleared_array<64>;

// This is an optional (i.e. can be empty) fixed-size (at construction) buffer that does allocation
// and freeing via libsodium.  It is slower and heavier than a regular allocation type but takes
// extra precautions, intended for storing sensitive values.
template <typename T>
struct sodium_array {
  private:
    T* buf;
    size_t len;

  public:
    // Default constructor: makes an empty object (that is, has no buffer and has `.size()` of 0).
    sodium_array() : buf{nullptr}, len{0} {}

    // Constructs an array with a given size, default-constructing the individual elements.
    template <typename = std::enable_if_t<std::is_default_constructible_v<T>>>
    explicit sodium_array(size_t length) :
            buf{length == 0 ? nullptr
                            : static_cast<T*>(sodium_buffer_allocate(length * sizeof(T)))},
            len{0} {

        if (length > 0) {
            if constexpr (std::is_trivial_v<T>) {
                std::memset(buf, 0, length * sizeof(T));
                len = length;
            } else if constexpr (std::is_nothrow_default_constructible_v<T>) {
                for (; len < length; len++)
                    new (buf[len]) T();
            } else {
                try {
                    for (; len < length; len++)
                        new (buf[len]) T();
                } catch (...) {
                    reset();
                    throw;
                }
            }
        }
    }

    ~sodium_array() { reset(); }

    // Moveable: ownership is transferred to the new object and the old object becomes empty.
    sodium_array(sodium_array&& other) : buf{other.buf}, len{other.len} {
        other.buf = nullptr;
        other.len = 0;
    }
    sodium_array& operator=(sodium_array&& other) {
        sodium_buffer_deallocate(buf);
        buf = other.buf;
        len = other.len;
        other.buf = nullptr;
        other.len = 0;
    }

    // Non-copyable
    sodium_array(const sodium_array&) = delete;
    sodium_array& operator=(const sodium_array&) = delete;

    // Destroys the held array; after destroying elements the allocated space is overwritten with
    // 0s before being deallocated.
    void reset() {
        if (buf) {
            if constexpr (!std::is_trivially_destructible_v<T>)
                while (len > 0)
                    buf[--len].~T();

            sodium_buffer_deallocate(buf);
        }
        buf = nullptr;
        len = 0;
    }

    // Calls reset() to destroy the current value (if any) and then allocates a new
    // default-constructed one of the given size.
    template <typename = std::enable_if_t<std::is_default_constructible_v<T>>>
    void reset(size_t length) {
        reset();
        if (length > 0) {
            buf = static_cast<T*>(sodium_buffer_allocate(length * sizeof(T)));
            if constexpr (std::is_trivial_v<T>) {
                std::memset(buf, 0, length * sizeof(T));
                len = length;
            } else {
                for (; len < length; len++)
                    new (buf[len]) T();
            }
        }
    }

    // Loads the array from a pointer and size; this first resets a value (if present), allocates a
    // new array of the given size, the copies the given value(s) into the new buffer.  T must be
    // copyable.  This is *not* safe to use if `buf` points into the currently allocated data.
    template <typename = std::enable_if_t<std::is_copy_constructible_v<T>>>
    void load(const T* data, size_t length) {
        reset(length);
        if (length == 0)
            return;

        if constexpr (std::is_trivially_copyable_v<T>)
            std::memcpy(buf, data, sizeof(T) * length);
        else
            for (; len < length; len++)
                new (buf[len]) T(data[len]);
    }

    const T& operator[](size_t i) const {
        assert(i < len);
        return buf[i];
    }
    T& operator[](size_t i) {
        assert(i < len);
        return buf[i];
    }

    T* data() { return buf; }
    const T* data() const { return buf; }

    size_t size() const { return len; }
    bool empty() const { return len == 0; }
    explicit operator bool() const { return !empty(); }

    T* begin() { return buf; }
    const T* begin() const { return buf; }
    T* end() { return buf + len; }
    const T* end() const { return buf + len; }

    using difference_type = ptrdiff_t;
    using value_type = T;
    using pointer = value_type*;
    using reference = value_type&;
    using iterator_category = std::random_access_iterator_tag;
};

// sodium Allocator wrapper; this allocates/frees via libsodium, which is designed for dealing with
// sensitive data.  It is as a result slower and has more overhead than a standard allocator and
// intended for use with a container (such as std::vector) when storing keys.
template <typename T>
struct sodium_allocator {
    using value_type = T;

    [[nodiscard]] static T* allocate(std::size_t n) {
        return static_cast<T*>(sodium_buffer_allocate(n * sizeof(T)));
    }

    static void deallocate(T* p, std::size_t) { sodium_buffer_deallocate(p); }

    template <typename T2>
    bool operator==(const sodium_allocator<T2>&) const noexcept {
        return true;
    }
    template <typename T2>
    bool operator!=(const sodium_allocator<T2>&) const noexcept {
        return false;
    }
};

/// Vector that uses sodium's secure (but heavy) memory allocations
template <typename T>
using sodium_vector = std::vector<T, sodium_allocator<T>>;

}  // namespace session
