package com.tv.live;

import android.content.Context;
import android.util.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static final String TAG = "EpgManager";
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";
    private static final String PLAYBACK_BASE = "http://epg.51zmt.top:8000";
    private static EpgManager instance;

    private final Map<String, List<MainActivity.Channel.EpgItem>> epgData = new HashMap<>();
    private final Map<String, String> channelIdToName = new HashMap<>();
    private final SimpleDateFormat sdfToday = new SimpleDateFormat("yyyyMMdd");

    public static EpgManager getInstance() {
        if (instance == null) instance = new EpgManager();
        return instance;
    }

    public void load(Context ctx, Runnable callback) {
        new Thread(() -> {
            try {
                URL url = new URL(EPG_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                InputStream is = new GZIPInputStream(new BufferedInputStream(conn.getInputStream()));
                parseXml(is);
                is.close();
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "EPG加载失败", e);
            }
            ctx.getMainExecutor().execute(callback);
        }).start();
    }

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, StandardCharsets.UTF_8.name());

        String currChannelId = null;
        String start = null, stop = null, title = null;

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            String tag = xml.getName();
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                if ("channel".equals(tag)) {
                    currChannelId = xml.getAttributeValue(null, "id");
                } else if ("display-name".equals(tag)) {
                    String currName = xml.nextText().trim();
                    channelIdToName.put(currChannelId, currName);
                    epgData.computeIfAbsent(currName, k -> new ArrayList<>());
                } else if ("programme".equals(tag)) {
                    currChannelId = xml.getAttributeValue(null, "channel");
                    start = xml.getAttributeValue(null, "start");
                    stop = xml.getAttributeValue(null, "stop");
                } else if ("title".equals(tag)) {
                    title = xml.nextText().trim();
                }
            }

            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)) {
                String currName = channelIdToName.get(currChannelId);
                if (currName != null && start != null && stop != null && title != null) {
                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();

                    String date = start.substring(0, 8);
                    String monthDay = date.substring(4,6)+"-"+date.substring(6,8);
                    String today = sdfToday.format(new Date());
                    long diff = Long.parseLong(date) - Long.parseLong(today);

                    // 严格匹配截图日期格式：今天/明天/后天 + 05-20
                    if (diff == 0) item.day = "今天\n"+monthDay;
                    else if (diff == 1) item.day = "明天\n"+monthDay;
                    else if (diff == 2) item.day = "后天\n"+monthDay;
                    else item.day = monthDay;

                    item.time = start.substring(8,10)+":"+start.substring(10,12)
                            +" - "+stop.substring(8,10)+":"+stop.substring(10,12);
                    item.title = title;

                    long now = System.currentTimeMillis();
                    long s = Long.parseLong(start.substring(0,14)) * 1000L;
                    long e = Long.parseLong(stop.substring(0,14)) * 1000L;
                    item.isNow = (now >= s && now <= e);

                    item.playUrl = PLAYBACK_BASE + "/" + currChannelId + "/"
                            + start.substring(0,8) + "/" + start.substring(8,14) + ".m3u8";

                    epgData.get(currName).add(item);
                }
                start = stop = title = null;
            }
            xml.next();
        }
    }

    public List<MainActivity.Channel.EpgItem> getEpg(String channelName) {
        String clean = channelName.replace("高清","").replace("HD","")
                .replace(" ","").replace("-","").replace("卫视","");
        for (String key : epgData.keySet()) {
            String kc = key.replace("高清","").replace("HD","")
                    .replace(" ","").replace("-","").replace("卫视","");
            if (clean.contains(kc) || kc.contains(clean)) {
                return epgData.get(key);
            }
        }
        return new ArrayList<>();
    }
}
