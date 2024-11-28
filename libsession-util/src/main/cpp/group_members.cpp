#include "group_members.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_00024Companion_newInstance(
        JNIEnv *env, jobject thiz, jbyteArray pub_key, jbyteArray secret_key,
        jbyteArray initial_dump) {
    std::lock_guard lock{util::util_mutex_};
    auto pub_key_bytes = util::ustring_from_bytes(env, pub_key);
    std::optional<session::ustring> secret_key_optional{std::nullopt};
    std::optional<session::ustring> initial_dump_optional{std::nullopt};
    if (secret_key && env->GetArrayLength(secret_key) > 0) {
        auto secret_key_bytes = util::ustring_from_bytes(env, secret_key);
        secret_key_optional = secret_key_bytes;
    }
    if (initial_dump && env->GetArrayLength(initial_dump) > 0) {
        auto initial_dump_bytes = util::ustring_from_bytes(env, initial_dump);
        initial_dump_optional = initial_dump_bytes;
    }

    auto* group_members = new session::config::groups::Members(pub_key_bytes, secret_key_optional, initial_dump_optional);
    return reinterpret_cast<jlong>(group_members);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_all(JNIEnv *env, jobject thiz) {
    std::lock_guard lock{util::util_mutex_};
    auto config = ptrToMembers(env, thiz);
    jclass stack = env->FindClass("java/util/Stack");
    jmethodID init = env->GetMethodID(stack, "<init>", "()V");
    jobject our_stack = env->NewObject(stack, init);
    jmethodID push = env->GetMethodID(stack, "push", "(Ljava/lang/Object;)Ljava/lang/Object;");
    for (auto& member : *config) {
        auto member_obj = util::serialize_group_member(env, member);
        env->CallObjectMethod(our_stack, push, member_obj);
    }
    return our_stack;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_erase(JNIEnv *env, jobject thiz, jstring pub_key_hex) {
    auto config = ptrToMembers(env, thiz);
    auto member_id = env->GetStringUTFChars(pub_key_hex, nullptr);
    auto erased = config->erase(member_id);
    env->ReleaseStringUTFChars(pub_key_hex, member_id);
    return erased;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_get(JNIEnv *env, jobject thiz,
                                                                    jstring pub_key_hex) {
    std::lock_guard lock{util::util_mutex_};
    auto config = ptrToMembers(env, thiz);
    auto pub_key_bytes = env->GetStringUTFChars(pub_key_hex, nullptr);
    auto member = config->get(pub_key_bytes);
    if (!member) {
        return nullptr;
    }
    auto serialized = util::serialize_group_member(env, *member);
    env->ReleaseStringUTFChars(pub_key_hex, pub_key_bytes);
    return serialized;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_getOrConstruct(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring pub_key_hex) {
    std::lock_guard lock{util::util_mutex_};
    auto config = ptrToMembers(env, thiz);
    auto pub_key_bytes = env->GetStringUTFChars(pub_key_hex, nullptr);
    auto member = config->get_or_construct(pub_key_bytes);
    auto serialized = util::serialize_group_member(env, member);
    env->ReleaseStringUTFChars(pub_key_hex, pub_key_bytes);
    return serialized;
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_GroupMembersConfig_set(JNIEnv *env, jobject thiz,
                                                                    jobject group_member) {
    std::lock_guard lock{util::util_mutex_};
    ptrToMembers(env, thiz)->set(*ptrToMember(env, group_member));
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setInvited(JNIEnv *env,
                                                                         jobject thiz) {
    ptrToMember(env, thiz)->invite_status = session::config::groups::STATUS_NOT_SENT;
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setInviteSent(JNIEnv *env,
                                                                            jobject thiz) {
    ptrToMember(env, thiz)->set_invite_sent();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setInviteFailed(JNIEnv *env,
                                                                              jobject thiz) {
    ptrToMember(env, thiz)->set_invite_failed();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setInviteAccepted(JNIEnv *env,
                                                                          jobject thiz) {
    ptrToMember(env, thiz)->set_invite_accepted();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setPromoted(JNIEnv *env,
                                                                          jobject thiz) {
    ptrToMember(env, thiz)->set_promoted();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setPromotionSent(JNIEnv *env,
                                                                               jobject thiz) {
    ptrToMember(env, thiz)->set_promotion_sent();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setPromotionFailed(JNIEnv *env,
                                                                                 jobject thiz) {
    ptrToMember(env, thiz)->set_promotion_failed();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setPromotionAccepted(JNIEnv *env,
                                                                                   jobject thiz) {
    ptrToMember(env, thiz)->set_promotion_accepted();
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setRemoved(JNIEnv *env, jobject thiz,
                                                                         jboolean also_remove_messages) {
    ptrToMember(env, thiz)->set_removed(also_remove_messages);
}

extern "C"
JNIEXPORT jint JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_statusInt(JNIEnv *env, jobject thiz) {
    return static_cast<jint>(ptrToMember(env, thiz)->status());
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setName(JNIEnv *env, jobject thiz,
                                                                      jstring name) {
    auto name_bytes = env->GetStringUTFChars(name, nullptr);
    ptrToMember(env, thiz)->set_name(name_bytes);
    env->ReleaseStringUTFChars(name, name_bytes);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_nameString(JNIEnv *env,
                                                                         jobject thiz) {
    return util::jstringFromOptional(env, ptrToMember(env, thiz)->name);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_isAdmin(JNIEnv *env, jobject thiz) {
    return ptrToMember(env, thiz)->admin;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_isSupplement(JNIEnv *env,
                                                                           jobject thiz) {
    return ptrToMember(env, thiz)->supplement;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_accountIdString(JNIEnv *env,
                                                                              jobject thiz) {
    return util::jstringFromOptional(env, ptrToMember(env, thiz)->session_id);
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_destroy(JNIEnv *env, jobject thiz) {
    delete ptrToMember(env, thiz);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_profilePic(JNIEnv *env,
                                                                         jobject thiz) {
    return util::serialize_user_pic(env, ptrToMember(env, thiz)->profile_picture);
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setProfilePic(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jobject pic) {
    const auto [jurl, jkey] = util::deserialize_user_pic(env, pic);
    auto url = util::string_from_jstring(env, jurl);
    auto key = util::ustring_from_bytes(env, jkey);
    auto &picture = ptrToMember(env, thiz)->profile_picture;
    picture.url = url;
    picture.key = key;
}

extern "C"
JNIEXPORT void JNICALL
Java_network_loki_messenger_libsession_1util_util_GroupMember_setSupplement(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jboolean supplement) {
    ptrToMember(env, thiz)->supplement = supplement;
}

