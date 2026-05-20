package com.tv.live;

import android.content.Context;
import android.os.AsyncTask;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static EpgManager instance;
    private Map<String, String> epgData = new HashMap<>();
    private final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    public static EpgManager getInstance() {
        if (instance == null) instance = new EpgManager();
        return instance;
    }

    public void loadEpg(Context context, OnEpgLoadListener listener) {
        new AsyncTask<Void, Void, Map<String, String>>() {
            @Override
            protected Map<String, String> doInBackground(Void... voids) {
                try {
                    URL url = new URL(EPG_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    InputStream is = new GZIPInputStream(new BufferedInputStream(conn.getInputStream()));

                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    XmlPullParser parser = factory.newPullParser();
                    parser.setInput(is, "utf-8");

                    Map<String, String> data = new HashMap<>();
                    int eventType = parser.getEventType();
                    String channelId = "";
                    String title = "";

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            if ("programme".equals(parser.getName())) {
                                channelId = parser.getAttributeValue(null, "channel");
                            } else if ("title".equals(parser.getName())) {
                                title = parser.nextText();
                                data.put(channelId, title);
                            }
                        }
                        eventType = parser.next();
                    }
                    return data;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Map<String, String> map) {
                if (map != null) {
                    epgData = map;
                    if (listener != null) listener.onLoadSuccess();
                } else {
                    if (listener != null) listener.onLoadFail();
                }
            }
        }.execute();
    }

    public String getEpgInfo(String channelName) {
        return epgData.getOrDefault(channelName, "暂无节目");
    }

    public interface OnEpgLoadListener {
        void onLoadSuccess();
        void onLoadFail();
    }
}
