package com.tv.live;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<MainActivity.Channel.EpgItem>> channelEpg = new HashMap<>();
    private String epgUrl = "http://epg.51zmt.top:8000/e.xml.gz";

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
            try {
                URL url = new URL(epgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                InputStream in = conn.getInputStream();

                // ✅ 修复 GZIP 解析崩溃
                if (epgUrl != null && epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                parseXml(in);
                in.close();
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
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

        Calendar today = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");

        String currentChannel = null;
        List<MainActivity.Channel.EpgItem> items = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    currentChannel = xml.getAttributeValue(null, "id");
                }

                if ("display-name".equals(tag)) {
                    currentChannel = xml.nextText().trim();
                }

                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");

                    Calendar itemCal = Calendar.getInstance();
                    itemCal.setTime(sdf.parse(start));

                    String dayName = getDayName(itemCal, today);
                    String time = start.substring(8, 10) + ":" + start.substring(10, 12)
                            + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem(
                            dayName,
                            time,
                            "",
                            "",
                            false
                    );
                    items.add(item);
                }

                if ("title".equals(tag) && !items.isEmpty()) {
                    String title = xml.nextText().trim();
                    items.get(items.size() - 1).title = title;
                }
            }

            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)) {
                if (currentChannel != null && !items.isEmpty()) {
                    channelEpg.put(currentChannel, new ArrayList<>(items));
                    items.clear();
                }
            }
            xml.next();
        }
    }

    // ✅ 获取节目单（模糊匹配频道名，支持高清/HD/超清）
    public List<MainActivity.Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        String cleanName = channelName.replaceAll("(?i)高清|hd|超清|4k| |-", "").toLowerCase();

        for (String key : channelEpg.keySet()) {
            String cleanKey = key.replaceAll("(?i)高清|hd|超清|4k| |-", "").toLowerCase();
            if (cleanName.contains(cleanKey) || cleanKey.contains(cleanName)) {
                return channelEpg.get(key);
            }
        }
        return new ArrayList<>();
    }

    // ✅ 显示：今天/明天/后天/周一~周日
    private String getDayName(Calendar itemCal, Calendar todayCal) {
        int dayDiff = itemCal.get(Calendar.DAY_OF_YEAR) - todayCal.get(Calendar.DAY_OF_YEAR);
        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";

        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int w = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return (w >= 0) ? week[w] : week[0];
    }
}
