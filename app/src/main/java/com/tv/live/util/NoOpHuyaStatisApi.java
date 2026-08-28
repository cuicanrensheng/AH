package com.tv.live.util;

import com.duowan.live.one.module.report.HuyaStatisApi;
import com.huya.statistics.core.StatisticsContent;

/**
 * 🔇 虎牙 SDK 内部 hiido 统计上报空实现（拦截 Report.event("PV/init") 残留）。
 *
 * 链路：HuyaBerryImpl.init() → initBaseApi()
 *        → HuyaStatisAgent.getInstance().getHuyaStatisApi()
 *        → HuyaStatisApi.init() 内部调用 LiveStaticsicsSdk.init() → 发出 PV/init
 *   且 HuyaBerryImpl.init() 末尾无条件执行：
 *        Report.event("PV/init")
 *   走 Report → HuyaReportModule → HuyaStatisAgent.getHuyaStatisApi().reportEvent()
 *
 * 这条链不经过 BaseApi.getReportApi()（NoOpReportApi 拦不住 PV/init），
 * 因此必须在 SDK init() 之前，把 HuyaStatisAgent 单例的私有字段 mApi
 * 替换为本 NoOp 子类：所有方法（含 init / reportEvent）空转，
 * LiveStaticsicsSdk 根本不被初始化，PV/init 等任何统计埋点都不发网络请求。
 */
public class NoOpHuyaStatisApi extends HuyaStatisApi {

    @Override
    public void init(android.content.Context context, String str, String str2,
                     String str3, String str4, String str5) {
        // 空实现：不初始化 hiido / LiveStaticsicsSdk → 不发 PV/init
    }

    @Override
    public void install() {
    }

    @Override
    public void installApps() {
    }

    @Override
    public void changeSessionId() {
    }

    @Override
    public void error(String str) {
    }

    @Override
    public void login() {
    }

    @Override
    public void startLive(long j, long j2, long j3, String str) {
    }

    @Override
    public void stopLive() {
    }

    @Override
    public void setGameId(long j) {
    }

    @Override
    public void reportEvent(String str, String str2) {
    }

    @Override
    public void reportEvent(String str, String str2, String str3,
                            String str4, StatisticsContent statisticsContent) {
    }

    @Override
    public void setRso(String str) {
    }

    @Override
    public void setUve(String str) {
    }

    @Override
    public void setReferrer(String str) {
    }

    @Override
    public void setYyUid(java.lang.Long l) {
    }

    @Override
    public void setGuid(String str) {
    }

    @Override
    public String getMid() {
        return "";
    }
}
