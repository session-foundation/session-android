package org.session.libsession.utilities


typealias StringSubKey = String

// String substitution keys for use with the Phrase library.
// Note: The substitution will be to {app_name} etc. in the strings - but do NOT include the curly braces in these keys!
object StringSubstitutionConstants {
    const val ACCOUNT_ID_KEY: StringSubKey                 = "account_id"
    const val APP_NAME_KEY: StringSubKey                   = "app_name"
    const val AUTHOR_KEY: StringSubKey                     = "author"
    const val COMMUNITY_NAME_KEY: StringSubKey             = "community_name"
    const val CONVERSATION_COUNT_KEY: StringSubKey         = "conversation_count"
    const val CONVERSATION_NAME_KEY: StringSubKey          = "conversation_name"
    const val COUNT_KEY: StringSubKey                      = "count"
    const val DATE_KEY: StringSubKey                       = "date"
    const val DATE_TIME_KEY: StringSubKey                  = "date_time"
    const val DISAPPEARING_MESSAGES_TYPE_KEY: StringSubKey = "disappearing_messages_type"
    const val DOWNLOAD_URL_KEY: StringSubKey               = "session_download_url" // Used to invite people to download Session
    const val EMOJI_KEY: StringSubKey                      = "emoji"
    const val ETHEREUM_KEY: StringSubKey                   = "ethereum"
    const val FILE_TYPE_KEY: StringSubKey                  = "file_type"
    const val GROUP_NAME_KEY: StringSubKey                 = "group_name"
    const val ICON_KEY: StringSubKey                       = "icon"
    const val MEMBERS_KEY: StringSubKey                    = "members"
    const val MESSAGE_COUNT_KEY: StringSubKey              = "message_count"
    const val MESSAGE_SNIPPET_KEY: StringSubKey            = "message_snippet"
    const val NAME_KEY: StringSubKey                       = "name"
    const val NETWORK_NAME_KEY: StringSubKey               = "network_name"
    const val OTHER_NAME_KEY: StringSubKey                 = "other_name"
    const val PRICE_DATA_POWERED_BY_KEY: StringSubKey      = "price_data_powered_by"
    const val QUERY_KEY: StringSubKey                      = "query"
    const val RELATIVE_TIME_KEY: StringSubKey              = "relative_time"
    const val SECONDS_KEY: StringSubKey                    = "seconds"
    const val SESSION_DOWNLOAD_URL_KEY: StringSubKey       = "session_download_url"
    const val STAKING_REWARD_POOL_KEY: StringSubKey        = "staking_reward_pool"
    const val TIME_KEY: StringSubKey                       = "time"
    const val TIME_LARGE_KEY: StringSubKey                 = "time_large"
    const val TIME_SMALL_KEY: StringSubKey                 = "time_small"
    const val TOKEN_BONUS_TITLE_KEY: StringSubKey          = "token_bonus_title"
    const val TOKEN_NAME_LONG_KEY: StringSubKey            = "token_name_long"
    const val TOKEN_NAME_LONG_PLURAL_KEY: StringSubKey     = "token_name_long_plural"
    const val TOKEN_NAME_SHORT_KEY: StringSubKey           = "token_name_short"
    const val TOTAL_COUNT_KEY: StringSubKey                = "total_count"
    const val URL_KEY: StringSubKey                        = "url"
    const val VALUE_KEY: StringSubKey                      = "value"
    const val VERSION_KEY: StringSubKey                    = "version"
    const val LIMIT_KEY: StringSubKey                      = "limit"
    const val STORE_VARIANT_KEY: StringSubKey              = "storevariant"
    const val APP_PRO_KEY: StringSubKey                    = "app_pro"
}