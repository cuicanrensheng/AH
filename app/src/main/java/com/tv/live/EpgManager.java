package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tv.live.util.CacheManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * ✅ EPG节目单管理器（带缓存 + 智能匹配 + 内存优化版）
 */
public class EpgManager {

    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new ConcurrentHashMap<>();
    
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
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(m3uUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();
            InputStream is = conn.getInputStream();
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
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;

            try {
                URL url = new URL(epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                in = conn.getInputStream();

                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

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

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
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

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        Calendar todayCheck = Calendar.getInstance();

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

    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (normalizedNameCache.containsKey(name)) {
            return normalizedNameCache.get(name);
        }

        String result = name.toLowerCase();

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
}
