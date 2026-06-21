package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tv.live.util.CacheManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * ✅ EPG节目单管理器（带缓存 + 智能匹配 + 内存优化版）
 *
 * 【2026-06-21 内存优化 V2】
 * 【优化原因】
 * V1 版虽然改成了流式读写，但是自己管理文件路径，
 * 没有复用 CacheManager 的缓存有效期逻辑，路径也不统一。
 *
 * 【V2 优化方案】
 * 1. 使用 CacheManager 的流式方法保存和读取缓存
 * 2. 自动复用 CacheManager 的 24 小时有效期逻辑
 * 3. 缓存路径统一，和直播源等其他缓存保持一致
 * 4. 内存占用仍然只有几 KB，彻底解决 OOM
 *
 * 【缓存策略】
 * 1. 加载成功后，自动保存原始XML文本到本地缓存
 * 2. 缓存有效期24小时（由 CacheManager 统一管理）
 * 3. 进入APP时先读缓存快速显示，后台再刷新最新的
 *
 * 【频道匹配策略】
 * 1. 先尝试精确匹配
 * 2. 精确匹配失败，尝试模糊匹配
 * 3. 计算匹配度分数，返回分数最高的
 * 4. 支持去掉 HD/高清/4K/卫视/频道 等干扰字符
 * 5. 支持中文数字转阿拉伯数字
 */
public class EpgManager {

    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false;

    // 缓存管理器
    private CacheManager cacheManager;
    // 上下文
    private Context context;

    // 缓存 key
    private static final String CACHE_KEY_EPG = "epg";

    /**
     * 获取单例（带Context初始化）
     * 第一次调用时传入Context，后续不用再传
     */
    public static EpgManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new EpgManager(ctx.getApplicationContext());
        }
        return instance;
    }

    /**
     * 兼容旧代码的无参方法
     * 注意：第一次调用必须用带Context的版本
     */
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

    /**
     * 从M3U直播源中提取EPG地址
     */
    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(() -> {
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                SettingsActivity.log("【EPG】📡 从直播源获取到EPG地址：" + extractedEpgUrl);
                epgUrl = extractedEpgUrl;
            } else {
                SettingsActivity.log("【EPG】📡 直播源未指定EPG地址，使用默认：" + epgUrl);
            }
            loadEpg(callback);
        }).start();
    }

    /**
     * 从M3U中提取x-tvg-url属性
     */
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
            SettingsActivity.log("【EPG】从M3U提取EPG地址失败：" + e.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ====================================================================
    // ✅ V2 优化版：加载EPG（CacheManager 流式保存 + 流式解析）
    // ====================================================================

    /**
     * ✅ 加载EPG节目单（带缓存 + 内存优化版 V2）
     *
     * 【V2 优化】
     * 使用 CacheManager 的流式方法保存缓存，统一管理，自动支持有效期。
     *
     * 【流程】
     * 1. 从网络下载，解压（如果是 gz）
     * 2. 用 CacheManager 流式保存到缓存文件
     * 3. 从 CacheManager 流式读取缓存，解析 XML
     * 4. 回调通知
     *
     * 【内存占用】
     * 峰值只有几 KB（缓冲区大小），彻底解决 OOM
     */
    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;

            try {
                // ================================================================
                // 第一步：从网络下载
                // ================================================================
                URL url = new URL(epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                in = conn.getInputStream();

                // 处理GZIP压缩
                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                // ================================================================
                // 第二步：用 CacheManager 流式保存到缓存
                // ================================================================
                // 【注意】
                // InputStream 只能读一次，所以这里我们需要先把数据保存到缓存，
                // 然后再从缓存读取出来解析。
                // 虽然读了两次（一次写磁盘，一次读磁盘），但是内存占用仍然很小。
                long savedBytes = cacheManager.saveFileCache(CACHE_KEY_EPG, in);
                if (savedBytes <= 0) {
                    SettingsActivity.log("【EPG】❌ 保存缓存失败");
                    return;
                }

                SettingsActivity.log("【EPG】下载完成，大小：" + (savedBytes / 1024) + " KB");
                SettingsActivity.log("【EPG】缓存已保存（有效期24小时）");

                // ================================================================
                // 第三步：从 CacheManager 流式读取缓存，解析 XML
                // ================================================================
                hasPrintedSample = false;
                channelEpgMap.clear();

                InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
                if (cacheIs == null) {
                    SettingsActivity.log("【EPG】❌ 读取缓存失败");
                    return;
                }

                try {
                    parseXml(cacheIs);
                } finally {
                    cacheIs.close();
                }

                SettingsActivity.log("【EPG】✅ 加载完成，共" + channelEpgMap.size() + "个频道");

            } catch (Exception e) {
                SettingsActivity.log("【EPG】❌ 加载失败：" + e.getMessage());
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

    // ====================================================================
    // ✅ V2 优化版：从缓存加载EPG（CacheManager 流式读取）
    // ====================================================================

    /**
     * 从缓存加载EPG（内存优化版）
     * 用于进入APP时快速显示
     *
     * 【说明】
     * 使用 CacheManager 的流式读取方法，
     * CacheManager 会自动判断缓存是否过期（24小时）。
     *
     * @return 是否加载成功
     */
    public boolean loadEpgFromCache() {
        try {
            // 用 CacheManager 的流式方法读取，自动判断有效期
            InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
            if (cacheIs == null) {
                return false; // 缓存不存在或已过期
            }

            SettingsActivity.log("【EPG】从缓存加载...");
            hasPrintedSample = false;
            channelEpgMap.clear();

            try {
                parseXml(cacheIs);
            } finally {
                cacheIs.close();
            }

            SettingsActivity.log("【EPG】缓存加载完成，共" + channelEpgMap.size() + "个频道");
            return true;

        } catch (Exception e) {
            SettingsActivity.log("【EPG】缓存加载失败：" + e.getMessage());
            return false;
        }
    }

    // ====================================================================
    // 解析XML节目单（不用改，本来就是流式解析的）
    // ====================================================================

    /**
     * 解析XML节目单
     *
     * 【说明】
     * XmlPullParser 本身就是流式解析的，边读边解析，
     * 只要传入的 InputStream 是文件流，内存占用就很小。
     */
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        Calendar todayCheck = Calendar.getInstance();
        SettingsActivity.log("【EPG】📅 今天日期：" + todayCheck.get(Calendar.YEAR) + "-"
                + (todayCheck.get(Calendar.MONTH) + 1) + "-" + todayCheck.get(Calendar.DAY_OF_MONTH)
                + "（周" + new String[]{"日","一","二","三","四","五","六"}[todayCheck.get(Calendar.DAY_OF_WEEK)-1] + "）");

        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();
        int programCount = 0;

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    currentChannelName = null;
                    tempPrograms.clear();
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

                        if (!hasPrintedSample && programCount < 5) {
                            SettingsActivity.log("【EPG】🔍 样本" + (programCount + 1)
                                    + "：原始时间=" + originalStart
                                    + "，解析日期=" + (startCal.get(Calendar.MONTH) + 1) + "月" + startCal.get(Calendar.DAY_OF_MONTH) + "日"
                                    + "，周" + new String[]{"日","一","二","三","四","五","六"}[startCal.get(Calendar.DAY_OF_WEEK)-1]
                                    + "，dayName=" + dayName);
                            programCount++;
                            if (programCount >= 5) hasPrintedSample = true;
                        }

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);

                    } catch (Exception e) {
                        SettingsActivity.log("【EPG】跳过异常时间：" + start + "，错误：" + e.getMessage());
                    }
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                if (currentChannelName != null && !tempPrograms.isEmpty()) {
                    tempPrograms.sort(Comparator.comparing(item -> item.time));
                    channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                }
            }

            xml.next();
        }

        int count = 0;
        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            if (count >= 3) break;
            Set<String> days = new HashSet<>();
            for (Channel.EpgItem item : entry.getValue()) {
                days.add(item.dayName);
            }
            SettingsActivity.log("【EPG】频道【" + entry.getKey() + "】包含日期：" + days + "，节目数：" + entry.getValue().size());
            count++;
        }
    }

    // ====================================================================
    // 频道匹配相关（不用改）
    // ====================================================================

    /**
     * ✅ 根据频道名获取 EPG 节目列表（增强版：智能模糊匹配）
     *
     * 【匹配策略】
     * 1. 先尝试精确匹配
     * 2. 精确匹配失败，尝试模糊匹配
     * 3. 计算匹配度分数，返回分数最高的
     * 4. 匹配失败返回空列表
     */
    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 先尝试精确匹配
        if (channelEpgMap.containsKey(channelName)) {
            SettingsActivity.log("【EPG】精确匹配成功：" + channelName);
            return channelEpgMap.get(channelName);
        }
        
        // 标准化输入的频道名
        String cleanName = normalizeChannelName(channelName);
        
        // 模糊匹配，找到最匹配的频道
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
            // 匹配度大于 20 分才算匹配成功
            SettingsActivity.log("【EPG】模糊匹配成功：" + channelName 
                    + " → " + bestMatch 
                    + "（匹配度：" + bestScore + "分）");
            return channelEpgMap.get(bestMatch);
        }
        
        // 都匹配失败
        SettingsActivity.log("【EPG】⚠️ 匹配失败：" + channelName 
                + "（标准化后：" + cleanName + "）"
                + "，共尝试 " + channelEpgMap.size() + " 个频道");
        return new ArrayList<>();
    }

    /**
     * ✅ 标准化频道名称
     * 去掉各种干扰字符，统一格式，方便匹配
     */
    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        
        String result = name.toLowerCase();
        
        // 1. 去掉画质后缀
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
        
        // 2. 去掉特殊字符
        result = result.replace(" ", "");
        result = result.replace("-", "");
        result = result.replace("_", "");
        result = result.replace(".", "");
        result = result.replace("·", "");
        result = result.replace(":", "");
        result = result.replace("：", "");
        
        // 3. 去掉"频道"、"卫视"、"电视台"等后缀
        result = result.replace("频道", "");
        result = result.replace("卫视", "");
        result = result.replace("电视台", "");
        result = result.replace("台", "");
        result = result.replace("传媒", "");
        
        // 4. 中文数字转阿拉伯数字
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
        
        // 5. CCTV 统一成"央视"
        result = result.replace("cctv", "央视");
        
        return result;
    }

    /**
     * ✅ 计算两个标准化后字符串的匹配度分数
     */
    private int calculateMatchScore(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }
        
        // 完全匹配：100 分
        if (s1.equals(s2)) {
            return 100;
        }
        
        // 互相包含：根据长度比例给分
        if (s1.contains(s2) || s2.contains(s1)) {
            int minLen = Math.min(s1.length(), s2.length());
            int maxLen = Math.max(s1.length(), s2.length());
            return 50 + (minLen * 40 / maxLen);
        }
        
        // 有共同前缀：根据前缀长度给分
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

    /**
     * 用 Calendar 直接比较日期，彻底解决毫秒差精度问题
     */
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
}
