########## BASELINE / ATTRIBUTES ##########
# Core attrs (serialization/DI/reflective access often rely on these)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Record
# Some tools repeat/override attribute keeps; keeping as provided
-keepattributes Signature,InnerClasses,EnclosingMethod

# Honor @Keep if present
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

########## OPTIONAL GOOGLE BITS (SUPPRESSED WARNINGS) ##########
-dontwarn com.google.android.gms.common.annotation.**
-dontwarn com.google.firebase.analytics.connector.**

########## ANDROID / DI ##########
# Workers constructed by class name
-keep class ** extends androidx.work.ListenableWorker

########## KOTLINX SERIALIZATION ##########
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

########## JACKSON (CORE + ANNOTATIONS + DTOs) ##########
# Keep Jackson packages and common annotated members
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class ** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty *;
}
-dontwarn com.fasterxml.jackson.databind.**

# Jackson DTO used by OpenGroupApi (reactions map values)
-keep class org.session.libsession.messaging.open_groups.OpenGroupApi$Reaction { *; }
-keepnames class org.session.libsession.messaging.open_groups.OpenGroupApi$Reaction
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Reaction {
    <fields>;
    *** get*();
    void set*(***);

    # keep the default constructor too:
    public <init>(***, int, kotlin.jvm.internal.DefaultConstructorMarker);
    # and a bare no-arg constructor if it exists
    public <init>();
}

# DTO used by OpenGroupApi
-keep class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities { *; }
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities { <init>(); }
-keepnames class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities

# Project models referenced via Jackson (from crashes)
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData { *; }
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData$* { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret$* { *; }

# Keep names + bean-style accessors for OpenGroupApi models
-keepnames class org.session.libsession.messaging.open_groups.**
-keepclassmembers class org.session.libsession.messaging.open_groups.** {
    <fields>;
    *** get*();
    void set*(***);
}

# Keep names + bean-style accessors for snode models
-keepnames class org.session.libsession.snode.**
-keepclassmembers class org.session.libsession.snode.** {
    <fields>;
    *** get*();
    void set*(***);
}

# TypeReference subclasses and repeated Jackson-annotation keep
-keep class ** extends com.fasterxml.jackson.core.type.TypeReference { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty *;
}

# Converters / Deserializers
-keep class org.session.libsession.snode.model.RetrieveMessageConverter { public <init>(); public *; }
-keep class * implements com.fasterxml.jackson.databind.util.Converter { public <init>(); public *; }
-keep class * extends com.fasterxml.jackson.databind.JsonDeserializer { public <init>(); public *; }

########## JNI LOGGER / NATIVE ENTRYPOINTS ##########
# Logging interface & implementations (JNI looks up log(String,String,int))
-keep interface network.loki.messenger.libsession_util.util.Logger { *; }
-keepnames class * implements network.loki.messenger.libsession_util.util.Logger
-keepclassmembers class * implements network.loki.messenger.libsession_util.util.Logger {
    public void log(java.lang.String, java.lang.String, int);
}

# JNI: ConfigPush constructors (exact signatures preserved)
-keepnames class network.loki.messenger.libsession_util.util.ConfigPush
-keepclassmembers class network.loki.messenger.libsession_util.util.ConfigPush {
    public <init>(java.util.List, long, java.util.List);
    public <init>(java.util.List, long, java.util.List, int, kotlin.jvm.internal.DefaultConstructorMarker);
}

# JNI: specific getter used from native
-keepnames class network.loki.messenger.libsession_util.util.UserPic
-keepclassmembers class network.loki.messenger.libsession_util.util.UserPic {
    public byte[] getKeyAsByteArray();
}

########## WEBRTC / CHROMIUM JNI ##########
# WebRTC public Java APIs (kept for JNI_OnLoad registration)
-keep class org.webrtc.** { *; }

# Chromium-based bits
-keep class org.chromium.** { *; }
-keep class org.chromium.base.** { *; }
-keep class org.chromium.net.** { *; }
-keep class org.chromium.media.** { *; }

# Keep all native bridges everywhere
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

########## WEBRTC / CHROMIUM jni_zero ##########
# Ensure jni_zero Java side is discoverable by native
-keep class org.jni_zero.** { *; }
-keepnames class org.jni_zero.**

########## CONVERSATION / MODELS (JNI + REFLECTION) ##########
# Conversation.* types constructed via JNI with (String,long,boolean)
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$OneToOne {
    public <init>(java.lang.String, long, boolean);
}
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$* {
    public <init>(java.lang.String, long, boolean);
}

# Keep names and members of Conversation/Community models (JNI searches by name)
-keep class network.loki.messenger.libsession_util.util.Conversation$Community { *; }
-keep class network.loki.messenger.libsession_util.util.Conversation$OneToOne { *; }
-keep class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup { *; }
-keep class network.loki.messenger.libsession_util.util.BaseCommunityInfo { *; }

-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$Community { public <init>(...); }
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$OneToOne { public <init>(...); }
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup { public <init>(...); }

-keepnames class network.loki.messenger.libsession_util.util.Conversation$Community
-keepnames class network.loki.messenger.libsession_util.util.Conversation$OneToOne
-keepnames class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup
-keepnames class network.loki.messenger.libsession_util.util.BaseCommunityInfo

# Group members (JNI constructor with long)
-keep class network.loki.messenger.libsession_util.GroupMembersConfig { *; }
-keep class network.loki.messenger.libsession_util.util.GroupMember { *; }
-keepclassmembers class network.loki.messenger.libsession_util.util.GroupMember { public <init>(long); }
-keepnames class network.loki.messenger.libsession_util.util.GroupMember

# Broad safety net for long-arg ctors in util package
-keepclassmembers class network.loki.messenger.libsession_util.util.** { public <init>(long); }

########## EMOJI SEARCH (JACKSON / POLYMORPHIC) ##########
# Keep names if @JsonTypeInfo uses CLASS/MINIMAL_CLASS
-keepnames class org.thoughtcrime.securesms.database.model.**
# Preserve abstract base + nested types for property/creator names
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData { *; }
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData$* { *; }

########## KRYO (SERIALIZATION OF DESTINATIONS) ##########
# No-arg contructors required at runtime for these sealed subclasses
-keepclassmembers class org.session.libsession.messaging.messages.Destination$ClosedGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$Contact { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$LegacyClosedGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$LegacyOpenGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroupInbox { <init>(); }

# Keep the Enum serializer contructor Kryo reflects on
-keepclassmembers class com.esotericsoftware.kryo.serializers.DefaultSerializers$EnumSerializer {
    public <init>(java.lang.Class);
}

# Prevent enum unboxing/renaming for the enum field being serialized
-keep class org.session.libsession.messaging.messages.control.TypingIndicator$Kind { *; }

# Preserve class names for Kryo
-keepnames class org.session.libsession.messaging.messages.Destination$**

########## OPEN GROUP API (MESSAGES) ##########
-keep class org.session.libsession.messaging.open_groups.OpenGroupApi$Message { *; }
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Message { <init>(); }
-keepnames class org.session.libsession.messaging.open_groups.OpenGroupApi$Message
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Message {
    *** get*();
    void set*(***);
}

# Misc suppressed warnings
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn sun.nio.ch.DirectBuffer