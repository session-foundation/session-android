package org.session.libsession.utilities

import org.session.libsignal.utilities.AccountId

fun truncateIdForDisplay(id: String): String =
    id.takeIf { it.length > 8 }?.run{ "${take(4)}…${takeLast(4)}" } ?: id

fun AccountId.truncatedForDisplay(): String = truncateIdForDisplay(hexString)