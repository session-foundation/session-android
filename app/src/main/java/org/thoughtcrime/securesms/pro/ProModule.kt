package org.thoughtcrime.securesms.pro

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.loki.messenger.libsession_util.pro.BackendRequests

@Module
@InstallIn(SingletonComponent::class)
class ProModule {
    @Provides
    fun provideProBackendConfig(): ProBackendConfig {
        // The backend URL + Ed25519 signing pubkey come from libsession (single source of truth), so a
        // future change happens in exactly one place rather than a per-client copy. x25519 is derived
        // on the fly from the Ed key (see ProBackendConfig).
        return ProBackendConfig(
            url = BackendRequests.proBackendUrl(),
            ed25519PubKeyHex = BackendRequests.proBackendPubKeyHex(),
        )
    }
}
