package com.tv.live;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放器管理类（ExoPlayer）
 * 负责：播放、解析地址、切换画面比例、暂停/恢复
 */
public class TVPlayerManager {
    // 单例实例
    private static TVPlayerManager instance;
    // ExoPlayer 核心播放器
    private ExoPlayer player;
    // 上下文
    private Context context;
    // 播放视图
    private PlayerView playerView;
    // 画面缩放模式
    public enum ScaleMode { FIT, FILL, ZOOM }
    // 播放状态回调
    private OnPlayStateListener listener;

    /**
     * 获取单例（全局唯一）
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * 私有构造：初始化 ExoPlayer
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
    }

    /**
     * 绑定播放视图
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    /**
     * 播放地址（支持 m3u8 / PHP 接口解析）
     */
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        new Thread(() -> {
            // 解析真实播放地址
            String realUrl = resolveStreamUrl(url);
            new android.os.Handler(context.getMainLooper()).post(() -> {
                try {
                    // 配置请求头（防盗链）
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getCommonHeaders());
                    
                    // 构建媒体项
                    MediaItem mediaItem = MediaItem.fromUri(realUrl);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                    
                    // 准备并播放
                    player.setMediaSource(source);
                    player.prepare();
                    player.play();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }).start();
    }
    
    /**
     * 简化播放方法
     */
    public void play(String url) {
        playUrl(url);
    }

    /**
     * 解析 PHP 接口，提取真实 m3u8 地址
     */
    private String resolveStreamUrl(String url) {
        if (url.endsWith(".m3u8") || url.endsWith(".ts")) return url;
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Referer", url.substring(0, url.indexOf("/", 8)));
            
            // 处理 302 重定向
            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.startsWith("http")) {
                    conn.disconnect();
                    return loc;
                }
            }
            
            // 读取页面内容，正则匹配 m3u8
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://[^\\s\"']+\\.m3u8)");
            java.util.regex.Matcher matcher = pattern.matcher(sb.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return url;
    }

    /**
     * 通用请求头（解决防盗链）
     */
    private Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://cdn.jdshipin.com/");
        headers.put("Origin", "http://cdn.jdshipin.com");
        return headers;
    }

    /**
     * 设置画面比例：适应 / 填充 / 拉伸
     */
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    /**
     * 设置播放状态监听
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 播放状态回调接口
     */
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

// ==================== 【完整追加代码】 ====================

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
        try {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(new StringReader(xml));
            String curChan = null, start = null, stop = null, title = null;
            int event = x.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = x.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("channel".equals(name)) curChan = x.getAttributeValue(null, "id");
                    if ("programme".equals(name)) {
                        start = x.getAttributeValue(null, "start");
                        stop = x.getAttributeValue(null, "stop");
                    }
                    if ("title".equals(name)) title = x.nextText().trim();
                }
                if (event == XmlPullParser.END_TAG && "programme".equals(name)) {
                    if (tvg.equals(curChan)) list.add(new Program(start, stop, title));
                }
                event = x.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
    void onFail();
}
public void loadPlayInfo(String playUrl, OnPlayInfoListener listener) {
    new Thread(() -> {
        try {
            String m3uTxt = HttpUtil.get(UrlConfig.M3U_URL);
            ArrayList<M3u.Channel> channels = M3u.parse(m3uTxt);
            M3u.Channel curr = null;
            for (M3u.Channel ch : channels) {
                if (playUrl.startsWith(ch.url)) {
                    curr = ch;
                    break;
                }
            }
            if (curr == null) {
                new Handler(Looper.getMainLooper()).post(listener::onFail);
                return;
            }
            String epgXml = HttpUtil.get(UrlConfig.EPG_URL);
            ArrayList<Epg.Program> programs = Epg.parse(epgXml, curr.tvg);
            PlayInfo info = new PlayInfo();
            info.channel = curr.name;
            info.tvg = curr.tvg;
            if (programs.size() >= 1) {
                Epg.Program p = programs.get(0);
                info.nowTitle = p.title;
                info.nowTime = TimeUtil.fmt(p.start) + " - " + TimeUtil.fmt(p.stop);
                info.progress = TimeUtil.progress(p.start, p.stop);
                info.remain = TimeUtil.remain(p.stop);
            }
            if (programs.size() >= 2) {
                Epg.Program p = programs.get(1);
                info.nextTitle = p.title;
                info.nextTime = TimeUtil.fmt(p.start) + " - " + TimeUtil.fmt(p.stop);
            }
            new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(info));
        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(listener::onFail);
        }
    }).start();
}
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
    void onFail();
}
public void loadChannelList(OnChannelListener listener) {
    new Thread(() -> {
        try {
            String txt = HttpUtil.get(UrlConfig.M3U_URL);
            ArrayList<M3u.Channel> list = M3u.parse(txt);
            new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(list));
        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(listener::onFail);
        }
    }).start();
}

// ==================== 追加结束 ====================

}
