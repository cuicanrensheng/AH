package com.tv.live.util;

import android.text.TextUtils;
import android.util.Log;

import com.tv.live.Channel;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 清晰度/线路管理器
 * 负责 variantList 管理、清晰度切换、线路切换、
 * M3U 播放列表解析、URL 解析等
 */
public class VariantManager {
    private static final String TAG = "VariantManager";

    public interface PlaybackCallback {
        void playUrl(String url);
        Channel getCurrentChannel();
        void dLog(String msg);
    }

    private PlaybackCallback callback;
    private final Object variantListLock = new Object();
    private volatile List<Variant> variantList = new ArrayList<>();
    private volatile boolean isParsingMasterPlaylist = false;
    private String currentResolutionLabel = "自适应";

    private int mCurrentHuyaLineIndex = 0;

    public VariantManager(PlaybackCallback callback) {
        this.callback = callback;
    }

    public List<Variant> getVariantList() {
        synchronized (variantListLock) {
            return new ArrayList<>(variantList);
        }
    }

    public void clearVariantList() {
        synchronized (variantListLock) {
            variantList.clear();
        }
    }

    public void addVariants(List<Variant> variants) {
        synchronized (variantListLock) {
            variantList.addAll(variants);
        }
    }

    public void setVariantList(List<Variant> list) {
        synchronized (variantListLock) {
            this.variantList = list;
        }
    }

    public boolean isParsingMasterPlaylist() {
        return isParsingMasterPlaylist;
    }

    public void setParsingMasterPlaylist(boolean parsing) {
        isParsingMasterPlaylist = parsing;
    }

    public int getCurrentHuyaLineIndex() {
        return mCurrentHuyaLineIndex;
    }

    public void setCurrentHuyaLineIndex(int index) {
        mCurrentHuyaLineIndex = index;
    }

    public String getCurrentResolutionLabel() {
        return currentResolutionLabel;
    }

    public void setCurrentResolutionLabel(String label) {
        this.currentResolutionLabel = label;
    }

    public List<String> getAvailableResolutions() {
        List<String> resolutions = new ArrayList<>();
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                String label = v.getDisplayLabel();
                if (!resolutions.contains(label)) {
                    resolutions.add(label);
                }
            }
        }
        return resolutions;
    }

    public List<String> getAvailableLines() {
        List<String> lines = new ArrayList<>();
        Channel ch = callback.getCurrentChannel();
        if (ch == null) return lines;

        lines.add("主源");
        List<String> backups = ch.getBackupUrls();
        if (backups != null) {
            for (int i = 0; i < backups.size(); i++) {
                lines.add("源" + (i + 1));
            }
        }
        return lines;
    }

    public void switchToHuyaLine(int lineIndex) {
        Channel ch = callback.getCurrentChannel();
        if (ch == null) return;

        if (lineIndex < 0) lineIndex = 0;

        if (lineIndex == 0) {
            String url = ch.getMainPlayUrl();
            if (!TextUtils.isEmpty(url)) {
                ch.setCurrentLineIndex(0);
                callback.playUrl(url);
            }
        } else {
            List<String> backups = ch.getBackupUrls();
            int backupIdx = lineIndex - 1;
            if (backups != null && backupIdx >= 0 && backupIdx < backups.size()) {
                ch.setCurrentLineIndex(lineIndex);
                callback.playUrl(backups.get(backupIdx));
            }
        }
    }

    private int findLineIndexByUrl(String url) {
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                if (url.equals(v.url)) return v.huyaLineIndex;
            }
        }
        return -1;
    }

    private void rebuildVariantListForLine(int lineIndex) {
        synchronized (variantListLock) {
            List<Variant> currentLine = new ArrayList<>();
            List<Variant> otherLines = new ArrayList<>();
            for (Variant v : variantList) {
                if (v.huyaLineIndex == lineIndex) {
                    currentLine.add(v);
                } else {
                    otherLines.add(v);
                }
            }
            Collections.sort(currentLine, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
            variantList.clear();
            variantList.addAll(currentLine);
            variantList.addAll(otherLines);

            if (!currentLine.isEmpty()) {
                currentResolutionLabel = currentLine.get(0).getDisplayLabel();
            }
        }
        Log.d(TAG, "【虎牙】切换到线路 " + lineIndex + ", 当前线路清晰度: " + getAvailableResolutions());
    }

    public void switchToResolution(int targetHeight, String... matchLabelOpt) {
        List<Variant> snapshot;
        synchronized (variantListLock) {
            snapshot = new ArrayList<>(variantList);
        }
        if (snapshot.isEmpty()) {
            Log.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        String matchLabel = (matchLabelOpt != null && matchLabelOpt.length > 0) ? matchLabelOpt[0] : null;
        Variant selected = null;

        if (!TextUtils.isEmpty(matchLabel)) {
            for (Variant v : snapshot) {
                if (matchLabel.equals(v.getDisplayLabel())
                        || matchLabel.equals(v.resolutionLabel)) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null && targetHeight > 0) {
            for (Variant v : snapshot) {
                if (v.height >= targetHeight) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null) {
            selected = snapshot.get(0);
        }
        currentResolutionLabel = selected.getDisplayLabel();
        callback.dLog("切换清晰度到：" + selected.getDisplayLabel() + "，URL=" + (selected.url != null ? selected.url.substring(0, Math.min(60, selected.url.length())) : "(空)"));
        callback.playUrl(selected.url);
    }

    public void parseMasterPlaylist(String playlist, String baseUrl) {
        List<Variant> list = new ArrayList<>();
        Pattern streamInfPattern = Pattern.compile("^#EXT-X-STREAM-INF:", Pattern.CASE_INSENSITIVE);
        Pattern bandwidthPattern = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern resolutionPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
        callback.dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!streamInfPattern.matcher(line).find()) continue;

            Matcher bwMatcher = bandwidthPattern.matcher(line);
            if (!bwMatcher.find()) continue;
            int bandwidth = Integer.parseInt(bwMatcher.group(1));

            int width = 0, height = 0;
            String resolutionStr = null;
            Matcher resMatcher = resolutionPattern.matcher(line);
            if (resMatcher.find()) {
                width = Integer.parseInt(resMatcher.group(1));
                height = Integer.parseInt(resMatcher.group(2));
                resolutionStr = width + "x" + height;
            }

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    uri = next;
                    break;
                }
            }
            if (uri != null) {
                if (!uri.startsWith("http")) {
                    uri = resolveUrl(baseUrl, uri);
                }
                list.add(new Variant(uri, bandwidth, width, height));
                callback.dLog("解析到清晰度: " + (height > 0 ? resolutionStr : "自适应") + " -> " + uri);
            }
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.height, b.height));
        synchronized (variantListLock) { this.variantList = list; }
        if (!list.isEmpty()) {
            callback.dLog("解析到 " + list.size() + " 个清晰度");
        } else {
            Log.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被拦截");
        }
    }

    public String resolveUrl(String base, String relative) {
        try {
            URL baseUrl = new URL(base);
            URL resolved = new URL(baseUrl, relative);
            return resolved.toString();
        } catch (Exception e) {
            return relative;
        }
    }

    public void release() {
        synchronized (variantListLock) {
            variantList.clear();
        }
        callback = null;
        currentResolutionLabel = null;
        isParsingMasterPlaylist = false;
    }
}
