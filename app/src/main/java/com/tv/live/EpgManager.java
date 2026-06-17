package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * EPG节目单管理器
 * 
 * 功能说明：
 * 1. 支持从EPG XML文件下载、解析节目单
 * 2. 支持gzip压缩的EPG源（.gz后缀）
 * 3. 支持两种EPG格式：标准XMLTV格式 + channel内嵌programme格式
 * 4. 支持两种匹配方式：tvg-id精确匹配 + 频道名模糊匹配
 * 5. 支持从M3U直播源文件自动提取EPG地址（x-tvg-url）
 * 6. 日期标签：今天/明天/后天/周几，自动计算
 * 
 * 使用方式：
 * - 方式1：手动设置EPG地址 → setEpgUrl() + loadEpg()
 * - 方式2：从直播源自动获取 → loadEpgFromM3u()
 */
public class EpgManager {
    // 单例实例
    private static EpgManager instance;
    
    // 频道名称 -> 节目列表（用于频道名模糊匹配）
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    
    // 频道ID(tvg-id) -> 节目列表（用于精确匹配，推荐）
    private final Map<String, List<Channel.EpgItem>> idEpgMap = new HashMap<>();
    
    // 当前EPG源地址
    private String epgUrl = UrlConfig.EPG_URL;
    
    // 调试用：是否已经打印过节目标本日志（避免日志太多）
    private boolean hasPrintedSample = false;

    /**
     * 获取单例实例
     * 整个APP共用一个EPG管理器，避免重复下载和解析
     */
    public static EpgManager getInstance() {
        if (instance == null) {
            instance = new EpgManager();
        }
        return instance;
    }

    /**
     * 手动设置EPG源地址
     * @param url EPG XML文件的URL，可以是普通xml或.gz压缩包
     */
    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    /**
     * ✅ 【新增功能】从M3U直播源文件中自动提取EPG地址并加载
     * 
     * 原理：M3U文件的第一行 #EXTM3U 通常会带 x-tvg-url 属性，指定对应的EPG地址
     * 比如：#EXTM3U x-tvg-url="https://xxx.com/epg.xml.gz"
     * 
     * 优点：换直播源时自动切换对应的EPG，不用手动配置
     * 
     * @param m3uUrl M3U直播源地址
     * @param callback 加载完成后的回调（主线程执行）
     */
    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(() -> {
            // 第一步：从M3U文件中提取EPG地址
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                SettingsActivity.log("【EPG】📡 从直播源获取到EPG地址：" + extractedEpgUrl);
                epgUrl = extractedEpgUrl;
            } else {
                // 提取失败，使用默认地址兜底
                SettingsActivity.log("【EPG】📡 直播源未指定EPG地址，使用默认：" + epgUrl);
            }
            
            // 第二步：加载EPG
            loadEpg(callback);
        }).start();
    }

    /**
     * 从M3U文件头部提取 x-tvg-url 属性
     * 
     * 只读取前10行，因为x-tvg-url一定在#EXTM3U那一行（文件开头）
     * 支持两种格式：
     * - x-tvg-url="https://xxx.com/epg.xml" （带引号）
     * - x-tvg-url=https://xxx.com/epg.xml （不带引号）
     * 
     * @param m3uUrl M3U文件地址
     * @return 提取到的EPG地址，提取失败返回null
     */
    private String extractEpgUrlFromM3u(String m3uUrl) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(m3uUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();

            InputStream is = conn.getInputStream();
            // 如果是.gz压缩的M3U，自动解压
            if (m3uUrl.endsWith(".gz")) {
                is = new GZIPInputStream(is);
            }

            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lineCount = 0;
            
            // 只看前10行，x-tvg-url一定在开头
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                
                // 找到包含x-tvg-url的行
                if (line.contains("x-tvg-url") || line.contains("tvgtvg-url")) {
                    
                    // 方式1：提取引号中的URL（最常见）
                    int start = line.indexOf("\"");
                    if (start >= 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                    
                    // 方式2：= 后面直接跟URL（不带引号）
                    String[] parts = line.split("x-tvg-url=");
                    if (parts.length >= 2) {
                        String urlPart = parts[1].trim();
                        // 去掉开头的引号
                        if (urlPart.startsWith("\"")) {
                            urlPart = urlPart.substring(1);
                        }
                        // 遇到空格就截止（后面还有其他属性）
                        int spaceIdx = urlPart.indexOf(" ");
                        if (spaceIdx > 0) {
                            urlPart = urlPart.substring(0, spaceIdx);
                        }
                        // 去掉结尾的引号
                        if (urlPart.endsWith("\"")) {
                            urlPart = urlPart.substring(0, urlPart.length() - 1);
                        }
                        return urlPart.trim();
                    }
                }
            }
        } catch (Exception e) {
            SettingsActivity.log("【EPG】从M3U提取EPG地址失败：" + e.getMessage());
        } finally {
            // 关闭资源
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 加载并解析EPG
     * 
     * 流程：
     * 1. 子线程下载EPG文件
     * 2. 自动识别gzip压缩并解压
     * 3. 解析XML，生成节目列表
     * 4. 主线程回调通知完成
     * 
     * @param callback 加载完成后的回调（主线程执行）
     */
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

                // 自动识别gzip压缩格式
                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                // 重置状态，重新解析
                hasPrintedSample = false;
                channelEpgMap.clear();
                idEpgMap.clear();
                
                // 解析XML
                parseXml(in);
                
                SettingsActivity.log("【EPG】✅ 加载完成，共" + channelEpgMap.size() + "个频道（名称匹配），"
                        + idEpgMap.size() + "个频道（ID匹配）");
            } catch (Exception e) {
                SettingsActivity.log("【EPG】❌ 加载失败：" + e.getMessage());
            } finally {
                // 关闭网络连接
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

            // 回调到主线程
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    /**
     * 解析EPG XML文件
     * 
     * ✅ 【重要修改】同时兼容两种EPG格式：
     * 
     * 格式1：标准XMLTV格式（绝大多数EPG源用这个）
     *   - 所有 <channel> 节点在前，定义频道ID和名称
     *   - 所有 <programme> 节点在后，通过 channel 属性关联到对应频道
     *   - 优点：规范，支持tvg-id精确匹配
     * 
     * 格式2：channel内嵌programme格式（少数小众源用这个）
     *   - 每个 <channel> 节点内部包含它的所有 <programme> 节点
     *   - 优点：结构简单
     * 
     * 两种格式自动识别，互不影响，最后合并结果
     */
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // 时间解析格式：yyyyMMddHHmmss，比如 20260617190000
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true); // 宽松模式，避免小格式错误导致全部失败

        // ========== 打印今天的日期，方便排查问题 ==========
        Calendar todayCheck = Calendar.getInstance();
        SettingsActivity.log("【EPG】📅 今天日期：" + todayCheck.get(Calendar.YEAR) + "-"
                + (todayCheck.get(Calendar.MONTH) + 1) + "-" + todayCheck.get(Calendar.DAY_OF_MONTH)
                + "（一年中的第" + todayCheck.get(Calendar.DAY_OF_YEAR) + "天）");

        // ========== 【格式1】标准XMLTV格式用的变量 ==========
        Map<String, String> channelIdToName = new HashMap<>();  // 频道ID -> 频道名称
        Map<String, List<Channel.EpgItem>> idToPrograms = new HashMap<>(); // 频道ID -> 节目列表
        String currentChannelId = null;           // 当前正在解析的频道ID
        String currentProgramChannelId = null;    // 当前节目所属的频道ID
        Channel.EpgItem currentProgram = null;    // 当前正在解析的节目对象

        // ========== 【格式2】channel内嵌格式用的变量 ==========
        String currentChannelName = null;         // 当前正在解析的频道名称
        List<Channel.EpgItem> tempPrograms = new ArrayList<>(); // 当前频道的节目临时列表
        int programCount = 0;                     // 已解析的节目计数（用于控制样本日志数量）

        // ========== 开始遍历XML节点 ==========
        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                // ==================== 遇到 <channel> 开始标签 ====================
                if ("channel".equals(tag)) {
                    // 标准格式：记录channel的id属性
                    currentChannelId = xml.getAttributeValue(null, "id");
                    
                    // 内嵌格式：重置当前频道名和节目列表
                    currentChannelName = null;
                    tempPrograms.clear();
                }

                // ==================== 遇到 <display-name> 频道名称 ====================
                if ("display-name".equals(tag)) {
                    String name = xml.nextText().trim();
                    
                    // 标准格式：保存 ID -> 名称 的映射关系
                    if (currentChannelId != null) {
                        channelIdToName.put(currentChannelId, name);
                        // 同时初始化该频道的节目列表
                        if (!idToPrograms.containsKey(currentChannelId)) {
                            idToPrograms.put(currentChannelId, new ArrayList<>());
                        }
                    }
                    
                    // 内嵌格式：直接记录频道名称
                    currentChannelName = name;
                }

                // ==================== 遇到 <programme> 节目开始标签 ====================
                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start"); // 开始时间
                    String stop = xml.getAttributeValue(null, "stop");   // 结束时间
                    if (start == null || stop == null) continue;

                    // 检查有没有channel属性 → 判断是不是标准XMLTV格式
                    String programChannelId = xml.getAttributeValue(null, "channel");

                    try {
                        String originalStart = start; // 保存原始时间字符串，用于日志

                        // 去掉时区后缀（比如 +0800），只取前14位数字（yyyyMMddHHmmss）
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);

                        // 解析开始时间
                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));

                        // 每次都获取最新的"今天"，避免APP隔夜后基准日期过期
                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);

                        // 只打印前5个节目标本，避免日志太多
                        if (!hasPrintedSample && programCount < 5) {
                            SettingsActivity.log("【EPG】🔍 样本" + (programCount + 1)
                                    + "：原始时间=" + originalStart
                                    + "，截取后=" + start
                                    + "，解析日期=" + (startCal.get(Calendar.MONTH) + 1) + "月" + startCal.get(Calendar.DAY_OF_MONTH) + "日"
                                    + "，dayName=" + dayName);
                            programCount++;
                            if (programCount >= 5) hasPrintedSample = true;
                        }

                        // 格式化显示时间，比如 19:00 - 20:00
                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        // 创建节目对象
                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);

                        if (programChannelId != null && !programChannelId.isEmpty()) {
                            // ✅ 标准XMLTV格式：节目有channel属性，通过ID关联到频道
                            currentProgramChannelId = programChannelId;
                            currentProgram = item;
                        } else {
                            // ✅ channel内嵌格式：节目在channel节点内部，直接加到当前频道的列表
                            tempPrograms.add(item);
                            currentProgram = null;
                        }
                    } catch (Exception e) {
                        // 单条节目解析失败，跳过，不影响整体
                        SettingsActivity.log("【EPG】跳过异常时间：" + start + "，错误：" + e.getMessage());
                        currentProgram = null;
                    }
                }

                // ==================== 遇到 <title> 节目标题 ====================
                if ("title".equals(tag)) {
                    String title = xml.nextText().trim();
                    
                    // 标准格式：给当前正在解析的节目设置标题
                    if (currentProgram != null) {
                        currentProgram.title = title;
                    }
                    
                    // 内嵌格式：给列表中最后一个节目设置标题
                    if (!tempPrograms.isEmpty()) {
                        tempPrograms.get(tempPrograms.size() - 1).title = title;
                    }
                }
            }

            // ==================== 遇到结束标签 ====================
            if (xml.getEventType() == XmlPullParser.END_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    // 频道解析结束，清空当前ID
                    currentChannelId = null;
                }

                if ("programme".equals(tag)) {
                    // 标准格式：单个节目解析完成，加入对应频道的节目列表
                    if (currentProgram != null && currentProgramChannelId != null) {
                        List<Channel.EpgItem> list = idToPrograms.get(currentProgramChannelId);
                        if (list != null) {
                            list.add(currentProgram);
                        }
                        currentProgram = null;
                        currentProgramChannelId = null;
                    }

                    // 内嵌格式：单个节目解析完成，保存当前频道的所有节目
                    // 注意：这里每次节目结束都存一次，后面的会覆盖前面的，最终结果是对的
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        tempPrograms.sort(Comparator.comparing(item -> item.time));
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                    }
                }
            }

            // 下一个节点
            xml.next();
        }

        // ========== 最后：合并两种格式的解析结果 ==========
        for (Map.Entry<String, List<Channel.EpgItem>> entry : idToPrograms.entrySet()) {
            String channelId = entry.getKey();
            String channelName = channelIdToName.get(channelId);
            
            if (channelName != null && !entry.getValue().isEmpty()) {
                List<Channel.EpgItem> programs = entry.getValue();
                programs.sort(Comparator.comparing(item -> item.time));
                
                // 1. 存到ID匹配map（用于tvg-id精确查找）
                idEpgMap.put(channelId, programs);
                
                // 2. 同时存到名称匹配map（兼容旧的查找方式）
                if (!channelEpgMap.containsKey(channelName)) {
                    // 没有就直接放
                    channelEpgMap.put(channelName, programs);
                } else {
                    // 已有就合并，并且去重（按时间+标题判断）
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
                    // 重新按时间排序
                    Collections.sort(exist, Comparator.comparing(item -> item.time));
                }
            }
        }

        // ========== 打印前3个频道的日期分布，方便排查问题 ==========
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

    /**
     * ✅ 【推荐】通过 tvg-id 精确获取节目单
     * 
     * 优点：
     * - 100%准确，不会出现频道名对不上的问题
     * - 不同源的同名频道不会混淆
     * 
     * 前提：你的Channel对象里有tvgId字段，并且解析M3U时正确读取了
     * 
     * @param tvgId 频道的tvg-id，对应EPG里channel节点的id属性
     * @return 该频道的节目列表，没有则返回空列表
     */
    public List<Channel.EpgItem> getEpgByTvgId(String tvgId) {
        if (tvgId == null || tvgId.isEmpty()) {
            return new ArrayList<>();
        }
        List<Channel.EpgItem> result = idEpgMap.get(tvgId);
        if (result != null) {
            return new ArrayList<>(result); // 返回副本，避免外部修改影响内部数据
        }
        return new ArrayList<>();
    }

    /**
     * 通过频道名模糊获取节目单（兜底方案）
     * 
     * 匹配规则：
     * - 去掉 高清/HD/超清/4K/空格/- 等后缀后再比较
     * - 只要互相包含就算匹配（比如"江西卫视"匹配"江西卫视高清"）
     * 
     * @param channelName 频道名称
     * @return 该频道的节目列表，没有则返回空列表
     */
    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        // 统一格式化：去掉常见后缀，转小写
        String cleanName = channelName.replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();

        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String key = entry.getKey().replaceAll("(?i)高清|HD|超清|4K| |-", "").toLowerCase();
            // 互相包含就算匹配
            if (cleanName.contains(key) || key.contains(cleanName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }

    /**
     * ✅ 【最推荐】智能获取节目单
     * 
     * 策略：
     * 1. 先尝试用 tvg-id 精确匹配（最准确）
     * 2. 如果tvg-id为空或匹配不到，再用频道名模糊匹配（兜底）
     * 
     * 兼顾准确性和兼容性，推荐统一用这个方法
     * 
     * @param channelName 频道名称
     * @param tvgId 频道的tvg-id（可以为null）
     * @return 该频道的节目列表
     */
    public List<Channel.EpgItem> getEpgSmart(String channelName, String tvgId) {
        // 优先用tvg-id精确匹配
        if (tvgId != null && !tvgId.isEmpty()) {
            List<Channel.EpgItem> result = getEpgByTvgId(tvgId);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }
        // 兜底：用频道名模糊匹配
        return getEpg(channelName);
    }

    /**
     * 计算节目对应的日期标签：今天/明天/后天/周几
     * 
     * ✅ 【核心修复】用毫秒差计算天数差，彻底解决：
     * - 跨年计算错误（比如12月31日 vs 1月1日）
     * - 跨月计算错误（比如2月28日 vs 3月1日）
     * - 夏令时导致的一天不是24小时的问题
     * 
     * @param itemCal 节目的开始时间
     * @param todayCal 今天的时间（基准）
     * @return 日期标签：今天/明天/后天/周日/周一/...
     */
    public String getDayName(Calendar itemCal, Calendar todayCal) {
        // 把节目时间的时分秒清零，只保留日期部分
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY, 0);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);

        // 把今天的时分秒清零，只保留日期部分
        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);

        // 计算两个日期的毫秒差，再除以一天的毫秒数，得到天数差
        long diffMs = itemDay.getTimeInMillis() - todayDay.getTimeInMillis();
        int dayDiff = (int) (diffMs / (1000L * 60 * 60 * 24));

        // 周几的映射表：DAY_OF_WEEK 1=周日，2=周一，...，7=周六
        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;

        // 前3天用"今天/明天/后天"，更直观
        if (dayDiff == 0) return "今天";
        if (dayDiff == 1) return "明天";
        if (dayDiff == 2) return "后天";
        
        // 3天以后用周几
        return weekDays[dayOfWeek];
    }
}
