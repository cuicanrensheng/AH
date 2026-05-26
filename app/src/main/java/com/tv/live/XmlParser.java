package com.tv.live;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class XmlParser {
    public static void parse(InputStream is, EpgCallback callback) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser xml = factory.newPullParser();
            xml.setInput(is, StandardCharsets.UTF_8.name());

            Calendar today = Calendar.getInstance();
            String currentChannel = null;
            List<Channel.EpgItem> items = new ArrayList<>();

            while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tag = xml.getName();
                if (xml.getEventType() == XmlPullParser.START_TAG) {
                    if ("channel".equals(tag)) {
                        currentChannel = xml.getAttributeValue(null, "id");
                    }
                    if ("display-name".equals(tag)) {
                        String name = xml.nextText().trim();
                        currentChannel = name;
                    }
                    if ("programme".equals(tag)) {
                        String start = xml.getAttributeValue(null, "start");
                        String stop = xml.getAttributeValue(null, "stop");
                        String channel = xml.getAttributeValue(null, "channel");

                        Calendar sCal = Calendar.getInstance();
                        try {
                            sCal.setTime(new SimpleDateFormat("yyyyMMddHHmmss").parse(start));
                        } catch (Exception e) {}

                        String dayName = EpgManager.getDayText(sCal, today);
                        String time = start.substring(8,10)+":"+start.substring(10,12)
                                + " - " + stop.substring(8,10)+":"+stop.substring(10,12);
                        String title = "";
                        String playUrl = "http://epg.51zmt.top:8000/"+channel+"/"+start.substring(0,8)+"/"+start.substring(8,14)+".m3u8";

                        Channel.EpgItem item = new Channel.EpgItem();
                        item.dayName = dayName;
                        item.time = time;
                        item.title = title;
                        item.playUrl = playUrl;
                        items.add(item);
                    }
                    if ("title".equals(tag)) {
                        if (!items.isEmpty()) {
                            items.get(items.size()-1).title = xml.nextText().trim();
                        }
                    }
                }
                xml.next();
            }
            callback.onParsed(currentChannel, items);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public interface EpgCallback {
        void onParsed(String channelName, List<Channel.EpgItem> items);
    }
}
