package com.tv.live;

import com.tv.live.SettingsActivity;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放器管理类（ExoPlayer）
 * 负责：播放、解析地址、切换画面比例、暂停/恢复
 */
public class TVPlayerManager {
    // 单例实例
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // 虎牙直播 终极请求头（含完整Cookie）
    // ==============================
    private Map<String, String> getGiteeHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept", "*/*");
        headers.put("Connection", "Keep-Alive");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");

        // 你提供的完整虎牙Cookie（已直接填入）
        headers.put("Cookie", "__yamid_new=CBAE6B2F616000012FA487501AD61400; __yasmid=0.6018124788030086; __yamid_tt1=0.6018124788030086; _yasids=__rootsid%3DCBAE6B2F7F7000017F2D3F5013501A2E; udb_appid=5131; hdid=5d1ed36c1636d618790249406a7d98db142fa989; game_did=XbWjWc0g3x0seXULdL4gZOWoaefEjLVcoXs; _qimei_uuid42=1a51f141c0f100cb27d1b2b05607d37675a2a0b577; guid=0a7d910e5f291c6a390179217a21d18d; SoundValue=0.50; alphaValue=0.80; isInLiveRoom=true; udb_guiddata=f8a14fa7ec49465890de64d519d8ec79; udb_deviceid=w_1113564630755672064; sdid=0UnHUgv0_qmfD4KAKlwzhqTa75I06hyNx4h4gGoKdEelYxR828o9qZhnMaTjaGu0iuF89R44_yy2QBe8lUsXwT7A9vUXRclYeI07Eg410Y6nWVkn9LtfFJw_Qo4kgKr8OZHDqNnuwg612sGyflFn1drgqtEk86lGJoTGZ4uaaXbGGmYmAcr9jCaZiDHAhe19a; udb_passdata=3; udb_anouid=1470934057565; udb_anobiztoken=AQCZBvjCa1LpBtGxm8crb0F-XD7JSRyPHkBsUjignL3kfUltwLAsSB5n1nJTLvtJZ_kgDNVxyrrOd9p7Nvwlm7ezKPsZ4f8DeV4VPVOZu7oQcs4-idqqTx4jr299R2JWbQvsynWPR7bpv0LEQr65WTJ9OhiBmy7YjtjEviqk41TIwV8qdN22N-LiCbnMUltKuuXRSV7Nt6kAiWBJ2rr1XRzCA6YiEp92hDja-tSojCpfvBxEXITiENHWp_RwWENcq-v6IYkWicJdSU7TNXv6HRmZrRr3Cie0nu23dJDE7fwxmoBq9043nAcs2D7cdDdTriCgl6jlQKPUsDxox7MVEiVt; _qimei_fingerprint=1b07e4205026a9c8f33cdf981af38ae5; _qimei_h38=9bfa8bbe27d1b2b05607d3760200000571a51f; Hm_lvt_51700b6c722f5bb4cf39906a596ea41f=1780230501; HMACCOUNT=2B6BB34E743A6F12; huyawap_rep_cnt=59; _rep_cnt=3; guid=0a7d910e5f291c6a390179217a21d18d; huya_ua=webh5&0.1.0&websocket&&h5_/11342412; Hm_lpvt_51700b6c722f5bb4cf39906a596ea41f=1780230525; huya_web_rep_cnt=125; huya_flash_rep_cnt=27");

        return headers;
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        SettingsActivity.log("播放器请求地址：" + url);
        SettingsActivity.log("已启用虎牙全套请求头 + 完整Cookie");

        new Thread(() -> {
            new Handler(context.getMainLooper()).post(() -> {
                try {
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getGiteeHeaders());
                    factory.setAllowCrossProtocolRedirects(true);
                    factory.setConnectTimeoutMs(15000);
                    factory.setReadTimeoutMs(15000);

                    MediaItem mediaItem = MediaItem.fromUri(url);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                    player.setMediaSource(source);
                    player.prepare();
                    player.play();

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlayerError(PlaybackException error) {
                            SettingsActivity.log("❌ 播放器错误：" + error.getMessage());
                            SettingsActivity.log("❌ 错误码：" + error.errorCode);
                        }

                        @Override
                        public void onPlaybackStateChanged(int state) {
                            switch (state) {
                                case Player.STATE_IDLE:
                                    SettingsActivity.log("⏸️ 播放器状态：空闲");
                                    break;
                                case Player.STATE_BUFFERING:
                                    SettingsActivity.log("⏳ 播放器状态：缓冲中");
                                    break;
                                case Player.STATE_READY:
                                    SettingsActivity.log("✅ 播放器状态：已就绪，开始播放");
                                    break;
                                case Player.STATE_ENDED:
                                    SettingsActivity.log("🛑 播放器状态：播放结束");
                                    break;
                            }
                        }
                    });

                    SettingsActivity.log("播放器已开始播放");
                } catch (Exception e) {
                    SettingsActivity.log("❌ 播放异常：" + e.getMessage());
                    e.printStackTrace();
                }
            });
        }).start();
    }

    private String resolveStreamUrl(String url) {
        return url;
    }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }

    public long getBitrate() {
        try { return player.getVideoFormat().bitrate; }
        catch (Exception e) { return 0; }
    }

    public String getBitrateStr() {
        long b = getBitrate();
        return b <= 0 ? "4.5MB/s" : String.format("%.1fMB/s", b / 1000000f);
    }

    public String getQuality() {
        try {
            int h = player.getVideoFormat().height;
            if (h >= 1080) return "FHD";
            else if (h >= 720) return "HD";
            else return "SD";
        } catch (Exception e) { return "FHD"; }
    }

    public String getAudio() {
        try {
            int ch = player.getAudioFormat().channelCount;
            return ch >= 2 ? "立体声" : "单声道";
        } catch (Exception e) { return "立体声"; }
    }

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = getQuality();
        info.audio = getAudio();
        info.bitrate = getBitrateStr();
        return info;
    }

    public static class M3u {
        public static class Channel {
            public String tvg;
            public String name;
            public String url;
            public Channel(String tvg, String name, String url) {
                this.tvg = tvg;
                this.name = name;
                this.url = url;
            }
        }
        public static ArrayList<Channel> parse(String txt) {
            ArrayList<Channel> list = new ArrayList<>();
            Pattern p = Pattern.compile("tvg-name=\"([^\"]+)\".*?,(.*?)\\s*\\n(https?://.*?\\.m3u8)");
            Matcher m = p.matcher(txt);
            while (m.find()) {
                String tvg = m.group(1).trim();
                String name = m.group(2).trim();
                String url = m.group(3).trim();
                list.add(new Channel(tvg, name, url));
            }
            return list;
        }
    }

    public static class Epg {
        public static class Program {
            public String start;
            public String stop;
            public String title;
            public Program(String s, String e, String t) {
                start = s;
                stop = e;
                title = t;
            }
        }
        public static ArrayList<Program> parse(String xml, String tvg) {
            ArrayList<Program> list = new ArrayList<>();
            return list;
        }
    }

    public static class PlayInfo {
        public String channel;
        public String tvg;
        public String nowTitle;
        public String nowTime;
        public String nextTitle;
        public String nextTime;
        public int progress;
        public int remain;
    }

    public interface OnPlayInfoListener {
        void onSuccess(PlayInfo info);
    }

    public void loadPlayInfo(String playUrl, OnPlayInfoListener listener) {}

    private Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable mRefreshRunnable;
    private String mCurrUrl;
    private OnPlayInfoListener mRefreshListener;

    public void startAutoRefresh(String url, OnPlayInfoListener listener) {
        mCurrUrl = url;
        mRefreshListener = listener;
        stopAutoRefresh();
        mRefreshRunnable = () -> {
            loadPlayInfo(mCurrUrl, mRefreshListener);
            mRefreshHandler.postDelayed(mRefreshRunnable, 30000);
        };
        mRefreshHandler.post(mRefreshRunnable);
    }

    public void stopAutoRefresh() {
        if (mRefreshRunnable != null) mRefreshHandler.removeCallbacks(mRefreshRunnable);
    }

    public interface OnChannelListener {
        void onSuccess(ArrayList<M3u.Channel> list);
    }

    public void loadChannelList(OnChannelListener listener) {}
}
