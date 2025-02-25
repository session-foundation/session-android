#pragma once

namespace session::config {

enum class notify_mode {
    defaulted = 0,
    all = 1,
    disabled = 2,
    mentions_only = 3,  // Only for groups; for DMs this becomes `all`
};

}
