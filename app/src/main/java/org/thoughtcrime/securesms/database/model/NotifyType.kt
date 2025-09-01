package org.thoughtcrime.securesms.database.model

/**
 * Note: names are used in the database, so changing them will require a migration.
 */
enum class NotifyType {
    ALL,
    MENTIONS,
    NONE,
}