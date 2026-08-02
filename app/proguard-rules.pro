# ===================== Bugly 崩溃堆栈必需配置 =====================
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===================== R8 优化基础配置 =====================
-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ===================== JNI Native 保护 =====================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===================== Android 系统通用规则 =====================
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===================== 自有TV业务代码精准保留 =====================
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

# ===================== ZXing 二维码 =====================
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

# ===================== EventBus 弹幕事件 =====================
-keep class de.greenrobot.event.** { *; }
-keepclassmembers class * {
    @de.greenrobot.event.Subscribe <methods>;
}
-dontwarn de.greenrobot.event.**
-dontwarn de.greenrobot.event.EventBusException
-dontwarn de.greenrobot.event.Subscribe
-dontwarn de.greenrobot.event.ThreadMode

# ===================== 虎牙SDK 精简keep（仅保留鉴权、直播、弹幕核心，已剔除推流/采集/滤镜） =====================
# 安全、设备ID、JNI桥
-keep class com.huya.security.** { *; }
-keep class com.huya.hydeviceid.** { *; }
-keep class **.NativeBridge { *; }

# 直播基础核心
-keep class com.huya.mtp.** { *; }
-keep interface com.huya.mtp.** { *; }
-keep class com.huya.berry.module.live.** { *; }
-keep interface com.huya.berry.module.live.** { *; }

# 账号、信令、统一登录库
-keep class com.huya.component.login.** { *; }
-keep class com.huya.hysignal.** { *; }
-keep class com.huya.hysignalwrapper.** { *; }
-keep class com.huyaudb.** { *; }
-keep class com.huyaudbunify.** { *; }
-keep class com.hysdkproxy.** { *; }

# 底层Ark框架、弹幕渲染（必须完整保留）
-keep class com.duowan.ark.** { *; }
-keep interface com.duowan.ark.** { *; }
-keep class com.duowan.kiwi.barrage.** { *; }
-keep interface com.duowan.kiwi.barrage.** { *; }

# WebRTC音频引擎（仅播放音频依赖）
-keep class hy.org.webrtc.voiceengine.WebRtcAudioTrack {*;}
-keep class hy.org.webrtc.voiceengine.WebRtcAudioRecord {*;}
-keep class hy.org.webrtc.voiceengine.AudioManagerAndroid {*;}

# 虎牙无用模块警告屏蔽
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

# ===================== OkHttp3 网络库 =====================
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ===================== Gson 序列化 =====================
-keep class com.google.gson.** { *;}
-keep class com.google.gson.JsonObject{*;}
-keep class com.google.gson.stream.** {*;}
-keep class com.google.gson.examples.android.model.** { *; }

# ===================== 第三方通用警告屏蔽 =====================
-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# 腾讯Mars、Tinker
-dontwarn com.tencent.**
-keep class com.tencent.mars.** {*;}
-dontwarn com.tencent.tinker.**
-keep class com.tencent.tinker.** {*;}

# FastJson
-keep public class com.alibaba.fastjson.** {*;}

# 注解、测试依赖屏蔽
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.junit.**
-dontwarn android.test.**
-keep public class javax.annotation.** {*;}
-keep public class org.jetbrains.annotations.** {*;}

# 谷歌服务
-dontwarn com.google.android.gms.**
-keep public class com.google.android.gms.* { public *; }

# 其他SDK警告
-dontwarn com.tencent.smtt.**
-dontwarn com.yy.open.agent.**
-dontwarn com.huyaudbunify.**
-dontwarn com.huya.component.login.module.**
-dontwarn com.huya.live.utils.image.**
-dontwarn com.umeng.social.tool.**

# IO兜底类
-keep public class org.codehaus.* { *; }
-keep public class java.nio.* { *; }

# Ark底层工具类警告屏蔽
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
