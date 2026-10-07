package org.thoughtcrime.securesms.groups

import network.loki.messenger.libsession_util.util.GroupInfo

/**
 * Whether this UserGroups entry is what a config merge leaves of a group another of our devices
 * erased, rather than a group we are (or were) in.
 *
 * libsession applies each device's diff in turn, so when one device erases a group while another
 * changes one of its fields (marking it destroyed after seeing the group's info, say), the merge
 * recreates the erased entry holding only the changed fields: no name and no keys. A real kicked or
 * destroyed entry always keeps its name, so a removed entry without one can only be this.
 *
 * The other clients apply the same rule; keep them in step.
 */
fun GroupInfo.ClosedGroupInfo.isErasedGroupStub(): Boolean =
    (destroyed || kicked) && name.isEmpty() && adminKey == null && authData == null
