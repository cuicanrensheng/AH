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
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    public static EpgManager getInstance() {
        if (instance == null) instance = new EpgManager();
        return instance;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            try {
                URL url = new URL(EPG_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                InputStream is = new GZIPInputStream(conn.getInputStream());
                parseXml(is);
                is.close();
                conn.disconnect();
            } catch (Exception e) { e.printStackTrace(); }
            if (callback != null) callback.run();
        }).start();
    }

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        Calendar todayCal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        String currentChannel = null;
        List<MainActivity.Channel.EpgItem> items = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            String tag = xml.getName();
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                if ("channel".equals(tag)) currentChannel = xml.getAttributeValue(null, "id");
                if ("display-name".equals(tag)) currentChannel = xml.nextText().trim();
                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    String channelId = xml.getAttributeValue(null, "channel");

                    Calendar itemCal = Calendar.getInstance();
                    itemCal.setTime(sdf.parse(start));
                    String dayName = getDayName(itemCal, todayCal);
                    String time = start.substring(8, 10) + ":" + start.substring(10, 12)
                            + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);
                    String playUrl = "http://epg.51zmt.top:8000/" + channelId + "/"
                            + start.substring(0, 8) + "/" + start.substring(8, 14) + ".m3u8";

                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();
                    item.dayName = dayName;
                    item.time = time;
                    item.playUrl = playUrl;
                    items.add(item);
                }
                if ("title".equals(tag) && !items.isEmpty()) {
                    items.get(items.size() - 1).title = xml.nextText().trim();
                }
            }
            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)) {
                channelEpg.put(currentChannel, new ArrayList<>(items));
                items.clear();
            }
            xml.next();
        }
    }

    public List<MainActivity.Channel.EpgItem> getEpg(String channelName) {
        for (String key : channelEpg.keySet()) {
            String k1 = key.replace("HD", "").replace("高清", "").replace(" ", "");
            String k2 = channelName.replace("HD", "").replace("高清", "").replace(" ", "");
            if (k2.contains(k1) || k1.contains(k2)) return channelEpg.get(key);
        }
        return new ArrayList<>();
    }

     public String getDayName(Calendar itemCal, Calendar todayCal) {
        int today = todayCal.get(Calendar.DAY_OF_YEAR);
        int itemDay = itemCal.get(Calendar.DAY_OF_YEAR);
        int diff = itemDay - today;
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        String[] week = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
        int w = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return w < 0 ? week[0] : week[w];
    }
}
