########## BASELINE ##########
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Record

# Honor @Keep if present
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

# Optional Google bits you excluded
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

########## JACKSON ##########
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class ** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty *;
}
-dontwarn com.fasterxml.jackson.databind.**

# Project models used via Jackson (from your crashes)
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData { *; }
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper$SealedData$* { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret { *; }
-keep class org.thoughtcrime.securesms.crypto.AttachmentSecret$* { *; }

########## JNI LOGGER ##########
# Keep the interface + all implementors (incl. anonymous/lambdas) and the exact method JNI looks up
-keep interface network.loki.messenger.libsession_util.util.Logger { *; }
-keepnames class * implements network.loki.messenger.libsession_util.util.Logger
-keepclassmembers class * implements network.loki.messenger.libsession_util.util.Logger {
    public void log(java.lang.String, java.lang.String, int);
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

# --- Emoji search (Jackson polymorphic) ---
# If @JsonTypeInfo uses CLASS or MINIMAL_CLASS, keep class NAMES for the model package.
-keepnames class org.thoughtcrime.securesms.database.model.**

# Keep the abstract base + its nested types and members so property/creator names stay intact.
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData { *; }
-keep class org.thoughtcrime.securesms.database.model.EmojiSearchData$* { *; }

########## (OPTIONAL) easier stack traces while iterating ##########
# -keepattributes SourceFile,LineNumberTable
# This is generated automatically by the Android Gradle plugin.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean