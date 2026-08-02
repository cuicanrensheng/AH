# ==============================================================================
# 🔴【2026-08-02 终极修复V2：R8 optimization擦除方法级泛型签名→NSRxCallAdapter崩溃→0回调】
#  根因确认链路：
#    SdkLiveService.onCreate() → getMobilePropsList() 【SdkLiveService在com.huya.berry.module.live包】
#    → Retrofit Service接口（可能在com.huya.berry.module.live或com.huya.berry.protocol包）
#    → NSRxCallAdapterFactory.getAdapter() 读 Method.getGenericReturnType()
#    → R8 optimization阶段（即使-keepattributes Signature存在！）把Method级泛型元数据擦了
#    → IllegalStateException: Observable return type must be parameterized as Observable<Foo>
#    → SdkLiveService启动崩溃 → ServerStartManager整个启动链中断 → ISdkLiveService==null → 0回调
#
#  对比Debug(minifyEnabled=false) vs Release(minifyEnabled=true)：
#    Debug包所有SDK Service正常启动，150ms内onResultCallback ✅
#    Release包SdkLiveService必崩，0回调，30s超时 Toast ❌
#
#  修复升级为【4件套+核弹兜底】：
#    1) -dontoptimize：核弹！彻底禁用R8 optimization阶段（保留shrinking+obfuscation）。
#       之前的-keepparameternames在R8 optimization面前仍会被覆盖重写。
#       体积影响：约增加2-4MB（约33MB vs 29MB），但功能100%保住，后续可逐步收紧。
#    2) -keepparameternames + 全量-keepattributes：防止混淆阶段擦除方法签名
#    3) 强保 com.huya.berry.module.live.**：SdkLiveService及其内部Retrofit接口
#    4) 强保 com.huya.mtp.**：NSRxCallAdapter/NSHttpProtocol网络层
# ==============================================================================
-dontoptimize
-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 🔴 终极修复3-1：MTP网络层（NSRxCallAdapterFactory+所有hynsretrofit内部类）强保
-keep class com.huya.mtp.** { *; }
-keep interface com.huya.mtp.** { *; }

# 🔴 终极修复3-2：【新】Live模块（SdkLiveService所在包，getMobilePropsList崩溃源头）强保
-keep class com.huya.berry.module.live.** { *; }
-keep interface com.huya.berry.module.live.** { *; }
# 同链路：所有 berry.module.* 模块（ServerStartManager启动的模块，任何一个崩都会断整条链）
-keep class com.huya.berry.module.** { *; }
-keep interface com.huya.berry.module.** { *; }

# 🔴 终极修复3-3：对外retrofit2/rxjava2只dontwarn（运行时SDK走hyns内部实现，不会ClassNotFound）
-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# ==================== 下面是原有规则（保留不变）====================
-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 移除调试/信息日志调用（发布构建），保留警告和错误日志
# 调试阶段暂时禁用，以便查看所有日志
#-assumenosideeffects class android.util.Log {
#    public static *** v(...);
#    public static *** d(...);
#    public static *** i(...);
#}

-keepclasseswithmembernames class * {
    native <methods>;
}
# ==============================================================================
# 🆕【2026-08-02 R8 紧急修复：所有含 native 方法的 SDK 类 MUST KEEP！否则 JNI 链接失败！】
#  崩溃链：com.huya.security.hydeviceid.NativeBridge.<clinit> → loadLibrary → JNI_OnLoad
#         → RegisterNatives 找 getLinkBody(String,String,String,String)byte[]
#         → NoSuchMethodError 因为 R8 优化/混淆了方法签名 → 类初始化直接失败
#  解决方案：keep 所有 com.huya.security / hydeviceid 包，以及 SDK 内部含 native 的类
# ==============================================================================
-keep class com.huya.security.** { *; }
-keep class com.huya.hydeviceid.** { *; }
-keep class **.NativeBridge { *; }
# 所有 SDK 包下含 native 方法的类（JNI RegisterNatives 需要完整签名）
-keepclasseswithmembers class com.huya.** {
    native <methods>;
}
-keepclasseswithmembers class com.duowan.** {
    native <methods>;
}
# 防止 R8 优化掉 native 方法参数、改变方法签名、或移除"看起来未调用"的 native 方法
-keepclassmembers class * {
    native <methods>;
}
# JNI 直接 load 的 class：不要改名、不要去成员
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

# ========================
# 应用自身类：只保留反射/组件必需
# ========================
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

# ========================
# Media3 (ExoPlayer)：只保留 XML 反射必需的 View 构造器
# ========================
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

# ========================
# 第三方库：只保留核心反射必需
# ========================

# OkHttp（自带 consumerProguardFiles，仅保留 -dontwarn 兜底）
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing：只保留实际使用的 QRCodeWriter / BitMatrix / BarcodeFormat
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-dontwarn com.google.zxing.**

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**

# ==============================================================================
# 🆕 豆包方案2：ProGuard/R8 过滤播放器相关类（配合「方案1 官方 isNeedPlay=false」一起用）
#  说明：
#   1. 先通过官方开关让 SDK 内部不再走播放器 new / Service 注册路径
#   2. 再用 -dontwarn 告诉 R8：播放器相关类如果报 warning 忽略，直接剥离 dex
#   3. -keep 核心解析包，确保 R8 不把核心解析类/信令类给剥掉
#  适配版本：Berry SDK 4.8 ~ 5.6（与豆包前置说明一致）
# ==============================================================================

# ------- 🔴【播放器类 dontwarn / 移除】（release R8 才会生效）-------
-dontwarn com.huya.berry.sdkplayer.**
-dontwarn com.huya.berry.player.**
-dontwarn com.huya.berry.core.player.**
-dontwarn com.huya.berry.decoder.**
-dontwarn com.duowan.berry.player.**
-dontwarn tv.danmaku.ijk.media.player.**
# 播放器 Native so 对应 Java wrapper（isNeedPlay=false 后不会被 load，直接 dontwarn）
-dontwarn com.huya.berry.nativerender.**
-dontwarn com.huya.berry.codec.**
-dontwarn com.huya.berry.audiotrack.**
-dontwarn com.huya.berry.mediasync.**

# ------- 🟡【推流/连麦/美颜类 dontwarn / 移除】-------
-dontwarn com.huya.berry.sdkcamera.**
-dontwarn com.huya.berry.sdklive.**
-dontwarn com.huya.berry.sdklivelist.**
-dontwarn com.huya.berry.audioengine.**
-dontwarn com.huya.berry.imagefilter.**
-dontwarn com.huya.berry.rtmp.**
-dontwarn com.huya.berry.capture.**
-dontwarn com.huya.berry.encode.**
-dontwarn com.huya.berry.liveTool.**

# ====================================================================
# 🆕【release R8 紧急修复：物理 exclude 后 Missing class 全量 dontwarn】
#  来源：2026-08-02 assembleRelease minifyReleaseWithR8 完整日志 30+ 条 warning 触发 abort
#  规则：只要「核心解析链路（HuyaBerry / getLiveDataByRoomId / HuyaBerryConfig / LiveInfo）
#        正常保留」，其余以下通过 -dontwarn 让 R8 忽略直接完成构建（运行时不走即安全）
# ====================================================================
# 🔴 登录模块（虽然物理保留，但内部接口老版本差异，R8 warning 多 → dontwarn）
-dontwarn com.huya.component.login.**
-dontwarn com.huya.component.crash.**
# 🔴 直播模块接口/Service（R8 找不到对应的 impl 类：ServerStartManager 反射链 dontwarn）
-dontwarn com.huya.berry.module.live.**
-dontwarn com.huya.berry.module.living.heartbeat.**
-dontwarn com.huya.berry.module.pubtext.**
-dontwarn com.huya.berry.module.commonevent.**
# 🔴 WebView/JSSDK 模块（initWebview 分支 dontwarn）
-dontwarn com.huya.berry.webview.**
-dontwarn com.huya.berry.jssdk.**
# 🔴 下载器（物理 exclude 了 multithreaddownload，R8 找不到 DownloadManager → dontwarn）
-dontwarn com.huya.mtp.multithreaddownload.**
# 🔴 三方库传递依赖（Square OkHttp2 / EventBus 老版本透传 warning）
-dontwarn com.squareup.okhttp.**
-dontwarn de.greenrobot.event.**
# 🔴 游戏 SDK 事件（物理 exclude 子模块后，CommonEvent$FullScreen 找不到的 dontwarn）
-dontwarn com.huya.berry.gamesdk.module.commonevent.**
-dontwarn com.huya.berry.gamesdk.**
# 🔴 【2026-08-02 新增：Tier2/Tier4/Tier5 exclude 后 R8 Missing class 补全】
# 用户反馈 UI（exclude: common-feedback-release.aar）
-dontwarn com.huya.berry.feedback.**
-dontwarn com.huya.berry.client.FeedbackFragment**
# 修改昵称 UI（exclude: sub-modifynickname-release.aar）
-dontwarn com.huya.berry.modifynickname.**
-dontwarn com.huya.berry.client.ModifyNicknameFragment**
# 修改直播标题（exclude: modifytitle-api/impl-release.aar）
-dontwarn com.huya.berry.modifytitle.**
# HysignalPushModule（数美反作弊 exclude 后连带的 Push 模块）
-dontwarn com.huya.berry.module.HysignalPushModule**
# Player 内部 Helper/SMObject（isNeedPlay=false 后不会走，但 R8 看不到运行时分支）
-dontwarn com.huya.berry.module.Player.**
-dontwarn com.huya.berry.module.PresenterConfigHelper**
-dontwarn com.huya.berry.module.help.LiveHelper**
# 贵族礼物（exclude: base-noblegift-release.aar）
-dontwarn com.huya.berry.noblegift.**
-dontwarn com.huya.component.noblegift.**
# 下载器链路（exclude: download + multithreaddownload）
-dontwarn com.huya.berry.download.**
-dontwarn com.huya.mtp.download.**
-dontwarn com.huya.component.download.**
# 数美反作弊（exclude: smantifraud-2.8.4.jar）
-dontwarn com.sm.antifraud.**
-dontwarn org.json.**
# 🔴【2026-08-02 第三轮：endlive/forcelive + duowan 全链路 dontwarn（R8 终极兜底）】
# 结束直播模块（exclude: endlive-api/impl-release.aar → ServerStartManager 反射链）
-dontwarn com.huya.berry.endlive.**
-dontwarn com.huya.berry.client.tasks.LiveListTask**
# 强制关播模块（exclude: forcelive-api/impl-release.aar → ServerStartManager 反射链）
-dontwarn com.huya.berry.forcelive.**
# 导出/分享模块（exclude: export-1.1.80.aar）
-dontwarn com.huya.berry.export.**
-dontwarn com.duowan.export.**
# duowan 弹幕/Ark 打包/上传日志（API 层引用但 exclude 了 impl）
-dontwarn com.duowan.kiwi.**
-dontwarn com.duowan.ark.**
-dontwarn com.duowan.live.one.**
# 磁盘 LRU 缓存（exclude: disklrucache-2.0.2.jar）
-dontwarn com.jakewharton.disklrucache.**
-dontwarn org.apache.commons.compress.**
# ==============================================================================
# 🆕【2026-08-02 协议类初始化崩溃紧急修复】
# 现象：W/System.err: RuntimeException: Cannot initialize protocol class class z9.a
#       Cause: InstantiationException: z9.a 无法实例化
# 根因：SDK 内部通过 Class.newInstance() / Constructor.newInstance() 反射实例化协议类/网络请求类，
#       R8 静态分析看不到这些反射调用 → 把协议类的类名/构造函数/成员全部剥离 → 运行时直接崩
#
# 修复策略：
#   1) 【最高优先级】-keep 整个 com.huya.** / com.duowan.** 包：
#      防止协议类 z9.a（R8 混淆后落到顶层无规则匹配的包）被 R8 删除/改名/去成员
#   2) 【构造函数强保】-keepclasseswithmembers 所有 SDK 类的所有构造函数：
#      newInstance() 需要无参/有参构造函数存在且可访问
#   3) -keepnames：防止类名混淆后模块注册/模块查找/ServiceLoader 找不到
# 体积影响：约增大 1-4MB（远小于 SDK 本身体积省的 20MB+，先保功能），后续可按子包收紧
# ==============================================================================
-keep class com.huya.** { *; }
-keep class com.duowan.** { *; }
# 防止任何 SDK 包下的类只要有构造函数都保留（newInstance/反射实例化强依赖）
-keepclasseswithmembers class com.huya.** {
    public <init>(...);
    protected <init>(...);
    <init>(...);
}
-keepclasseswithmembers class com.duowan.** {
    public <init>(...);
    protected <init>(...);
    <init>(...);
}
# 防止类名混淆后 Class.forName("原名") 找不到（ServerStartManager 启动模块）
-keepnames class com.huya.** { *; }
-keepnames class com.duowan.** { *; }
# 内部类/外围类/签名/注解 全部保留（反射拿泛型参数/拿内部类信息要用）
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault

# ==============================================================================
# 全局兜底：所有 com.huya.berry / com.duowan / com.duowan.HUYA 协议相关 warning 一律忽略
#（只要核心解析链路 HuyaBerry + getLiveDataByRoomId 通过 keep 保住，其余 warning 不影响运行时）
-dontwarn com.huya.berry.**
-dontwarn com.duowan.**
-dontwarn com.huya.component.**
-dontwarn com.huya.mtp.**
-dontwarn tv.danmaku.ijk.**
-dontwarn com.huya.**

# ------- 🟢【核心解析包 MUST KEEP，否则 SDK 初始化直接失效】-------
# SDK 核心入口
-keep class com.huya.berry.HuyaBerry { *; }
-keep class com.huya.berry.HuyaBerry$* { *; }
-keep class com.huya.berry.config.** { *; }
-keep class com.huya.berry.client.** { *; }
# 核心解析/数据模型（getLiveDataByRoomId / LiveInfo 解析链路）
-keep class com.huya.berry.protocol.bean.** { *; }
-keep class com.huya.berry.protocol.model.** { *; }
-keep class com.huya.berry.protocol.entity.** { *; }
-keep class com.huya.berry.liveinfo.** { *; }
-keep class **.LiveInfo { *; }
-keep class **.BitRateInfo { *; }
-keep class **.LineInfo { *; }
-keep class **.StreamInfo { *; }
# MTP 信令 / 网络 / 模块注册 核心
-keep class com.huya.berry.module.** { *; }
-keep class com.huya.berry.net.** { *; }
-keep class com.huya.berry.signaling.** { *; }
-keep class com.huya.berry.mtp.** { *; }
-keep class com.huya.berry.core.** { *; }
# 弹幕核心
-keep class com.huya.berry.danmu.** { *; }
-keep class com.huya.berry.im.** { *; }
# META-DATA 读取（NS_APPID / HY_APPID / HY_APPKEY）
-keep class com.huya.berry.utils.** { *; }
-keep class com.huya.berry.common.** { *; }
# 回调接口绝对不能被混淆（Proxy.newProxyInstance 动态代理）
-keep interface com.huya.berry.ICustomUICallback { *; }
-keep interface com.huya.berry.callback.** { *; }
-keep interface com.huya.berry.listener.** { *; }

# ------- 🟢【反射调用必须 keep：SDK 内部 Class.forName() 加载的类】-------
# 这些是 ServerStartManager.startModules 会 Class.forName 的 Service，
# 虽然我们不使用，但「存在但未实例化」是安全的，不能让 R8 直接删（否则 init 阶段 NoClassDefFound）
-keep class com.huya.berry.sdkcamera.SdkCameraService { *; }
-keep class com.huya.berry.sdkplayer.SdkPlayerService { *; }
-keep class com.huya.berry.sdklive.SdkLiveService { *; }
-keep class com.huya.berry.sdklivelist.SdkLiveListService { *; }

# ------- 豆包避坑：不要屏蔽整个 com.huya.berry 包（会导致核心解析一起删）-------
# （本规则严格按「播放器/dontwarn + 核心/keep」拆分，不会出现一竿子打翻核心解析的问题）

# ------- release 阶段顺便移除 Log.i/d/v（豆包：极致 R8 优化）-------
#  调试阶段保持注释，方便看日志；打 release 时取消下面注释可再省几 KB
#-assumenosideeffects class android.util.Log {
#    public static *** v(...);
#    public static *** d(...);
#    public static *** i(...);
#}