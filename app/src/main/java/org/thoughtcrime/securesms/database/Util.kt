package org.thoughtcrime.securesms.database


// Helper method to generate SQL placeholders (?, ?, ?)
fun StringBuilder.generateSQLPlaceholders(count: Int): StringBuilder {
    repeat(count) { nth ->
        if (nth > 0) append(", ")
        append("?")
    }

    return this
}