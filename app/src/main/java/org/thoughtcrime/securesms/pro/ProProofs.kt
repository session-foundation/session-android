package org.thoughtcrime.securesms.pro

import com.google.protobuf.ByteString
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsignal.protos.SignalServiceProtos

/**
 * Copies values from a libsession ProProof into a protobuf-based ProProof.
 */
fun SignalServiceProtos.ProProof.Builder.copyFromLibSession(
    proProof: ProProof
): SignalServiceProtos.ProProof.Builder = setVersion(proProof.version)
    .setExpiryUnixTs(proProof.expiryMs)
    .setGenIndexHash(ByteString.copyFrom(proProof.genIndexHashHex.hexToByteArray()))
    .setRotatingPublicKey(ByteString.copyFrom(proProof.rotatingPubKeyHex.hexToByteArray()))
    .setSig(ByteString.copyFrom(proProof.signatureHex.hexToByteArray()))
