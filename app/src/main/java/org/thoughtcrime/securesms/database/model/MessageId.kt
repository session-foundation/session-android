package org.thoughtcrime.securesms.database.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

/**
 * Represents a pair of values that can be used to find a message. Because we have two tables,
 * that means this has both the primary key and a boolean indicating which table it's in.
 */
@Parcelize
data class MessageId(
  val id: Long,
  @get:JvmName("isMms") val mms: Boolean
): Parcelable {
  // Exists only because Kryo wants it
  @Keep
  private constructor(): this(0, false)

  val sms: Boolean get() = !mms

  fun serialize(): String {
    return "$id|$mms"
  }

  companion object {
    /**
     * Returns null for invalid IDs. Useful when pulling a possibly-unset ID from a database, or something like that.
     */
    @JvmStatic
    fun fromNullable(id: Long, mms: Boolean): MessageId? {
      return if (id > 0) {
        MessageId(id, mms)
      } else {
        null
      }
    }

    @JvmStatic
    fun deserialize(serialized: String): MessageId {
      val parts: List<String> = serialized.split("|")
      return MessageId(parts[0].toLong(), parts[1].toBoolean())
    }
  }
}
