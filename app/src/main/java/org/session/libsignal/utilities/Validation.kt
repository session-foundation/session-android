package org.session.libsignal.utilities

object PublicKeyValidation {
    private val HEX_CHARACTERS = "0123456789ABCDEFabcdef".toSet()
    private val INVALID_PREFIXES = setOf(IdPrefix.GROUP, IdPrefix.BLINDED, IdPrefix.BLINDEDV2)

    fun isValid(candidate: String, isPrefixRequired: Boolean = true): Boolean {
        if (!hasValidLength(candidate)) return false

        val prefix = IdPrefix.fromValue(candidate)

        // Handle invalid Account ID conditions
        // Case 1: Standard prefix "05" but not valid hex
        if (prefix == IdPrefix.STANDARD && !isValidHexEncoding(candidate)) return false

        // Case 2: Blinded or Group IDs should never be accepted as valid Account IDs
        if (prefix in INVALID_PREFIXES) return false

        // Standard validity rules
        return isValidHexEncoding(candidate) &&
                (!isPrefixRequired || prefix != null)
    }

    fun hasValidPrefix(candidate: String) = IdPrefix.fromValue(candidate) !in INVALID_PREFIXES
    fun hasValidLength(candidate: String) = candidate.length == 66
    private fun isValidHexEncoding(candidate: String) = HEX_CHARACTERS.containsAll(candidate.toSet())
}
