package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlaylistParser {

    /**
     * 从网络 URL 解析直播源
     * (内部会发起网络请求下载 M3U 文件)
     */
    public static List<Channel> parse(String url) throws Exception {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new URL(url).openStream()));
        return parseInternal(br, channelMap);
    }

    /**
     * 🟢【新增】从字符串内容解析直播源
     * (推荐 LiveSourceLoader 使用此方法，避免重复网络请求)
     */
    public static List<Channel> parseContent(String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        BufferedReader br = new BufferedReader(new StringReader(content));
        return parseInternal(br, channelMap);
    }

    // ============================================================
    // 🟢【核心抽取】将原有逻辑提取为通用内部方法
    // ============================================================
    private static List<Channel> parseInternal(BufferedReader br, Map<String, Channel> channelMap) throws Exception {
        String line;
        String currentGroup = "未分类";

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            if (line.startsWith("#EXTINF:")) {
                String name = "";
                String tvgId = "";
                String group = currentGroup;

                // 提取 tvg-id
                if (line.contains("tvg-id=\"")) {
                    try {
                        tvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取 group-title
                if (line.contains("group-title=\"")) {
                    try {
                        group = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取频道名称
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                String uri = br.readLine();
                if (uri == null || !uri.startsWith("http")) continue;

                // 全局去重：优先使用 tvg-id，没有则用频道名作为 Key
                String key = !tvgId.isEmpty() ? tvgId : name;
                if (key.isEmpty()) continue;

                Channel existing = channelMap.get(key);
                if (existing != null) {
                    // 🚫 已取消：当主源无效时自动跳转备用源
                    // existing.addBackupUrl(uri); 
                    
                    // 🟢【核心修改】只要解析到有效的分组名称，就无条件覆盖旧分组！
                    if (group != null && !group.isEmpty()) {
                        existing.setGroup(group);
                    }
                } else {
                    // ✅ 频道不存在：新建（第一条作为主源 mainPlayUrl）
                    Channel newChannel = new Channel(name, uri, group, tvgId);
                    channelMap.put(key, newChannel);
                }
            }
        }
        br.close();
        // 将 Map 的所有 value 转成 List 返回
        return new ArrayList<>(channelMap.values());
    }
}
