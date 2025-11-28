package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import network.loki.messenger.libsession_util.pro.ProConfig
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet

fun ConfigFactoryProtocol.watchUserProConfig(): Flow<ProConfig?> =
    userConfigsChanged(EnumSet.of(UserConfigType.USER_PROFILE))
        .castAwayType()
        .onStart { emit(Unit) }
        .map {
            withUserConfigs { configs ->
                configs.userProfile.getProConfig()
            }
        }
