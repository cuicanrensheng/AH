package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
        List<Channel> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new URL(url).openStream(), "UTF-8"));
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

                try {
                    if (line.contains("tvg-id=\"")) {
                        tvgId = line.split("tvg-id=\"")[1].split("\"")[0];
                    }
                } catch (Exception ignored) {}

                try {
                    if (line.contains("group-title=\"")) {
                        group = line.split("group-title=\"")[1].split("\"")[0];
                    }
                } catch (Exception ignored) {}

                try {
                    if (line.contains(",")) {
                        name = line.substring(line.indexOf(",") + 1).trim();
                    }
                } catch (Exception ignored) {}

                String uri = br.readLine();
                if (uri != null) {
                    uri = uri.trim();
                    if (uri.startsWith("http")) {
                        list.add(new Channel(name, uri, group, tvgId));
                    }
                }
            }
        }
        br.close();
        return list;
    }
}
