package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class EpgManager {
    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = UrlConfig.EPG_URL;

    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                URL url = new URL(epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                in = conn.getInputStream();

                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                parseXml(in);
                SettingsActivity.log("【EPG】✅ 加载完成，共" + channelEpgMap.size() + "个频道");
            } catch (Exception e) {
                SettingsActivity.log("【EPG】❌ 加载失败：" + e.getMessage());
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    /**
     * ✅ 全能解析：自动兼容标准XMLTV格式 和 channel内嵌programme格式
     */
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);

        // ========== 标准XMLTV格式用 ==========
        // 频道ID -> 频道名称
        Map<String, String> channelIdToName = new HashMap<>();
        // 频道ID -> 节目列表
        Map<String, List<Channel.EpgItem>> idToPrograms = new HashMap<>();
        String currentChannelId = null;
        String currentProgramChannelId = null;
        Channel.EpgItem currentProgram = null;

        // ========== channel内嵌格式用 ==========
        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                // ==================== 处理频道 ====================
                if ("channel".equals(tag)) {
                    // 标准格式：记录channel id
                    currentChannelId = xml.getAttributeValue(null, "id");
                    // 内嵌格式：重置当前频道名和节目列表
                    currentChannelName = null;
                    tempPrograms.clear();
                }

                if ("display-name".equals(tag)) {
                    String name = xml.nextText().trim();
                    // 标准格式：id -> name 映射
                    if (currentChannelId != null) {
                        channelIdToName.put(currentChannelId, name);
                        if (!idToPrograms.containsKey(currentChannelId)) {
                            idToPrograms.put(currentChannelId, new ArrayList<>());
                        }
                    }
                    // 内嵌格式：直接记录频道名
                    currentChannelName = name;
                }

                // ==================== 处理节目 ====================
                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    if (start == null || stop == null) continue;

                    // 检查有没有channel属性，判断是不是标准格式
                    String programChannelId = xml.getAttributeValue(null, "channel");

                    try {
                        // 去掉时区后缀，只取前14位
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        // 每次取最新今天，避免隔夜基准过期
                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);

                        if (programChannelId != null && !programChannelId.isEmpty()) {
                            // ✅ 标准XMLTV格式：通过channel属性关联
                            currentProgramChannelId = programChannelId;
                            currentProgram = item;
                        } else {
                            // ✅ channel内嵌格式：直接加到当前频道的列表
                            tempPrograms.add(item);
                            currentProgram = null;
                        }
                    } catch (Exception e) {
                        SettingsActivity.log("【EPG】跳过异常时间：" + start);
                        currentProgram = null;
                    }
                }

                if ("title".equals(tag)) {
                    String title = xml.nextText().trim();
                    // 标准格式：给当前program设置标题
                    if (currentProgram != null) {
                        currentProgram.title = title;
                    }
                    // 内嵌格式：给最后一个program设置标题
                    if (!tempPrograms.isEmpty()) {
                        tempPrograms.get(tempPrograms.size() - 1).title = title;
                    }
                }
            }

            // ==================== 结束标签处理 ====================
            if (xml.getEventType() == XmlPullParser.END_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    currentChannelId = null;
                }

                if ("programme".equals(tag)) {
                    // 标准格式：节目结束，加入对应频道的列表
                    if (currentProgram != null && currentProgramChannelId != null) {
                        List<Channel.EpgItem> list = idToPrograms.get(currentProgramChannelId);
                        if (list != null) {
                            list.add(currentProgram);
                        }
                        currentProgram = null;
                        currentProgramChannelId = null;
                    }

                    // 内嵌格式：节目结束，保存当前频道所有节目
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        tempPrograms.sort(Comparator.comparing(item -> item.time));
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                    }
                }
            }

            xml.next();
        }

        // ==================== 最后：合并两种格式的结果 ====================
        // 标准格式结果转存：id -> name
        for (Map.Entry<String, List<Channel.EpgItem>> entry : idToPrograms.entrySet()) {
            String channelId = entry.getKey();
            String channelName = channelIdToName.get(channelId);
            if (channelName != null && !entry.getValue().isEmpty()) {
                List<Channel.EpgItem> programs = entry.getValue();
                programs.sort(Comparator.comparing(item -> item.time));
                // 如果内嵌格式已经有这个频道，合并节目（去重）
                if (channelEpgMap.containsKey(channelName)) {
                    List<Channel.EpgItem> exist = channelEpgMap.get(channelName);
                    Set<String> existTimes = new HashSet<>();
                    for (Channel.EpgItem item : exist) {
                        existTimes.add(item.time + "|" + item.title);
                    }
                    for (Channel.EpgItem item : programs) {
                        String key = item.time + "|" + item.title;
                        if (!existTimes.contains(key)) {
                            exist.add(item);
                        }
                    }
                    Collections.sort(exist, Comparator.comparing(item -> item.time));
                } else {
                    channelEpgMap.put(channelName, programs);
                }
            }
        }

        // 打印前3个频道的日期分布，方便排查
        int count = 0;
        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            if (count >= 3) break;
            Set<String> days = new HashSet<>();
            for (Channel.EpgItem item : entry.getValue()) {
                days.add(item.dayName);
            }
            SettingsActivity.log("【EPG】频道【" + entry.getKey() + "】包含日期：" + days + "，节目数：" + entry.getValue().size());
            count++;
        }
    }

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
     * ✅ 毫秒差计算天数，彻底解决跨年、跨月、时区问题
     */
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY, 0);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);

        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);

        long diffMs = itemDay.getTimeInMillis() - todayDay.getTimeInMillis();
        int dayDiff = (int) (diffMs / (1000L * 60 * 60 * 24));

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;

        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";
        return weekDays[dayOfWeek];
    }
}
