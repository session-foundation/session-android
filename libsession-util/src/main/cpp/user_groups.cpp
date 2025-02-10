#include "user_groups.h"
#include "oxenc/hex.h"

#include "session/ed25519.hpp"

extern "C"
JNIEXPORT jint JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupInfo_00024LegacyGroupInfo_00024Companion_NAME_1MAX_1LENGTH(
        JNIEnv *env, jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    return session::config::legacy_group_info::NAME_MAX_LENGTH;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getCommunityInfo(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring base_url,
                                                                               jstring room) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto base_url_bytes = env->GetStringUTFChars(base_url, nullptr);
    auto room_bytes = env->GetStringUTFChars(room, nullptr);

    auto community = conf->get_community(base_url_bytes, room_bytes);

    jobject community_info = nullptr;

    if (community) {
        community_info = serialize_community_info(env, *community);
    }
    env->ReleaseStringUTFChars(base_url, base_url_bytes);
    env->ReleaseStringUTFChars(room, room_bytes);
    return community_info;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getLegacyGroupInfo(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jstring account_id) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto id_bytes = env->GetStringUTFChars(account_id, nullptr);
    auto legacy_group = conf->get_legacy_group(id_bytes);
    jobject return_group = nullptr;
    if (legacy_group) {
        return_group = serialize_legacy_group_info(env, *legacy_group);
    }
    env->ReleaseStringUTFChars(account_id, id_bytes);
    return return_group;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getOrConstructCommunityInfo(
        JNIEnv *env, jobject thiz, jstring base_url, jstring room, jstring pub_key_hex) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto base_url_bytes = env->GetStringUTFChars(base_url, nullptr);
    auto room_bytes = env->GetStringUTFChars(room, nullptr);
    auto pub_hex_bytes = env->GetStringUTFChars(pub_key_hex, nullptr);

    auto group = conf->get_or_construct_community(base_url_bytes, room_bytes, pub_hex_bytes);

    env->ReleaseStringUTFChars(base_url, base_url_bytes);
    env->ReleaseStringUTFChars(room, room_bytes);
    env->ReleaseStringUTFChars(pub_key_hex, pub_hex_bytes);
    return serialize_community_info(env, group);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getOrConstructLegacyGroupInfo(
        JNIEnv *env, jobject thiz, jstring account_id) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto id_bytes = env->GetStringUTFChars(account_id, nullptr);
    auto group = conf->get_or_construct_legacy_group(id_bytes);
    env->ReleaseStringUTFChars(account_id, id_bytes);
    return serialize_legacy_group_info(env, group);
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_set__Lnetwork_loki_messenger_libsession_1util_util_GroupInfo_2(
        JNIEnv *env, jobject thiz, jobject group_info) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto community_info = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$CommunityGroupInfo");
    auto legacy_info = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$LegacyGroupInfo");
    auto closed_group_info = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$ClosedGroupInfo");

    auto object_class = env->GetObjectClass(group_info);
    if (env->IsSameObject(community_info, object_class)) {
        auto deserialized = deserialize_community_info(env, group_info, conf);
        conf->set(deserialized);
    } else if (env->IsSameObject(legacy_info, object_class)) {
        auto deserialized = deserialize_legacy_group_info(env, group_info, conf);
        conf->set(deserialized);
    } else if (env->IsSameObject(closed_group_info, object_class)) {
        auto deserialized = deserialize_closed_group_info(env, group_info);
        conf->set(deserialized);
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_erase__Lnetwork_loki_messenger_libsession_1util_util_GroupInfo_2(
        JNIEnv *env, jobject thiz, jobject group_info) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto communityInfo = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$CommunityGroupInfo");
    auto legacyInfo = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$LegacyGroupInfo");
    auto closedGroupInfo = env->FindClass("network/loki/messenger/libsession_util/util/GroupInfo$ClosedGroupInfo");
    auto object_class = env->GetObjectClass(group_info);
    if (env->IsSameObject(communityInfo, object_class)) {
        auto deserialized = deserialize_community_info(env, group_info, conf);
        conf->erase(deserialized);
    } else if (env->IsSameObject(legacyInfo, object_class)) {
        auto deserialized = deserialize_legacy_group_info(env, group_info, conf);
        conf->erase(deserialized);
    } else if (env->IsSameObject(closedGroupInfo, object_class)) {
        auto deserialized = deserialize_closed_group_info(env, group_info);
        conf->erase(deserialized);
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_sizeCommunityInfo(JNIEnv *env,
                                                                                jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    return conf->size_communities();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_sizeLegacyGroupInfo(JNIEnv *env,
                                                                                  jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    return conf->size_legacy_groups();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_size(JNIEnv *env, jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToConvoInfo(env, thiz);
    return conf->size();
}

inline jobject iterator_as_java_stack(JNIEnv *env, const session::config::UserGroups::iterator& begin, const session::config::UserGroups::iterator& end) {
    jclass stack = env->FindClass("java/util/Stack");
    jmethodID init = env->GetMethodID(stack, "<init>", "()V");
    jobject our_stack = env->NewObject(stack, init);
    jmethodID push = env->GetMethodID(stack, "push", "(Ljava/lang/Object;)Ljava/lang/Object;");
    for (auto it = begin; it != end;) {
        // do something with it
        auto item = *it;
        jobject serialized = nullptr;
        if (auto* lgc = std::get_if<session::config::legacy_group_info>(&item)) {
            serialized = serialize_legacy_group_info(env, *lgc);
        } else if (auto* community = std::get_if<session::config::community_info>(&item)) {
            serialized = serialize_community_info(env, *community);
        } else if (auto* closed = std::get_if<session::config::group_info>(&item)) {
            serialized = serialize_closed_group_info(env, *closed);
        }
        if (serialized != nullptr) {
            env->CallObjectMethod(our_stack, push, serialized);
        }
        it++;
    }
    return our_stack;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_all(JNIEnv *env, jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    jobject all_stack = iterator_as_java_stack(env, conf->begin(), conf->end());
    return all_stack;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_allCommunityInfo(JNIEnv *env,
                                                                               jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    jobject community_stack = iterator_as_java_stack(env, conf->begin_communities(), conf->end());
    return community_stack;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_allLegacyGroupInfo(JNIEnv *env,
                                                                                 jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    jobject legacy_stack = iterator_as_java_stack(env, conf->begin_legacy_groups(), conf->end());
    return legacy_stack;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_eraseCommunity__Lnetwork_loki_messenger_libsession_1util_util_BaseCommunityInfo_2(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jobject base_community_info) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto base_community = util::deserialize_base_community(env, base_community_info);
    return conf->erase_community(base_community.base_url(),base_community.room());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_eraseCommunity__Ljava_lang_String_2Ljava_lang_String_2(
        JNIEnv *env, jobject thiz, jstring server, jstring room) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto server_bytes = env->GetStringUTFChars(server, nullptr);
    auto room_bytes = env->GetStringUTFChars(room, nullptr);
    auto community = conf->get_community(server_bytes, room_bytes);
    bool deleted = false;
    if (community) {
        deleted = conf->erase(*community);
    }
    env->ReleaseStringUTFChars(server, server_bytes);
    env->ReleaseStringUTFChars(room, room_bytes);
    return deleted;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_eraseLegacyGroup(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring account_id) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto account_id_bytes = env->GetStringUTFChars(account_id, nullptr);
    bool return_bool = conf->erase_legacy_group(account_id_bytes);
    env->ReleaseStringUTFChars(account_id, account_id_bytes);
    return return_bool;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getClosedGroup(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jstring session_id) {
    std::lock_guard guard{util::util_mutex_};
    auto config = ptrToUserGroups(env, thiz);
    auto session_id_bytes = env->GetStringUTFChars(session_id, nullptr);

    auto group = config->get_group(session_id_bytes);

    env->ReleaseStringUTFChars(session_id, session_id_bytes);

    if (group) {
        return serialize_closed_group_info(env, *group);
    }
    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_getOrConstructClosedGroup(JNIEnv *env,
                                                                                        jobject thiz,
                                                                                        jstring session_id) {
    std::lock_guard guard{util::util_mutex_};
    auto config = ptrToUserGroups(env, thiz);
    auto session_id_bytes = env->GetStringUTFChars(session_id, nullptr);

    auto group = config->get_or_construct_group(session_id_bytes);

    env->ReleaseStringUTFChars(session_id, session_id_bytes);

    return serialize_closed_group_info(env, group);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_allClosedGroupInfo(JNIEnv *env,
                                                                                 jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto conf = ptrToUserGroups(env, thiz);
    auto closed_group_stack = iterator_as_java_stack(env, conf->begin_groups(), conf->end());

    return closed_group_stack;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_createGroup(JNIEnv *env,
                                                                          jobject thiz) {
    std::lock_guard guard{util::util_mutex_};
    auto config = ptrToUserGroups(env, thiz);

    auto group = config->create_group();
    return serialize_closed_group_info(env, group);
}

extern "C"
JNIEXPORT jlong JNICALL
  Java_network_loki_messenger_libsession_1util_UserGroupsConfig_sizeClosedGroup(JNIEnv *env,
                                                                              jobject thiz) {
    std::lock_guard guard{util::util_mutex_};
    auto config = ptrToUserGroups(env, thiz);
    return config->size_groups();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_UserGroupsConfig_eraseClosedGroup(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring session_id) {
    std::lock_guard guard{util::util_mutex_};
    auto config = ptrToUserGroups(env, thiz);
    auto session_id_bytes = env->GetStringUTFChars(session_id, nullptr);
    bool return_value = config->erase_group(session_id_bytes);
    env->ReleaseStringUTFChars(session_id, session_id_bytes);
    return return_value;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupInfo_00024ClosedGroupInfo_adminKeyFromSeed(
        JNIEnv *env, jclass clazz, jbyteArray seed) {
    auto len = env->GetArrayLength(seed);
    if (len != 32) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Seed must be 32 bytes");
        return nullptr;
    }

    auto seed_bytes = env->GetByteArrayElements(seed, nullptr);
    auto admin_key = session::ed25519::ed25519_key_pair(
            session::ustring_view(reinterpret_cast<unsigned char *>(seed_bytes), 32)).second;
    env->ReleaseByteArrayElements(seed, seed_bytes, 0);

    return util::bytes_from_ustring(env, session::ustring_view(admin_key.data(), admin_key.size()));
}