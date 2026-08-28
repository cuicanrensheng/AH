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
# ===== 虎牙 SDK 收缩策略（v3.3 全量强 keep 版）=====
# 实测历史：
#   - debug 版（R8 不生效）解析正常：房间 2 线路 × 4 码率（蓝光6M/蓝光4M/超清/流畅）
#   - v3.1/v3.2（com.huya.** allowshrinking）release：码率列表只剩 1 项（仅蓝光4M）
#     —— 根因：SMObject/PlayerHelper 的码率构建代码（GetLivingInfoRsp →
#       SMObject.SingleStreamInfo.bitRateInfoList 填充）被 R8 收缩删除，
#       只留默认档兜底。App 侧 liveInfo.getBitRateList() 遍历该列表 → 只剩 1 档。
# 因此 com.huya.** 必须整体不收缩（保留完整码率构建逻辑）。
# 例外：SdkPlayerService / CommonService / CrashService 必须保持"构造器被删"
#       （SDK 用 getService(XXX) != null 抛 ClassCastException 做组件校验，
#       构造器保留 → createService 注册成功 → init 抛异常）。ProGuard 类名
#       过滤用 ! 排除这 3 个类，使它们只命中下方各自的 allowshrinking 规则。
# 注意：ProGuard 同一类匹配多条 keep 规则时，任一规则不允许收缩则该类不收缩；
#       被 ! 排除的类不匹配本规则，仅匹配各自的 allowshrinking 规则 → 可收缩。
-keep class !com.huya.berry.sdkplayer.SdkPlayerService,!com.huya.berry.gamesdk.module.CommonService,!com.huya.berry.gamesdk.crash.CrashService,com.huya.** { *; }
-keep,allowshrinking class com.huya.berry.sdkplayer.SdkPlayerService { *; }
-keep,allowshrinking class com.huya.berry.gamesdk.module.CommonService { *; }
-keep,allowshrinking class com.huya.berry.gamesdk.crash.CrashService { *; }
-keep class com.duowan.** { *; }
-keep class com.duowan.live.** { *; }
-keep class com.duowan.kiwi.** { *; }
# 虎牙兄弟包（com.huya.* 不会匹配 com.huyaudb.*，必须单独列出）
# UDB 登录/鉴权模块：libudbauthunify.so 经 JNI 动态加载，强 keep
# 注意 proguard 通配符：com.huyaudbunify 与 com.huyaudb 是两个独立前缀，必须分别列出
-keep class com.huyaudbunify.** { *; }
-keep class com.huyaudb.** { *; }
-keep class com.hycom.** { *; }
# 设备指纹 libhydeviceid.so 的 JNI 回调类（com.duowan.kiwi 已在上面 keep）
# com.huya.security.* 是 UDB 设备指纹 SDK（DeviceFingerprintSDK / HyDeviceChecker），
# HuyaSDKParser 直接引用其 host/kiwiHost/nimoHost/openApiHost 字段做上报拦截，必须强 keep。
-keep class com.huya.security.** { *; }
-keep class com.huya.nimo.** { *; }
-keep class com.huyaosdk.** { *; }
-keep class com.huyahi.** { *; }
-keep class com.huyall.** { *; }

# ===== SDK 反射实例化类构造器强 keep（v3.1 关键修复）=====
# 背景：上面的 -keep,allowshrinking 允许 R8 移除"仅被反射 newInstance() 使用"
#       的无参构造器。而 SDK 内部 Ark.startModule()（ArkModule 子类）和
#       ServiceHelper.createService()（服务实现类）全部用反射实例化：
#       - FeedBackModule 构造器被删 → InstantiationException → crashIfDebug
#         → onCrashIfDebug 无条件 throw RuntimeException → HuyaBerry.init 失败
#         → SDK 回调永不触发（12:01 release 实测 "❌ HuyaBerry init 失败"）
#       - 登录/解析必需的服务类构造器被删 → createService 静默失败 → 服务未
#         注册 → 解析/登录链路缺服务
# 修复：对这些类显式强 keep <init>（-keep 默认禁止收缩构造器）。
# ⚠️ 刻意不 keep（SDK 用 getService(XXX) != null 抛 ClassCastException 做组件
#    校验，必须保持"注册失败"）：SdkPlayerService / CommonService / CrashService
#    ——它们只被 cls.newInstance() 反射使用，allowshrinking 下 R8 会确定性删除
#   其构造器，createService 静默失败 → 服务未注册 → 校验通过（不抛）。
-keep class com.duowan.live.one.module.uploadLog.FeedBackModule { <init>(); }
-keep class com.huya.berry.module.HysignalPushModule { <init>(); }
-keep class com.huya.berry.LoginSdkService { <init>(); }
-keep class com.huya.component.login.module.LoginModule { <init>(); }
-keep class com.huya.component.user.module.UserService { <init>(); }
-keep class com.huya.berry.sdkcamera.SdkCameraService { <init>(); }
-keep class com.huya.berry.sdklivelist.SdkLiveListService { <init>(); }
-keep class com.huya.berry.modifytitle.ModifyTitleService { <init>(); }
-keep class com.huya.berry.endlive.EndLiveService { <init>(); }
-keep class com.huya.berry.module.live.SdkLiveService { <init>(); }
-keep class com.huya.berry.sdklive.LiveService { <init>(); }
-keep class com.huya.berry.forcelive.ForceLiveService { <init>(); }

# ===== JCE/Wup 协议结构体强 keep（v3.2 关键修复）=====
# 背景：Wup 协议响应解析 getObjectFromUniPacket →
#       Reflect.on(cls).createAuto(false) 反射实例化响应类。R8 收缩把
#       JCE 结构体（extends JceStruct）删成 abstract 空类（构造器/字段/
#       readFrom/writeTo 全删）→ createAuto 抛 InstantiationException →
#       ParseException("Cannot initialize proxy") → getLivingInfo 失败
#       → App 显示"获取参数失败，请重试"→ 解析失败（12:17 release 实测）。
# 修复：com.duowan.** 已在上方改为强 keep（不收缩），覆盖协议 DTO 核心包
#       com.duowan.HUYA.* 及 jce/taf/networkmars 序列化框架；此处对
#       com.huya 侧含 JceStruct 的协议包同样强 keep，防止内部 Wup/WebSocket
#       信令解析反射实例化失败。
-keep class com.huya.hysignal.jce.** { *; }
-keep class com.huya.hyhttpdns.jce.** { *; }
-keep class com.huya.statistics.jce.** { *; }
-keep class com.huya.mtp.hyns.miniprogram.jce.** { *; }
-keep class com.huya.mtp.hyns.api.** { *; }

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
# 注：仅对 RxJava 开启 allowshrinking（第三方库，SDK 静态引用，可安全收缩未用 operator）
# 虎牙 SDK 自身包保持强 keep（实测 allowshrinking 会删掉 SDK 反射依赖导致解析超时）
-dontwarn rx.**
-dontwarn io.reactivex.**
-keep,allowshrinking class rx.** { *; }
-keep,allowshrinking class io.reactivex.** { *; }
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

# —— TVLive 接入 Bugly / 虎牙SDK 异常上报的关键业务类（防止 R8 优化导致静态入口被移除/改名）——
# BuglyLogSender：所有 reportXxxSafely / reportHuyaXxx 对外入口（含被其它类直接 Java 调用 & 内部反射 postTrackEvent）
-keep class com.tv.live.util.BuglyLogSender { *; }
# ExceptionReporter：全局 catch(Throwable) 时统一调用的静态入口（report / reportHuyaBusinessFailure）
-keep class com.tv.live.util.ExceptionReporter { *; }
# HuyaSDKLogger：虎牙 SDK 事件/回调/错误 的第一现场分发器（含被虎牙SDK的 BerryEvent/CustomUICallback 反射调用路径）
-keep class com.tv.live.util.HuyaSDKLogger { *; }
# NoOpReportApi：替换 SDK 统计通道为空实现（BaseApi.setReportApi 直接 new 调用）
-keep class com.tv.live.util.NoOpReportApi { *; }
# NoOpCrashService：替换 SDK Bugly 崩溃上报服务为空实现
# ServiceHelper.createService() 内部用 cls2.newInstance() 反射实例化，必须保留无参构造器
-keep class com.tv.live.util.NoOpCrashService { <init>(); }
# NoOpHuyaStatisApi：替换 HuyaStatisAgent.mApi 拦截 hiido 统计(PV/init 残留)
# 通过 new 实例化并反射写私有字段 mApi，必须保留类、无参构造器及所有重写方法
-keep class com.tv.live.util.NoOpHuyaStatisApi { <init>(...); *; }

# ============== 项目业务类 ==============
# MainActivity 入口（防止被混淆找不到）
-keep class com.tv.live.MainActivity { *; }

# ============== 反射/单例 ==============
-keepclassmembers class * {
    @com.google.inject.Inject <init>(...);
    @javax.inject.Inject <init>(...);
    @dagger.Inject <init>(...);
}

# ============== 移除所有日志（防调试，只能通过内置服务器查看） ==============
# 全部 android.util.Log 调用（v/d/i/w/e/println）被 R8 裁剪 → logcat 完全不可见，
# 反编译/ADB 都拿不到日志，软件对外是"黑盒子"。
# 但日志数据仍通过 LogBridge 写入 LogCollector 内存缓冲，
# 可经 App 内置 LogServer（端口 9527）/api/logs 拉取 —— 内部服务器仍可全量查看。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}

# 日志条目字段名保留（内置服务器 /api/logs 的 JSON 序列化依赖字段名，混淆后无法解析）
-keepclassmembers class com.tv.live.util.LogCollector$LogEntry {
    <fields>;
}

# 保留关键业务类的完整实现（防止 R8 移除 Java fallback 或网络重试逻辑）
# HuyaParser：旧版 APK 即含此解析兜底类，虽无静态引用，但为与旧版文件结构完全一致
# 并规避虎牙 SDK 生态可能的运行时类探测，显式 keep 防止 R8 移除
-keep class com.tv.live.util.HuyaParser { *; }
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
