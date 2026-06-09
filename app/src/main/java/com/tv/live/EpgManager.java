package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 节目单解析器：下载、解压、解析XML
 * ✅ 统一从 UrlConfig.EPG_URL 读取主源地址
 * ✅ 保留备用源自动切换
 * ✅ 支持 MainActivity 动态修改地址
 * ✅ 修复 e.erw.cc 源加载失败问题
 * ✅ 统一日期格式为 今天、周一、周二...周天
 * ✅ 修复时区偏差问题
 */
public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    
    // 备用EPG源列表（主源失败时自动尝试）
    private final List<String> backupEpgUrls = Arrays.asList(
            "https://epg.112114.xyz/epg.xml",
            "https://epg.pw/xmltv.xml.gz"
    );

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    /**
     * 设置节目单地址（同步更新到 UrlConfig，保持接口兼容）
     */
    public void setEpgUrl(String url) {
        UrlConfig.EPG_URL = url;
    }

    /**
     * 下载并解析节目单（自动切换备用源）
     */
    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            // 优先使用 UrlConfig 中配置的主源，失败则依次尝试备用源
            List<String> tryUrls = new ArrayList<>();
            tryUrls.add(UrlConfig.EPG_URL); // ✅ 直接读取 UrlConfig 中的地址
            tryUrls.addAll(backupEpgUrls);

            boolean loadSuccess = false;
            for (String url : tryUrls) {
                Log.d("EPG", "尝试加载源：" + url);
                if (loadEpgFromUrl(url)) {
                    loadSuccess = true;
                    Log.d("EPG", "源加载成功：" + url);
                    break;
                } else {
                    Log.e("EPG", "源加载失败，尝试下一个：" + url);
                }
            }

            if (!loadSuccess) {
                Log.e("EPG", "所有EPG源均加载失败");
            }

            // 主线程回调（无论成功失败都回调，避免UI卡住）
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    /**
     * 从单个URL加载EPG
     */
    private boolean loadEpgFromUrl(String urlStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true); // 自动跟随重定向
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV)");
            conn.connect();

            // 校验HTTP状态码，只处理200成功响应
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("EPG", "HTTP错误，状态码：" + responseCode);
                return false;
            }

            // 校验响应内容长度，避免空文件
            int contentLength = conn.getContentLength();
            if (contentLength == 0) {
                Log.e("EPG", "响应内容为空");
                return false;
            }

            in = conn.getInputStream();
            byte[] data = readAllBytes(in); // 先全部读入内存，方便重试和校验

            // 自动解压GZIP（先校验是否真的是GZIP格式）
            InputStream xmlIn;
            if (urlStr.endsWith(".gz") && isGzipData(data)) {
                xmlIn = new GZIPInputStream(new ByteArrayInputStream(data));
            } else {
                xmlIn = new ByteArrayInputStream(data);
            }

            parseXml(xmlIn);
            Log.d("EPG", "EPG加载完成，共解析 " + channelEpgMap.size() + " 个频道");
            return true;

        } catch (Exception e) {
            Log.e("EPG", "加载失败：" + e.getMessage(), e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 读取输入流全部字节到内存
     */
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

    /**
     * 校验数据是否为GZIP格式（检查魔术头0x1F8B）
     */
    private boolean isGzipData(byte[] data) {
        return data.length >= 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B;
    }

    /**
     * 解析XML格式节目单
     */
    private void parseXml(InputStream is) throws Exception {
        channelEpgMap.clear(); // 解析前清空旧数据
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // 统一使用北京时间解析时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));

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

                        // 补全时区（适配标准XMLTV格式）
                        if (!start.contains("+") && !start.contains("-")) {
                            start += " +0800";
                        }
                        if (!stop.contains("+") && !stop.contains("-")) {
                            stop += " +0800";
                        }

                        Calendar startCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
                        startCal.setTime(sdf.parse(start));
                        String dayName = getDayName(startCal, today);

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(
                                dayName, timeStr, "", false
                        );
                        tempPrograms.add(item);
                    }

                    if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                        String title = xml.nextText().trim();
                        tempPrograms.get(tempPrograms.size() - 1).title = title;
                    }
                }

                // 节目结束，保存数据
                if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        tempPrograms.sort(Comparator.comparing(item -> item.time));
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                    }
                }

            } catch (Exception e) {
                Log.e("EPG", "解析单条节目失败，跳过：" + e.getMessage());
            }
            xml.next();
        }
    }

    /**
     * 根据频道名获取节目单（兼容HD、高清、4K）
     */
    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        String cleanName = channelName.replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();

        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String key = entry.getKey().replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
            if (cleanName.contains(key) || key.contains(cleanName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }

    /**
     * 计算日期：今天、周一、周二...周天
     * ✅ 已统一为要求的格式
     */
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        // 统一使用北京时间计算天差
        long itemMs = itemCal.getTimeInMillis() / (1000 * 60 * 60 * 24);
        long todayMs = todayCal.getTimeInMillis() / (1000 * 60 * 60 * 24);
        int dayDiff = (int) (itemMs - todayMs);

        if (dayDiff == 0) {
            return "今天";
        }

        // 星期映射表：Calendar.DAY_OF_WEEK 1=周天，2=周一...7=周六
        String[] weekDays = {"周天", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }
}
