#include "session/config/error.h"

const char* config_errstr(int err) {
    switch (err) {
        case SESSION_ERR_INVALID_DUMP: return "Dumped data is invalid";
        case SESSION_ERR_BAD_VALUE: return "Invalid value";
    }
    return "Unknown error";
}
