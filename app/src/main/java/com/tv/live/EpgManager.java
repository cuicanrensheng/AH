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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static final String TAG = "EpgManager";
    private static final String EPG_URL = "https://e.erw.cc/all.xml.gz";
    private static final String PLAYBACK_BASE = "https://e.erw.cc";
    private static EpgManager instance;

    private final Map<String, List<MainActivity.Channel.EpgItem>> epgData = new HashMap<>();
    private final Map<String, String> idToName = new HashMap<>();

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

        String chId = null, chName = null, start = null, stop = null, title = null;

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            String tag = xml.getName();
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                if ("channel".equals(tag)) chId = xml.getAttributeValue(null, "id");
                else if ("display-name".equals(tag)) {
                    chName = xml.nextText().trim();
                    idToName.put(chId, chName);
                    epgData.computeIfAbsent(chName, k -> new ArrayList<>());
                } else if ("programme".equals(tag)) {
                    chId = xml.getAttributeValue(null, "channel");
                    start = xml.getAttributeValue(null, "start");
                    stop = xml.getAttributeValue(null, "stop");
                } else if ("title".equals(tag)) title = xml.nextText().trim();
            }

            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)) {
                chName = idToName.get(chId);
                if (chName != null && start != null && stop != null && title != null) {
                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();
                    item.time = start.substring(8,10)+":"+start.substring(10,12)+"–"+stop.substring(8,10)+":"+stop.substring(10,12);
                    item.title = title;
                    long now = System.currentTimeMillis();
                    long s = Long.parseLong(start.substring(0,14))*1000L;
                    long e = Long.parseLong(stop.substring(0,14))*1000L;
                    item.isNow = now >= s && now <= e;
                    item.playUrl = PLAYBACK_BASE+"/"+chId+"/"+start.substring(0,8)+"/"+start.substring(8,14)+".m3u8";
                    epgData.get(chName).add(item);
                }
                start=stop=title=null;
            }
            xml.next();
        }
    }

    public List<MainActivity.Channel.EpgItem> getEpg(String srcName) {
        String clean = srcName.replace("高清","").replace("HD","").replace(" ","").replace("-","");
        for (Map.Entry<String, List<MainActivity.Channel.EpgItem>> entry : epgData.entrySet()) {
            String key = entry.getKey().replace("高清","").replace("HD","").replace(" ","").replace("-","");
            if (clean.contains(key) || key.contains(clean)) return entry.getValue();
        }
        return new ArrayList<>();
    }
}
