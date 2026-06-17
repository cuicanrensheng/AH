package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false; // 只打印前5个节目样本，避免日志太多

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    /**
     * ✅ 【新增】从M3U直播源文件中自动提取EPG地址并加载
     * 
     * 原理：M3U文件的 #EXTM3U 行通常会带 x-tvg-url 属性，指定对应的EPG地址
     * 比如：#EXTM3U x-tvg-url="https://xxx.com/epg.xml.gz"
     * 
     * 优点：换直播源时自动切换对应的EPG，不用手动配置
     * 如果直播源里没有指定EPG地址，会自动使用默认的 UrlConfig.EPG_URL
     * 
     * @param m3uUrl M3U直播源地址
     * @param callback 加载完成后的回调（主线程执行）
     */
    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(() -> {
            // 第一步：从M3U文件中提取EPG地址
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                SettingsActivity.log("【EPG】📡 从直播源获取到EPG地址：" + extractedEpgUrl);
                epgUrl = extractedEpgUrl;
            } else {
                SettingsActivity.log("【EPG】📡 直播源未指定EPG地址，使用默认：" + epgUrl);
            }
            
            // 第二步：加载EPG（复用原有的加载逻辑）
            loadEpg(callback);
        }).start();
    }

    /**
     * 从M3U文件头部提取 x-tvg-url 属性
     * 
     * 只读取前10行，因为x-tvg-url一定在#EXTM3U那一行（文件开头）
     * 支持两种格式：
     * - x-tvg-url="https://xxx.com/epg.xml" （带引号，最常见）
     * - x-tvg-url=https://xxx.com/epg.xml （不带引号）
     * 
     * @param m3uUrl M3U文件地址
     * @return 提取到的EPG地址，提取失败返回null
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
            // 如果是.gz压缩的M3U，自动解压
            if (m3uUrl.endsWith(".gz")) {
                is = new GZIPInputStream(is);
            }

            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lineCount = 0;
            
            // 只看前10行，x-tvg-url一定在开头
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                
                // 找到包含x-tvg-url的行
                if (line.contains("x-tvg-url") || line.contains("tvgtvg-url")) {
                    
                    // 方式1：提取引号中的URL（最常见）
                    int start = line.indexOf("\"");
                    if (start >= 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                    
                    // 方式2：= 后面直接跟URL（不带引号）
                    String[] parts = line.split("x-tvg-url=");
                    if (parts.length >= 2) {
                        String urlPart = parts[1].trim();
                        // 去掉开头的引号
                        if (urlPart.startsWith("\"")) {
                            urlPart = urlPart.substring(1);
                        }
                        // 遇到空格就截止（后面还有其他属性）
                        int spaceIdx = urlPart.indexOf(" ");
                        if (spaceIdx > 0) {
                            urlPart = urlPart.substring(0, spaceIdx);
                        }
                        // 去掉结尾的引号
                        if (urlPart.endsWith("\"")) {
                            urlPart = urlPart.substring(0, urlPart.length() - 1);
                        }
                        return urlPart.trim();
                    }
                }
            }
        } catch (Exception e) {
            SettingsActivity.log("【EPG】从M3U提取EPG地址失败：" + e.getMessage());
        } finally {
            // 关闭资源
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

                hasPrintedSample = false;
                parseXml(in);
                SettingsActivity.log("【EPG】✅ 加载完成，共" + channelEpgMap.size() + "个频道");
            } catch (Exception e) {
                SettingsActivity.log("【EPG】❌ 加载失败：" + e.getMessage());
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

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        // 打印今天的日期，确认基准是否正确
        Calendar todayCheck = Calendar.getInstance();
        SettingsActivity.log("【EPG】📅 今天日期：" + todayCheck.get(Calendar.YEAR) + "-"
                + (todayCheck.get(Calendar.MONTH) + 1) + "-" + todayCheck.get(Calendar.DAY_OF_MONTH)
                + "（一年中的第" + todayCheck.get(Calendar.DAY_OF_YEAR) + "天）");

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
                        String originalStart = start; // 保存原始字符串

                        // 去掉时区后缀，只取前14位
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        // 只打印前5个节目样本，方便排查
                        if (!hasPrintedSample && programCount < 5) {
                            SettingsActivity.log("【EPG】🔍 样本" + (programCount + 1)
                                    + "：原始时间=" + originalStart
                                    + "，截取后=" + start
                                    + "，解析日期=" + (startCal.get(Calendar.MONTH) + 1) + "月" + startCal.get(Calendar.DAY_OF_MONTH) + "日"
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

        // 打印前3个频道的日期分布
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

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        String cleanName = channelName.replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();

        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String key = entry.getKey().replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
            if (cleanName.contains(key) || key.contains(cleanName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }

    /**
     * ✅ 毫秒差计算天数，彻底解决跨年、跨月、时区问题
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

        long diffMs = itemDay.getTimeInMillis() - todayDay.getTimeInMillis();
        int dayDiff = (int) (diffMs / (1000L * 60 * 60 * 24));

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;

        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";
        return weekDays[dayOfWeek];
    }
}
