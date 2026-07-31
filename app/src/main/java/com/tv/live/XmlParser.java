package com.tv.live;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
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
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            
            String currentChannel = null;
            // 🟢 每次检测到新频道时，我们会重新创建这个列表，防止无限累积导致 OOM
            List<Channel.EpgItem> items = new ArrayList<>();

            while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() == XmlPullParser.START_TAG) {
                    String tag = xml.getName();

                    if ("channel".equals(tag)) {
                        // 🟢【核心修复】如果解析到了一个新的频道标签，且前一个频道的列表不为空，
                        // 说明前一个频道的节目已经全部解析完。先触发回调，再重置列表！
                        if (currentChannel != null && !items.isEmpty()) {
                            if (callback != null) {
                                callback.onParsed(currentChannel, items);
                            }
                            items = new ArrayList<>(); // 创建新列表，彻底释放旧列表内存
                        }
                        currentChannel = xml.getAttributeValue(null, "id");
                    }
                    
                    if ("display-name".equals(tag)) {
                        currentChannel = xml.nextText().trim();
                    }
                    
                    if ("programme".equals(tag)) {
                        String start = xml.getAttributeValue(null, "start");
                        String stop = xml.getAttributeValue(null, "stop");
                        String channelId = xml.getAttributeValue(null, "channel");

                        Calendar sCal = Calendar.getInstance();
                        sCal.setTime(sdf.parse(start));

                        String dayName = EpgManager.getInstance().getDayName(sCal, today);
                        String time = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);
                        
                        String playUrl = "http://epg.51zmt.top:8000/" + channelId + "/"
                                + start.substring(0, 8) + "/" + start.substring(8, 14) + ".m3u8";

                        Channel.EpgItem item = new Channel.EpgItem(dayName, time, playUrl, false);
                        items.add(item);
                    }
                    
                    if ("title".equals(tag)) {
                        if (!items.isEmpty()) {
                            items.get(items.size() - 1).title = xml.nextText().trim();
                        }
                    }
                }
                xml.next();
            }

            // 🟢 文档解析结束，处理最后一个频道的剩余节目
            if (currentChannel != null && !items.isEmpty()) {
                if (callback != null) {
                    callback.onParsed(currentChannel, items);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public interface EpgCallback {
       void onParsed(String channelName, List<Channel.EpgItem> items);
    }
}
