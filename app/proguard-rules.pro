# 纯下载器：不打包 GeckoView / WebView 引擎，无需相关 keep 规则。

# OkHttp / Okio 的可选平台引用。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room 生成类由数据库类按名称引用。
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
