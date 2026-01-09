package org.session.libsession.network.model

enum class PathStatus {
    READY,      // green
    BUILDING,   // orange
    ERROR       // red (offline, no path, repeated failures, etc.)
}