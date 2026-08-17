package com.tv.live;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

class PlayerQualityManager {
    private static final String TAG = "PlayerQualityManager";

    private final TVPlayerManager mgr;

    PlayerQualityManager(TVPlayerManager mgr) {
        this.mgr = mgr;
    }

    List<String> getAvailableResolutions() {
        List<String> resolutions = new ArrayList<>();
        synchronized (mgr.variantListLock) {
            for (TVPlayerManager.Variant v : mgr.variantList) {
                String label = v.getDisplayLabel();
                if (!resolutions.contains(label)) {
                    resolutions.add(label);
                }
            }
        }
        return resolutions;
    }

    List<String> getAvailableLines() {
        List<String> lines = new ArrayList<>();
        Channel ch = mgr.currentChannel;
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

    void switchToHuyaLine(int lineIndex) {
        Channel ch = mgr.currentChannel;
        if (ch == null) return;

        if (lineIndex < 0) lineIndex = 0;

        if (lineIndex == 0) {
            String url = ch.getMainPlayUrl();
            if (!TextUtils.isEmpty(url)) {
                ch.setCurrentLineIndex(0);
                mgr.playbackManager.playUrlInternal(url);
            }
        } else {
            List<String> backups = ch.getBackupUrls();
            int backupIdx = lineIndex - 1;
            if (backups != null && backupIdx >= 0 && backupIdx < backups.size()) {
                ch.setCurrentLineIndex(lineIndex);
                mgr.playbackManager.playUrlInternal(backups.get(backupIdx));
            }
        }
    }

    private int findLineIndexByUrl(String url) {
        synchronized (mgr.variantListLock) {
            for (TVPlayerManager.Variant v : mgr.variantList) {
                if (url.equals(v.url)) return v.huyaLineIndex;
            }
        }
        return -1;
    }

    private void rebuildVariantListForLine(int lineIndex) {
        synchronized (mgr.variantListLock) {
            List<TVPlayerManager.Variant> currentLine = new ArrayList<>();
            List<TVPlayerManager.Variant> otherLines = new ArrayList<>();
            for (TVPlayerManager.Variant v : mgr.variantList) {
                if (v.huyaLineIndex == lineIndex) {
                    currentLine.add(v);
                } else {
                    otherLines.add(v);
                }
            }
            currentLine.sort((a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
            mgr.variantList.clear();
            mgr.variantList.addAll(currentLine);
            mgr.variantList.addAll(otherLines);

            if (!currentLine.isEmpty()) {
                mgr.currentResolutionLabel = currentLine.get(0).getDisplayLabel();
            }
        }
        Log.d(TAG, "【虎牙】切换到线路 " + lineIndex + ", 当前线路清晰度: " + getAvailableResolutions());
    }

    void switchToResolution(int targetHeight, String... matchLabelOpt) {
        List<TVPlayerManager.Variant> snapshot;
        synchronized (mgr.variantListLock) {
            snapshot = new ArrayList<>(mgr.variantList);
        }
        if (snapshot.isEmpty()) {
            Log.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        String matchLabel = (matchLabelOpt != null && matchLabelOpt.length > 0) ? matchLabelOpt[0] : null;
        TVPlayerManager.Variant selected = null;

        if (!TextUtils.isEmpty(matchLabel)) {
            for (TVPlayerManager.Variant v : snapshot) {
                if (matchLabel.equals(v.getDisplayLabel())
                        || matchLabel.equals(v.resolutionLabel)) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null && targetHeight > 0) {
            for (TVPlayerManager.Variant v : snapshot) {
                if (v.height >= targetHeight) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null) {
            selected = snapshot.get(0);
        }
        mgr.currentResolutionLabel = selected.getDisplayLabel();
        mgr.dLog("切换清晰度到：" + selected.getDisplayLabel() + "，URL=" + (selected.url != null ? selected.url.substring(0, Math.min(60, selected.url.length())) : "(空)"));
        mgr.playbackManager.playUrlInternal(selected.url);
    }

    public static String inferResolutionLabelFromUrl(String url, int bitRate) {
        if (url.contains("_6000.") || bitRate >= 6000) return "1080p高清";
        if (url.contains("_4000.") || bitRate >= 4000) return "1080p";
        if (url.contains("_2000.") || bitRate >= 2000) return "720p";
        if (url.contains("_1000.") || bitRate >= 1000) return "480p";
        if (url.contains("_500.") || bitRate >= 400) return "360p";
        return "360p";
    }

    public static int inferHeightFromUrl(String url, int bitRate) {
        if (url.contains("_6000.") || bitRate >= 6000) return 1080;
        if (url.contains("_4000.") || bitRate >= 4000) return 1080;
        if (url.contains("_2000.") || bitRate >= 2000) return 720;
        if (url.contains("_1000.") || bitRate >= 1000) return 480;
        if (url.contains("_500.") || bitRate >= 400) return 360;
        return 360;
    }
}