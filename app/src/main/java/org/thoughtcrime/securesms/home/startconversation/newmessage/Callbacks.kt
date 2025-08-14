package org.thoughtcrime.securesms.home.startconversation.newmessage

internal interface Callbacks {
    fun onChange(value: String) {}
    fun onContinue() {}
    fun onScanQrCode(value: String) {}
}
