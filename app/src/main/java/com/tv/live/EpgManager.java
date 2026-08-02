package com.tv.live;

import android.annotation.SuppressLint; // 🟢 新增导入
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.util.CacheManager;
// 🔧【清理一起看】已移除：import com.tv.live.manager.HuyaTogetherWatchManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * ✅ EPG节目单管理器（带缓存 + 智能匹配 + 内存优化版）
 */
@SuppressLint("StaticFieldLeak") // 🟢 忽略 Lint 静态字段持有 ApplicationContext 的安全警告
public class EpgManager {

    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new ConcurrentHashMap<>();
    // 🟢【虎牙一起看专属】直播源拉取同步写入的伪EPG存储（按频道名Key，比XML优先级高）
    private final Map<String, List<Channel.EpgItem>> huyaGeneratedEpgMap = new ConcurrentHashMap<>();
    
    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false;

    private CacheManager cacheManager;
    private Context context;

    private final Map<String, String> normalizedNameCache = new ConcurrentHashMap<>();

    private static final String CACHE_KEY_EPG = "epg";

    public static EpgManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new EpgManager(ctx.getApplicationContext());
        }
        return instance;
    }

    public static EpgManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("EpgManager 未初始化，请先调用 getInstance(Context)");
        }
        return instance;
    }

    private EpgManager(Context ctx) {
        this.context = ctx;
        this.cacheManager = CacheManager.getInstance(ctx);
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(() -> {
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                epgUrl = extractedEpgUrl;
            }
            loadEpg(callback);
        }).start();
    }

    private String extractEpgUrlFromM3u(String m3uUrl) {
        BufferedReader reader = null;
        try (okhttp3.Response response = com.tv.live.util.NetUtil.getInstance().syncGet(m3uUrl)) {
            if (!response.isSuccessful() || response.body() == null) return null;
            InputStream is = response.body().byteStream();
            if (m3uUrl.endsWith(".gz")) {
                is = new GZIPInputStream(is);
            }
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                if (line.contains("x-tvg-url") || line.contains("tvgtvg-url")) {
                    int start = line.indexOf("\"");
                    if (start >= 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                    String[] parts = line.split("x-tvg-url=");
                    if (parts.length >= 2) {
                        String urlPart = parts[1].trim();
                        if (urlPart.startsWith("\"")) urlPart = urlPart.substring(1);
                        int spaceIdx = urlPart.indexOf(" ");
                        if (spaceIdx > 0) urlPart = urlPart.substring(0, spaceIdx);
                        if (urlPart.endsWith("\"")) urlPart = urlPart.substring(0, urlPart.length() - 1);
                        return urlPart.trim();
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            try (okhttp3.Response response = com.tv.live.util.NetUtil.getInstance().syncGet(epgUrl)) {
                if (!response.isSuccessful() || response.body() == null) return;

                InputStream rawIn = response.body().byteStream();
                InputStream in = epgUrl.endsWith(".gz") ? new GZIPInputStream(rawIn) : rawIn;

                try {
                    long savedBytes = cacheManager.saveFileCache(CACHE_KEY_EPG, in);
                    if (savedBytes <= 0) {
                        return;
                    }

                    hasPrintedSample = false;
                    channelEpgMap.clear();

                    InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
                    if (cacheIs == null) {
                        return;
                    }

                    try {
                        parseXml(cacheIs);
                    } finally {
                        cacheIs.close();
                    }
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    public boolean loadEpgFromCache() {
        try {
            InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
            if (cacheIs == null) {
                return false;
            }

            hasPrintedSample = false;
            channelEpgMap.clear();

            try {
                parseXml(cacheIs);
            } finally {
                cacheIs.close();
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================
    // 🟢【核心修复】parseXml 方法，修复了节目单被逐条覆盖导致空白的问题
    // ====================================================================
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // 🟢【关键修复】添加 Locale.US，解决 SimpleDateFormat 区域设置警告
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        sdf.setLenient(true);

        Calendar todayCheck = Calendar.getInstance();
        Calendar maxDate = Calendar.getInstance();
        maxDate.add(Calendar.DAY_OF_YEAR, 3);

        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    // 🟢【关键修复】检测到新频道时，先保存前一个频道的完整节目列表
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                        tempPrograms.clear();
                    }
                    currentChannelName = null;
                }

                if ("display-name".equals(tag)) {
                    currentChannelName = xml.nextText().trim();
                }

                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    if (start == null || stop == null) continue;

                    try {
                        String originalStart = start;
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        if (startCal.after(maxDate)) {
                            continue;
                        }

                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);

                    } catch (Exception e) {
                    }
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            // 🟢【移除】去掉了原来 END_TAG 为 "programme" 时立即保存并清空的逻辑，
            // 避免每读一个节目就覆盖一次之前的数据。
            xml.next();
        }

        // 🟢【关键修复】解析结束后，保存最后一个频道的节目列表
        if (currentChannelName != null && !tempPrograms.isEmpty()) {
            channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
        }
    }

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        // ⚠️ 严格遵守用户：只改虎牙的，自己的不要动
        // 通过 String channelName 无法判断是不是虎牙频道，直接走原有 XML EPG 逻辑
        // （虎牙播放历史EPG只在 getEpg(Channel) 里通过 isTogetherWatch / huyaRoomId 判断）

        if (channelEpgMap.containsKey(channelName)) {
            return channelEpgMap.get(channelName);
        }

        String cleanName = normalizeChannelName(channelName);

        String bestMatch = null;
        int bestScore = 0;

        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String epgName = entry.getKey();
            String cleanEpgName = normalizeChannelName(epgName);

            int score = calculateMatchScore(cleanName, cleanEpgName);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = epgName;
            }
        }

        if (bestMatch != null && bestScore >= 20) {
            return channelEpgMap.get(bestMatch);
        }

        return new ArrayList<>();
    }

    public List<Channel.EpgItem> getEpg(Channel channel) {
        if (channel == null) {
            return new ArrayList<>();
        }

        // 🟢 严格遵守用户：只改虎牙的，自己的不要动
        //  → 只有虎牙TogetherWatch / 带huyaRoomId的频道，才走"播放历史EPG"
        //  → 用户自己的m3u直播源（CCTV/卫视等）完全走下面的 getEpg(name) → XML EPG 老逻辑
        boolean isHuyaChannel = (channel.isTogetherWatch() || channel.getHuyaRoomId() > 0);
        if (isHuyaChannel) {
            List<Channel.EpgItem> history = getPlaybackHistoryEpg(channel);
            if (history != null && !history.isEmpty()) {
                return history;
            }
        }

        return getEpg(channel.getName());
    }

    // ====================================================================
    // 🟢【播放历史EPG】：严格遵守用户最新要求 —— "只要显示播放之前的节目就可以了，
    //                    随时可以调用回放"。不伪造未来，只记录真实播过的片段。
    // ====================================================================

    /** 单个历史播放片段的内部记录（带绝对时间戳，方便排序 / 计算时长 / 回放 seek） */
    public static class PlaybackSegment {
        public final String title;       // 播放时的房间标题 / 节目名
        public final long startTimeMs;   // 播放开始的绝对时间戳（ms）
        public final long endTimeMs;     // 结束时间戳（-1 表示正在播放中）
        public PlaybackSegment(String title, long startTimeMs, long endTimeMs) {
            this.title = title; this.startTimeMs = startTimeMs; this.endTimeMs = endTimeMs;
        }
    }

    /** Map Key = 频道唯一标识（用 huyaRoomId 或 channelName）→ 该频道按时间排序的播放片段列表 */
    private final Map<String, List<PlaybackSegment>> playbackHistoryMap = new ConcurrentHashMap<>();
    /** Map Key = 与上面一致 → 正在播放中的片段 startTs（null 表示当前没在播） */
    private final Map<String, Long> playingStartMap = new ConcurrentHashMap<>();
    /** Map Key = 与上面一致 → 正在播放中的 title（用于最后更新"播放中"那条） */
    private final Map<String, String> playingTitleMap = new ConcurrentHashMap<>();
    // 🟢 异步解析完成后，EPG面板需要立刻刷新的回调（否则第一次进入虎牙频道时，解析还没结束，面板显示空白）
    private final Set<OnHuyaEpgReadyListener> huyaEpgReadyListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<OnHuyaEpgReadyListener, Boolean>());

    /** 虎牙解析完成、startPlayback写入成功的通知回调（用于刷新右侧EPG面板） */
    public interface OnHuyaEpgReadyListener {
        void onHuyaEpgReady(Channel channel);
    }
    public void registerHuyaEpgReadyListener(OnHuyaEpgReadyListener listener) {
        if (listener != null) huyaEpgReadyListeners.add(listener);
    }
    public void unregisterHuyaEpgReadyListener(OnHuyaEpgReadyListener listener) {
        if (listener != null) huyaEpgReadyListeners.remove(listener);
    }
    private void notifyHuyaEpgReady(Channel channel) {
        if (channel == null || huyaEpgReadyListeners.isEmpty()) return;
        for (OnHuyaEpgReadyListener l : huyaEpgReadyListeners) {
            try { l.onHuyaEpgReady(channel); } catch (Exception ignored) {}
        }
    }

    private static String channelKey(Channel ch) {
        if (ch == null) return "";
        // 虎牙房间按 roomId 做唯一标识，其他频道用名称
        if (ch.getHuyaRoomId() > 0) return "huya:" + ch.getHuyaRoomId();
        if (!TextUtils.isEmpty(ch.getChannelId())) return "id:" + ch.getChannelId();
        return "name:" + ch.getName();
    }
    private static String channelKey(String channelName) {
        return "name:" + (channelName == null ? "" : channelName);
    }

    /**
     * 🟢 开始播放某频道时调用 —— 把上一段正在"播放中"的片段闭合 endTime，
     *    并把新 title 记入 playingStartMap/playingTitleMap，形成"正在播放"状态。
     *  EpgManagerWrapper 会把这条标记 isPlaying=true，action 按钮显示"播放中"。
     *
     *  写入成功后会触发 OnHuyaEpgReadyListener 通知 ChannelPanelController 立即刷新右侧EPG面板，
     *  避免"第一次进入虎牙频道时，解析还没结束，EPG显示空白"的问题。
     */
    public void startPlayback(Channel ch, String programTitle) {
        if (ch == null) return;
        String key = channelKey(ch);
        long now = System.currentTimeMillis();
        String safeTitle = TextUtils.isEmpty(programTitle) ? (ch.getName() + " · 正在直播") : programTitle;

        // 先把前一段正在播放中的闭合掉
        Long prevStart = playingStartMap.remove(key);
        String prevTitle = playingTitleMap.remove(key);
        if (prevStart != null && now - prevStart >= 30_000L) { // ≥30秒才落历史，避免反复切换产生碎片
            List<PlaybackSegment> list = playbackHistoryMap.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
            list.add(new PlaybackSegment(prevTitle == null ? ch.getName() : prevTitle, prevStart, now));
        }

        playingStartMap.put(key, now);
        playingTitleMap.put(key, safeTitle);

        // 🔴 严格遵守：只对虎牙频道发通知刷新EPG面板，其他频道不动
        boolean isHuya = (ch.isTogetherWatch() || ch.getHuyaRoomId() > 0);
        if (isHuya) {
            notifyHuyaEpgReady(ch);
        }
    }

    /** 🟢 播放器 STOP / 切换频道前调用 —— 把当前"播放中"片段闭合 endTime，形成一段完整的历史 */
    public void stopPlayback(Channel ch) {
        if (ch == null) return;
        String key = channelKey(ch);
        Long start = playingStartMap.remove(key);
        String title = playingTitleMap.remove(key);
        if (start == null) return;
        long now = System.currentTimeMillis();
        if (now - start < 30_000L) return; // <30秒丢弃
        List<PlaybackSegment> list = playbackHistoryMap.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        list.add(new PlaybackSegment(title == null ? ch.getName() : title, start, now));
    }

    /** 🟢 用于获取"播放历史EPG" —— 只显示该频道在本APP会话内真实播放过的片段（+当前正在播放的那条） */
    private List<Channel.EpgItem> getPlaybackHistoryEpg(Channel ch) {
        if (ch == null) return null;
        return buildHistoryEpgFromStore(channelKey(ch), ch.getName());
    }
    private List<Channel.EpgItem> getPlaybackHistoryEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        return buildHistoryEpgFromStore(channelKey(channelName), channelName);
    }

    private List<Channel.EpgItem> buildHistoryEpgFromStore(String key, String fallbackName) {
        List<PlaybackSegment> raw = playbackHistoryMap.get(key);
        List<PlaybackSegment> segments = new ArrayList<>();
        if (raw != null) segments.addAll(raw);

        // 加上"正在播放中"的那条（还未闭合）
        Long playingStart = playingStartMap.get(key);
        String playingTitle = playingTitleMap.get(key);
        boolean hasPlaying = (playingStart != null);
        if (hasPlaying) {
            segments.add(new PlaybackSegment(
                    playingTitle == null ? fallbackName : playingTitle,
                    playingStart, -1L));
        }
        if (segments.isEmpty()) return null;

        // 按开始时间升序排，保证 EPG 列表从上到下"早→晚"
        Collections.sort(segments, (a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));

        SimpleDateFormat sdfHm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        Calendar today = Calendar.getInstance();
        List<Channel.EpgItem> result = new ArrayList<>();
        for (PlaybackSegment seg : segments) {
            boolean isPlaying = (seg.endTimeMs < 0);
            String startHm = sdfHm.format(new java.util.Date(seg.startTimeMs));
            String endHm;
            if (isPlaying) endHm = "直播中";
            else endHm = sdfHm.format(new java.util.Date(seg.endTimeMs));

            Channel.EpgItem item = new Channel.EpgItem(
                    "今天",
                    startHm + " - " + endHm,
                    seg.title,
                    isPlaying
            );
            result.add(item);
        }
        return result;
    }

    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (normalizedNameCache.containsKey(name)) {
            return normalizedNameCache.get(name);
        }

        // 🟢【关键修复】添加 Locale.ROOT，解决 toLowerCase 区域设置警告
        String result = name.toLowerCase(Locale.ROOT);

        result = result.replaceAll("(?i)hd", "");
        result = result.replaceAll("(?i)fhd", "");
        result = result.replaceAll("(?i)uhd", "");
        result = result.replaceAll("(?i)sdtv", "");
        result = result.replaceAll("(?i)hdtv", "");
        result = result.replace("高清", "");
        result = result.replace("超清", "");
        result = result.replace("标清", "");
        result = result.replace("4k", "");
        result = result.replace("8k", "");

        result = result.replace(" ", "");
        result = result.replace("-", "");
        result = result.replace("_", "");
        result = result.replace(".", "");
        result = result.replace("·", "");
        result = result.replace(":", "");
        result = result.replace("：", "");

        result = result.replace("频道", "");
        result = result.replace("卫视", "");
        result = result.replace("电视台", "");
        result = result.replace("台", "");
        result = result.replace("传媒", "");

        result = result.replace("一套", "1套");
        result = result.replace("二套", "2套");
        result = result.replace("三套", "3套");
        result = result.replace("四套", "4套");
        result = result.replace("五套", "5套");
        result = result.replace("六套", "6套");
        result = result.replace("七套", "7套");
        result = result.replace("八套", "8套");
        result = result.replace("九套", "9套");
        result = result.replace("十套", "10套");
        result = result.replace("十一", "11");
        result = result.replace("十二", "12");
        result = result.replace("十三", "13");
        result = result.replace("十四", "14");
        result = result.replace("十五", "15");

        result = result.replace("cctv", "央视");

        normalizedNameCache.put(name, result);
        return result;
    }

    private int calculateMatchScore(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }

        if (s1.equals(s2)) {
            return 100;
        }

        if (s1.contains(s2) || s2.contains(s1)) {
            int minLen = Math.min(s1.length(), s2.length());
            int maxLen = Math.max(s1.length(), s2.length());
            return 50 + (minLen * 40 / maxLen);
        }

        int prefixLen = 0;
        int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefixLen++;
            } else {
                break;
            }
        }
        if (prefixLen >= 2) {
            return prefixLen * 5;
        }

        return 0;
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY, 0);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);

        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);

        if (itemDay.get(Calendar.YEAR) == todayDay.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == todayDay.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }

        Calendar tomorrow = Calendar.getInstance();
        tomorrow.setTime(todayDay.getTime());
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        if (itemDay.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)) {
            return "明天";
        }

        Calendar dayAfter = Calendar.getInstance();
        dayAfter.setTime(todayDay.getTime());
        dayAfter.add(Calendar.DAY_OF_YEAR, 2);
        if (itemDay.get(Calendar.YEAR) == dayAfter.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == dayAfter.get(Calendar.DAY_OF_YEAR)) {
            return "后天";
        }

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }

    public int getChannelEpgMapSize() {
        return channelEpgMap.size();
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
