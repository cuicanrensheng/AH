package com.tv.live;
import android.content.Context;
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
    private static EpgManager instance;
    private Map<String, List<MainActivity.Channel.EpgItem>> epgData = new HashMap<>();
    private Map<String, String> idToName = new HashMap<>();
    private SimpleDateFormat sdfDay = new SimpleDateFormat("yyyy‑MM‑dd");

    public static EpgManager getInstance() {
        if(instance == null) instance = new EpgManager();
        return instance;
    }

    // 自动拉取EPG
    public void loadEpg(String epgUrl, Runnable callback){
        new Thread(() -> {
            try{
                URL url = new URL(epgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                InputStream is = new GZIPInputStream(new BufferedInputStream(conn.getInputStream()));
                parseEpgXml(is);
                is.close();
                conn.disconnect();
            }catch (Exception e){e.printStackTrace();}
            callback.run();
        }).start();
    }

    private void parseEpgXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, StandardCharsets.UTF_8.name());

        String channelId = null;
        String start = null, stop = null, title = null;

        while(xml.getEventType() != XmlPullParser.END_DOCUMENT){
            String tag = xml.getName();
            if(xml.getEventType() == XmlPullParser.START_TAG){
                if("channel".equals(tag)){
                    channelId = xml.getAttributeValue(null,"id");
                }else if("display‑name".equals(tag)){
                    String name = xml.nextText().trim();
                    idToName.put(channelId, name);
                    epgData.computeIfAbsent(name, k -> new ArrayList<>());
                }else if("programme".equals(tag)){
                    channelId = xml.getAttributeValue(null,"channel");
                    start = xml.getAttributeValue(null,"start");
                    stop = xml.getAttributeValue(null,"stop");
                }else if("title".equals(tag)){
                    title = xml.nextText().trim();
                }
            }

            if(xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(tag)){
                String chName = idToName.get(channelId);
                if(chName != null && start != null && stop != null && title != null){
                    MainActivity.Channel.EpgItem item = new MainActivity.Channel.EpgItem();
                    String date = start.substring(0,8);
                    String nowDate = new SimpleDateFormat("yyyyMMdd").format(new Date());
                    long diff = Long.parseLong(date) - Long.parseLong(nowDate);

                    if(diff == 0) item.day = "今天";
                    else if(diff == 1) item.day = "明天";
                    else if(diff == 2) item.day = "后天";
                    else item.day = start.substring(4,6)+"‑"+start.substring(6,8);

                    item.time = start.substring(8,10)+":"+start.substring(10,12)+"‑"+stop.substring(8,10)+":"+stop.substring(10,12);
                    item.title = title;
                    item.playUrl = "http://epg.51zmt.top:8000/"+channelId+"/"+date+"/"+start.substring(8,14)+".m3u8";

                    long now = System.currentTimeMillis();
                    long sTime = Long.parseLong(start.substring(0,14))*1000L;
                    long eTime = Long.parseLong(stop.substring(0,14))*1000L;
                    item.isNow = (now >= sTime && now <= eTime);

                    epgData.get(chName).add(item);
                }
                start=stop=title=null;
            }
            xml.next();
        }
    }

    // 根据频道名称获取EPG（模糊匹配，适配江西卫视等）
    public List<MainActivity.Channel.EpgItem> getEpgByChannelName(String chName){
        String clean = chName.replace("高清","").replace("HD","").replace(" ","").replace("‑","");
        for(String key : epgData.keySet()){
            String kClean = key.replace("高清","").replace("HD","").replace(" ","").replace("‑","");
            if(clean.contains(kClean) || kClean.contains(clean)){
                return epgData.get(key);
            }
        }
        return new ArrayList<>();
    }
}
