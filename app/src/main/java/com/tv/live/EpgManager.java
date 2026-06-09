package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private final List<String> backupEpgUrls = Arrays.asList(
            "https://epg.112114.xyz/epg.xml",
            "https://epg.pw/xmltv.xml.gz"
    );
    // 强制使用北京时间时区
    private static final TimeZone BEIJING_TZ = TimeZone.getTimeZone("GMT+8");
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

    static {
        sdf.setTimeZone(BEIJING_TZ);
    }

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    public void setEpgUrl(String url) {
        UrlConfig.EPG_URL = url;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            List<String> tryUrls = new ArrayList<>();
            tryUrls.add(UrlConfig.EPG_URL);
            tryUrls.addAll(backupEpgUrls);

            boolean loadSuccess = false;
            for (String url : tryUrls) {
                MainActivity.log("【EPG】尝试加载源：" + url);
                if (loadEpgFromUrl(url)) {
                    loadSuccess = true;
                    MainActivity.log("【EPG】源加载成功：" + url);
                    break;
                } else {
                    MainActivity.log("【EPG】源加载失败，尝试下一个：" + url);
                }
            }

            if (!loadSuccess) {
                MainActivity.log("【EPG】所有源均加载失败");
            }

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    private boolean loadEpgFromUrl(String urlStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV)");
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                MainActivity.log("【EPG】HTTP错误，状态码：" + responseCode);
                return false;
            }

            in = conn.getInputStream();
            byte[] data = readAllBytes(in);
            InputStream xmlIn = urlStr.endsWith(".gz") && isGzipData(data)
                    ? new GZIPInputStream(new ByteArrayInputStream(data))
                    : new ByteArrayInputStream(data);

            parseXml(xmlIn);
            MainActivity.log("【EPG】加载完成，共解析 " + channelEpgMap.size() + " 个频道");
            return true;
        } catch (Exception e) {
            MainActivity.log("【EPG】加载失败：" + e.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private byte[] readAllBytes(InputStream in) throws Exception {
        List<Byte> bytes = new ArrayList<>();
        int b;
        while ((b = in.read()) != -1) {
            bytes.add((byte) b);
        }
        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            result[i] = bytes.get(i);
        }
        return result;
    }

    private boolean isGzipData(byte[] data) {
        return data.length >= 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B;
    }

    private void parseXml(InputStream is) throws Exception {
        channelEpgMap.clear();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // ✅ 核心修复：今天的日期也用北京时间计算
        Calendar today = Calendar.getInstance(BEIJING_TZ);
        // 计算今天的自然天（毫秒数/一天毫秒数）
        long todayDay = (today.getTimeInMillis() + BEIJING_TZ.getRawOffset()) / (1000L * 60 * 60 * 24);

        String currentChannelId = null;
        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            try {
                if (xml.getEventType() == XmlPullParser.START_TAG) {
                    String tag = xml.getName();
                    if ("channel".equals(tag)) {
                        currentChannelId = xml.getAttributeValue(null, "id");
                        tempPrograms.clear();
                    }
                    if ("display-name".equals(tag) && currentChannelId != null) {
                        currentChannelName = xml.nextText().trim();
                    }
                    if ("programme".equals(tag)) {
                        String start = xml.getAttributeValue(null, "start");
                        String stop = xml.getAttributeValue(null, "stop");
                        if (start == null || stop == null) continue;

                        // 补全时区
                        if (!start.contains("+") && !start.contains("-")) start += " +0800";
                        if (!stop.contains("+") && !stop.contains("-")) stop += " +0800";

                        Calendar startCal = Calendar.getInstance(BEIJING_TZ);
                        startCal.setTime(sdf.parse(start));
                        // 计算节目所在的自然天
                        long itemDay = (startCal.getTimeInMillis() + BEIJING_TZ.getRawOffset()) / (1000L * 60 * 60 * 24);
                        int dayDiff = (int) (itemDay - todayDay);

                        // ✅ 只保留今天及未来7天的节目
                        if (dayDiff < 0 || dayDiff >= 7) {
                            xml.next();
                            continue;
                        }

                        String dayName = getDayName(startCal, today);
                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);
                    }
                    if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                        tempPrograms.get(tempPrograms.size() - 1).title = xml.nextText().trim();
                    }
                }
                if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        tempPrograms.sort(Comparator.comparing(item -> item.time));
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                    }
                }
            } catch (Exception e) {
                MainActivity.log("【EPG】解析单条节目失败：" + e.getMessage());
            }
            xml.next();
        }
    }

    // ✅ 终极修复：日期计算完全对齐DateListManager
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        long itemDay = (itemCal.getTimeInMillis() + BEIJING_TZ.getRawOffset()) / (1000L * 60 * 60 * 24);
        long todayDay = (todayCal.getTimeInMillis() + BEIJING_TZ.getRawOffset()) / (1000L * 60 * 60 * 24);
        int dayDiff = (int) (itemDay - todayDay);

        if (dayDiff == 0) {
            return "今天";
        }

        String[] weekMap = {"周天", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekMap[dayOfWeek];
    }

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) return new ArrayList<>();
        String cleanName = channelName.replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String key = entry.getKey().replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
            if (cleanName.contains(key) || key.contains(cleanName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }
}
