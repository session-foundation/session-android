package org.thoughtcrime.securesms.home.startconversation.newmessage

import org.session.libsession.network.snode.SnodeDirectory
import org.thoughtcrime.securesms.api.snode.OnsResolveApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.execute
import javax.inject.Inject

class OnsResolver @Inject constructor(
    private val snodeApiExecutor: SnodeApiExecutor,
    private val snodeDirectory: SnodeDirectory,
    private val onsResolveApiFactory: OnsResolveApi.Factory,
) {
    suspend fun resolve(name: String): String {
        val validationCount = 3

        val results = List(validationCount) {
            snodeApiExecutor.execute(
                SnodeApiRequest(
                    snode = snodeDirectory.getRandomSnode(),
                    onsResolveApiFactory.create(name)
                )
            )
        }

        check(results.toSet().size == 1) {
            "ONS resolution results do not match: $results"
        }

        return results.first()
    }
}