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

                if (line.contains("tvg-id=\"")) {
                    try {
                        tvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains("group-title=\"")) {
                    try {
                        group = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                String uri = br.readLine();
                if (uri == null || !uri.startsWith("http")) continue;

                String key = !tvgId.isEmpty() ? tvgId : name;
                if (key.isEmpty()) continue;

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
        }
        br.close();
        return new ArrayList<>(channelMap.values());
    }
}
