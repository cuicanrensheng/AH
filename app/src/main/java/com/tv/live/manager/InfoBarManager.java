package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.R;
import com.tv.live.TVPlayerManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息栏管理器（双布局版）
 *
 * 【两种信息栏】
 * 1. 底部信息栏（info_bar）：切台时显示，5 秒后自动隐藏
 *    - 位置：屏幕底部，全宽
 *    - 内容：完整信息（频道名 + 画质 + 音频 + 码率 + 节目 + 进度 + 下一个节目）
 *    - 【注意】底部信息栏的逻辑保持不动，和原来完全一样
 *
 * 2. 右下角信息栏（info_bar_corner）：打开面板时显示，一直显示
 *    - 位置：屏幕右下角，圆角背景
 *    - 内容：完整信息（频道名 + 画质 + 音频 + 码率 + 节目 + 进度 + 下一个节目）
 *    - 【新增】面板打开时显示，面板关闭时隐藏
 *
 * 【为什么要有两个信息栏？】
 * 切台时用底部信息栏，全屏宽度，提示用户当前切到了哪个台；
 * 打开面板时用户在浏览频道，信息栏放右下角不挡面板内容。
 * 两个信息栏内容一样，只是位置和显示时机不同。
 *
 * 【显示逻辑总览】
 * - 切台时（面板未打开）：显示底部信息栏，5 秒后自动隐藏
 * - 打开面板时：隐藏底部信息栏，显示右下角信息栏（一直显示）
 * - 关闭面板时：隐藏右下角信息栏
 * - 切台时（面板已打开）：只更新右下角信息栏内容，不改变显示状态
 *
 * 【节目进度更新】
 * 每分钟更新一次节目进度和剩余时间，两个信息栏同步更新
 */
public class InfoBarManager {

    // ====================== 常量定义 ======================

    /**
     * 底部信息栏自动隐藏时间（毫秒）
     * 切台后显示 5 秒，然后自动隐藏，不影响观看
     */
    private static final long INFO_BAR_HIDE_DELAY = 5000;

    /**
     * 节目进度更新间隔（毫秒）
     * 每分钟更新一次，不需要太频繁，节省性能
     */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // ====================== 底部信息栏相关（保持不动） ======================

    /** 底部信息栏根布局 */
    private View infoBarView;
    /** 频道名称 */
    private TextView tvChannelName;
    /** 画质标签（FHD/HD 等） */
    private TextView tvTagFhd;
    /** 音频信息（立体声/单声道等） */
    private TextView tvTagAudio;
    /** 码率（如 4.5MB/s） */
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

    // ====================== 右下角信息栏相关（新增） ======================

    /** 右下角信息栏根布局 */
    private View infoBarCorner;
    /** 频道名称（右下角） */
    private TextView tvChannelNameCorner;
    /** 画质标签（右下角） */
    private TextView tvTagFhdCorner;
    /** 音频信息（右下角） */
    private TextView tvTagAudioCorner;
    /** 码率（右下角） */
    private TextView tvBitrateCorner;
    /** 当前节目名称（右下角） */
    private TextView tvCurrentProgramNameCorner;
    /** 当前节目时间范围（右下角） */
    private TextView tvCurrentTimeRangeCorner;
    /** 节目进度条（右下角） */
    private ProgressBar progressProgramCorner;
    /** 剩余时间（右下角） */
    private TextView tvRemainingTimeCorner;
    /** 下一个节目（右下角） */
    private TextView tvNextProgramCorner;

    // ====================== 数据相关 ======================

    /** Context，用于获取资源等 */
    private Context context;
    /** 频道列表引用（和 MainActivity 共享同一个列表） */
    private List<Channel> channelSourceList;
    /** 当前播放索引 */
    private int currentPlayIndex = 0;

    // ====================== Handler 相关 ======================

    /** 主线程 Handler，用于 post 延迟任务 */
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 隐藏底部信息栏的任务
     * 切台后 5 秒自动隐藏底部信息栏
     */
    private final Runnable hideInfoBarRunnable = new Runnable() {
        @Override
        public void run() {
            hideBottom();
        }
    };

    /**
     * 节目进度定时更新任务
     * 每分钟更新一次当前播放节目的进度
     */
    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // 有频道数据才更新
            if (channelSourceList != null && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel channel = channelSourceList.get(currentPlayIndex);
                // 底部信息栏显示中就更新
                if (isBottomShowing()) {
                    updateBottomEpgInfo(channel);
                }
                // 右下角信息栏显示中也更新
                if (isCornerShowing()) {
                    updateCornerEpgInfo(channel);
                }
            }
            // 继续下一次更新
            mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    // ====================== 构造函数 ======================

    /**
     * 构造函数
     *
     * @param context Context
     * @param rootView 根布局，用于 findViewById 查找所有控件
     */
    public InfoBarManager(Context context, View rootView) {
        this.context = context;
        initViews(rootView);
    }

    // ====================== 初始化 ======================

    /**
     * 初始化两个信息栏的所有控件
     *
     * 【查找的控件清单】
     * 底部信息栏：info_bar、tv_channel_name、tv_tag_fhd、tv_tag_audio、tv_bitrate、
     *           tv_current_program_name、tv_current_time_range、progress_program、
     *           tv_remaining_time、tv_next_program_name、tv_next_time_range
     *
     * 右下角信息栏：info_bar_corner、tv_channel_name_corner、tv_tag_fhd_corner、
     *             tv_tag_audio_corner、tv_bitrate_corner、tv_current_program_name_corner、
     *             tv_current_time_range_corner、progress_program_corner、
     *             tv_remaining_time_corner、tv_next_program_corner
     */
    private void initViews(View rootView) {
        // ===== 底部信息栏（保持不动，和原来完全一样） =====
        infoBarView = rootView.findViewById(R.id.info_bar);
        tvChannelName = rootView.findViewById(R.id.tv_channel_name);
        tvTagFhd = rootView.findViewById(R.id.tv_tag_fhd);
        tvTagAudio = rootView.findViewById(R.id.tv_tag_audio);
        tvBitrate = rootView.findViewById(R.id.tv_bitrate);
        tvCurrentProgramName = rootView.findViewById(R.id.tv_current_program_name);
        tvCurrentTimeRange = rootView.findViewById(R.id.tv_current_time_range);
        progressProgram = rootView.findViewById(R.id.progress_program);
        tvRemainingTime = rootView.findViewById(R.id.tv_remaining_time);
        tvNextProgramName = rootView.findViewById(R.id.tv_next_program_name);
        tvNextTimeRange = rootView.findViewById(R.id.tv_next_time_range);

        // ===== 新增：右下角信息栏 =====
        infoBarCorner = rootView.findViewById(R.id.info_bar_corner);
        tvChannelNameCorner = rootView.findViewById(R.id.tv_channel_name_corner);
        tvTagFhdCorner = rootView.findViewById(R.id.tv_tag_fhd_corner);
        tvTagAudioCorner = rootView.findViewById(R.id.tv_tag_audio_corner);
        tvBitrateCorner = rootView.findViewById(R.id.tv_bitrate_corner);
        tvCurrentProgramNameCorner = rootView.findViewById(R.id.tv_current_program_name_corner);
        tvCurrentTimeRangeCorner = rootView.findViewById(R.id.tv_current_time_range_corner);
        progressProgramCorner = rootView.findViewById(R.id.progress_program_corner);
        tvRemainingTimeCorner = rootView.findViewById(R.id.tv_remaining_time_corner);
        tvNextProgramCorner = rootView.findViewById(R.id.tv_next_program_corner);
    }

    // ================================================================
    // 底部信息栏相关方法（保持不动，和原来逻辑完全一样）
    // ================================================================

    /**
     * 显示底部信息栏（自动隐藏）
     *
     * 【使用场景】
     * 切台时调用，显示 5 秒后自动隐藏
     *
     * 【更新内容】
     * 1. 频道名称
     * 2. 画质、音频、码率
     * 3. EPG 节目信息（当前节目、下一个节目、进度等）
     * 4. 重启节目进度定时更新
     *
     * @param channel 当前播放的频道
     * @param liveInfo 播放器实时信息（画质、音频、码率）
     */
    public void showBottom(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        // 先更新内容
        updateBottomInfo(channel, liveInfo);
        // 再显示
        if (infoBarView != null) {
            infoBarView.setVisibility(View.VISIBLE);
        }
        // 移除之前的隐藏任务（防止快速切台时提前隐藏）
        mainHandler.removeCallbacks(hideInfoBarRunnable);
        // 5 秒后自动隐藏
        mainHandler.postDelayed(hideInfoBarRunnable, INFO_BAR_HIDE_DELAY);
        // 重启节目进度定时更新
        restartProgramProgressUpdate();
    }

    /**
     * 隐藏底部信息栏
     *
     * 【使用场景】
     * 1. 打开面板时调用（切换到右下角信息栏）
     * 2. 自动隐藏任务触发时调用
     */
    public void hideBottom() {
        if (infoBarView != null) {
            infoBarView.setVisibility(View.GONE);
        }
        // 移除自动隐藏任务
        mainHandler.removeCallbacks(hideInfoBarRunnable);
    }

    /**
     * 底部信息栏是否正在显示
     *
     * @return true=显示中，false=已隐藏
     */
    public boolean isBottomShowing() {
        return infoBarView != null && infoBarView.getVisibility() == View.VISIBLE;
    }

    /**
     * 更新底部信息栏的全部内容
     *
     * 【更新内容】
     * 1. 频道名称
     * 2. 画质、音频、码率
     * 3. EPG 节目信息
     *
     * @param channel 当前频道
     * @param liveInfo 播放器实时信息
     */
    public void updateBottomInfo(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (channel == null) return;

        // 频道名称
        if (tvChannelName != null) {
            tvChannelName.setText(channel.getName());
        }

        // 画质、音频、码率
        if (liveInfo != null) {
            updateBottomLiveInfo(liveInfo);
        }

        // EPG 节目信息
        updateBottomEpgInfo(channel);
    }

    /**
     * 更新底部信息栏的实时信息（画质、音频、码率）
     *
     * 【使用场景】
     * 播放器的 onLiveInfoUpdate 回调中调用，实时更新码率等信息
     *
     * @param liveInfo 播放器实时信息
     */
    public void updateBottomLiveInfo(TVPlayerManager.LiveInfo liveInfo) {
        if (liveInfo == null) return;
        if (tvTagFhd != null) tvTagFhd.setText(liveInfo.quality);
        if (tvTagAudio != null) tvTagAudio.setText(liveInfo.audio);
        if (tvBitrate != null) tvBitrate.setText(liveInfo.bitrate);
    }

    /**
     * 更新底部信息栏的 EPG 节目信息
     *
     * 【处理流程】
     * 1. 从 EpgManager 获取该频道的所有节目
     * 2. 筛选今天的节目（双重兼容：今天/对应周几）
     * 3. 按开始时间排序
     * 4. 找到当前正在播放的节目
     * 5. 更新当前节目信息（名称、时间、进度、剩余时间）
     * 6. 更新下一个节目信息（名称、时间）
     *
     * 【异常兜底】
     * - 没有匹配到节目 → 显示"暂无节目信息"
     * - 解析异常 → 显示"暂无节目信息"
     *
     * @param channel 当前播放的频道
     */
    private void updateBottomEpgInfo(Channel channel) {
        if (channel == null || tvCurrentProgramName == null) {
            return;
        }

        try {
            // ========================================
            // 第一步：从 EpgManager 获取该频道的所有节目
            // ========================================
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());

            if (epgList == null || epgList.isEmpty()) {
                // 没有匹配到节目数据
                showBottomNoProgramInfo();
                return;
            }

            // ========================================
            // 第二步：筛选今天的节目（双重兼容：今天/对应周几）
            // ========================================
            List<Channel.EpgItem> todayEpg = filterTodayPrograms(epgList);

            if (todayEpg.isEmpty()) {
                showBottomNoProgramInfo();
                return;
            }

            // ========================================
            // 第三步：按开始时间排序
            // ========================================
            sortProgramsByTime(todayEpg);

            // ========================================
            // 第四步：找到当前正在播放的节目
            // ========================================
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;

            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime;

                // 计算结束时间（下一个节目的开始时间，最后一个默认到 23:59）
                if (i + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(i + 1).time;
                } else {
                    endTime = "23:59";
                }

                // 判断当前时间是否在这个节目范围内
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
            // 第五步：更新当前节目信息
            // ========================================
            updateBottomCurrentProgramInfo(currentProgram, currentIndex, todayEpg, now);

            // ========================================
            // 第六步：更新下一个节目信息
            // ========================================
            updateBottomNextProgramInfo(nextProgram, currentIndex, todayEpg);

        } catch (Exception e) {
            e.printStackTrace();
            showBottomNoProgramInfo();
        }
    }

    /**
     * 底部信息栏显示"暂无节目信息"
     *
     * 【使用场景】
     * 没有匹配到节目数据，或解析异常时调用
     * 把所有节目相关的控件都清空
     */
    private void showBottomNoProgramInfo() {
        tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (tvNextProgramName != null) tvNextProgramName.setText("");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }

    /**
     * 更新底部信息栏的当前节目信息
     *
     * 【更新内容】
     * 1. 节目名称
     * 2. 时间范围（开始 - 结束）
     * 3. 进度百分比
     * 4. 剩余时间（X时X分 或 X分钟）
     *
     * @param currentProgram 当前节目
     * @param currentIndex 当前节目在今天列表中的索引
     * @param todayEpg 今天的节目列表
     * @param now 当前时间字符串
     */
    private void updateBottomCurrentProgramInfo(Channel.EpgItem currentProgram, int currentIndex,
                                                List<Channel.EpgItem> todayEpg, String now) {
        if (currentProgram != null) {
            // 节目名称
            tvCurrentProgramName.setText(currentProgram.title);

            // 计算结束时间（下一个节目的开始时间）
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
                        // 超过 60 分钟，显示 X时X分
                        int hours = remainingMinutes / 60;
                        int mins = remainingMinutes % 60;
                        tvRemainingTime.setText("剩余 " + hours + "时" + mins + "分");
                    } else {
                        // 不到 60 分钟，直接显示 X分钟
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
     * 更新底部信息栏的下一个节目信息
     *
     * @param nextProgram 下一个节目
     * @param currentIndex 当前节目索引
     * @param todayEpg 今天的节目列表
     */
    private void updateBottomNextProgramInfo(Channel.EpgItem nextProgram, int currentIndex,
                                             List<Channel.EpgItem> todayEpg) {
        if (nextProgram != null && tvNextProgramName != null) {
            // 下一个节目名称
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
            // 没有下一个节目
            if (tvNextProgramName != null) tvNextProgramName.setText("");
            if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    // ================================================================
    // 右下角信息栏相关方法（新增）
    // ================================================================

    /**
     * 显示右下角信息栏（一直显示）
     *
     * 【使用场景】
     * 打开频道面板时调用，一直显示不自动隐藏
     *
     * 【更新内容】
     * 1. 频道名称
     * 2. 画质、音频、码率
     * 3. EPG 节目信息（当前节目、下一个节目、进度等）
     * 4. 重启节目进度定时更新
     *
     * @param channel 当前播放的频道
     * @param liveInfo 播放器实时信息
     */
    public void showCorner(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        // 先更新内容
        updateCornerInfo(channel, liveInfo);
        // 再显示
        if (infoBarCorner != null) {
            infoBarCorner.setVisibility(View.VISIBLE);
        }
        // 重启节目进度定时更新
        restartProgramProgressUpdate();
    }

    /**
     * 隐藏右下角信息栏
     *
     * 【使用场景】
     * 关闭频道面板时调用
     */
    public void hideCorner() {
        if (infoBarCorner != null) {
            infoBarCorner.setVisibility(View.GONE);
        }
    }

    /**
     * 右下角信息栏是否正在显示
     *
     * @return true=显示中，false=已隐藏
     */
    public boolean isCornerShowing() {
        return infoBarCorner != null && infoBarCorner.getVisibility() == View.VISIBLE;
    }

    /**
     * 更新右下角信息栏的全部内容
     *
     * 【更新内容】
     * 1. 频道名称
     * 2. 画质、音频、码率
     * 3. EPG 节目信息
     *
     * @param channel 当前频道
     * @param liveInfo 播放器实时信息
     */
    public void updateCornerInfo(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (channel == null) return;

        // 频道名称
        if (tvChannelNameCorner != null) {
            tvChannelNameCorner.setText(channel.getName());
        }

        // 画质、音频、码率
        if (liveInfo != null) {
            updateCornerLiveInfo(liveInfo);
        }

        // EPG 节目信息
        updateCornerEpgInfo(channel);
    }

    /**
     * 更新右下角信息栏的实时信息（画质、音频、码率）
     *
     * 【使用场景】
     * 播放器的 onLiveInfoUpdate 回调中调用
     *
     * @param liveInfo 播放器实时信息
     */
    public void updateCornerLiveInfo(TVPlayerManager.LiveInfo liveInfo) {
        if (liveInfo == null) return;
        if (tvTagFhdCorner != null) tvTagFhdCorner.setText(liveInfo.quality);
        if (tvTagAudioCorner != null) tvTagAudioCorner.setText(liveInfo.audio);
        if (tvBitrateCorner != null) tvBitrateCorner.setText(liveInfo.bitrate);
    }

    /**
     * 更新右下角信息栏的 EPG 节目信息
     *
     * 【处理流程】和底部信息栏完全一样
     * 1. 从 EpgManager 获取该频道的所有节目
     * 2. 筛选今天的节目
     * 3. 按开始时间排序
     * 4. 找到当前正在播放的节目
     * 5. 更新当前节目信息
     * 6. 更新下一个节目信息
     *
     * @param channel 当前播放的频道
     */
    public void updateCornerEpgInfo(Channel channel) {
        if (channel == null || tvCurrentProgramNameCorner == null) {
            return;
        }

        try {
            // 从 EpgManager 获取该频道的所有节目
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());

            if (epgList == null || epgList.isEmpty()) {
                showCornerNoProgramInfo();
                return;
            }

            // 筛选今天的节目
            List<Channel.EpgItem> todayEpg = filterTodayPrograms(epgList);

            if (todayEpg.isEmpty()) {
                showCornerNoProgramInfo();
                return;
            }

            // 按开始时间排序
            sortProgramsByTime(todayEpg);

            // 找到当前正在播放的节目
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;

            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime = (i + 1 < todayEpg.size()) ? todayEpg.get(i + 1).time : "23:59";

                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    if (i + 1 < todayEpg.size()) nextProgram = todayEpg.get(i + 1);
                    break;
                }
            }

            // 更新当前节目信息
            updateCornerCurrentProgramInfo(currentProgram, currentIndex, todayEpg, now);
            // 更新下一个节目信息
            updateCornerNextProgramInfo(nextProgram, currentIndex, todayEpg);

        } catch (Exception e) {
            e.printStackTrace();
            showCornerNoProgramInfo();
        }
    }

    /**
     * 右下角信息栏显示"暂无节目信息"
     */
    private void showCornerNoProgramInfo() {
        tvCurrentProgramNameCorner.setText("暂无节目信息");
        if (tvCurrentTimeRangeCorner != null) tvCurrentTimeRangeCorner.setText("");
        if (progressProgramCorner != null) progressProgramCorner.setProgress(0);
        if (tvRemainingTimeCorner != null) tvRemainingTimeCorner.setText("");
        if (tvNextProgramCorner != null) tvNextProgramCorner.setText("");
    }

    /**
     * 更新右下角信息栏的当前节目信息
     *
     * @param currentProgram 当前节目
     * @param currentIndex 当前节目索引
     * @param todayEpg 今天的节目列表
     * @param now 当前时间字符串
     */
    private void updateCornerCurrentProgramInfo(Channel.EpgItem currentProgram, int currentIndex,
                                                List<Channel.EpgItem> todayEpg, String now) {
        if (currentProgram != null) {
            // 节目名称
            tvCurrentProgramNameCorner.setText(currentProgram.title);

            // 计算结束时间
            String endTime = (currentIndex + 1 < todayEpg.size()) ? todayEpg.get(currentIndex + 1).time : "23:59";

            // 时间范围
            if (tvCurrentTimeRangeCorner != null) {
                tvCurrentTimeRangeCorner.setText(currentProgram.time + " - " + endTime);
            }

            // 计算进度和剩余时间
            long nowMillis = timeToMillis(now);
            long startMillis = timeToMillis(currentProgram.time);
            long endMillis = timeToMillis(endTime);

            if (endMillis > startMillis && progressProgramCorner != null) {
                // 进度百分比
                int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
                progressProgramCorner.setProgress(progress);

                // 剩余时间
                long remainingMillis = endMillis - nowMillis;
                int remainingMinutes = (int) (remainingMillis / 1000 / 60);
                if (tvRemainingTimeCorner != null) {
                    if (remainingMinutes >= 60) {
                        int hours = remainingMinutes / 60;
                        int mins = remainingMinutes % 60;
                        tvRemainingTimeCorner.setText("剩余" + hours + "时" + mins + "分");
                    } else {
                        tvRemainingTimeCorner.setText("剩余" + remainingMinutes + "分");
                    }
                }
            }
        } else {
            // 没找到当前播放的节目
            tvCurrentProgramNameCorner.setText("暂无节目信息");
            if (tvCurrentTimeRangeCorner != null) tvCurrentTimeRangeCorner.setText("");
            if (progressProgramCorner != null) progressProgramCorner.setProgress(0);
            if (tvRemainingTimeCorner != null) tvRemainingTimeCorner.setText("");
        }
    }

    /**
     * 更新右下角信息栏的下一个节目信息
     *
     * @param nextProgram 下一个节目
     * @param currentIndex 当前节目索引
     * @param todayEpg 今天的节目列表
     */
    private void updateCornerNextProgramInfo(Channel.EpgItem nextProgram, int currentIndex,
                                             List<Channel.EpgItem> todayEpg) {
        if (nextProgram != null && tvNextProgramCorner != null) {
            tvNextProgramCorner.setText("接下来：" + nextProgram.title);
        } else {
            if (tvNextProgramCorner != null) tvNextProgramCorner.setText("");
        }
    }

    // ================================================================
    // 通用工具方法（两个信息栏共用）
    // ================================================================

    /**
     * 筛选今天的节目（双重兼容：今天/对应周几）
     *
     * 【为什么要双重兼容？】
     * 不同的 EPG 源，日期格式不一样：
     * - 有的用 "今天"、"明天"、"后天"
     * - 有的用 "周一"、"周二"、"周三"
     * 两种都匹配，提高兼容性
     *
     * @param epgList 所有节目列表
     * @return 今天的节目列表
     */
    private List<Channel.EpgItem> filterTodayPrograms(List<Channel.EpgItem> epgList) {
        List<Channel.EpgItem> todayEpg = new ArrayList<>();

        // 获取今天是周几
        Calendar cal = Calendar.getInstance();
        int w = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeekDay = weekMap[w - 1];

        // 遍历所有节目，筛选今天的
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
     * 按开始时间排序节目
     *
     * 【排序方式】
     * 直接用字符串比较，因为 HH:mm 格式的字符串字典序和时间序一致
     *
     * @param epgList 节目列表
     */
    private void sortProgramsByTime(List<Channel.EpgItem> epgList) {
        Collections.sort(epgList, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }

    /**
     * 获取当前时间字符串（HH:mm 格式）
     *
     * @return 当前时间字符串，如 "14:30"
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
     * 【比较方式】
     * 直接用字符串比较，因为 HH:mm 格式的字符串字典序和时间序一致
     * 比如 "14:30".compareTo("14:00") >= 0，说明 14:30 在 14:00 之后
     *
     * @param now 当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end 结束时间（HH:mm）
     * @return 是否在范围内（start <= now < end）
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
     *
     * 【用途】
     * 用于计算节目进度百分比和剩余时间
     *
     * 【注意】
     * 只取小时和分钟，秒和毫秒都设为 0，
     * 因为 EPG 节目时间一般只精确到分钟。
     *
     * @param timeStr 时间字符串（HH:mm）
     * @return 当天的毫秒数（从 00:00:00 开始算）
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

    // ================================================================
    // 节目进度定时更新
    // ================================================================

    /**
     * 重启节目进度定时更新
     *
     * 【使用场景】
     * 切台时、打开面板时调用，重新开始计时
     *
     * 【为什么要重启？】
     * 切台后，当前播放的节目变了，需要重新开始计时，
     * 确保下一次更新是在 1 分钟后，而不是用之前的计时。
     */
    public void restartProgramProgressUpdate() {
        mainHandler.removeCallbacks(updateProgramProgressRunnable);
        mainHandler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
    }

    /**
     * 停止节目进度定时更新
     *
     * 【使用场景】
     * Activity onDestroy 时调用，防止内存泄漏
     */
    public void stopProgramProgressUpdate() {
        mainHandler.removeCallbacks(updateProgramProgressRunnable);
    }

    // ================================================================
    // 数据同步
    // ================================================================

    /**
     * 设置频道列表引用和当前播放索引
     *
     * 【为什么要传引用？】
     * InfoBarManager 需要访问频道列表来获取当前频道信息，
     * 直接传引用，不需要每次都拷贝数据，效率更高。
     *
     * @param channelList 频道列表
     * @param playIndex 当前播放索引
     */
    public void setChannelList(List<Channel> channelList, int playIndex) {
        this.channelSourceList = channelList;
        this.currentPlayIndex = playIndex;
    }

    /**
     * 更新当前播放索引
     *
     * 【使用场景】
     * 切台时调用，同步更新当前播放索引
     *
     * @param playIndex 当前播放索引
     */
    public void setCurrentPlayIndex(int playIndex) {
        this.currentPlayIndex = playIndex;
    }

    // ================================================================
    // 资源释放
    // ================================================================

    /**
     * 释放资源
     *
     * 【使用场景】
     * Activity onDestroy 时调用，防止内存泄漏
     *
     * 【释放内容】
     * 1. 移除所有 Handler 消息和回调
     * 2. 清空所有 View 引用
     * 3. 清空 Context 引用
     */
    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        infoBarView = null;
        infoBarCorner = null;
        context = null;
    }
}
