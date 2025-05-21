package org.thoughtcrime.securesms.tokenpage

// Commands that we can ask the Token Page to perform
sealed class TokenPageCommand {

    // Refresh current data / or pretend to
    data object RefreshData: TokenPageCommand()
}