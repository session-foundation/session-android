package org.thoughtcrime.securesms.database

import android.content.Context
import org.thoughtcrime.securesms.dependencies.DatabaseComponent

fun Context.threadDatabase() = DatabaseComponent.get(this).threadDatabase()

// Helper method to generate SQL placeholders (?, ?, ?)
fun generatePlaceholders(count: Int): String {
    if (count <= 0) return ""

    val placeholders = StringBuilder()
    for (i in 0..<count) {
        if (i > 0) placeholders.append(", ")
        placeholders.append("?")
    }

    return placeholders.toString()
}