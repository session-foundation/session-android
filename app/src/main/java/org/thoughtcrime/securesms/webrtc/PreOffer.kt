package org.thoughtcrime.securesms.webrtc

import org.session.libsession.utilities.Address
import java.util.UUID

data class PreOffer(val callId: UUID, val recipient: Address)