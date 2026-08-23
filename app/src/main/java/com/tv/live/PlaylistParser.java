package com.tv.live;

import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Response;

public class PlaylistParser {

    public static List<Channel> parse(String url) throws Exception {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        try (Response response = NetUtil.getInstance().syncGet(url)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("请求失败 code=" + response.code());
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(response.body().byteStream()));
            return parseInternal(br, channelMap);
        }
    }

    public static List<Channel> parseContent(String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        BufferedReader br = new BufferedReader(new StringReader(content));
        return parseInternal(br, channelMap);
    }

    private static List<Channel> parseInternal(BufferedReader br, Map<String, Channel> channelMap) throws Exception {
        String line;
        String currentGroup = "未分类";

        // —— M3U 解析用临时状态 ——
        String m3uName = "";
        String m3uTvgId = "";
        String m3uGroup = "";
        boolean pendingM3uUri = false;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // ============================================================
            // ✅ 格式 A：DIYP TXT（666 源使用此格式）
            //   1) 分组标记：  央卫,#genre#
            //   2) 频道条目：  CCTV-1,http://xxx.m3u8
            //   3) 同名多源：  CCTV-1,http://a  后面再跟  CCTV-1,http://b → 合并备用源
            // ============================================================
            if (line.endsWith(",#genre#") || line.endsWith("#genre#")) {
                // 分组切换
                String group = line;
                if (group.endsWith(",#genre#")) {
                    group = group.substring(0, group.length() - ",#genre#".length()).trim();
                } else if (group.endsWith("#genre#")) {
                    int idx = group.lastIndexOf("#genre#");
                    if (idx > 0) group = group.substring(0, idx).trim();
                    if (group.endsWith(",")) group = group.substring(0, group.length() - 1).trim();
                }
                if (!group.isEmpty()) currentGroup = group;

                // 若有 M3U #EXTINF 等待的 uri，清除状态（避免与下一行混淆）
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = "";
                continue;
            }

            // DIYP 频道行：匹配「名称,http(s)://...」（第一个逗号前是频道名）
            int diypComma = findFirstHttpComma(line);
            if (diypComma > 0) {
                String diypName = line.substring(0, diypComma).trim();
                String diypUri = line.substring(diypComma + 1).trim();
                if (!diypName.isEmpty() && diypUri.startsWith("http")) {
                    addOrMergeChannel(channelMap, diypName, diypUri, currentGroup, "");
                    pendingM3uUri = false;
                    m3uName = ""; m3uTvgId = ""; m3uGroup = "";
                    continue;
                }
            }

            // ============================================================
            // 格式 B：标准 M3U (#EXTM3U / #EXTINF / #EXTGRP)
            // ============================================================
            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            if (line.startsWith("#EXTINF:")) {
                m3uName = "";
                m3uTvgId = "";
                m3uGroup = currentGroup;

                if (line.contains("tvg-id=\"")) {
                    try {
                        m3uTvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains("group-title=\"")) {
                    try {
                        m3uGroup = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains(",")) {
                    m3uName = line.substring(line.indexOf(",") + 1).trim();
                }
                pendingM3uUri = true;
                continue;
            }

            // 非注释行 + M3U 等待 uri：下一行就是 URL
            if (!line.startsWith("#") && pendingM3uUri) {
                String uri = line;
                if (uri.startsWith("http")) {
                    String key = !m3uTvgId.isEmpty() ? m3uTvgId : m3uName;
                    if (!key.isEmpty()) {
                        addOrMergeChannel(channelMap, m3uName, uri, m3uGroup, m3uTvgId);
                    }
                }
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = "";
                continue;
            }

            // 兜底：既不是 M3U 也不是 DIYP，但直接就是一个 URL（无逗号、非注释）
            // —— 按 URL 本身当临时名加入未分类，避免漏项
            if (!line.startsWith("#") && line.startsWith("http")) {
                addOrMergeChannel(channelMap, line, line, currentGroup, "");
            }
        }
        br.close();
        return new ArrayList<>(channelMap.values());
    }

    /** 在 channelMap 里新增或合并频道（同名多源 → 备用 URL 列表） */
    private static void addOrMergeChannel(Map<String, Channel> channelMap,
                                          String name, String uri,
                                          String group, String tvgId) {
        if (name == null || name.isEmpty() || uri == null || !uri.startsWith("http")) return;
        String key = (tvgId != null && !tvgId.isEmpty()) ? tvgId : name;
        if (key.isEmpty()) return;

        Channel existing = channelMap.get(key);
        if (existing != null) {
            existing.addBackupUrl(uri);
            if (group != null && !group.isEmpty()) {
                existing.setGroup(group);
            }
        } else {
            Channel newChannel = new Channel(name, uri, group, tvgId);
            channelMap.put(key, newChannel);
        }
    }

    /** 寻找 DIYP TXT 行里「频道名,http://...」的第一个逗号位置（URL 不会包含逗号，安全取第一个 http 前的逗号） */
    private static int findFirstHttpComma(String line) {
        if (line == null || line.isEmpty()) return -1;
        int httpIdx = line.indexOf("http://");
        if (httpIdx < 0) httpIdx = line.indexOf("https://");
        if (httpIdx <= 1) return -1;
        // 找 httpIdx 之前最后一个逗号（支持 频道名里有逗号的极端情况，以 http 前第一个逗号为界）
        int comma = line.lastIndexOf(',', httpIdx - 1);
        if (comma <= 0) return -1;
        return comma;
    }
}
