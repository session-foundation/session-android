package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.BitSet
import network.loki.messenger.libsession_util.util.asSequence
import network.loki.messenger.libsession_util.util.toBitSet

fun Long.toProMessageFeatures(out: MutableCollection<ProFeature>) {
    out.addAll(BitSet<ProMessageFeature>(this).asSequence())
}

fun Long.toProProfileFeatures(out: MutableCollection<ProFeature>) {
    out.addAll(BitSet<ProProfileFeature>(this).asSequence())
}

fun Iterable<ProFeature>.toProMessageBitSetValue(): Long {
    return filterIsInstance<ProMessageFeature>().toBitSet().rawValue
}

fun Iterable<ProFeature>.toProProfileBitSetValue(): Long {
    return filterIsInstance<ProProfileFeature>().toBitSet().rawValue
}