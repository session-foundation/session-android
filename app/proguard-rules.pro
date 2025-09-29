########## BASELINE ##########
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Record

# Honor @Keep if present
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

# Optional Google bits you excluded
-dontwarn com.google.android.gms.common.annotation.**
-dontwarn com.google.firebase.analytics.connector.**
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

########## JACKSON ##########
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class ** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty *;
}
-dontwarn com.fasterxml.jackson.databind.**

# Jackson DTO used by OpenGroupApi
-keep class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities { *; }
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities { <init>(); }
-keepnames class org.session.libsession.messaging.open_groups.OpenGroupApi$Capabilities

# Project models used via Jackson (from your crashes)
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData { *; }
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData$* { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret$* { *; }

-keepnames class org.session.libsession.messaging.open_groups.**
-keepclassmembers class org.session.libsession.messaging.open_groups.** {
    <fields>;
    *** get*();
    void set*(***);
}

-keepnames class org.session.libsession.snode.**
-keepclassmembers class org.session.libsession.snode.** {
    <fields>;
    *** get*();
    void set*(***);
}

-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class ** extends com.fasterxml.jackson.core.type.TypeReference { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty *;
}

########## JNI LOGGER ##########
# Keep the interface + all implementors (incl. anonymous/lambdas) and the exact method JNI looks up
-keep interface network.loki.messenger.libsession_util.util.Logger { *; }
-keepnames class * implements network.loki.messenger.libsession_util.util.Logger
-keepclassmembers class * implements network.loki.messenger.libsession_util.util.Logger {
    public void log(java.lang.String, java.lang.String, int);
}

# JNI: Config push constructor(s) must stay exactly as-is
-keepnames class network.loki.messenger.libsession_util.util.ConfigPush

-keepclassmembers class network.loki.messenger.libsession_util.util.ConfigPush {
    # The one JNI is calling:
    public <init>(java.util.List, long, java.util.List);
    # Keep the Kotlin default-params ctor too (harmless if absent):
    public <init>(java.util.List, long, java.util.List, int, kotlin.jvm.internal.DefaultConstructorMarker);
}

# JNI: preserve the exact getter used from native
-keepnames class network.loki.messenger.libsession_util.util.UserPic
-keepclassmembers class network.loki.messenger.libsession_util.util.UserPic {
    public byte[] getKeyAsByteArray();
}


########## WEBRTC / CHROMIUM JNI ##########
# Keep WebRTC Java APIs fully to satisfy JNI_OnLoad registration
-keep class org.webrtc.** { *; }

# Some builds ship Chromium base helpers; harmless if absent
-keep class org.chromium.** { *; }
-keep class org.chromium.base.** { *; }
-keep class org.chromium.net.** { *; }
-keep class org.chromium.media.** { *; }

# Keep all JNI bridges everywhere (prevents stripping/renaming of native methods)
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

########## WebRTC/Chromium jni_zero ##########
# Keep the jni_zero Java side so JNI_OnLoad can FindClass it.
-keep class org.jni_zero.** { *; }
-keepnames class org.jni_zero.**

# Conversation.* inner types constructed reflectively/JNI:
# keep the (String, long, boolean) ctor so GetMethodID/newInstance can find it
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$OneToOne {
    public <init>(java.lang.String, long, boolean);
}
# if other Conversation nested types do the same, cover them too:
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$* {
    public <init>(java.lang.String, long, boolean);
}

# Emoji search (Jackson polymorphic)
# If @JsonTypeInfo uses CLASS or MINIMAL_CLASS, keep class NAMES for the model package.
-keepnames class org.thoughtcrime.securesms.database.model.**

# Keep the abstract base + its nested types and members so property/creator names stay intact.
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData { *; }
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData$* { *; }

# Kryo needs real no-arg constructors at runtime
-keepclassmembers class org.session.libsession.messaging.messages.Destination$Contact { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$LegacyClosedGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$LegacyOpenGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$ClosedGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroupInbox { <init>(); }

# Keep the Conversation model types used by JNI
-keep class network.loki.messenger.libsession_util.util.Conversation$Community { *; }
-keep class network.loki.messenger.libsession_util.util.Conversation$OneToOne { *; }
-keep class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup { *; }
-keep class network.loki.messenger.libsession_util.util.BaseCommunityInfo { *; }

# Ensure all constructors (including @JvmOverloads-generated) stay public & unstripped
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$Community {
    public <init>(...);
}
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$OneToOne {
    public <init>(...);
}
-keepclassmembers class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup {
    public <init>(...);
}

# Don’t rename these (JNI searches by name)
-keepnames class network.loki.messenger.libsession_util.util.Conversation$Community
-keepnames class network.loki.messenger.libsession_util.util.Conversation$OneToOne
-keepnames class network.loki.messenger.libsession_util.util.Conversation$ClosedGroup
-keepnames class network.loki.messenger.libsession_util.util.BaseCommunityInfo

# Optional: preserve class names so Kryo’s writeClassAndObject stays stable across app updates
-keepnames class org.session.libsession.messaging.messages.Destination$**

-keep class org.session.libsession.messaging.open_groups.OpenGroupApi$Message { *; }
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Message { <init>(); }
-keepnames class org.session.libsession.messaging.open_groups.OpenGroupApi$Message
-keepclassmembers class org.session.libsession.messaging.open_groups.OpenGroupApi$Message {
    *** get*();
    void set*(***);
}

########## (OPTIONAL) easier stack traces while iterating ##########
# -keepattributes SourceFile,LineNumberTable
# This is generated automatically by the Android Gradle plugin.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn sun.nio.ch.DirectBuffer