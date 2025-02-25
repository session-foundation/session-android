package com.encrypt.bwt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object PendingIntentHelper {
    fun getActivityPendingIntent(
        context: Context,
        intent: Intent,
        requestCode: Int = 0
    ): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
