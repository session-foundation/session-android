package org.thoughtcrime.securesms.database.model

import network.loki.messenger.libsession_util.protocol.ProFeatures

val MessageRecord.proFeatures: ProFeatures get() = ProFeatures(proFeaturesRawValue)