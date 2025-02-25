#pragma once

#include <cstdint>

namespace session::config {

enum class Namespace : std::int16_t {
    UserProfile = 2,
    Contacts = 3,
    ConvoInfoVolatile = 4,
    UserGroups = 5,

    // Messages sent to a closed group:
    GroupMessages = 11,
    // Groups config namespaces (i.e. for shared config of the group itself, not one user's group
    // settings)
    GroupKeys = 12,
    GroupInfo = 13,
    GroupMembers = 14,
};

}  // namespace session::config
