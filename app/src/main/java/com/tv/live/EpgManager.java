package com.tv.live;

import android.util.Log;
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
    private final Map<String, List<MainActivity.Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = "https://e.erw.cc/all.xml.gz";

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

                // 自动解压gzip文件
                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                parseXml(in);
                Log.d("EPG", "EPG加载完成，共解析 " + channelEpgMap.size() + " 个频道");

            } catch (Exception e) {
                Log.e("EPG", "EPG加载失败", e);
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

            if (callback != null) {
                callback.run();
            }
        }).start();
    }

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        Calendar today = Calendar.getInstance();

        String currentChannelId = null;
        String currentChannelName = null;
        List<MainActivity.Channel.EpgItem> tempPrograms = new ArrayList<>();

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

                    Calendar startCal = Calendar.getInstance();
                    startCal.setTime(sdf.parse(start));

                    String dayName = getDayName(startCal, today);
                    String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                            + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem(
                            dayName, timeStr, "", "", false
                    );
                    tempPrograms.add(item);
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                if (currentChannelName != null && !tempPrograms.isEmpty()) {
                    // 按开始时间排序，保证节目顺序正确
                    tempPrograms.sort(Comparator.comparing(item -> item.time));
                    channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                }
            }

            xml.next();
        }
    }

    public List<MainActivity.Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        // 模糊匹配频道名，兼容不同格式
        String cleanName = channelName.replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
        for (Map.Entry<String, List<MainActivity.Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String key = entry.getKey().replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
            if (cleanName.contains(key) || key.contains(cleanName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        int dayDiff = itemCal.get(Calendar.DAY_OF_YEAR) - todayCal.get(Calendar.DAY_OF_YEAR);
        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }
}
