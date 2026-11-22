# libVLC: Klassen werden aus nativem Code (JNI) angesprochen – nichts umbenennen/entfernen.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# kotlinx.serialization: Serializer der eigenen Datenklassen behalten (Speicherdateien, Sync-Format).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.iptv.tv.**$$serializer { *; }
-keepclassmembers class app.iptv.tv.** {
    *** Companion;
}
-keepclasseswithmembers class app.iptv.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Absturzmeldungen lesbar halten (Zeilennummern), Namen dürfen gekürzt werden.
-keepattributes SourceFile,LineNumberTable

# OkHttp / zxing
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Namen der App-Klassen/-Methoden behalten (für verständliche Absturzmeldungen und Bildschirmnamen).
-keepnames class app.iptv.tv.** { *; }
