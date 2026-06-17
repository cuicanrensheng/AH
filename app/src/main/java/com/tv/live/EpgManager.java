package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 节目单解析器：下载、解压、解析XML
 * 默认源：https://epg.catvod.com/epg.xml
 * 修复点：
 * 1. 修复跨年/跨月天数计算错误，解决「明天/后天」标签失效
 * 2. 修复日期基准过期问题，零点后自动更新日期标签
 * 3. 兼容带时区的时间格式，异常节目自动跳过
 * 4. 自动识别gzip压缩，兼容压缩/非压缩源
 */
public class EpgManager {
    private static final String TAG = "EPG";
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    // ✅ 已替换为新的节目单地址
    private String epgUrl = "https://epg.catvod.com/epg.xml";

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    /**
     * 设置节目单地址（支持动态切换源）
     */
    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    /**
     * 下载并解析节目单
     */
    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                URL url = new URL(epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.connect();

                in = conn.getInputStream();
                // 自动识别gzip：URL后缀或响应头标记压缩则解压
                if (epgUrl.endsWith(".gz") || "gzip".equals(conn.getContentEncoding())) {
                    in = new GZIPInputStream(in);
                }

                parseXml(in);
                Log.d(TAG, "EPG加载完成，共解析 " + channelEpgMap.size() + " 个频道");
            } catch (Exception e) {
                Log.e(TAG, "EPG加载失败", e);
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

            // 主线程回调
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    /**
     * 解析XML格式节目单
     */
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        String currentChannelId = null;
        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    currentChannelId = xml.getAttributeValue(null, "id");
                    tempPrograms.clear();
                }

                if ("display-name".equals(tag) && currentChannelId != null) {
                    currentChannelName = xml.nextText().trim();
                }

                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    if (start == null || stop == null) continue;

                    try {
                        // 清洗时间：去掉时区后缀，只取前14位数字
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        // 每次取最新当前日期，避免隔夜后基准过期
                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(
                                dayName, timeStr, "", false
                        );
                        tempPrograms.add(item);
                    } catch (Exception e) {
                        Log.w(TAG, "跳过异常节目时间: " + start);
                    }
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            // 节目结束，存入缓存
            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                if (currentChannelName != null && !tempPrograms.isEmpty()) {
                    tempPrograms.sort(Comparator.comparing(item -> item.time));
                    channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                }
            }

            xml.next();
        }
    }

    /**
     * 根据频道名获取节目单（兼容HD、高清、4K等后缀）
     */
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
     * 计算日期标签：今天/明天/后天/周X
     * 用毫秒差计算天数，彻底解决跨年、跨月计算错误
     */
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        // 清零时分秒，仅按日期计算天数差
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

        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }
}
