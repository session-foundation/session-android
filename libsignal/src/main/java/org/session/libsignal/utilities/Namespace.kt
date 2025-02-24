package org.session.libsignal.utilities

object Namespace {
    fun ALL() = "all"

    // Namespaces used for legacy group
    fun UNAUTHENTICATED_CLOSED_GROUP() = -10

    // Namespaces used for user's own swarm
    external fun DEFAULT(): Int
    external fun USER_PROFILE(): Int
    external fun CONTACTS(): Int
    external fun CONVO_INFO_VOLATILE(): Int
    external fun USER_GROUPS(): Int

    // Namesapced used for groupv2
    external fun GROUP_INFO(): Int
    external fun GROUP_MEMBERS(): Int
    external fun GROUP_KEYS(): Int
    external fun GROUP_MESSAGES(): Int
    external fun REVOKED_GROUP_MESSAGES(): Int
}