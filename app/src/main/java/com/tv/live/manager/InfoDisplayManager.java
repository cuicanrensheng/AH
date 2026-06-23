package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.SettingsActivity;
import com.tv.live.TVPlayerManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息展示管理器
 *
 * 【职责】
 * 统一管理所有信息展示相关的 UI 组件，包括：
 * 1. 频道号显示（右上角弹出）
 * 2. 底部信息栏（频道名、画质、音频、码率、节目信息等）
 * 3. EPG 节目单数据计算和展示（当前节目、下一个节目、进度、剩余时间）
 *
 * 【拆分原因】
 * 这三部分代码在 MainActivity 里约 250 行，占了近 30%，
 * 而且都是纯 UI 展示逻辑，和业务逻辑耦合度低，适合独立拆分。
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：频道号显示状态、信息栏显示状态、进度更新状态
 * 2. 数据筛选层：EPG 节目筛选（今天/对应周几双重兼容）
 * 3. 状态同步层：切台时同步更新所有信息展示
 * 4. 异常兜底层：EPG 数据为空、解析异常时的兜底显示
 * 5. 交互闭环层：切台、面板开关等操作都触发信息更新
 */
public class InfoDisplayManager {

    // ====================== 常量 ======================
    /** 信息栏自动隐藏延迟（毫秒） */
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    /** 频道号自动隐藏延迟（毫秒） */
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    /** 节目进度更新间隔（1 分钟） */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // ====================== 视图引用 ======================
    private Context context;
    /** 频道号显示（右上角弹出） */
    private TextView tvChannelNum;
    /** 底部信息栏根布局 */
    private View infoBar;
    /** 频道名称 */
    private TextView tvChannelName;
    /** 画质标签（FHD/HD 等） */
    private TextView tvTagFhd;
    /** 音频信息 */
    private TextView tvTagAudio;
    /** 码率 */
    private TextView tvBitrate;
    /** 当前节目名称 */
    private TextView tvCurrentProgramName;
    /** 当前节目时间范围 */
    private TextView tvCurrentTimeRange;
    /** 节目进度条 */
    private ProgressBar progressProgram;
    /** 剩余时间 */
    private TextView tvRemainingTime;
    /** 下一个节目名称 */
    private TextView tvNextProgramName;
    /** 下一个节目时间范围 */
    private TextView tvNextTimeRange;

    // ====================== 状态相关 ======================
    /** Handler 用于延迟操作和定时更新 */
    private Handler handler = new Handler(Looper.getMainLooper());
    /** 隐藏信息栏的 Runnable */
    private final Runnable hideInfoBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (infoBar != null) {
                infoBar.setVisibility(View.GONE);
            }
        }
    };
    /** 隐藏频道号的 Runnable */
    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override
        public void run() {
            if (tvChannelNum != null) {
                tvChannelNum.setVisibility(View.GONE);
            }
        }
    };
    /** 节目进度更新 Runnable */
    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // 如果有当前频道，就更新一下进度
            if (currentChannel != null) {
                updateEpgInfoInternal(currentChannel);
            }
            // 继续下一次更新
            handler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };
    /** 当前播放的频道（用于定时更新进度） */
    private Channel currentChannel;

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param context           上下文
     * @param tvChannelNum      频道号 TextView
     * @param infoBar           信息栏根布局
     * @param tvChannelName     频道名称 TextView
     * @param tvTagFhd          画质标签 TextView
     * @param tvTagAudio        音频标签 TextView
     * @param tvBitrate         码率 TextView
     * @param tvCurrentProgramName  当前节目名称 TextView
     * @param tvCurrentTimeRange    当前节目时间范围 TextView
     * @param progressProgram   节目进度条
     * @param tvRemainingTime   剩余时间 TextView
     * @param tvNextProgramName 下一个节目名称 TextView
     * @param tvNextTimeRange   下一个节目时间范围 TextView
     */
    public InfoDisplayManager(
            Context context,
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
            TextView tvNextTimeRange
    ) {
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
    }

    // ====================================================================
    // 1. 频道号相关
    // ====================================================================

    /**
     * 显示频道号（右上角弹出，3 秒后自动隐藏）
     *
     * @param num 频道号（从 1 开始）
     */
    public void showChannelNum(int num) {
        if (tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        // 移除之前的隐藏任务，重新计时
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.postDelayed(hideChannelNumRunnable, CHANNEL_NUM_HIDE_DELAY);
    }

    /**
     * 立即隐藏频道号
     */
    public void hideChannelNum() {
        if (tvChannelNum == null) return;
        handler.removeCallbacks(hideChannelNumRunnable);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ====================================================================
    // 2. 信息栏相关
    // ====================================================================

    /**
     * 显示信息栏（切台时调用，2 秒后自动隐藏）
     * 同时更新频道名称、画质、音频、码率、EPG 节目信息
     *
     * @param channel   当前频道
     * @param liveInfo  播放器实时信息（画质、音频、码率）
     */
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (infoBar == null || channel == null) return;

        // 保存当前频道，用于定时更新进度
        currentChannel = channel;

        // 显示信息栏
        infoBar.setVisibility(View.VISIBLE);

        // 移除之前的隐藏任务，重新计时
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.postDelayed(hideInfoBarRunnable, INFO_BAR_HIDE_DELAY);

        // 更新频道名称
        if (tvChannelName != null) {
            tvChannelName.setText(channel.getName());
        }

        // 更新画质、音频、码率
        updateLiveInfo(liveInfo);

        // 更新 EPG 节目信息
        updateEpgInfoInternal(channel);

        // 重启节目进度定时更新
        startProgressUpdate();
    }

    /**
     * 立即隐藏信息栏
     */
    public void hideInfoBar() {
        if (infoBar == null) return;
        handler.removeCallbacks(hideInfoBarRunnable);
        infoBar.setVisibility(View.GONE);
    }

    /**
     * 更新直播信息（画质、音频、码率）
     * 播放器回调时调用
     *
     * @param info 播放器实时信息
     */
    public void updateLiveInfo(TVPlayerManager.LiveInfo info) {
        if (info == null) return;
        if (tvTagFhd != null) tvTagFhd.setText(info.quality);
        if (tvTagAudio != null) tvTagAudio.setText(info.audio);
        if (tvBitrate != null) tvBitrate.setText(info.bitrate);
    }

    // ====================================================================
    // 3. EPG 节目信息相关
    // ====================================================================

    /**
     * 更新 EPG 节目信息（公开方法，供外部调用）
     * 切台、EPG 加载完成时调用
     *
     * @param channel 当前频道
     */
    public void updateEpgInfo(Channel channel) {
        if (channel == null) return;
        currentChannel = channel;
        updateEpgInfoInternal(channel);
    }

    /**
     * 更新 EPG 节目信息（内部实现）
     * 计算当前节目、下一个节目、进度、剩余时间，并更新 UI
     *
     * 【数据筛选逻辑】
     * 1. 从 EpgManager 获取该频道的所有节目
     * 2. 筛选今天的节目（双重兼容："今天" 或 对应周几）
     * 3. 按开始时间排序
     * 4. 找到当前正在播放的节目
     * 5. 计算进度和剩余时间
     *
     * 【异常兜底】
     * - 没有 EPG 数据 → 显示"暂无节目信息"
     * - 解析异常 → 捕获异常，显示兜底文字
     *
     * @param channel 当前频道
     */
    private void updateEpgInfoInternal(Channel channel) {
        if (channel == null || tvCurrentProgramName == null) {
            return;
        }

        try {
            // 从 EpgManager 获取该频道的所有节目
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());

            if (epgList == null || epgList.isEmpty()) {
                // 没有匹配到节目数据
                setEpgEmpty();
                return;
            }

            // ========================================
            // 筛选今天的节目（双重兼容：今天/对应周几）
            // ========================================
            List<Channel.EpgItem> todayEpg = filterTodayPrograms(epgList);

            if (todayEpg.isEmpty()) {
                setEpgEmpty();
                return;
            }

            // 按开始时间排序
            sortProgramsByTime(todayEpg);

            // ========================================
            // 找到当前正在播放的节目
            // ========================================
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;

            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime;

                // 计算结束时间（下一个节目的开始时间）
                if (i + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(i + 1).time;
                } else {
                    endTime = "23:59"; // 最后一个节目默认到 23:59
                }

                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    // 下一个节目
                    if (i + 1 < todayEpg.size()) {
                        nextProgram = todayEpg.get(i + 1);
                    }
                    break;
                }
            }

            // ========================================
            // 更新当前节目信息
            // ========================================
            updateCurrentProgramInfo(currentProgram, currentIndex, todayEpg, now);

            // ========================================
            // 更新下一个节目信息
            // ========================================
            updateNextProgramInfo(nextProgram, currentIndex, todayEpg);

        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.log("【EPG】信息栏更新异常：" + e.getMessage());
            setEpgEmpty();
        }
    }

    /**
     * 筛选今天的节目（双重兼容："今天" 或 对应周几）
     *
     * 【为什么要双重兼容？】
     * 不同的 EPG 源格式不一样：
     * - 有的用 "今天"、"明天" 这种相对日期
     * - 有的用 "周一"、"周二" 这种绝对周几
     * 两种都匹配，兼容性更好。
     *
     * @param epgList 全部节目列表
     * @return 今天的节目列表
     */
    private List<Channel.EpgItem> filterTodayPrograms(List<Channel.EpgItem> epgList) {
        List<Channel.EpgItem> todayEpg = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int w = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeekDay = weekMap[w - 1];

        for (Channel.EpgItem item : epgList) {
            if (item.dayName == null) continue;
            String dayName = item.dayName.trim();
            // 双重兼容：匹配 "今天" 或对应的周几
            if ("今天".equals(dayName) || todayWeekDay.equals(dayName)) {
                todayEpg.add(item);
            }
        }
        return todayEpg;
    }

    /**
     * 按开始时间对节目列表排序
     *
     * @param programList 节目列表
     */
    private void sortProgramsByTime(List<Channel.EpgItem> programList) {
        Collections.sort(programList, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }

    /**
     * 更新当前节目信息（名称、时间范围、进度、剩余时间）
     *
     * @param currentProgram 当前节目
     * @param currentIndex   当前节目索引
     * @param todayEpg       今天的节目列表
     * @param now            当前时间字符串
     */
    private void updateCurrentProgramInfo(Channel.EpgItem currentProgram, int currentIndex,
                                          List<Channel.EpgItem> todayEpg, String now) {
        if (currentProgram != null) {
            // 节目名称
            tvCurrentProgramName.setText(currentProgram.title);

            // 计算结束时间
            String endTime;
            if (currentIndex + 1 < todayEpg.size()) {
                endTime = todayEpg.get(currentIndex + 1).time;
            } else {
                endTime = "23:59";
            }

            // 时间范围
            if (tvCurrentTimeRange != null) {
                tvCurrentTimeRange.setText(currentProgram.time + " - " + endTime);
            }

            // 计算进度和剩余时间
            long nowMillis = timeToMillis(now);
            long startMillis = timeToMillis(currentProgram.time);
            long endMillis = timeToMillis(endTime);

            if (endMillis > startMillis && progressProgram != null) {
                // 进度百分比
                int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
                progressProgram.setProgress(progress);

                // 剩余时间
                long remainingMillis = endMillis - nowMillis;
                int remainingMinutes = (int) (remainingMillis / 1000 / 60);
                if (tvRemainingTime != null) {
                    if (remainingMinutes >= 60) {
                        int hours = remainingMinutes / 60;
                        int mins = remainingMinutes % 60;
                        tvRemainingTime.setText("剩余 " + hours + "时" + mins + "分");
                    } else {
                        tvRemainingTime.setText("剩余 " + remainingMinutes + "分钟");
                    }
                }
            }
        } else {
            // 没找到当前播放的节目
            tvCurrentProgramName.setText("暂无节目信息");
            if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if (progressProgram != null) progressProgram.setProgress(0);
            if (tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }

    /**
     * 更新下一个节目信息（名称、时间范围）
     *
     * @param nextProgram  下一个节目
     * @param currentIndex 当前节目索引
     * @param todayEpg     今天的节目列表
     */
    private void updateNextProgramInfo(Channel.EpgItem nextProgram, int currentIndex,
                                       List<Channel.EpgItem> todayEpg) {
        if (nextProgram != null && tvNextProgramName != null) {
            tvNextProgramName.setText(nextProgram.title);
            // 下一个节目的结束时间
            String nextEndTime;
            if (currentIndex + 2 < todayEpg.size()) {
                nextEndTime = todayEpg.get(currentIndex + 2).time;
            } else {
                nextEndTime = "23:59";
            }
            if (tvNextTimeRange != null) {
                tvNextTimeRange.setText(nextProgram.time + " - " + nextEndTime);
            }
        } else {
            if (tvNextProgramName != null) tvNextProgramName.setText("");
            if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    /**
     * 设置 EPG 为空的兜底显示
     */
    private void setEpgEmpty() {
        if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (tvNextProgramName != null) tvNextProgramName.setText("");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // ====================================================================
    // 4. 节目进度定时更新
    // ====================================================================

    /**
     * 开始节目进度定时更新
     * 每分钟更新一次进度和剩余时间
     */
    public void startProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
        handler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
    }

    /**
     * 停止节目进度定时更新
     */
    public void stopProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
    }

    // ====================================================================
    // 5. 时间工具方法（内部使用）
    // ====================================================================

    /**
     * 获取当前时间字符串（HH:mm 格式）
     *
     * @return 当前时间字符串
     */
    private String getNowTimeStr() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE));
    }

    /**
     * 判断时间是否在指定范围内
     *
     * @param now   当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end   结束时间（HH:mm）
     * @return 是否在范围内
     */
    private boolean isTimeInRange(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) {
                return false;
            }
            if (!now.contains(":") || !start.contains(":") || !end.contains(":")) {
                return false;
            }
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把 HH:mm 格式的时间转换成当天的毫秒数
     * 用于计算节目进度和剩余时间
     *
     * @param timeStr 时间字符串（HH:mm）
     * @return 当天的毫秒数
     */
    private long timeToMillis(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    // ====================================================================
    // 6. 资源释放
    // ====================================================================

    /**
     * 释放资源
     * Activity onDestroy 时调用
     */
    public void release() {
        // 停止所有延迟任务
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.removeCallbacks(updateProgramProgressRunnable);
        // 清空引用，避免内存泄漏
        currentChannel = null;
        context = null;
    }
}
