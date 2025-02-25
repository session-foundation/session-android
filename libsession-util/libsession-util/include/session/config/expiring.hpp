#pragma once
#include <cstdint>

namespace session::config {

enum class expiration_mode : int8_t { none = 0, after_send = 1, after_read = 2 };

}
