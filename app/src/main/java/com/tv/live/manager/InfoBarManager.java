package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import com.tv.live.R;
import com.tv.live.TVPlayerManager;

/**
 * 顶部频道信息悬浮栏管理器
 * 拆分自MainActivity initInfoBar() + hideInfoBar自动隐藏逻辑
 * 职责：控件绑定、信息填充、延时自动隐藏频道信息条
 */
public class InfoBarManager {
    // 信息栏根布局
    private final View info_bar;
    private final TextView tv_channel_name;
    private final TextView tv_tag_fhd;
    private final TextView tv_tag_audio;
    private final TextView tv_bitrate;
    private final TextView tv_current_program_name;
    private final TextView tv_current_time_range;
    private final TextView tv_remaining_time;
    private final TextView tv_next_program_name;
    private final TextView tv_next_time_range;
    private final android.widget.ProgressBar progress_program;

    // 自动隐藏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    /**
     * 构造：从页面根布局查找所有信息栏控件
     * @param rootView activity根布局view
     */
    public InfoBarManager(View rootView) {
        info_bar = rootView.findViewById(R.id.info_bar);
        tv_channel_name = rootView.findViewById(R.id.tv_channel_name);
        tv_tag_fhd = rootView.findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = rootView.findViewById(R.id.tv_tag_audio);
        tv_bitrate = rootView.findViewById(R.id.tv_bitrate);
        tv_current_program_name = rootView.findViewById(R.id.tv_current_program_name);
        tv_current_time_range = rootView.findViewById(R.id.tv_current_time_range);
        progress_program = rootView.findViewById(R.id.progress_program);
        tv_remaining_time = rootView.findViewById(R.id.tv_remaining_time);
        tv_next_program_name = rootView.findViewById(R.id.tv_next_program_name);
        tv_next_time_range = rootView.findViewById(R.id.tv_next_time_range);
    }

    /**
     * 展示频道信息栏，赋值数据+延时自动隐藏
     * @param chName 当前播放频道名
     * @param liveInfo 播放器实时码率/画质信息
     * @param delayMs 自动隐藏延迟毫秒
     */
    public void showInfoBar(String chName, TVPlayerManager.LiveInfo liveInfo, long delayMs) {
        if (info_bar == null) return;
        info_bar.setVisibility(View.VISIBLE);
        info_bar.removeCallbacks(hideInfoBar);
        info_bar.postDelayed(hideInfoBar, delayMs);
        tv_channel_name.setText(chName);
        tv_tag_fhd.setText(liveInfo.quality);
        tv_tag_audio.setText(liveInfo.audio);
        tv_bitrate.setText(liveInfo.bitrate);
    }
}
