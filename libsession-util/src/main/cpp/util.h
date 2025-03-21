#ifndef SESSION_ANDROID_UTIL_H
#define SESSION_ANDROID_UTIL_H

#include <jni.h>
#include <array>
#include <optional>
#include <span>
#include <vector>
#include "session/types.hpp"
#include "session/config/groups/info.hpp"
#include "session/config/groups/keys.hpp"
#include "session/config/groups/members.hpp"
#include "session/config/profile_pic.hpp"
#include "session/config/user_groups.hpp"
#include "session/config/expiring.hpp"

namespace util {
    extern std::mutex util_mutex_;
    jbyteArray bytes_from_vector(JNIEnv* env, std::vector<unsigned char> from_str);
    std::vector<unsigned char> vector_from_bytes(JNIEnv* env, jbyteArray byteArray);
    jbyteArray bytes_from_span(JNIEnv* env, std::span<const unsigned char> from_str);
    std::string string_from_jstring(JNIEnv* env, jstring string);
    jobject serialize_user_pic(JNIEnv *env, session::config::profile_pic pic);
    std::pair<jstring, jbyteArray> deserialize_user_pic(JNIEnv *env, jobject user_pic);
    jobject serialize_base_community(JNIEnv *env, const session::config::community& base_community);
    session::config::community deserialize_base_community(JNIEnv *env, jobject base_community);
    jobject serialize_expiry(JNIEnv *env, const session::config::expiration_mode& mode, const std::chrono::seconds& time_seconds);
    std::pair<session::config::expiration_mode, long> deserialize_expiry(JNIEnv *env, jobject expiry_mode);
    jobject serialize_group_member(JNIEnv* env, const session::config::groups::member& member);
    jobject jlongFromOptional(JNIEnv* env, std::optional<long long> optional);
    jstring jstringFromOptional(JNIEnv* env, std::optional<std::string_view> optional);
    jobject serialize_account_id(JNIEnv* env, std::string_view session_id);
    std::string deserialize_account_id(JNIEnv* env, jobject account_id);
    jobject build_string_stack(JNIEnv* env, std::vector<std::string> to_add);
    jobject deserialize_swarm_auth(JNIEnv *env, session::config::groups::Keys::swarm_auth auth);}

#endif