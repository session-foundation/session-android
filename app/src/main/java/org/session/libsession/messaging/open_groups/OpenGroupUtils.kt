package org.session.libsession.messaging.open_groups

fun String.migrateLegacyServerUrl() = if (contains(OpenGroupApi.legacyServerIP)) {
    OfficialCommunityRepository.OFFICIAL_COMMUNITY_URL
} else if (contains(OfficialCommunityRepository.OFFICIAL_COMMUNITY_URL_INSECURE)) {
    OfficialCommunityRepository.OFFICIAL_COMMUNITY_URL
} else {
    this
}