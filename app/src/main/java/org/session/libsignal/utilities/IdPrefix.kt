package org.session.libsignal.utilities

enum class IdPrefix(val value: String, val binaryValue: Byte) {
    STANDARD("05", 0x05), BLINDED("15", 0x15), UN_BLINDED("00", 0x00), GROUP("03", 0x03), BLINDEDV2("25", 0x25);

    fun isBlinded() = value == BLINDED.value || value == BLINDEDV2.value

    companion object {
        fun fromValue(rawValue: String): IdPrefix? = when(rawValue.take(2)) {
            STANDARD.value -> STANDARD
            BLINDED.value -> BLINDED
            BLINDEDV2.value -> BLINDEDV2
            UN_BLINDED.value -> UN_BLINDED
            GROUP.value -> GROUP
            else -> null
        }
    }

}