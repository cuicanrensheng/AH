package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.text.TextUtils;
import com.tv.live.AppConfig;
import com.tv.live.Channel;
import com.tv.live.ChannelSwitchManager;
import com.tv.live.EpgManagerWrapper;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;
import com.tv.live.util.LogUtils;
import com.tv.live.util.RedirectUrlUtil;
import java.util.List;

/**
 * 播放切台业务管理器
 * 拆分：playPrev、playNext、playChannel、showChannelNum四个核心播放方法
 * 统一管控：切台防抖、链接解析、播放器调用、EPG刷新、右上角频道数字提示
 */
public class PlayControlManager {
    // 切台防抖间隔300ms，防止遥控器连按疯狂切台
    private static final long CHANNEL_COOLDOWN = 300;
    // 上次切台时间戳，用于防抖判断
    private long lastChannelChangeTime = 0;
    private final MainActivity activity;
    private final TVPlayerManager mPlayerManager;
    private final ChannelSwitchManager switchManager;
    private final AppConfig appConfig;
    // 右上角频道数字TextView
    private final TextView tv_channel_num;
    private final InfoBarManager infoBarManager;
    private final EpgManagerWrapper epgManagerWrapper;
    private final SettingsManager settingsManager;
    // 当前EPG选中日期下标
    private int currentSelectedDateIndex;

    /**
     * 播放管理器构造，注入所有依赖对象
     */
    public PlayControlManager(MainActivity activity, TVPlayerManager playerManager, ChannelSwitchManager switchMgr,
                             AppConfig cfg, TextView numTv, InfoBarManager infoBar, EpgManagerWrapper epgWrap,
                             SettingsManager setMgr, int dateIndex) {
        this.activity = activity;
        this.mPlayerManager = playerManager;
        this.switchManager = switchMgr;
        this.appConfig = cfg;
        this.tv_channel_num = numTv;
        this.infoBarManager = infoBar;
        this.epgManagerWrapper = epgWrap;
        this.settingsManager = setMgr;
        this.currentSelectedDateIndex = dateIndex;
    }

    /**
     * 更新当前选中EPG日期下标（日期列表点击回调使用）
     */
    public void setCurrentDateIndex(int idx) {
        this.currentSelectedDateIndex = idx;
    }

    /**
     * 右上角弹出频道数字，倒计时自动消失
     * @param num 频道序号
     * @param delay 消失延时ms
     */
    public void showChannelNum(int num, long delay) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(android.view.View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(android.view.View.GONE);
        }, delay);
    }

    /**
     * 上一个频道
     * @param sourceList 全频道数据源
     * @return 更新后的播放下标
     */
    public int playPrev(List<Channel> sourceList, int nowIndex) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return nowIndex;
        lastChannelChangeTime = now;
        LogUtils.log("【切台】上一台");
        // 根据配置判断切台方向是否反转
        int idx = settingsManager.channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx, sourceList);
        return idx;
    }

    /**
     * 下一个频道
     * @param sourceList 全频道数据源
     * @return 更新后的播放下标
     */
    public int playNext(List<Channel> sourceList, int nowIndex) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return nowIndex;
        lastChannelChangeTime = now;
        LogUtils.log("【切台】下一台");
        int idx = settingsManager.channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx, sourceList);
        return idx;
    }

    /**
     * 核心播放方法：根据下标播放对应频道
     * @param index 全局频道下标
     * @param channelSourceList 全频道集合
     */
    public void playChannel(int index, List<Channel> channelSourceList) {
        // 空列表拦截
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            LogUtils.log("【播放】频道列表为空，无法播放");
            return;
        }
        // 下标越界修正
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        Channel ch = channelSourceList.get(index);
        // 空播放地址拦截
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            LogUtils.log("【播放】频道地址为空");
            return;
        }
        String originalUrl = ch.getPlayUrl();
        LogUtils.log("========================================");
        LogUtils.log("【播放】频道：" + ch.getName());
        LogUtils.log("【原始地址】：" + originalUrl);
        LogUtils.log("========================================");

        // 给播放器监听设置当前频道名
        activity.playerStateListener.setCurrentChannelName(ch.getName());

        // 子线程解析重定向链接
        new Thread(() -> {
            String realUrl = RedirectUrlUtil.getRealPlayUrl(originalUrl);
            String playUrl = TextUtils.isEmpty(realUrl) ? originalUrl : realUrl;
            LogUtils.log("【最终播放地址】→ " + playUrl);
            // 切主线程调用播放器播放
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(playUrl);
            });
        }).start();

        // 弹出频道号、保存上次播放记录、刷新EPG、刷新顶部信息栏
        showChannelNum(index + 1, 4000);
        appConfig.setLastPlayIndex(index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoBarManager.showInfoBar(ch.getName(), live, 4000);
    }
}
