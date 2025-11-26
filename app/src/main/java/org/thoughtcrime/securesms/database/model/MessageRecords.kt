package org.thoughtcrime.securesms.database.model

import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.util.BitSet

val MessageRecord.proFeatures: BitSet<ProMessageFeature> get() = BitSet(proFeaturesRawValue)