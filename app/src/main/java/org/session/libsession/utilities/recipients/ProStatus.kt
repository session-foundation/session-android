package org.session.libsession.utilities.recipients

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface ProStatus {
    @Serializable
    @SerialName("pro")
    data class Pro(
        /**
         * Whether the Pro badge should be visible or not.
         */
        val visible: Boolean = true,

        /**
         * The validity of the Pro status, if null, it means the Pro status is permanent.
         */
        @Serializable(with = InstantAsMillisSerializer::class)
        val validUntil: Instant? = null,
    ) : ProStatus

    @Serializable
    @SerialName("none")
    data object None : ProStatus
}

fun ProStatus.isPro(now: Instant = Instant.now()): Boolean {
    return this is ProStatus.Pro && (validUntil == null || validUntil.isAfter(now))
}

fun ProStatus.shouldShowProBadge(now: Instant = Instant.now()): Boolean {
    return isPro(now) && (this as ProStatus.Pro).visible
}
