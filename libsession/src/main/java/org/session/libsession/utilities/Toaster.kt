package org.session.libsession.utilities

import androidx.annotation.StringRes

interface Toaster {
    fun toast(@StringRes stringRes: Int, toastLength: Int, parameters: Map<String, String>)
    fun toast(message: CharSequence, toastLength: Int)
}