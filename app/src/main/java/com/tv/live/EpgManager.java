package com.tv.live;
import android.content.Context;
import android.util.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";
    private static final String PLAYBACK_BASE = "https://e.erw.cc";
    private static EpgManager instance;
    private final Map<String, List<MainActivity.Channel.EpgItem>> epgCache = new HashMap<>();

    public static EpgManager getInstance() {
        if (instance == null) instance = new EpgManager();
        return instance;
    }

    public void downloadAndParseEpg(Context context, Runnable callback) {
        new Thread(() -> {
            try {
                URL url = new URL(EPG_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                InputStream is = new GZIPInputStream(new BufferedInputStream(conn.getInputStream()));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                is.close();
                conn.disconnect();

                parseXml(new ByteArrayInputStream(bos.toByteArray()));
                context.getMainExecutor().execute(callback);
            } catch (Exception e) {
                Log.e(TAG, "EPG下载/解析失败", e);
                context.getMainExecutor().execute(callback);
            }
        }).start();
    }

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(is, StandardCharsets.UTF_8.name());

        String currentChannelName = null;
        String startTime = null;
        String stopTime = null;
        String title = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    if ("display-name".equals(tag)) {
                        currentChannelName = parser.nextText().trim();
                        epgCache.put(currentChannelName, new ArrayList<>());
                    } else if ("programme".equals(tag)) {
                        startTime = parser.getAttributeValue(null, "start");
                        stopTime = parser.getAttributeValue(null, "stop");
                    } else if ("title".equals(tag)) {
                        title = parser.nextText().trim();
                    }
                    break;
                case XmlPullParser.END_TAG:
                    if ("programme".equals(tag) && currentChannelName != null) {
                        List<MainActivity.Channel.EpgItem> list = epgCache.get(currentChannelName);
                        if (list != null && startTime != null && stopTime != null && title != null) {
                            MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();
                            item.time = formatTime(startTime) + "–" + formatTime(stopTime);
                            item.title = title;
                            item.isNow = isNow(startTime, stopTime);
                            item.playUrl = buildPlaybackUrl(currentChannelName, startTime);
                            list.add(item);
                        }
                        startTime = stopTime = title = null;
                    }
                    break;
            }
            eventType = parser.next();
        }
        is.close();
    }

    private String buildPlaybackUrl(String chName, String xmlTime) {
        try {
            String date = xmlTime.substring(0, 8);
            String hms = xmlTime.substring(8, 14);
            String cleanName = chName.replace(" ", "").replace("高清", "").replace("卫视", "").replace("频道", "");
            return PLAYBACK_BASE + "/" + cleanName + "/" + date + "/" + hms + ".m3u8";
        } catch (Exception e) {
            return "";
        }
    }

    private String formatTime(String xmlTime) {
        return xmlTime.substring(8, 10) + ":" + xmlTime.substring(10, 12);
    }

    private boolean isNow(String start, String stop) {
        try {
            long now = System.currentTimeMillis();
            long s = Long.parseLong(start.substring(0, 14)) * 1000;
            long e = Long.parseLong(stop.substring(0, 14)) * 1000;
            return now >= s && now <= e;
        } catch (Exception e) {
            return false;
        }
    }

    // ✅ 超级宽松匹配：包含关键字就匹配（解决江西卫视[高清]匹配不到）
    public List<MainActivity.Channel.EpgItem> getEpgByChannelName(String channelName) {
        String srcClean = channelName.replace(" ", "").replace("高清", "").replace("卫视", "").replace("频道", "");
        for (Map.Entry<String, List<MainActivity.Channel.EpgItem>> entry : epgCache.entrySet()) {
            String keyClean = entry.getKey().replace(" ", "").replace("高清", "").replace("卫视", "").replace("频道", "");
            if (keyClean.contains(srcClean) || srcClean.contains(keyClean)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }
}
