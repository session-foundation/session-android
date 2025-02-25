###############################################################################
## ProGuard / R8 rules
###############################################################################

#--------------------------------------------------
# 1) MANTENER Actividades, servicios, y clases que
#    forman parte del entry point de Android.
#    Ajusta el package a tu proyecto (com.encrypt.bwt).
#--------------------------------------------------
-keep class com.encrypt.bwt.** {
    <init>();
}

#--------------------------------------------------
# 2) ViewBinding / DataBinding
#    Mantiene clases generadas por el binding,
#    y evita problemas con reflexiones internas.
#--------------------------------------------------
-keep class **ViewBinding
-keep class **ViewDataBinding
-keep class androidx.databinding.** { *; }

#--------------------------------------------------
# 3) Mantener las anotaciones (por ejemplo para
#    inyección de dependencias o Compose).
#--------------------------------------------------
-keepattributes *Annotation*

#--------------------------------------------------
# 4) GSON
#    Si usas GSON para serializar 'KeyItem' u otras
#    clases, conviene mantener sus campos si GSON
#    hace reflexiones en runtime para parsear JSON.
#    -keep class com.encrypt.bwt.KeyItem { *; }
#
#    O, si no quieres que se renombre,
#    -keep class com.encrypt.bwt.** implements java.io.Serializable { *; }
#    Pero en muchos casos GSON sigue funcionando si
#    no usas reflection de campos private.
#    Ajusta según convenga.
#--------------------------------------------------
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# (Opcional) si tus modelos se parsean con GSON por reflection:
# -keepclassmembers class com.encrypt.bwt.** {
#    <fields>;
# }

#--------------------------------------------------
# 5) PBKDF2 + javax.crypto + SecureRandom
#    Normalmente no hace falta,
#    pero si quieres evitar warnings:
#--------------------------------------------------
-dontwarn javax.crypto.**
-dontwarn java.security.**
-dontwarn org.bouncycastle.**

#--------------------------------------------------
# 6) Jetpack Compose (si tuvieras Compose).
#    Para evitar warnings y mantener lo esencial:
#--------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

#--------------------------------------------------
# 7) Remover logs de linea si lo deseas
#    -renamesourcefileattribute SourceFile
#    -keepattributes SourceFile,LineNumberTable
#--------------------------------------------------

#--------------------------------------------------
# 8) Overaggressive rename
#    -overloadaggressively
#    Solo si quieres agresividad mayor.
#    Puede romper ciertos Reflection. Úsalo con cuidado.
#--------------------------------------------------

###############################################################################
## FIN
###############################################################################
