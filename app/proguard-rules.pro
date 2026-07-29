-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keepclasseswithmembernames class * {
    native <methods>;
}

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
-keep class com.tv.live.bean.** { *; }
-keep class com.tv.live.SourceManager$SourceItem { *; }

# ==========================================================
# Media3 (ExoPlayer) - 保留 XML 反射必需的 View 类
# ==========================================================
-keep class androidx.media3.ui.PlayerView {
    public <init>(...);
}
-keep class androidx.media3.ui.AspectRatioFrameLayout {
    public <init>(...);
}

-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.DefaultLoadControl { *; }
-keep class androidx.media3.exoplayer.DefaultTrackSelector { *; }
-keep class androidx.media3.exoplayer.source.DefaultMediaSourceFactory { *; }
-keep class androidx.media3.exoplayer.source.ProgressiveMediaSource { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.common.Format { *; }
-keep class androidx.media3.common.MimeTypes { *; }
-keep class androidx.media3.common.C { *; }
-keep class androidx.media3.common.C$* { *; }
-keep class androidx.media3.common.Player$* { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }
-keep interface androidx.media3.common.Player { *; }

-dontwarn androidx.media3.**

# ==========================================================
# 第三方库混淆规则
# ==========================================================

# OkHttp
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.OkHttpClient$Builder { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Request$Builder { *; }
-keep class okhttp3.Response { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-dontwarn com.google.zxing.**

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**
