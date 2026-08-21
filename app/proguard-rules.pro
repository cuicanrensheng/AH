# ============== 基础优化 ==============
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses

# AndroidX VectorDrawable / AppCompat：R8 缩减 + 资源混淆下必须保留，
# 否则 startup 加载 <vector> 抛 IllegalStateException（卡启动界面/图标丢失）
-keep class androidx.vectordrawable.** { *; }
-keep class androidx.appcompat.widget.AppCompatViewInflater { *; }
-keep class androidx.appcompat.app.AppCompatDelegate { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }
-dontwarn androidx.vectordrawable.**
-dontwarn androidx.appcompat.**

# 正式版移除 SourceFile 和 LineNumberTable，增加反编译难度
# 所有堆栈中的文件名都会显示为 "SourceFile"，行号也会被移除
-renamesourcefileattribute SourceFile

# 保留泛型信息（JSON 解析、反射用到）
# 注意：Signature, InnerClasses, EnclosingMethod 是 Retrofit/Gson 必需的
# 不能移除，否则 JSON 解析和 Retrofit 反射会失败
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# ============== Android 组件保留 ==============
# Activity / Service / Receiver / Provider 不能被混淆
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 自定义 View 构造方法（XML inflate 用）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
# onClick 方法（XML android:onClick 用）
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# ============== 枚举保留 ==============
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============== Parcelable ==============
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ============== Native 方法 ==============
# native 方法必须保留成员名 + 代码（includecode）
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# ============== 虎牙直播 SDK 全部不混淆 ==============
# 虎牙 SDK 包含 native bridge 和 JNI 反射调用，混淆后崩溃
# 关键：必须覆盖所有"虎牙"相关的前缀包（含兄弟包），否则 R8 会混淆 native 方法名，
#       SO 内部通过 JNI_OnLoad + RegisterNatives 找不到 Java 端方法 → 运行时崩溃：
#         NoSuchMethodError: no non-static method "Lcom/huyaudb/HuyaAuthCore;.sendNet(JI[B)V"
-keep class com.huya.** { *; }
-keep class com.duowan.** { *; }
-keep class com.duowan.live.** { *; }
-keep class com.duowan.kiwi.** { *; }
# 虎牙兄弟包（com.huya.* 不会匹配 com.huyaudb.*，必须单独列出）
-keep class com.huyaudb.** { *; }
-keep class com.huyaosdk.** { *; }
-keep class com.huyahi.** { *; }
-keep class com.huyall.** { *; }

# ============== WebView + JS 接口 ==============
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# ============== OkHttp / Okio ==============
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn com.squareup.okhttp.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============== 虎牙直播 SDK（缺失类） ==============
-dontwarn com.duowan.ark.util.ThreadUtils
-dontwarn com.duowan.ark.util.pack.**
-dontwarn com.duowan.ark.**
-dontwarn com.duowan.auk.share.**
-dontwarn com.duowan.**
-dontwarn com.huya.mtp.hyns.volley.**
-dontwarn de.greenrobot.event.**
-dontwarn com.duowan.live.**
-dontwarn com.duowan.kiwi.**
-dontwarn com.huya.force.**
-dontwarn com.huya.berry.**
-dontwarn com.huya.component.**
-dontwarn com.huya.mtp.**
-dontwarn com.huya.security.**
-dontwarn com.huya.encrypt.**
-dontwarn com.huya.stats.**
-dontwarn com.huya.**
-dontwarn com.duowan.**
-dontwarn com.huyaudb.**
-dontwarn com.huyaosdk.**
-dontwarn com.huyahi.**
-dontwarn com.huyall.**
-dontwarn com.umeng.socialize.**
-dontwarn retrofit2.**
-dontwarn org.greenrobot.eventbus.**

# ============== Retrofit + RxJava（关键：保留泛型签名）==============
# Retrofit 通过方法返回类型的 GenericSignature 反射读取 Observable<T> 的 T
# 必须保留 Signature/Exceptions/Annotation 属性
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, AnnotationDefault

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# 保留所有带 retrofit2.http.* 注解的方法（泛型签名完整）
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# 保留方法上的泛型返回值类型
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# 业务接口类（虎牙 SDK 内部用了大量 Retrofit 接口）
-keep class com.huya.berry.module.live.** { *; }
-keep class com.huya.berry.module.** { *; }
-keep class com.huya.mtp.hyns.retrofit.** { *; }
-keep class com.huya.mtp.hyns.** { *; }
-keep class com.huya.mtp.** { *; }

# RxJava1/2/3 通用
-dontwarn rx.**
-dontwarn io.reactivex.**
-keep class rx.** { *; }
-keep class io.reactivex.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 实体类（防混淆字段）
-keep class com.tv.live.model.** { *; }
-keep class com.tv.live.config.AppConfig { *; }
-keep class com.tv.live.UrlConfig { *; }
-keep class com.tv.live.SecurityCheck { *; }

# NDK 安全层 JNI 桥接（不能被混淆，否则 native 方法找不到）
# 关键：必须保留所有 Java fallback 代码（KEY_PART_A/B、buildAesKeyJava、javaAesDecrypt），
# 否则 R8 会移除 Native 加载失败时的 Java 解密路径，导致模拟器/非 arm64 架构解密失败
-keep class com.tv.live.security.SecurityCore { *; }
-keep class com.tv.live.security.IntegrityCheck { *; }
-keep class com.tv.live.security.StringProtector { *; }
-keep class com.tv.live.security.DexProtector { *; }
-keep class com.tv.live.security.SecurityGuard { *; }
-keep class com.tv.live.security.AntiDebug { *; }
-keep class com.tv.live.security.TamperReporter { *; }
-keep class com.tv.live.security.StringObfuscator { *; }

# ============== Bugly SDK（日志上报/崩溃监控） ==============
# Bugly SDK 使用反射和 native 方法，必须完整保留
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
# Bugly native so 库保留
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============== 项目业务类 ==============
# MainActivity 入口（防止被混淆找不到）
-keep class com.tv.live.MainActivity { *; }

# ============== 反射/单例 ==============
-keepclassmembers class * {
    @com.google.inject.Inject <init>(...);
    @javax.inject.Inject <init>(...);
    @dagger.Inject <init>(...);
}

# ============== 移除日志（防调试） ==============
# 移除开发调试日志（v 和 d），但保留 i/w/e 用于关键错误追踪
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# 保留关键业务类的完整实现（防止 R8 移除 Java fallback 或网络重试逻辑）
-keep class com.tv.live.loader.LiveSourceLoader { *; }
-keep class com.tv.live.loader.** { *; }
-keep class com.tv.live.PlaylistParser { *; }
-keep class com.tv.live.Channel { *; }
-keep class com.tv.live.util.NetUtil { *; }
-keep class com.tv.live.util.CacheManager { *; }

# ============== 增强反编译难度 ==============
# 移除 SourceFile 属性
-renamesourcefileattribute SourceFile

# 强制所有类使用短名
-flattenpackagehierarchy ''

# 合并接口
-mergeinterfacesaggressively

# 移除反射相关信息（除了必要的）
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# ============== 优化 ==============
-allowaccessmodification
-repackageclasses 'o'

# ============== R8 自动生成补全 ==============
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
