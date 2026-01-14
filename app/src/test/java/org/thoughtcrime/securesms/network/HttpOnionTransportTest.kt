package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.Test
import org.mockito.kotlin.mock
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.http.HttpOnionTransport
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.LogMockingTestBase

class HttpOnionTransportMappingTest: LogMockingTestBase() {


    private val snodeDirLazy: Lazy<SnodeDirectory> = Lazy { mock() }
    private val transport = HttpOnionTransport(snodeDirectory = snodeDirLazy)

    private fun snode(id: String): Snode =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
            version = Snode.Version.ZERO
        )

    private fun serverDest(): OnionDestination =
        OnionDestination.ServerDestination(
            host = "example.com",
            target = "v4",
            x25519PublicKey = "xkey",
            scheme = "https",
            port = 443
        )

    private fun httpFail(code: Int, body: String?): HTTP.HTTPRequestFailedException =
        HTTP.HTTPRequestFailedException(statusCode = code, body = body)

    // ----- 502 mapping -----

    @Test
    fun `502 next node not found matching snode destination pk to DestinationUnreachable`() {
        val guard = snode("guard")
        val dest = snode("dest")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(502, "Next node not found: ${dest.publicKeySet!!.ed25519Key}")

        val err = transport.mapPathHttpError(
            node = guard,
            ex = ex,
            path = path,
            destination = OnionDestination.SnodeDestination(dest)
        )

        assertThat(err).isInstanceOf(OnionError.DestinationUnreachable::class.java)
    }

    @Test
    fun `502 next node not found non-matching pk to IntermediateNodeUnreachable with failed pk`() {
        val guard = snode("guard")
        val dest = snode("dest")
        val failedPk = "ed_other"
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(502, "Next node not found: $failedPk")

        val err = transport.mapPathHttpError(guard, ex, path, OnionDestination.SnodeDestination(dest))

        assertThat(err).isInstanceOf(OnionError.IntermediateNodeUnreachable::class.java)
        assertThat((err as OnionError.IntermediateNodeUnreachable).failedPublicKey).isEqualTo(failedPk)
    }

    @Test
    fun `502 next node unreachable server destination to IntermediateNodeUnreachable`() {
        val guard = snode("guard")
        val failedPk = "ed_other"
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(502, "Next node is currently unreachable: $failedPk")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.IntermediateNodeUnreachable::class.java)
        assertThat((err as OnionError.IntermediateNodeUnreachable).failedPublicKey).isEqualTo(failedPk)
    }

    @Test
    fun `502 trims parsed pk`() {
        val guard = snode("guard")
        val dest = snode("dest")
        val pk = "ed_trim"
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(502, "Next node not found:   $pk   ")

        val err = transport.mapPathHttpError(guard, ex, path, OnionDestination.SnodeDestination(dest))

        assertThat(err).isInstanceOf(OnionError.IntermediateNodeUnreachable::class.java)
        assertThat((err as OnionError.IntermediateNodeUnreachable).failedPublicKey).isEqualTo(pk)
    }

    // ----- 503 mapping -----

    @Test
    fun `503 service node not ready to SnodeNotReady with guard pk`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(503, "Service node is not ready: something")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.SnodeNotReady::class.java)
        assertThat((err as OnionError.SnodeNotReady).failedPublicKey)
            .isEqualTo(guard.publicKeySet!!.ed25519Key)
    }

    @Test
    fun `503 server busy to SnodeNotReady with guard pk`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(503, "Server busy, try again later")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.SnodeNotReady::class.java)
        assertThat((err as OnionError.SnodeNotReady).failedPublicKey)
            .isEqualTo(guard.publicKeySet!!.ed25519Key)
    }

    @Test
    fun `503 snode not ready prefix to SnodeNotReady with parsed pk`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))
        val failedPk = "ed_target"

        val ex = httpFail(503, "Snode not ready: $failedPk")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.SnodeNotReady::class.java)
        assertThat((err as OnionError.SnodeNotReady).failedPublicKey).isEqualTo(failedPk)
    }

    // ----- 504 mapping -----

    @Test
    fun `504 request time out to PathTimedOut`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(504, "Request time out")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.PathTimedOut::class.java)
    }

    @Test
    fun `504 without request time out to PathError`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(504, "Gateway timeout") // doesn't match your substring

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.PathError::class.java)
    }

    // ----- 500 mapping -----

    @Test
    fun `500 invalid response from snode to InvalidHopResponse`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(500, "Invalid response from snode")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.InvalidHopResponse::class.java)
    }

    // ----- default mapping -----

    @Test
    fun `other status to PathError`() {
        val guard = snode("guard")
        val path: Path = listOf(guard, snode("m1"), snode("m2"))

        val ex = httpFail(418, "I'm a teapot")

        val err = transport.mapPathHttpError(guard, ex, path, serverDest())

        assertThat(err).isInstanceOf(OnionError.PathError::class.java)
    }
}
