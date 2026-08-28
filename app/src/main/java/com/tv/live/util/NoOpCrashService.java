package com.tv.live.util;

import com.huya.berry.gamesdk.crash.ICrashService;
import com.huya.live.service.AbsService;

/**
 * SDK 崩溃上报服务的 NoOp（空操作）实现 —— 防崩溃保护（Bugly 专业版已移除）。
 *
 * 【用途】
 *   在 HuyaBerry.init() 之前向 ServiceCenter 注册本类，替代 SDK 默认的
 *   com.huya.berry.client.CrashService，使任何 ICrashService 调用变为空操作。
 *
 * 【为什么需要】
 *   1. 虎牙 SDK 内嵌的 Bugly 专业版 aar（crashreport-3.0.0 / nativecrashreport-3.7.1）
 *      已物理删除（备份于 _backup/），APK 内已无 com.tencent.bugly.* 类；
 *   2. 但 SDK 的 CrashService 类仍硬引用 com.tencent.bugly.crashreport.CrashReport
 *      （initCrashReport/postCatchedException 方法调用已编译进常量池），一旦被
 *      实例化或调用即触发 NoClassDefFoundError 崩溃；
 *   3. SDK init 后仍会无条件注册 CrashService（HuyaBerryImpl L79），且 MTP/WUP
 *      错误回调（initMTP / wupInit）会 getService(ICrashService) 并调用 postCatchedException()；
 *   4. ServiceCenter.addService() 不覆盖已注册的 key，因此先注册本 NoOp 实现，
 *      SDK 后续 createService(ICrashService, CrashService) 会被静默忽略，
 *      真实 CrashService 永远不会被实例化/调用。
 *
 * 【与 NoOpReportApi 的分工】
 *   - NoOpCrashService：拦截 ICrashService（SDK 崩溃上报通道）
 *   - NoOpReportApi：拦截 ReportApi（Hiido 运营统计/PV/事件上报）
 *   - MonitorCenter.stopReport()：关闭 ciku APM 性能监控
 */
public class NoOpCrashService extends AbsService implements ICrashService {

    /**
     * 崩溃上报服务初始化（空实现）。
     * SDK 默认实现会调用 CrashReport.initCrashReport(context, "ccd26cafc4", false, strategy)
     * —— 该调用会触发 NoClassDefFoundError，这里保持空操作以阻止。
     */
    @Override
    public void init() {
        // NoOp: 不执行任何 Bugly 初始化（防崩溃保护）
    }

    /**
     * 主动上报捕获的异常（空实现）。
     * SDK 默认实现会调用 CrashReport.postCatchedException(th) 上报，同样会
     * 触发 NoClassDefFoundError。异常来源包括 MTP Mars crashIfDebug、
     * WUP onDecodeError/onRespError、JCE onParseJceError。
     */
    @Override
    public void postCatchedException(java.lang.Throwable th) {
        // NoOp: 不调用 Bugly API（防崩溃保护）
    }
}
