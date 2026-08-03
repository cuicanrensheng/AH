-dontobfuscate
-dontshrink
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 🔴 虎牙 SDK 核心模块保留 - 仅保留直播核心功能，移除无用模块
# 基础网络与数据层
-keep class com.huya.mtp.hyns.** { *; }
-keep interface com.huya.mtp.hyns.** { *; }
-keep class com.huya.mtp.utils.** { *; }
-keep interface com.huya.mtp.utils.** { *; }
-keep class com.huya.mtp.downloader.** { *; }
-keep class com.huya.mtp.multithreaddownload.** { *; }
-keep class com.huya.mtp.nsdt.** { *; }
-keep class com.huya.mtp.dimens.** { *; }
-keep class com.huya.mtp.http.** { *; }
-keep class com.huya.mtp.anotation.** { *; }
-keep class com.huya.mtp.ciku.apm.** { *; }

# 直播核心逻辑
-keep class com.huya.berry.client.** { *; }
-keep interface com.huya.berry.client.** { *; }
-keep class com.huya.berry.sdklive.** { *; }
-keep interface com.huya.berry.sdklive.** { *; }
-keep class com.huya.berry.sdkplayer.** { *; }
-keep interface com.huya.berry.sdkplayer.** { *; }
-keep class com.huya.berry.sdklivelist.** { *; }
-keep interface com.huya.berry.sdklivelist.** { *; }
-keep class com.huya.berry.module.** { *; }
-keep interface com.huya.berry.module.** { *; }

# 网络信号与DNS
-keep class com.huya.hysignal.** { *; }
-keep interface com.huya.hysignal.** { *; }
-keep class com.huya.hyhttpdns.** { *; }

# 通用直播工具
-keep class com.huya.live.common.** { *; }
-keep class com.huya.live.utils.** { *; }

# 允许 R8 移除以下非核心模块（用于减小体积）：
# -keep class com.huya.berry.gamesdk.** { *; }
# -keep class com.huya.berry.webview.** { *; }
# -keep class com.huya.berry.forcelive.** { *; }
# -keep class com.huya.berry.endlive.** { *; }
# -keep class com.huya.berry.sdkcamera.** { *; }
# -keep class com.huya.berry.login.** { *; }
# -keep class com.huya.component.login.** { *; }
# -keep class com.huya.berry.modifynickname.** { *; }
# -keep class com.huya.berry.modifytitle.** { *; }
# -keep class com.huya.mtp.hycloudgame.** { *; }

-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# 🔴 Retrofit + RxJava 全面保护 - 虎牙 SDK 依赖这些做网络请求
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
