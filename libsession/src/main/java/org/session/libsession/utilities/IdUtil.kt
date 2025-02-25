package org.session.libsession.utilities

fun truncateIdForDisplay(id: String): String =
    id.takeIf { it.length > 8 }?.run{ "${take(4)}…${takeLast(4)}" } ?: id
