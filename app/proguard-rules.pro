# Bugly 崩溃堆栈必需配置
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===================== 虎牙SDK规则 =====================
# 目前为全量keep，后续稳定后可以逐步缩小范围
-keep class com.huya.mtp.** { *; }
-keep interface com.huya.mtp.** { *; }
-keep class com.huya.berry.module.live.** { *; }
-keep interface com.huya.berry.module.live.** { *; }
-keep class com.huya.berry.module.** { *; }
-keep interface com.huya.berry.module.** { *; }

# 三方框架警告屏蔽
-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# ===================== ProGuard 优化配置 =====================
-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
# 注意：若出现虎牙SDK运行异常，直接注释下面这一行
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ===================== JNI Native 核心保护【不可删】 =====================
-keepclasseswithmembernames class * {
    native <methods>;
}

# 虎牙安全、设备ID、JNI桥接类
-keep class com.huya.security.** { *; }
-keep class com.huya.hydeviceid.** { *; }
-keep class **.NativeBridge { *; }

# ===================== Android 系统通用规则 =====================
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===================== 自有业务代码 =====================
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

# ===================== Media3播放器 =====================
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

# ===================== 二维码 ZXing =====================
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

# ===================== 网络库 =====================
-dontwarn okhttp3.**
-dontwarn okio.**

# ===================== 虎牙细分警告屏蔽（移除全局-dontwarn com.huya.**） =====================
-dontwarn com.huya.berry.module.audio.**
-dontwarn com.huya.berry.module.push.**
-dontwarn com.huya.berry.module.rtc.**
-dontwarn com.huya.berry.module.capture.**
-dontwarn com.huya.berry.module.player.**
-dontwarn com.duowan.android.avp.**
-dontwarn com.duowan.mobile.netroid.**
-dontwarn com.duowan.mobile.yt.**

# ===================== R8自动生成 解决minifyReleaseWithR8合并失败【新增部分】 =====================
-dontwarn com.duowan.ark.util.ThreadUtils
-dontwarn com.duowan.ark.util.pack.Uint16
-dontwarn com.duowan.ark.util.pack.Uint32
-dontwarn com.duowan.ark.util.pack.Uint64
-dontwarn com.duowan.ark.util.pack.Uint8
-dontwarn com.duowan.ark.util.pack.Unpack
-dontwarn com.squareup.okhttp.OkHttpClient
-dontwarn com.squareup.okhttp.OkUrlFactory
-dontwarn de.greenrobot.event.EventBus

# 弹幕相关类保留，防止运行时 ClassNotFoundException
-keep class com.duowan.ark.** { *; }
-keep class com.duowan.kiwi.barrage.** { *; }

# 通用第三方
-dontwarn com.tencent.**
-dontwarn com.alibaba.**
-dontwarn org.apache.**
