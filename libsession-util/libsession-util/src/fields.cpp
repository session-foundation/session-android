#include "session/fields.hpp"

#include <oxenc/hex.h>

#include <iterator>

namespace session {

std::string SessionID::hex() const {
    std::string id;
    id.reserve(33);
    id.push_back(static_cast<char>(netid));
    oxenc::to_hex(pubkey.begin(), pubkey.end(), std::back_inserter(id));
    return id;
}

}  // namespace session
