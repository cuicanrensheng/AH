package com.tv.live.util;

import android.app.Activity;

import com.huya.live.common.api.report.ReportApi;

import java.util.Map;

/**
 * 🔇 虎牙 SDK 统计上报通道空实现。
 *
 * SDK 内部 initBaseApi() 会无条件 new ReportApiImpl 并 BaseApi.setReportApi(...)，
 * Builder 上没有运营统计（isOpenStat/isOpenAnalytics/isOpenReport/isOpenMonitor）开关。
 * 因此在本类启动 SDK 成功后，再次直接调用 BaseApi.setReportApi(NoOpReportApi)，
 * 把 SDK 内部的上报实现整体替换为空实现——后续所有 PV/事件/直播统计全部被吞掉，不发任何网络请求。
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class NoOpReportApi implements ReportApi {

    @Override
    public void init(String appKey, String env, String channel, String version) {
        // 空实现：不初始化 hiido 上报通道
    }

    @Override
    public void event(String eventName) {
    }

    @Override
    public void event(String eventName, String param1) {
    }

    @Override
    public void event(String eventName, String param1, String param2) {
    }

    @Override
    public void event(String eventName, String param1, String param2, Map map) {
    }

    @Override
    public void event(String eventName, String param1, String param2, String param3) {
    }

    @Override
    public void event(String eventName, String param1, String param2, String param3, Map map) {
    }

    @Override
    public void eventHuya(String eventName, String param1) {
    }

    @Override
    public void startLive(long roomId, long liveId, long uid, String anchorNick) {
    }

    @Override
    public void stopLive() {
    }

    @Override
    public void loginSuccess(long uid) {
    }

    @Override
    public void reportGuid(String guid) {
    }

    @Override
    public void changeHuyaSessionId() {
    }

    @Override
    public void value(String key, int value) {
    }

    @Override
    public void value(String key, String param, int value) {
    }

    @Override
    public void error(String errorType, String errorCode, String errorMsg) {
    }

    @Override
    public void resume(Activity activity) {
    }

    @Override
    public void pause(Activity activity) {
    }
}
