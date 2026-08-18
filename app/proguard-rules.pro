-keepparameternames
-keepattributes EnclosingMethod,InnerClasses,Signature,*Annotation*,AnnotationDefault,MethodParameters

# ====================================================================
# 🟢 R8 全局优化配置
# ====================================================================
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
# 注意：!field/* 和 !class/merging/* 保持禁用，确保虎牙 SDK 反射访问字段/类名不被破坏
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ====================================================================
# 🔴 R8 优化 - 正式版全量移除日志/打印/堆栈
# ⚠️ 用户明确要求：正式版不要保留 Log.i/w/e、printStackTrace、System.out 等任何调试输出
#   · android.util.Log: v/d/i/w/e（含带Throwable的三参数重载）+ isLoggable 全部删除
#   · Throwable.printStackTrace() / Exception.printStackTrace() → 删除
#   · System.out.println / System.err.println → 删除
# 收益：
#   1) APK 包体进一步减小（字符串常量/拼接/Log调用点全删）
#   2) Release 版运行时不再产生任何 logcat 输出 → 提升安全性 + 减少主线程小开销
# 代价：
#   Release 版线上崩溃无堆栈可查、启动时序/业务日志不可见（用户已接受此取舍）
# ====================================================================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int w(java.lang.String, java.lang.String);
    public static int w(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int w(java.lang.String, java.lang.Throwable);
    public static int e(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int wtf(java.lang.String, java.lang.String);
    public static int wtf(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int wtf(java.lang.String, java.lang.Throwable);
    public static int println(int, java.lang.String, java.lang.String);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
# 删除 Throwable.printStackTrace() / Exception.printStackTrace()（含两种重载：无参/带PrintStream/带PrintWriter）
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
    public void printStackTrace(java.io.PrintStream);
    public void printStackTrace(java.io.PrintWriter);
    public java.lang.Throwable fillInStackTrace();
}
-assumenosideeffects class java.lang.Exception {
    public void printStackTrace();
    public void printStackTrace(java.io.PrintStream);
    public void printStackTrace(java.io.PrintWriter);
}
# 删除 System.out / System.err 的 println/print/printf/format 调用
-assumenosideeffects class java.io.PrintStream {
    public void println();
    public void println(java.lang.Object);
    public void println(java.lang.String);
    public void println(boolean);
    public void println(char);
    public void println(char[]);
    public void println(double);
    public void println(float);
    public void println(int);
    public void println(long);
    public void print(java.lang.Object);
    public void print(java.lang.String);
    public void print(boolean);
    public void print(char);
    public void print(char[]);
    public void print(double);
    public void print(float);
    public void print(int);
    public void print(long);
    public java.io.PrintStream printf(java.lang.String, java.lang.Object[]);
    public java.io.PrintStream format(java.lang.String, java.lang.Object[]);
}

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

# ====================================================================
# 🔴 虎牙 SDK - 全量保留（SDK 大量 Class.forName/JNI/反射动态加载，严禁精细裁剪）
# ====================================================================
# 【教训】2026-08-16 精剪规则尝试导致"获取参数失败，请重试"Toast 连续弹出
# 原因：R8 把 SDK 内部 C++/JNI 通过字符串动态加载的类删除了（395类→保留16类→实际运行缺失379类）
# 结论：虎牙 SDK 是高度封闭的黑盒，内部动态加载链路无法从字节码静态分析穷尽。
#       必须全量 keep，不得做精细裁剪。
# ====================================================================
-keep class com.huya.** { *; }
-keep interface com.huya.** { *; }
-keep class com.duowan.** { *; }
-keep interface com.duowan.** { *; }
-keepclassmembers class com.huya.** { *; }
-keepclassmembers interface com.huya.** { *; }
-keepclassmembers class com.duowan.** { *; }
-keepclassmembers interface com.duowan.** { *; }
-keepnames class com.huya.** { *; }
-keepnames interface com.huya.** { *; }
-keepnames class com.duowan.** { *; }
-keepnames interface com.duowan.** { *; }
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

# 🔴 Native 方法保护
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembers class com.huya.** {
    native <methods>;
}
-keepclasseswithmembers class com.duowan.** {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# 通用直播工具
-keep class com.huya.live.common.** { *; }
-keep class com.huya.live.utils.** { *; }

-keepnames class com.huya.security.** { *; }
-keepnames class com.huya.hydeviceid.** { *; }
-keepnames class **.NativeBridge { *; }

# ====================================================================
# E. Retrofit + RxJava + OkHttp（网络库，已有直调调用）
# ====================================================================
-dontwarn retrofit2.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**
-dontwarn okhttp3.**
-dontwarn okio.**

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
-keep class io.reactivex.** { *; }
-keep class org.reactivestreams.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ====================================================================
# F. 其他保留（枚举、Activity 入口等通用规则）
# ====================================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
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
