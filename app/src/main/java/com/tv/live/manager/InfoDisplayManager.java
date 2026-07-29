package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.TVPlayerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 信息展示管理器（已恢复频道号显示与自动隐藏）
 */
public class InfoDisplayManager {
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000; // ✅ 新增：频道号隐藏延迟
    private static final long PROGRAM_PROGRESS_INTERVAL = 30000;

    private Context context;
    private TextView tvChannelNum;
    private View infoBar;
    private TextView tvChannelName;
    private TextView tvTagFhd;
    private TextView tvTagAudio;
    private TextView tvBitrate;
    private TextView tvCurrentProgramName;
    private TextView tvCurrentTimeRange;
    private ProgressBar progressProgram;
    private TextView tvRemainingTime;
    private TextView tvNextProgramName;
    private TextView tvNextTimeRange;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Channel currentPlayChannel;

    private boolean isReleased = false;

    private final Runnable hideInfoBarTask = new Runnable() {
        @Override
        public void run() {
            if(infoBar != null) infoBar.setVisibility(View.GONE);
        }
    };

    // ✅【恢复】频道号自动隐藏任务
    private final Runnable hideChannelNumTask = new Runnable() {
        @Override
        public void run() {
            if(tvChannelNum != null) tvChannelNum.setVisibility(View.GONE);
        }
    };

    private final Runnable refreshProgressTask = new Runnable() {
        @Override
        public void run() {
            if (currentPlayChannel != null) {
                performEpgUpdateInBackground(currentPlayChannel);
            }
            mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    public InfoDisplayManager(Context context,
                              TextView tvChannelNum,
                              View infoBar,
                              TextView tvChannelName,
                              TextView tvTagFhd,
                              TextView tvTagAudio,
                              TextView tvBitrate,
                              TextView tvCurrentProgramName,
                              TextView tvCurrentTimeRange,
                              ProgressBar progressProgram,
                              TextView tvRemainingTime,
                              TextView tvNextProgramName,
                              TextView tvNextTimeRange){
        this.context = context.getApplicationContext();
        this.tvChannelNum = tvChannelNum;
        this.infoBar = infoBar;
        this.tvChannelName = tvChannelName;
        this.tvTagFhd = tvTagFhd;
        this.tvTagAudio = tvTagAudio;
        this.tvBitrate = tvBitrate;
        this.tvCurrentProgramName = tvCurrentProgramName;
        this.tvCurrentTimeRange = tvCurrentTimeRange;
        this.progressProgram = progressProgram;
        this.tvRemainingTime = tvRemainingTime;
        this.tvNextProgramName = tvNextProgramName;
        this.tvNextTimeRange = tvNextTimeRange;
        if(tvTagAudio != null){
            tvTagAudio.setText("立体声");
        }
    }

    // ✅【恢复】显示频道号并开始倒计时隐藏
    public void showChannelNum(int num){
        if(tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        // 取消之前的隐藏任务，重新开始计时
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }

    // ✅【恢复】手动隐藏频道号
    public void hideChannelNum(){
        if(tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }

    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        updateLiveInfo(liveInfo);
        performEpgUpdateInBackground(channel);
        startProgressLoop();
    }

    public void hideInfoBar(){
        if(infoBar == null) return;
        mainHandler.removeCallbacks(hideInfoBarTask);
        infoBar.setVisibility(View.GONE);
    }

    public void updateLiveInfo(TVPlayerManager.LiveInfo info){
        if(info == null) return;
        if(tvTagFhd != null){
            tvTagFhd.setText(parseQualityText(info.resolution));
        }
        if(tvBitrate != null){
            tvBitrate.setText(info.bitrate);
        }
    }

    private String parseQualityText(String resolution){
        if(resolution == null || resolution.isEmpty()) return "未知";
        try {
            String[] split = resolution.split("×");
            if(split.length >= 2){
                int height = Integer.parseInt(split[1].trim());
                if(height >= 1080) return "FHD";
                else if(height >=720) return "HD";
                else return "SD";
            }
        }catch (Exception e){
            Log.e("InfoDisplayManager", "【分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }

    public void updateEpgInfo(Channel channel){
        if(channel == null) return;
        currentPlayChannel = channel;
        performEpgUpdateInBackground(channel);
    }

    private void performEpgUpdateInBackground(Channel channel) {
        if (channel == null) return;
        new Thread(() -> {
            try {
                List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());
                
                EpgCalculationResult result = calculateEpgData(epgList, channel);

                mainHandler.post(() -> {
                    if (isReleased) {
                        return;
                    }
                    if (result == null) {
                        setEpgEmptyUi();
                        return;
                    }
                    applyEpgUiResult(result, channel);
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    if (!isReleased) {
                        setEpgEmptyUi();
                    }
                });
            }
        }).start();
    }

    private EpgCalculationResult calculateEpgData(List<Channel.EpgItem> epgList, Channel channel) {
        if (epgList == null || epgList.isEmpty()) {
            if (EpgManager.getInstance().getChannelEpgMapSize() == 0) {
                EpgCalculationResult loadingResult = new EpgCalculationResult();
                loadingResult.isLoading = true;
                return loadingResult;
            }
            return null;
        }

        List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
        if (todayEpg.isEmpty()) {
            return null;
        }

        sortEpgByTime(todayEpg);
        String nowTime = getCurrentTimeStr();
        Channel.EpgItem currItem = null;
        Channel.EpgItem nextItem = null;
        int currIndex = -1;

        for(int i=0; i<todayEpg.size(); i++){
            Channel.EpgItem item = todayEpg.get(i);
            String start = extractTimeSegment(item.time, false);
            String end = (i+1 < todayEpg.size()) ? extractTimeSegment(todayEpg.get(i+1).time, false) : "23:59";
            if(timeBetween(nowTime, start, end)){
                currItem = item;
                currIndex = i;
                if(i+1 < todayEpg.size()) nextItem = todayEpg.get(i+1);
                break;
            }
        }

        EpgCalculationResult result = new EpgCalculationResult();
        result.currItem = currItem;
        result.nextItem = nextItem;
        result.currIndex = currIndex;
        result.todayList = todayEpg;
        result.nowTime = nowTime;
        result.isLoading = false;
        return result;
    }

    private void applyEpgUiResult(EpgCalculationResult result, Channel channel) {
        if (isReleased) {
            return;
        }
        if (tvCurrentProgramName == null && tvCurrentTimeRange == null && progressProgram == null) {
            return;
        }

        if (result.isLoading) {
            setEpgLoadingUi();
            return;
        }

        Channel.EpgItem currItem = result.currItem;
        Channel.EpgItem nextItem = result.nextItem;
        int currIndex = result.currIndex;
        List<Channel.EpgItem> todayList = result.todayList;
        String nowTime = result.nowTime;

        if (currItem != null) {
            if (tvCurrentProgramName != null) {
                tvCurrentProgramName.setText(currItem.title);
            }
            String start = extractTimeSegment(currItem.time, false);
            String end = (currIndex + 1 < todayList.size()) ? extractTimeSegment(todayList.get(currIndex + 1).time, false) : "23:59";
            if (tvCurrentTimeRange != null) {
                tvCurrentTimeRange.setText(String.format(Locale.ROOT, "%s - %s", start, end));
            }

            long nowMs = timeToMs(nowTime, false, 0);
            long sMs = timeToMs(start, false, 0);
            long eMs = timeToMs(end, true, sMs);
            if (progressProgram != null) {
                long totalDuration = eMs - sMs;
                long played = nowMs - sMs;
                int progress = 0;
                if (totalDuration > 0) {
                    progress = (int) (played * 100 / totalDuration);
                    progress = Math.max(0, Math.min(100, progress));
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            if (tvRemainingTime != null) {
                long played = nowMs - sMs;
                if (played < 0) {
                    tvRemainingTime.setText("已播放0分钟");
                } else {
                    long playedSec = played / 1000;
                    long validSec = playedSec % (24 * 3600);
                    long playedMin = validSec / 60;
                    if (playedMin >= 60) {
                        int h = (int) (playedMin / 60);
                        int m = (int) (playedMin % 60);
                        tvRemainingTime.setText(String.format(Locale.ROOT, "已播放%d时%d分", h, m));
                    } else {
                        tvRemainingTime.setText(String.format(Locale.ROOT, "已播放%d分钟", playedMin));
                    }
                }
            }
        } else {
            if (tvCurrentProgramName != null) {
                tvCurrentProgramName.setText("暂无节目信息");
            }
            if (tvCurrentTimeRange != null) {
                tvCurrentTimeRange.setText("");
            }
            if (progressProgram != null) {
                progressProgram.setProgress(0);
                progressProgram.invalidate();
            }
            if (tvRemainingTime != null) {
                tvRemainingTime.setText("");
            }
        }

        if (nextItem != null) {
            String s = extractTimeSegment(nextItem.time, false);
            String e = (currIndex + 2 < todayList.size()) ? extractTimeSegment(todayList.get(currIndex + 2).time, false) : "23:59";
            if (tvNextTimeRange != null) {
                tvNextTimeRange.setText(String.format(Locale.ROOT, "%s - %s", s, e));
            }
            if (tvNextProgramName != null) {
                tvNextProgramName.setText(nextItem.title);
            }
        } else {
            if (tvNextProgramName != null) {
                tvNextProgramName.setText("暂无下一档节目");
            }
            if (tvNextTimeRange != null) {
                tvNextTimeRange.setText("");
            }
        }
    }

    private String extractTimeSegment(String fullTime, boolean isEnd) {
        if (fullTime == null || fullTime.trim().isEmpty()) return "";
        String trimmed = fullTime.trim();
        if (trimmed.contains(" - ")) {
            String[] parts = trimmed.split(" - ");
            if (parts.length >= 2) {
                return isEnd ? parts[1].trim() : parts[0].trim();
            }
        }
        return trimmed;
    }

    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source){
        List<Channel.EpgItem> res = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekArr = {"周日","周一","周二","周三","周四","周五","周六"};
        String todayWeek = weekArr[weekNum - 1];
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String todayDate = sdf.format(cal.getTime());

        for(Channel.EpgItem item : source){
            if(item.dayName == null) continue;
            String day = item.dayName.trim();
            if ("今天".equals(day) || todayWeek.equals(day) || todayDate.equals(day)) {
                res.add(item);
            }
        }
        return res;
    }

    private void sortEpgByTime(List<Channel.EpgItem> list){
        Collections.sort(list, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                String t1 = (o1.time != null) ? extractTimeSegment(o1.time, false) : "";
                String t2 = (o2.time != null) ? extractTimeSegment(o2.time, false) : "";
                return t1.compareTo(t2);
            }
        });
    }

    private void setEpgEmptyUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }

    private void setEpgLoadingUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("节目单加载中...");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }

    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }

    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }

    private String getCurrentTimeStr(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    private boolean timeBetween(String now, String start, String end){
        try {
            if (now == null || start == null || end == null) return false;
            long nowMs = timeToMs(now, false, 0);
            long startMs = timeToMs(start, false, 0);
            long endMs = timeToMs(end, true, startMs);
            return nowMs >= startMs && nowMs < endMs;
        }catch (Exception e){
            return false;
        }
    }

    private long timeToMs(String timeStr, boolean isEndTime, long startMs){
        try {
            String targetTime = extractTimeSegment(timeStr, isEndTime);
            if (targetTime.isEmpty()) return 0;
            String[] split = targetTime.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long ms = cal.getTimeInMillis();
            if(isEndTime && ms <= startMs){
                cal.add(Calendar.DAY_OF_MONTH, 1);
                ms = cal.getTimeInMillis();
            }
            return ms;
        }catch (Exception e){
            return 0;
        }
    }

    public void release(){
        mainHandler.removeCallbacksAndMessages(null);
        isReleased = true;
        currentPlayChannel = null;
        context = null;
        tvChannelNum = null;
        infoBar = null;
        tvChannelName = null;
        tvTagFhd = null;
        tvTagAudio = null;
        tvBitrate = null;
        tvCurrentProgramName = null;
        tvCurrentTimeRange = null;
        progressProgram = null;
        tvRemainingTime = null;
        tvNextProgramName = null;
        tvNextTimeRange = null;
    }

    private static class EpgCalculationResult {
        boolean isLoading = false;
        Channel.EpgItem currItem;
        Channel.EpgItem nextItem;
        int currIndex;
        List<Channel.EpgItem> todayList;
        String nowTime;
    }
}
