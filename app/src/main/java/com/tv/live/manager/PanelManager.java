package com.tv.live.manager;

import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import com.tv.live.R;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import java.util.List;

/**
 * 面板管理类
 * 控制左侧频道面板、节目单的显示与隐藏
 * 支持三栏式布局：分组 → 频道 → 节目单按钮
 * 点击节目单按钮后展开节目单（日期 + 节目列表）
 */
public class PanelManager {
    // 面板根布局
    private final View panelLayout;
    // 频道列表管理器
    private final ChannelListManager channelListManager;
    // 节目单管理器
    private final EpgManagerWrapper epgManagerWrapper;
    // 当前选中的日期索引，默认今天=0
    private int currentDateIndex = 0;
    // 节目单是否展开
    private boolean isEpgExpanded = false;
    
    // 子视图引用
    private ListView lvGroup;
    private ListView lvChannelList;
    private TextView btnShowEpg;
    private View llEpgPanel;

    /**
     * 构造方法
     * @param panelLayout 整个左侧面板布局
     * @param channelListManager 频道列表管理
     * @param epgManagerWrapper 节目单管理
     */
    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        
        // 绑定子视图
        lvGroup = panelLayout.findViewById(R.id.lv_group);
        lvChannelList = panelLayout.findViewById(R.id.lv_channel_list);
        btnShowEpg = panelLayout.findViewById(R.id.btn_show_epg);
        llEpgPanel = panelLayout.findViewById(R.id.ll_epg_panel);
    }

    /**
     * 设置当前选中的日期索引
     * 切换日期时调用，同步更新面板内的日期状态
     * @param dateIndex 日期索引
     */
    public void setCurrentDateIndex(int dateIndex) {
        this.currentDateIndex = dateIndex;
    }

    /**
     * 切换节目单面板展开/收起
     * 点击节目单按钮时调用
     */
    public void toggleEpgPanel() {
        isEpgExpanded = !isEpgExpanded;
        if (isEpgExpanded) {
            // 展开节目单：隐藏分组和频道列表，显示节目单
            lvGroup.setVisibility(View.GONE);
            lvChannelList.setVisibility(View.GONE);
            btnShowEpg.setVisibility(View.GONE);
            llEpgPanel.setVisibility(View.VISIBLE);
        } else {
            // 收起节目单：显示分组和频道列表，隐藏节目单
            lvGroup.setVisibility(View.VISIBLE);
            lvChannelList.setVisibility(View.VISIBLE);
            btnShowEpg.setVisibility(View.VISIBLE);
            llEpgPanel.setVisibility(View.GONE);
        }
    }

    /**
     * 节目单是否展开
     */
    public boolean isEpgExpanded() {
        return isEpgExpanded;
    }

    /**
     * 开关面板：显示 / 隐藏
     * @param channelList 频道列表
     * @param currentIndex 当前播放的频道下标
     * @param dateListManager 日期列表管理器，用于同步选中高亮
     */
    public void toggle(List<Channel> channelList, int currentIndex, DateListManager dateListManager) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            // 已经显示则隐藏
            panelLayout.setVisibility(View.GONE);
            // 关闭面板时，重置节目单展开状态
            isEpgExpanded = false;
            llEpgPanel.setVisibility(View.GONE);
            lvGroup.setVisibility(View.VISIBLE);
            lvChannelList.setVisibility(View.VISIBLE);
            btnShowEpg.setVisibility(View.VISIBLE);
        } else {
            // 隐藏则显示，先同步日期列表UI高亮，再刷新节目单
            panelLayout.setVisibility(View.VISIBLE);
            // 打开面板时同步日期列表的选中高亮，解决视觉与数据不一致
            dateListManager.setSelectedPosition(currentDateIndex);
            // 自动刷新当前频道的节目单，保留上次选中的日期
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
            }
        }
    }
}
