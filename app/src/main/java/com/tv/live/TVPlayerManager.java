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

    // ==================== 新增：仅用于后台暂停 / 前台恢复 ====================
    /**
     * 暂停播放（按 Home 时调用）
     */
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * 恢复播放（返回 APP 时调用）
     */
    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
}
// ==================== 【完整版追加：码率+清晰度+音轨+M3U+EPG+自动刷新+防泄漏】 ====================
import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 1. 码率、清晰度、音轨
public long getCurrentBitrate() {
    if (player == null) return 0;
    try {
        return player.getVideoFormat().bitrate;
    } catch (Exception e) {
        return 0;
    }
}

public String getBitrateText() {
    long bitrate = getCurrentBitrate();
    if (bitrate <= 0) return "4.5MB/s";
    return String.format("%.1fMB/s", bitrate / 1000000f);
}

public String getQualityText() {
    if (player == null || player.getVideoFormat() == null) return "FHD";
    int h = player.getVideoFormat().height;
    if (h >= 1080) return "FHD";
    else if (h >= 720) return "HD";
    else return "SD";
}

public String getAudioType() {
    if (player == null || player.getAudioFormat() == null) return "立体声";
    int ch = player.getAudioFormat().channelCount;
    return ch >= 2 ? "立体声" : "单声道";
}

public static class LiveInfo {
    public String quality;
    public String audioType;
    public String bitrateText;
}

public LiveInfo getLiveInfo() {
    LiveInfo info = new LiveInfo();
    info.quality = getQualityText();
    info.audioType = getAudioType();
    info.bitrateText = getBitrateText();
    return info;
}

// 2. M3U解析
public static class M3uParser {
    private static final Pattern PATTERN = Pattern.compile("tvg-name=\"([^\"]+)\".*?,(.*?)\\s*\\n(https?://.*?\\.m3u8)");
    public static ArrayList<M3uParser.Channel> parse(String content) {
        ArrayList<M3uParser.Channel> list = new ArrayList<>();
        Matcher m = PATTERN.matcher(content);
        while (m.find()) {
            String tvgName = m.group(1).trim();
            String title = m.group(2).trim();
            String url = m.group(3).trim();
            list.add(new M3uParser.Channel(tvgName, title, url));
        }
        return list;
    }

    public static class Channel {
        public String tvgName;
        public String channelName;
        public String url;
        public Channel(String tvgName, String channelName, String url) {
            this.tvgName = tvgName;
            this.channelName = channelName;
            this.url = url;
        }
    }
}

// 3. EPG XML解析
public static class EpgParser {
    public static ArrayList<EpgParser.Program> parse(String xml, String tvgName) {
        ArrayList<EpgParser.Program> programs = new ArrayList<>();
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(xml));
            String currentChannel = null;
            String start = null, stop = null, title = null;
            int event = parser.getEventType();

            while (event != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if ("channel".equals(name))
                            currentChannel = parser.getAttributeValue(null, "id");
                        if ("programme".equals(name)) {
                            start = parser.getAttributeValue(null, "start");
                            stop = parser.getAttributeValue(null, "stop");
                        }
                        if ("title".equals(name))
                            title = parser.nextText().trim();
                        break;
                    case XmlPullParser.END_TAG:
                        if ("programme".equals(name) && tvgName.equals(currentChannel)) {
                            programs.add(new EpgParser.Program(start, stop, title));
                        }
                        break;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return programs;
    }

    public static class Program {
        public String start;
        public String stop;
        public String title;
        public Program(String start, String stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }
}

// 4. 时间工具
public static class TimeUtil {
    public static String format(String time) {
        if (time == null || time.length() < 14) return "00:00";
        return time.substring(8, 10) + ":" + time.substring(10, 12);
    }

    public static int getProgress(String start, String stop) {
        try {
            long s = parse(start);
            long e = parse(stop);
            long n = System.currentTimeMillis();
            if (s >= e) return 0;
            return (int) ((n - s) * 100 / (e - s));
        } catch (Exception e) {
            return 0;
        }
    }

    public static int getRemainMinutes(String stop) {
        try {
            long e = parse(stop);
            long n = System.currentTimeMillis();
            return (int) ((e - n) / 60000);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parse(String time) throws Exception {
        int y = Integer.parseInt(time.substring(0, 4));
        int M = Integer.parseInt(time.substring(4, 6)) - 1;
        int d = Integer.parseInt(time.substring(6, 8));
        int h = Integer.parseInt(time.substring(8, 10));
        int m = Integer.parseInt(time.substring(10, 12));
        return new java.util.Calendar.Builder()
                .setDate(y, M, d)
                .setTimeOfDay(h, m, 0)
                .build()
                .getTimeInMillis();
    }
}

// 5. 对外数据结构
public static class PlayInfo {
    public String channelName;
    public String tvgName;
    public String currentTitle;
    public String currentTime;
    public String nextTitle;
    public String nextTime;
    public int progress;
    public int remainMin;
}

public interface OnPlayInfoListener {
    void onSuccess(PlayInfo info);
    void onFail();
}

// 6. 加载节目信息
public void loadPlayInfo(String playUrl, OnPlayInfoListener listener) {
    new Thread(() -> {
        try {
            URL m3uUrl = new URL("https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
            java.io.InputStream is = m3uUrl.openStream();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            StringBuilder m3uContent = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) m3uContent.append(line).append("\n");
            br.close();

            ArrayList<M3uParser.Channel> channels = M3uParser.parse(m3uContent.toString());
            M3uParser.Channel current = null;
            for (M3uParser.Channel ch : channels) {
                if (playUrl.startsWith(ch.url)) {
                    current = ch;
                    break;
                }
            }

            if (current == null) {
                new Handler(Looper.getMainLooper()).post(listener::onFail);
                return;
            }

            URL epgUrl = new URL("https://epg.catvod.com/epg.xml");
            java.io.InputStream eis = epgUrl.openStream();
            java.io.BufferedReader ebr = new java.io.BufferedReader(new java.io.InputStreamReader(eis));
            StringBuilder xml = new StringBuilder();
            while ((line = ebr.readLine()) != null) xml.append(line).append("\n");
            ebr.close();

            ArrayList<EpgParser.Program> programs = EpgParser.parse(xml.toString(), current.tvgName);
            PlayInfo info = new PlayInfo();
            info.channelName = current.channelName;
            info.tvgName = current.tvgName;

            if (programs.size() >= 1) {
                EpgParser.Program now = programs.get(0);
                info.currentTitle = now.title;
                info.currentTime = TimeUtil.format(now.start) + " - " + TimeUtil.format(now.stop);
                info.progress = TimeUtil.getProgress(now.start, now.stop);
                info.remainMin = TimeUtil.getRemainMinutes(now.stop);
            }
            if (programs.size() >= 2) {
                EpgParser.Program next = programs.get(1);
                info.nextTitle = next.title;
                info.nextTime = TimeUtil.format(next.start) + " - " + TimeUtil.format(next.stop);
            }

            new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(info));
        } catch (Exception e) {
            e.printStackTrace();
            new Handler(Looper.getMainLooper()).post(listener::onFail);
        }
    }).start();
}

// 7. 自动刷新（30秒）
private Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
private Runnable autoRefreshRunnable;
private String mCurrentPlayUrl;
private OnPlayInfoListener mRefreshListener;

public void startAutoRefresh(String playUrl, OnPlayInfoListener listener) {
    mCurrentPlayUrl = playUrl;
    mRefreshListener = listener;
    stopAutoRefresh();
    autoRefreshRunnable = () -> {
        loadPlayInfo(mCurrentPlayUrl, mRefreshListener);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 30000);
    };
    autoRefreshHandler.post(autoRefreshRunnable);
}

public void stopAutoRefresh() {
    if (autoRefreshRunnable != null) {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }
}

// 8. 释放时停止刷新（防内存泄漏）
public void releaseAll() {
    stopAutoRefresh();
    release();
}
// ==================== 【完整版追加结束】 ====================
