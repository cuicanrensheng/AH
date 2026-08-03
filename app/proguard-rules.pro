-dontoptimize
-dontobfuscate
-dontshrink
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Huya SDK full protection - SDK uses reflection+Retrofit, must keep all classes
-keep class com.huya.** { *; }
-keep interface com.huya.** { *; }
-keepclasseswithmembers class com.huya.** { *; }

-keep class com.huya.mtp.** { *; }
-keep interface com.huya.mtp.** { *; }
-keep class com.huya.berry.module.live.** { *; }
-keep interface com.huya.berry.module.live.** { *; }
-keep class com.huya.berry.module.** { *; }
-keep interface com.huya.berry.module.** { *; }

-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# Retrofit + RxJava full protection
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

-optimizationpasses 0
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

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