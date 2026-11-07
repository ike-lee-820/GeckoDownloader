# GeckoView loads its native bridge through reflection and JNI names.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# OkHttp and Okio optional platform references.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room generated code is referenced by name from the database class.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Kotlin serialization of the sniffer messages uses org.json, so nothing extra is required here.

# GeckoView pulls in org.yaml:snakeyaml, whose introspector references java.beans.* classes.
# Android has no java.beans package and GeckoView does not use that code path on device,
# so R8's "Missing class" error must be silenced (full-mode R8 treats it as fatal).
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**
