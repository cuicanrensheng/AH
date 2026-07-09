package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.tv.live.Channel;
import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;

import java.util.ArrayList;
import java.util.List;

public class MainController {

    private Context context;

    private ChannelPanelController channelPanelController;
    private TvRemoteManager remoteManager;
    private InfoDisplayManager infoDisplayManager;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;

    private boolean channelReverse = false;
    private boolean numberChannelEnable = true;
    private boolean epgEnable = true;
    private boolean autoUpdateSource = true;

    private int currentPlayIndex = 0;

    // 🟢【优化】彻底移除 logList，消除 ArrayList 头部插入的复制开销
    // private static List<String> logList = new ArrayList<>();

    private OnPlayControlListener playControlListener;
    private OnPanelControlListener panelControlListener;

    public interface OnPlayControlListener {
        void onPlayChannel(Channel channel, int index);
    }

    public interface OnPanelControlListener {
        void onTogglePanel();
        void onRequestFocus();
    }

    public MainController(
            Context context,
            ChannelPanelController channelPanelController,
            TvRemoteManager remoteManager,
            InfoDisplayManager infoDisplayManager,
            TVPlayerManager playerManager,
            AppConfig appConfig,
            PlayerStateListenerImpl playerStateListener
    ) {
        this.context = context.getApplicationContext();
        this.channelPanelController = channelPanelController;
        this.remoteManager = remoteManager;
        this.infoDisplayManager = infoDisplayManager;
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.playerStateListener = playerStateListener;
    }

    // ====================================================================
    // 1. 按键处理相关
    // ====================================================================

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager.handleNumberKey(keyCode)) {
            return true;
        }
        if (handleDirectionKey(keyCode)) {
            return true;
        }
        return false;
    }

    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelReverse) {
                    playNext();
                } else {
                    playPrev();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelReverse) {
                    playPrev();
                } else {
                    playNext();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (remoteManager.isNumberInputting()) {
                    remoteManager.confirmChannelNum();
                    return true;
                }
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    public boolean handleBackPressed() {
        if (remoteManager.isNumberInputting()) {
            remoteManager.cancelNumberInput();
            return true;
        }
        if (channelPanelController.handleBackPressed()) {
            if (panelControlListener != null) {
                panelControlListener.onRequestFocus();
            }
            return true;
        }
        return false;
    }

    // ====================================================================
    // 2. 播放控制相关
    // ====================================================================

    public void playPrev() {
        channelPanelController.playPrev();
    }

    public void playNext() {
        channelPanelController.playNext();
    }

    public void playChannel(int index) {
        channelPanelController.playChannel(index);
    }

    public void doPlayChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        Log.d("MainController", "========================================");
        Log.d("MainController", "【播放】频道名称：" + channel.getName());
        Log.d("MainController", "【播放】播放地址：" + channel.getPlayUrl());
        Log.d("MainController", "【播放】当前索引：" + index);
        Log.d("MainController", "========================================");

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        playerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = playerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        if (playControlListener != null) {
            playControlListener.onPlayChannel(channel, index);
        }
    }

    public void togglePanel() {
        channelPanelController.togglePanel();
        if (panelControlListener != null) {
            panelControlListener.onTogglePanel();
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        channelPanelController.setCurrentPlayIndex(index);
    }

    // ====================================================================
    // 3. 设置管理相关
    // ====================================================================

    public void loadSettings() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epgEnable = sp.getBoolean("epg_enable", true);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("number_channel_enable", true);
        autoUpdateSource = sp.getBoolean("auto_update_source", true);

        if (remoteManager != null) {
            remoteManager.setNumberChannelEnable(numberChannelEnable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epgEnable);
        }

        Log.d("MainController", "【设置】EPG开关：" + epgEnable);
        Log.d("MainController", "【设置】切台反转：" + channelReverse);
        Log.d("MainController", "【设置】数字选台：" + numberChannelEnable);
        Log.d("MainController", "【设置】自动更新源：" + autoUpdateSource);
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public boolean isNumberChannelEnable() {
        return numberChannelEnable;
    }

    public boolean isEpgEnable() {
        return epgEnable;
    }

    public boolean isAutoUpdateSource() {
        return autoUpdateSource;
    }

    // ====================================================================
    // 4. 日志管理相关（已彻底移除，防止阵列复制造成的卡顿）
    // ====================================================================

    // 🟢 已移除所有的 logList 和静态缓存

    // ====================================================================
    // 5. 监听器设置
    // ====================================================================

    public void setOnPlayControlListener(OnPlayControlListener listener) {
        this.playControlListener = listener;
    }

    public void setOnPanelControlListener(OnPanelControlListener listener) {
        this.panelControlListener = listener;
    }

    // ====================================================================
    // 6. 资源释放
    // ====================================================================

    public void release() {
        context = null;
        channelPanelController = null;
        remoteManager = null;
        infoDisplayManager = null;
        playerManager = null;
        appConfig = null;
        playerStateListener = null;
        playControlListener = null;
        panelControlListener = null;
    }
}
