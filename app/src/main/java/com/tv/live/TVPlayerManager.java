package com.tv.live;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private PlayerView playerView;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;

    // 播放缩放模式（修复ScreenRatioManager报错）
    public enum ScaleMode {
        FIT, FILL, ZOOM
    }

    // 直播信息回调
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    // 播放状态接口（保持兼容）
    public interface OnPlayStateListener {
        void onPlayStarted();
        void onPlayPaused();
        void onPlayCompleted();
        void onPlayError(Exception e);
        void onBuffering();
        void onPlaying();
    }

    public static class LiveInfo {
        public String quality = "未知";
        public String audio = "未知";
        public String bitrate = "未知";
    }

    private TVPlayerManager() {}

    public static TVPlayerManager getInstance() {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) instance = new TVPlayerManager();
            }
        }
        return instance;
    }

    public void setOnPlayStateListener(OnPlayStateListener listener) {}

    public void attachPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        Context context = playerView.getContext();
        if (player == null) createExoPlayer(context);
        playerView.setPlayer(player);
    }

    private void createExoPlayer(Context context) {
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(getHeaders());

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new com.google.android.exoplayer2.source.DefaultMediaSourceFactory(dataSourceFactory))
                .build();
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://cdn.jdshipin.com/");
        headers.put("Accept", "*/*");
        headers.put("Icy-MetaData", "1");
        headers.put("Accept-Encoding", "identity");
        return headers;
    }

    public void playUrl(String url) {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void resume() {
        if (player != null) player.play();
    }

    public void onBackground() { pause(); }
    public void onForeground() { resume(); }

    public void release() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        playerView = null;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "高清";
        info.audio = "立体声";
        info.bitrate = "直播流";
        return info;
    }

    // 修复缩放模式
    public void setScaleMode(ScaleMode scaleMode) {
        if (playerView != null) {
            playerView.setResizeMode(
                    scaleMode == ScaleMode.FIT ? PlayerView.RESIZE_MODE_FIT :
                    scaleMode == ScaleMode.FILL ? PlayerView.RESIZE_MODE_FILL :
                    PlayerView.RESIZE_MODE_ZOOM
            );
        }
    }
}
