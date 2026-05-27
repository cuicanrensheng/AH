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
    private Map<String, List<MainActivity.Channel.EpgItem>> channelEpg = new HashMap<>();
    private String epgUrl = "http://epg.51zmt.top:8000/e.xml.gz";

    public static EpgManager getInstance() {
        if (instance == null) instance = new EpgManager();
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
                InputStream in = conn.getInputStream();
                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }
                parseXml(in);
                in.close();
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (callback != null) callback.run();
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
            String tag = xml.getName();
            if (xml.getEventType() == XmlPullParser.START_TAG) {
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

                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();
                    item.dayName = dayName;
                    item.time = time;
                    item.playUrl = "";
                    items.add(item);
                }
                if ("title".equals(tag) && !items.isEmpty()) {
                    items.get(items.size() - 1).title = xml.nextText().trim();
                }
            }
            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)) {
                if (currentChannel != null) {
                    channelEpg.put(currentChannel, new ArrayList<>(items));
                }
                items.clear();
            }
            xml.next();
        }
    }

    public List<MainActivity.Channel.EpgItem> getEpg(String channelName) {
        for (String key : channelEpg.keySet()) {
            String k1 = key.replace("HD", "").replace("高清", "").replace(" ", "").trim();
            String k2 = channelName.replace("HD", "").replace("高清", "").replace(" ", "").trim();
            if (k2.contains(k1) || k1.contains(k2)) {
                return channelEpg.get(key);
            }
        }
        return new ArrayList<>();
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        int d = itemCal.get(Calendar.DAY_OF_YEAR) - todayCal.get(Calendar.DAY_OF_YEAR);
        if (d == 0) return "今天";
        if (d == 1) return "明天";
        if (d == 2) return "后天";
        String[] week = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
        int w = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return w < 0 ? week[0] : week[w];
    }
}
