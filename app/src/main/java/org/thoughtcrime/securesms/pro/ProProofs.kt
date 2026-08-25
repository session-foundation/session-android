package org.thoughtcrime.securesms.pro

import com.google.protobuf.ByteString
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.protos.SessionProtos

/**
 * Copies values from a libsession ProProof into a protobuf-based ProProof.
 *
 * There is deliberately no version field. A proof's format is bound into its signature by the
 * 16-byte domain prefix the signer selects (`ProProof_v0_____`), so the version was never part of
 * the signed payload — carrying it alongside as a plain value proved nothing and could disagree
 * with the format the signature actually attests to. Verification derives the format from the
 * prefix instead, and libsession-util has dropped `ProProof::version` accordingly.
 *
 * Protobuf field tag 1 on `ProProof` is retired and must never be reused: peers built before the
 * removal still put a `uint32` there, so a new field on tag 1 would be fed their stale version
 * numbers. A peer that sends one is handled by libsession, which reports the proof as
 * `ProStatus::UnsupportedVersion` and lets the message through without Pro content rather than
 * dropping it.
 */
fun SessionProtos.ProProof.Builder.copyFromLibSession(
    proProof: ProProof
): SessionProtos.ProProof.Builder = setExpiryUnixTs(proProof.expirySeconds)
    .setRevocationTag(ByteString.copyFrom(proProof.revocationTagHex.hexToByteArray()))
    .setRotatingPublicKey(ByteString.copyFrom(proProof.rotatingPubKeyHex.hexToByteArray()))
    .setSig(ByteString.copyFrom(proProof.signatureHex.hexToByteArray()))
