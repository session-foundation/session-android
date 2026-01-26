package org.session.libsignal.utilities

import android.annotation.SuppressLint
import android.util.LruCache
import org.thoughtcrime.securesms.api.batch.BatchApiExecutor

/**
 * Create a Snode from a "-" delimited String if valid, null otherwise.
 */
fun Snode(string: String): Snode? {
    val components = string.split("-")
    val address = components[0]
    val port = components.getOrNull(1)?.toIntOrNull() ?: return null
    val ed25519Key = components.getOrNull(2) ?: return null
    val x25519Key = components.getOrNull(3) ?: return null
    return Snode(address, port, Snode.KeySet(ed25519Key, x25519Key))
}

data class Snode(val address: String, val port: Int, val publicKeySet: KeySet?) {
    val ip: String get() = address.removePrefix("https://")
    val ed25519Key: String get() = publicKeySet!!.ed25519Key
    val x25519Key: String get() = publicKeySet!!.x25519Key

    enum class Method(val rawValue: String) {
        GetSwarm("get_snodes_for_pubkey"),
        Retrieve("retrieve"),
        SendMessage("store"),
        DeleteMessage("delete"),
        OxenDaemonRPCCall("oxend_request"),
        Info("info"),
        DeleteAll("delete_all"),
        Batch("batch"),
        Sequence("sequence"),
        Expire("expire"),
        GetExpiries("get_expiries"),
        RevokeSubAccount("revoke_subaccount"),
        UnrevokeSubAccount("unrevoke_subaccount"),
        ActiveSnodesBin("active_nodes_bin"),
    }

    data class KeySet(val ed25519Key: String, val x25519Key: String)

    override fun equals(other: Any?) = other is Snode && address == other.address && port == other.port
    override fun hashCode(): Int = address.hashCode() xor port.hashCode()
    override fun toString(): String = "$address:$port"
}
