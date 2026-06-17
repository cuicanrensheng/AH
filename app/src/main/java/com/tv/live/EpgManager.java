package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = UrlConfig.EPG_URL;

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
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

        // 国内EPG源都是北京时间，用本地时区解析
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

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
                        // 去掉时区后缀，只取前14位
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        // 每次取最新今天，避免隔夜基准过期
                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);
                    } catch (Exception e) {
                        SettingsActivity.log("【EPG】跳过异常时间：" + start);
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

        // 打印前3个频道的日期分布，方便排查
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
     * ✅ 核心修复：用毫秒差计算天数，彻底解决跨年、跨月计算错误
     */
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        // 清零时分秒，只按日期计算
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
