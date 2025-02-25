#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace session {

using ustring = std::basic_string<unsigned char>;
using ustring_view = std::basic_string_view<unsigned char>;

namespace config {

    using seqno_t = std::int64_t;

}  // namespace config

}  // namespace session
