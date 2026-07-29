package com.tv.live.listener;

import android.content.Context;
import com.tv.live.TVPlayerManager;

/**
 * ================================================
 * 播放状态监听器实现类
 * 核心职责：
 * 1. 接收播放器各状态回调，统一做UI层提示
 * 2. 遵循「只提示、不自动重试」原则，播放异常由用户手动切台
 * 3. 与 TVPlayerManager 内部逻辑完全对齐，无行为冲突
 * ================================================
 */
public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {
    // 应用上下文，保留引用备用
    private final Context context;
    // 当前播放的频道名称，保留备用
    private String currentChannelName = "";

    /**
     * 构造函数
     * @param context 上下文，内部自动转成ApplicationContext避免内存泄漏
     */
    public PlayerStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 设置当前播放的频道名称
     * 切换频道时调用，确保提示信息与当前频道对应
     * @param name 频道名称
     */
    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    /**
     * 播放器空闲状态回调
     * 播放器已初始化但未加载媒体时触发，无需额外处理
     */
    @Override
    public void onIdle() {
        // 空闲状态无UI操作
    }

    /**
     * 缓冲中状态回调
     * PlayerView 已自带缓冲转圈动画，无需重复弹窗提示
     * 避免频繁弹窗干扰用户观看
     */
    @Override
    public void onBuffering() {
        // 缓冲状态由播放器视图自带加载动画反馈
    }

    /**
     * 播放就绪回调
     * 播放器准备完成、开始正常播放时触发，无需额外提示
     */
    @Override
    public void onPlayReady() {
        // 播放就绪无额外UI操作
    }

    /**
     * 播放结束回调
     * ✅ 已屏蔽：不弹Toast提示
     */
    @Override
    public void onPlayEnd() {
        // 已屏蔽播放结束提示
    }

    /**
     * 播放错误回调
     * ✅ 已屏蔽：不弹Toast提示
     * 网络异常、源失效、解码失败等情况触发
     */
    @Override
    public void onPlayError(String msg) {
        // 已屏蔽播放错误提示
    }
}
