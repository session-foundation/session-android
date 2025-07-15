package org.thoughtcrime.securesms.database

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.userConfigsChanged
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject
import javax.inject.Singleton

private typealias CommunityServerUrl = String
private typealias BlindedAddress = Address

/**
 * A class that handles the blind mappings of addresses reactively.
 */
@Singleton
class BlindMappingRepository @Inject constructor(
    configFactory: ConfigFactory,
    prefs: TextSecurePreferences,
) {

    /**
     * A [StateFlow] that emits a map of community server URLs to their respective mappings of
     * blinded addresses to 05 prefixed addresses
     */
    @Suppress("OPT_IN_USAGE")
    val mappings: StateFlow<Map<CommunityServerUrl, Map<BlindedAddress, Address>>> = prefs.watchLocalNumber()
        .filterNotNull()
        .flatMapLatest { localAddress ->
            configFactory
                .userConfigsChanged(200L)
                .onStart { emit(Unit) }
                .map {
                    configFactory.withUserConfigs { configs ->
                        Pair(
                            configs.userGroups.allCommunityInfo().map { it.community },
                            configs.contacts.all().map { Address.fromSerialized(it.id) }
                                    + Address.fromSerialized(localAddress)
                        )
                    }
                }
        }
        .distinctUntilChanged()
        .map { (allCommunities, allContacts) ->
            allContacts.asSequence()
                .flatMap { contactAddress ->
                    allCommunities.asSequence()
                        .map { community ->
                            val allBlindIDs = BlindKeyAPI.blind15Ids(
                                sessionId = contactAddress.address,
                                serverPubKey = community.pubKeyHex
                            ).asSequence() + BlindKeyAPI.blind25Id(
                                sessionId = contactAddress.address,
                                serverPubKey = community.pubKeyHex
                            )

                            community.baseUrl to
                            allBlindIDs
                                .map(Address::fromSerialized)
                                .associateWith { contactAddress }
                        }

                }
                .toMap()
        }
        .stateIn(GlobalScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

    fun getMapping(
        serverUrl: CommunityServerUrl,
        blindedAddress: BlindedAddress
    ): Address? {
        return mappings.value[serverUrl]?.get(blindedAddress)
    }

    fun getReverseMappings(
        contactAddress: Address,
    ): List<Pair<CommunityServerUrl, BlindedAddress>> {
        return mappings.value.flatMap { (communityUrl, mapping) ->
            mapping.filter { it.value == contactAddress }
                .map { (blindedAddress, _) -> communityUrl to blindedAddress }
        }
    }

    fun observeMapping(
        communityAddress: CommunityServerUrl,
        blindedAddress: BlindedAddress
    ): StateFlow<Address?> {
        return mappings.map { it[communityAddress]?.get(blindedAddress) }
            .distinctUntilChanged()
            .stateIn(GlobalScope, started = SharingStarted.Eagerly, initialValue = getMapping(communityAddress, blindedAddress))
    }
}