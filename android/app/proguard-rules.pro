# Whistle release (R8) rules. AndroidX, Room, OkHttp, CameraX and
# kotlinx.serialization ship their own consumer rules; these cover the rest.

# Keep line numbers for readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# xterm.js calls into the terminal bridge by method name (addJavascriptInterface).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization: keep generated serializers of the app's @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.whistle.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.whistle.app.**$$serializer { *; }
-keepclassmembers class dev.whistle.app.** {
    *** Companion;
}

# OkHttp optional platform integrations.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ZXing (core only) references java.awt in unused code paths.
-dontwarn java.awt.**
