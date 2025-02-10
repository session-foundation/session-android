#ifndef SESSION_ANDROID_GROUP_MEMBERS_H
#define SESSION_ANDROID_GROUP_MEMBERS_H

#include "util.h"

inline session::config::groups::Members* ptrToMembers(JNIEnv* env, jobject obj) {
    jclass configClass = env->FindClass("network/loki/messenger/libsession_util/GroupMembersConfig");
    jfieldID pointerField = env->GetFieldID(configClass, "pointer", "J");
    return (session::config::groups::Members*) env->GetLongField(obj, pointerField);
}

inline session::config::groups::member *ptrToMember(JNIEnv *env, jobject thiz) {
    auto ptrField = env->GetFieldID(env->GetObjectClass(thiz), "nativePtr", "J");
    return reinterpret_cast<session::config::groups::member*>(env->GetLongField(thiz, ptrField));
}


#endif //SESSION_ANDROID_GROUP_MEMBERS_H
