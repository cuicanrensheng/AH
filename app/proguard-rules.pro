# 虎牙解析类不混淆
-keep class com.tv.live.HuyaParser{*;}

# okhttp混淆配置
-dontwarn okhttp3.**
-keep class okhttp3.**{*;}

# 额外补充retrofit、gson、exoplayer常用防崩溃混淆（适配你项目依赖）
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

-keep class com.google.gson.**{*;}
-dontwarn com.google.gson.**

-keep class com.google.android.exoplayer.**{*;}
-dontwarn com.google.android.exoplayer.**

# glide混淆
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.**{*;}
