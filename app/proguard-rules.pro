-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-keepattributes **
-renamesourcefileattribute SourceFile

# 🔴 Gson 保护规则（防止 JSON 解析时字段名被混淆导致崩溃）
-keep class com.google.gson.** { *; }
-keepattributes com.google.gson.annotations.*
-dontwarn com.google.gson.**
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 🔴 反射保护 - 保留所有注解和反射相关
-keepclassmembers class * {
    @* <fields>;
    @* <methods>;
}
-keepclasseswithmembers class * {
    @* <methods>;
}

# 🔴 虎牙 SDK - 全量保留所有模块 + 所有方法不被移除
-keep class com.huya.** { *; }
-keep interface com.huya.** { *; }
-keep class com.duowan.** { *; }
-keep interface com.duowan.** { *; }

# 🔴 虎牙 SDK - 保留所有成员（防止方法被 R8 移除）
-keepclassmembers class com.huya.** { *; }
-keepclassmembers interface com.huya.** { *; }
-keepclassmembers class com.duowan.** { *; }
-keepclassmembers interface com.duowan.** { *; }

# 🔴 虎牙 SDK - 禁用混淆（防止反射调用失败）
-keepnames class com.huya.** { *; }
-keepnames interface com.huya.** { *; }
-keepnames class com.duowan.** { *; }

# 🔴 虎牙 SDK - 保留所有方法名（JNI/Native 调用依赖方法名）
-keepclassmembers,allowshrinking,allowoptimization class com.huya.** {
    <methods>;
    <fields>;
}
-keepclassmembers,allowshrinking,allowoptimization class com.duowan.** {
    <methods>;
    <fields>;
}

# 🔴 关键认证模块保护（JNI 调用 log、sendNet 方法）
-keep class com.huyaudb.** { *; }
-keepclassmembers class com.huyaudb.** { *; }
-keep class com.huyaudbunify.** { *; }
-keepclassmembers class com.huyaudbunify.** { *; }

# 通用直播工具
-keep class com.huya.live.common.** { *; }
-keep class com.huya.live.utils.** { *; }

-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# 🔴 Retrofit + RxJava 全面保护
-keepattributes Signature,Exceptions,*Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class rx.** { *; }
-keep class rx.schedulers.** { *; }
-keep class io.reactivex.** { *; }
-keep class io.reactivex.schedulers.** { *; }
-keep class org.reactivestreams.** { *; }
-keep class com.squareup.okhttp3.** { *; }
-keep interface com.squareup.okhttp3.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

-optimizationpasses 2
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.huya.security.** { *; }
-keep class com.huya.hydeviceid.** { *; }
-keep class **.NativeBridge { *; }
-keepclasseswithmembers class com.huya.** {
    native <methods>;
}
-keepclasseswithmembers class com.duowan.** {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}
-keepnames class com.huya.security.** { *; }
-keepnames class com.huya.hydeviceid.** { *; }
-keepnames class **.NativeBridge { *; }

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.tv.live.MainActivity { *; }
-keep class com.tv.live.CrashActivity { *; }
-keep class com.tv.live.MyApplication { *; }
-keep class com.tv.live.BootStartReceiver { *; }
-keep class com.tv.live.BootReceiver { *; }
-keep class com.tv.live.jsparser.JsLayer$ParserJsInterface { *; }
-keepclassmembers class com.tv.live.jsparser.** {
    public <methods>;
}
-keep class com.tv.live.Channel { *; }
-keep class com.tv.live.Channel$EpgItem { *; }
-keep class com.tv.live.Channel$Variant { *; }
-keep class com.tv.live.SourceManager$SourceItem { *; }

-keep class androidx.media3.ui.PlayerView {
    public <init>(...);
}
-keep class androidx.media3.ui.AspectRatioFrameLayout {
    public <init>(...);
}
-keep class androidx.media3.common.C { *; }
-keep class androidx.media3.common.C$* { *; }
-keep class androidx.media3.common.Format { *; }
-keep interface androidx.media3.common.Player { *; }
-keep class androidx.media3.common.Player$* { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }
-dontwarn androidx.media3.**

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

-dontwarn com.huya.**
-dontwarn com.duowan.**
-dontwarn com.tencent.**
-dontwarn com.alibaba.**
-dontwarn org.apache.**

-dontwarn com.huya.berry.module.audio.**
-dontwarn com.huya.berry.module.push.**
-dontwarn com.huya.berry.module.rtc.**
-dontwarn com.huya.berry.module.capture.**
-dontwarn com.huya.berry.module.player.**

-dontwarn com.duowan.android.avp.**
-dontwarn com.duowan.mobile.netroid.**
-dontwarn com.duowan.mobile.yt.**

-dontwarn com.nostra13.**
-dontwarn com.lidroid.xutils.**
-dontwarn com.squareup.picasso.**
-dontwarn com.bumptech.glide.**
-dontwarn uk.co.senab.photoview.**
-dontwarn it.sephiroth.android.library.**
-dontwarn com.dd.morphingbutton.**
-dontwarn com.facebook.drawee.**
-dontwarn com.facebook.imagepipeline.**
-dontwarn com.facebook.common.**
-dontwarn org.android.**
-dontwarn com.loopj.android.**
-dontwarn com.etsy.android.grid.**
-dontwarn com.handmark.pulltorefresh.**
-dontwarn com.tjerkw.**
-dontwarn org.zeroturnaround.**
-dontwarn com.stericson.roottools.**
-dontwarn stericson.RootTools.**
-dontwarn com.stericson.RootShell.**
-dontwarn stericson.RootShell.**

# R8优化移除日志调用
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String);
    public static int w(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String);
}
-assumenosideeffects class java.lang.System {
    public static void println(java.lang.String);
    public static void println(java.lang.Object);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
