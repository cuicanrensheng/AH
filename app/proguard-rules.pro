# ===================== Bugly 崩溃堆栈必需配置（不可删减） =====================
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===================== ProGuard/R8 基础优化配置 =====================
-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
# 虎牙SDK大量反射，关闭易引发崩溃的优化项
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ===================== JNI Native 通用保护（不可删除） =====================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===================== Android 系统通用基础规则 =====================
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===================== 自有TV直播业务代码精准保留 =====================
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

# ===================== Media3 播放器混淆规则 =====================
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

# ===================== ZXing 二维码相关 =====================
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

# ===================== EventBus 弹幕事件回调核心规则 =====================
-keep class de.greenrobot.event.** { *; }
-keepclassmembers class * {
    @de.greenrobot.event.Subscribe <methods>;
}
-dontwarn de.greenrobot.event.**
-dontwarn de.greenrobot.event.EventBusException
-dontwarn de.greenrobot.event.Subscribe
-dontwarn de.greenrobot.event.ThreadMode

# ===================== 虎牙SDK 精简混淆防护（移除无脑全量keep，仅保留运行必需模块） =====================
# SDK底层安全、设备标识、JNI桥接类
-keep class com.huya.security.** { *; }
-keep class com.huya.hydeviceid.** { *; }
-keep class **.NativeBridge { *; }

# 直播核心模块（仅保留live播放相关，剔除音频/推流/RTC/采集无用模块）
-keep class com.huya.mtp.** { *; }
-keep interface com.huya.mtp.** { *; }
-keep class com.huya.berry.module.live.** { *; }
-keep interface com.huya.berry.module.live.** { *; }

# 账号、信令、统一账号库、SDK代理依赖
-keep class com.huya.component.login.** { *; }
-keep class com.huya.hysignal.** { *; }
-keep class com.huya.hysignalwrapper.** { *; }
-keep class com.huyaudb.** { *; }
-keep class com.huyaudbunify.** { *; }
-keep class com.hysdkproxy.** { *; }

# 多玩底层Ark框架、弹幕渲染核心（弹幕功能必须完整保留）
-keep class com.duowan.ark.** { *; }
-keep interface com.duowan.ark.** { *; }
-keep class com.duowan.kiwi.barrage.** { *; }
-keep interface com.duowan.kiwi.barrage.** { *; }

# WebRTC音频引擎（播放音频依赖）
-keep class hy.org.webrtc.voiceengine.WebRtcAudioTrack {*;}
-keep class hy.org.webrtc.voiceengine.WebRtcAudioRecord {*;}
-keep class hy.org.webrtc.voiceengine.AudioManagerAndroid {*;}

# 虎牙SDK全分支警告屏蔽
-dontwarn com.huya.berry.module.audio.**
-dontwarn com.huya.berry.module.push.**
-dontwarn com.huya.berry.module.rtc.**
-dontwarn com.huya.berry.module.capture.**
-dontwarn com.huya.berry.module.player.**
-dontwarn com.duowan.android.avp.**
-dontwarn com.duowan.mobile.netroid.**
-dontwarn com.duowan.mobile.yt.**
-dontwarn com.huya.force.**
-dontwarn com.huya.berry.**
-dontwarn com.duowan.**

# ===================== 网络库 OkHttp2 / OkHttp3 / Okio 统一规则 =====================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.squareup.okhttp.**
-dontwarn com.squareup.**
# OkHttp3
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
# OkHttp2 兼容适配
-keep class com.squareup.okhttp.** { *; }
-keep interface com.squareup.okhttp.** { *; }
-keep class com.zhy.http.okhttp.** { *; }

# ===================== Gson 序列化 =====================
-keep class com.google.gson.** { *;}
-keep class com.google.gson.JsonObject{*;}
-keep class com.google.gson.stream.** {*;}
-keep class com.google.gson.examples.android.model.** { *; }

# ===================== Glide 图片加载框架 =====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder
-dontwarn com.bumptech.glide.annotation.compiler.*
-keep public class com.bumptech.glide.annotation.compiler.* { public *; }

# ===================== 第三方通用库 警告屏蔽+最小保留 =====================
# Retrofit、RxJava
-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# 腾讯相关组件
-dontwarn com.tencent.**
-keep class com.tencent.mars.** {*;}
# Tinker热修复
-dontwarn com.tencent.tinker.**
-keep class com.tencent.tinker.** {*;}

# FastJson序列化
-keep public class com.alibaba.fastjson.** {*;}

# 注解、测试类依赖屏蔽
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.junit.**
-dontwarn android.test.**
-keep public class javax.annotation.** {*;}
-keep public class org.jetbrains.annotations.** {*;}

# 谷歌服务
-dontwarn com.google.android.gms.**
-keep public class com.google.android.gms.* { public *; }

# 其他SDK内置第三方警告屏蔽
-dontwarn com.tencent.smtt.**
-dontwarn com.yy.open.agent.**
-dontwarn com.huyaudbunify.**
-dontwarn com.huya.component.login.module.**
-dontwarn com.huya.live.utils.image.**
-dontwarn com.umeng.social.tool.**

# 底层IO工具兜底
-keep public class org.codehaus.* { *; }
-keep public class java.nio.* { *; }

# 废弃/冗余类警告屏蔽（R8编译多余提示）
-dontwarn com.duowan.ark.util.ThreadUtils
-dontwarn com.duowan.ark.util.pack.Uint16
-dontwarn com.duowan.ark.util.pack.Uint32
-dontwarn com.duowan.ark.util.pack.Uint64
-dontwarn com.duowan.ark.util.pack.Uint8
-dontwarn com.duowan.ark.util.pack.Unpack
-dontwarn com.squareup.okhttp.OkHttpClient
-dontwarn com.squareup.okhttp.OkUrlFactory
-dontwarn com.duowan.ark.api.ApiHolder
-dontwarn com.duowan.ark.api.DebugApi
-dontwarn com.duowan.ark.api.DebugApiDelegate
-dontwarn com.duowan.ark.api.LogApi
-dontwarn com.duowan.ark.api.LogApiDelegate
-dontwarn com.duowan.ark.asignal.notify.PropertySet
-dontwarn com.duowan.ark.util.BitmapUtils
-dontwarn com.duowan.ark.util.ConfigWithTimeout
-dontwarn com.duowan.ark.util.StringUtils
-dontwarn com.duowan.ark.util.json.JsonUtils
