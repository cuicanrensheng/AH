package com.xxx.tvplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

/**
 * 电视播放主页Activity
 * 已全局禁用Exo原生控制面板，任何操作不会弹出控制器UI
 */
public class MainActivity extends AppCompatActivity {
    //播放器实例
    private ExoPlayer exoPlayer;
    //播放器画面控件
    private PlayerView playerView;
    //控制器显示标记
    private boolean isControllerVisible = false;

    //控制器切换广播接收器（已屏蔽打开控制器逻辑）
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            //【关键注释：彻底禁用广播唤起原生控制栏】
            //playerView.setUseController(isControllerVisible);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //全屏、屏幕常亮配置
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        //绑定播放器控件
        playerView = findViewById(R.id.player_view);

        //====================【核心4行：永久关闭Exo原生控制器，根治弹出】====================
        //1.关闭原生控制器总开关，控件内部销毁控制视图
        playerView.setUseController(false);
        //2.控制器自动隐藏超时设0，杜绝延时弹出
        playerView.setControllerShowTimeoutMs(0);
        //3.移除控制器显示监听，拦截内部自动唤起回调
        playerView.setControllerVisibilityListener(null);
        //4.关闭触摸弹出控制器，点击画面不再弹控制栏
        playerView.setControllerHideOnTouch(false);
        //==================================================================================

        //初始化Exo播放器
        exoPlayer = new ExoPlayer.Builder(this).build();
        //播放器和画面控件绑定
        playerView.setPlayer(exoPlayer);

        //配置播放源（你的原有播放地址，无需改动）
        String playUrl = "http://xxx/stream.m3u8";
        MediaItem mediaItem = MediaItem.fromUri(playUrl);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        //注册切换控制器广播（仅保留注册，内部触发代码已注释）
        IntentFilter filter = new IntentFilter("ACTION_TOGGLE_CONTROLLER");
        registerReceiver(toggleControllerReceiver, filter);
    }

    /**
     * 切换播放暂停（原有自定义逻辑保留，原生控件不会弹出）
     */
    public void togglePlay() {
        if (exoPlayer.isPlaying()) {
            exoPlayer.pause();
        } else {
            exoPlayer.play();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) {
            exoPlayer.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //注销广播
        unregisterReceiver(toggleControllerReceiver);
        //释放播放器资源
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
