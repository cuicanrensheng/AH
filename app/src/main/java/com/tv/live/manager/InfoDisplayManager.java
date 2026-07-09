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
 * 信息展示管理器【优化版：彻底隔离主线程与EPG计算】
 */
public class InfoDisplayManager {
    // ===================== 定时延时常量 =====================
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 30000;

    // ===================== UI控件引用 =====================
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

    private final Runnable hideInfoBarTask = new Runnable() {
        @Override
        public void run() {
            if(infoBar != null) infoBar.setVisibility(View.GONE);
        }
    };

    private final Runnable hideChannelNumTask = new Runnable() {
        @Override
        public void run() {
            if(tvChannelNum != null) tvChannelNum.setVisibility(View.GONE);
        }
    };

    // 🟢【优化1】定时刷新器：只负责触发任务，不在此处进行任何 UI 阻塞操作
    private final Runnable refreshProgressTask = new Runnable() {
        @Override
        public void run() {
            if (currentPlayChannel != null) {
                // 触发后台线程执行 EPG 刷新
                performEpgUpdateInBackground(currentPlayChannel);
            }
            mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    // ===================== 构造方法 =====================
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

    // ===================== 频道数字弹窗 =====================
    public void showChannelNum(int num){
        if(tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }

    public void hideChannelNum(){
        if(tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ===================== 底部信息栏 =====================
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        // 优先更新码率、画质（主线程快速执行）
        updateLiveInfo(liveInfo);
        // 后处理EPG节目信息（触发后台计算）
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

    // 🟢【优化2】核心改动：将所有耗时的 EPG 匹配逻辑彻底丢到子线程
    private void performEpgUpdateInBackground(Channel channel) {
        if (channel == null) return;
        new Thread(() -> {
            try {
                // 1. 后台获取数据
                List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());
                
                // 2. 后台进行过滤、排序、时间匹配计算
                EpgCalculationResult result = calculateEpgData(epgList, channel);

                // 3. 切回主线程进行简单的 UI 渲染
                mainHandler.post(() -> {
                    if (result == null) {
                        setEpgEmptyUi();
                        return;
                    }
                    applyEpgUiResult(result, channel);
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(this::setEpgEmptyUi);
            }
        }).start();
    }

    // 🟢【优化3】在后台线程中执行纯数据运算，严禁触碰 View
    private EpgCalculationResult calculateEpgData(List<Channel.EpgItem> epgList, Channel channel) {
        // 如果 EPG 未加载完
        if (EpgManager.getInstance().getChannelEpgMapSize() == 0) {
            EpgCalculationResult loadingResult = new EpgCalculationResult();
            loadingResult.isLoading = true;
            return loadingResult;
        }

        if (epgList == null || epgList.isEmpty()) {
            return null; // 表示无数据，会触发主线程刷新 Empty UI
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

        // 组装计算返回结果
        EpgCalculationResult result = new EpgCalculationResult();
        result.currItem = currItem;
        result.nextItem = nextItem;
        result.currIndex = currIndex;
        result.todayList = todayEpg;
        result.nowTime = nowTime;
        result.isLoading = false;
        return result;
    }

    // 🟢【优化4】主线程只执行这种纯 UI 绑定的低耗时方法
    private void applyEpgUiResult(EpgCalculationResult result, Channel channel) {
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
            tvCurrentProgramName.setText(currItem.title);
            String start = extractTimeSegment(currItem.time, false);
            String end = (currIndex+1 < todayList.size()) ? extractTimeSegment(todayList.get(currIndex+1).time, false) : "23:59";
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText(start + " - " + end);

            // 计算进度（依然要在主线程完成，但计算量很小，直接使用当前毫秒数）
            long nowMs = timeToMs(nowTime, false, 0);
            long sMs = timeToMs(start, false, 0);
            long eMs = timeToMs(end, true, sMs);
            if(progressProgram != null){
                long totalDuration = eMs - sMs;
                long played = nowMs - sMs;
                int progress = 0;
                if(totalDuration > 0){
                    progress = (int) (played * 100 / totalDuration);
                    progress = Math.max(0, Math.min(100, progress));
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            if(tvRemainingTime != null){
                long played = nowMs - sMs;
                if(played < 0){
                    tvRemainingTime.setText("已播放0分钟");
                } else {
                    long playedSec = played / 1000;
                    long validSec = playedSec % (24 * 3600);
                    long playedMin = validSec / 60;
                    if(playedMin >= 60){
                        int h = (int) (playedMin / 60);
                        int m = (int) (playedMin % 60);
                        tvRemainingTime.setText("已播放"+h+"时"+m+"分");
                    }else {
                        tvRemainingTime.setText("已播放"+playedMin+"分钟");
                    }
                }
            }
        } else {
            tvCurrentProgramName.setText("暂无节目信息");
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if(progressProgram != null) {
                progressProgram.setProgress(0);
                progressProgram.invalidate();
            }
            if(tvRemainingTime != null) tvRemainingTime.setText("");
        }

        // 下一档节目 UI
        if(nextItem != null && tvNextProgramName != null && tvNextTimeRange != null){
            String s = extractTimeSegment(nextItem.time, false);
            String e = (currIndex + 2 < todayList.size()) ? extractTimeSegment(todayList.get(currIndex+2).time, false) : "23:59";
            tvNextTimeRange.setText(s + " - " + e);
            tvNextProgramName.setText(nextItem.title);
        } else {
            if(tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
            if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    // ===================== 时间格式提取工具 =====================
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

    // ===================== 日期过滤与排序 =====================
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

    // ===================== 定时控制 =====================
    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }

    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }

    // ===================== 时间工具 =====================
    private String getCurrentTimeStr(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format("%02d:%02d", h, m);
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

    // 🟢【优化5】减少 Calendar.getInstance() 的频繁调用
    private long timeToMs(String timeStr, boolean isEndTime, long startMs){
        try {
            String targetTime = extractTimeSegment(timeStr, isEndTime);
            if (targetTime.isEmpty()) return 0;
            String[] split = targetTime.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            // 使用当前日期，但固定为今天的这个时间
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

    // ===================== 资源释放 =====================
    public void release(){
        // 🟢【优化6】彻底清空 Handler 中的所有排期任务，防止内存泄漏
        mainHandler.removeCallbacksAndMessages(null);
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

    // ============================================================
    // 🟢【优化7】引入内部数据类，用于在子线程和主线程间传递计算出的结果
    // ============================================================
    private static class EpgCalculationResult {
        boolean isLoading = false;
        Channel.EpgItem currItem;
        Channel.EpgItem nextItem;
        int currIndex;
        List<Channel.EpgItem> todayList;
        String nowTime;
    }
}
