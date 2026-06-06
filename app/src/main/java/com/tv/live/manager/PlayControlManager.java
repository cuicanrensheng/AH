package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.text.TextUtils;
import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.util.LogUtils;
import com.tv.live.util.RedirectUrlUtil;
import java.util.List;

public class PlayControlManager {
    private static final long CHANNEL_COOLDOWN = 300;
    private long lastChannelChangeTime = 0;
    private final MainActivity activity;
    private final TVPlayerManager mPlayerManager;
    private final AppConfig appConfig;
    private final TextView tv_channel_num;
    private final InfoBarManager infoBarManager;
    private final EpgManagerWrapper epgManagerWrapper;
    private final SettingsManager settingsManager;
    private int currentSelectedDateIndex;

    public PlayControlManager(MainActivity activity, TVPlayerManager playerManager,
                             AppConfig cfg, TextView numTv, InfoBarManager infoBar, EpgManagerWrapper epgWrap,
                             SettingsManager setMgr, int dateIndex) {
        this.activity = activity;
        this.mPlayerManager = playerManager;
        this.appConfig = cfg;
        this.tv_channel_num = numTv;
        this.infoBarManager = infoBar;
        this.epgManagerWrapper = epgWrap;
        this.settingsManager = setMgr;
        this.currentSelectedDateIndex = dateIndex;
    }

    public void setCurrentDateIndex(int idx) {
        this.currentSelectedDateIndex = idx;
    }

    public void showChannelNum(int num, long delay) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, delay);
    }

    public int playPrev(List<Channel> sourceList, int nowIndex) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return nowIndex;
        lastChannelChangeTime = now;
        LogUtils.log("【切台】上一台");
        int idx = settingsManager.channel_reverse ? activity.switchManager.next() : activity.switchManager.prev();
        playChannel(idx, sourceList);
        return idx;
    }

    public int playNext(List<Channel> sourceList, int nowIndex) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return nowIndex;
        lastChannelChangeTime = now;
        LogUtils.log("【切台】下一台");
        int idx = settingsManager.channel_reverse ? activity.switchManager.prev() : activity.switchManager.next();
        playChannel(idx, sourceList);
        return idx;
    }

    public void playChannel(int index, List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            LogUtils.log("【播放】频道列表为空，无法播放");
            return;
        }
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            LogUtils.log("【播放】频道地址为空");
            return;
        }
        String originalUrl = ch.getPlayUrl();
        LogUtils.log("========================================");
        LogUtils.log("【播放】频道：" + ch.getName());
        LogUtils.log("【原始地址】：" + originalUrl);
        LogUtils.log("========================================");

        activity.playerStateListener.setCurrentChannelName(ch.getName());

        new Thread(() -> {
            String realUrl = RedirectUrlUtil.getRealPlayUrl(originalUrl);
            String playUrl = TextUtils.isEmpty(realUrl) ? originalUrl : realUrl;
            LogUtils.log("【最终播放地址】→ " + playUrl);
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(playUrl);
            });
        }).start();

        showChannelNum(index + 1, 4000);
        appConfig.setLastPlayIndex(index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoBarManager.showInfoBar(ch.getName(), live, 4000);
    }
}
