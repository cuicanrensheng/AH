package com.tv.live.util;

import com.huya.berry.client.HuyaBerry;
import com.tv.live.util.LogBridge;

/**
 * 🔒 虎牙 SDK 数据上报「双重保险」守卫。
 *
 * <p>背景：{@link HuyaBerry#sendPlayerData(HuyaBerry.BerryPlayerDataHelper)}（播放器心跳/状态上报）
 * 与 {@link HuyaBerry#setGameAccountID(String)}（关联游戏账号）属于 SDK 数据上报入口。
 * 本应用为纯观看架构（isNeedPlay=false），不调用这两个方法，已天然禁用。
 *
 * <p>为防止：① 未来误调用 ② SDK 升级后内部自动触发，这里集中声明这两个入口为 no-op，
 * 并在 SDK init 完成后主动清空可能残留的游戏账号绑定。任何上报都不走真实网络。
 *
 * <p>注意：sendPlayerData / setGameAccountID 是 HuyaBerry 实例方法（非接口注入），
 * 无法像 NoOpReportApi 那样整体替换实现。因此「双重保险」采取：
 * 1) 本项目所有 sHuyaBerry 调用集中在 HuyaSDKParser，已确认无 sendPlayerData/setGameAccountID 调用；
 * 2) 本 Guard 提供显式 no-op 守卫方法，杜绝误用；
 * 3) setGameAccountID("") 主动清空残留账号绑定。
 */
public final class HuyaBerryReportGuard {

    private static final String TAG = "HuyaBerryReportGuard";

    private HuyaBerryReportGuard() {}

    /**
     * 在 SDK init 完成后调用：清空游戏账号绑定 + 声明播放数据上报已禁用。
     */
    public static void applyAfterInit(HuyaBerry berry) {
        if (berry == null) return;

        // 1) 清空可能残留的游戏账号绑定（本地存储，不联网，安全）
        try {
            berry.setGameAccountID("");
            LogBridge.i(TAG, "✅ setGameAccountID(\"\") 已清空游戏账号绑定（不关联任何游戏账号）");
        } catch (Throwable e) {
            LogBridge.w(TAG, "setGameAccountID 清空异常(可忽略): " + e.getMessage());
        }

        // 2) 声明 sendPlayerData 播放数据上报已禁用（no-op，不调用 SDK 真实上报）
        guardSendPlayerData();
    }

    /**
     * 播放器心跳/状态上报 —— 双重保险 no-op。
     * 任何调用方（含未来代码）应使用此方法而非 berry.sendPlayerData(...)，
     * 以确保永不触发真实网络上报。
     */
    public static void guardSendPlayerData() {
        // 故意不调用 sHuyaBerry.sendPlayerData(...)，吞掉所有上报
        LogBridge.i(TAG, "✅ sendPlayerData 播放数据上报已禁用（no-op，不触发任何网络请求）");
    }

    /**
     * 游戏账号绑定 —— 双重保险 no-op。
     * 任何调用方应使用此方法而非 berry.setGameAccountID(...)，确保不关联账号。
     */
    public static void guardSetGameAccountID() {
        // 故意不调用 sHuyaBerry.setGameAccountID(...)，保持账号解绑状态
        LogBridge.i(TAG, "✅ setGameAccountID 游戏账号上报已禁用（no-op）");
    }
}
