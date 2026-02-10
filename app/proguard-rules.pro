-dontobfuscate

########## BASELINE / ATTRIBUTES ##########
# Core attrs (serialization/DI/reflective access often rely on these)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Record,KotlinMetadata

# Keep Kotlin collection singleton
-keep class kotlin.collections.EmptySet { *; }
-keep class kotlin.collections.EmptyList { *; }
-keep class kotlin.collections.EmptyMap { *; }

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

-keep class ** extends com.fasterxml.jackson.core.type.TypeReference { *; }
-keep class * implements com.fasterxml.jackson.databind.util.Converter { public <init>(); public *; }
-keep class * extends com.fasterxml.jackson.databind.JsonDeserializer { public <init>(); public *; }

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

########## JNI LOGGER / NATIVE ENTRYPOINTS ##########


########## WEBRTC / CHROMIUM JNI ##########
# WebRTC public Java APIs (kept for JNI_OnLoad registration)
-keep class org.webrtc.** { *; }

# Chromium-based bits
-keep class org.chromium.base.** { *; }
-keep class org.chromium.net.** { *; }

# Keep all native bridges everywhere
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

########## WEBRTC / CHROMIUM jni_zero ##########
# Ensure jni_zero Java side is discoverable by native
-keep class org.jni_zero.** { *; }
-keepnames class org.jni_zero.**

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
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroup { <init>(); }
-keepclassmembers class org.session.libsession.messaging.messages.Destination$OpenGroupInbox { <init>(); }

# Keep the Enum serializer contructor Kryo reflects on
-keepclassmembers class com.esotericsoftware.kryo.serializers.** {
    public <init>(...);
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

-keep class org.session.libsession.messaging.utilities.UpdateMessageData { *; }
-keep class org.session.libsession.messaging.utilities.UpdateMessageData$* { *; }
-keepnames class org.session.libsession.messaging.utilities.UpdateMessageData$*
-keep class org.session.libsession.messaging.utilities.Data { *; }
-keepnames class org.session.libsession.messaging.utilities.Data
-keep class org.session.libsession.messaging.messages.Message { *; }
-keepnames class org.session.libsession.messaging.messages.Message

########## HUAWEI / HMS (minified builds) ##########
# Device-only classes referenced by HMS internals — not present on Maven.
-dontwarn android.telephony.HwTelephonyManager
-dontwarn com.huawei.android.os.BuildEx$VERSION
-dontwarn com.huawei.libcore.io.**
-dontwarn com.huawei.hianalytics.**
-dontwarn com.huawei.hms.availableupdate.**

# Misc suppressed warnings
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn sun.nio.ch.DirectBuffer