package com.tv.live.manager;

import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 * 已恢复遥控器按键与焦点管理
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private MainActivity activity;
    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;

    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;

    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;

    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String lastSwitchDirection = "";
    private boolean isSwitchingChannel = false;
    private int autoSkipCount = 0;

    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }

    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

    public ChannelPanelController(
            MainActivity activity,
            View panelLayout,
            View llLeftPanel,
            View llRightPanel,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            TextView btnBackGroup,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            PanelManager panelManager
    ) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.panelLayout = panelLayout;
        this.llLeftPanel = llLeftPanel;
        this.llRightPanel = llRightPanel;
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvChannelListEpg = lvChannelListEpg;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.panelManager = panelManager;
        initClickListeners();
        // 已删除 initFocusListeners()，焦点由 ListView 自身管理
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));

        channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));

        btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
            btnShowEpg.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnShowEpg.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
        btnShowEpg.setOnKeyListener((v, keyCode, event) -> {
            android.util.Log.d("ChannelPanel", "btnShowEpg onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onEpgButtonClicked();
                    return true;
                }
            }
            return false;
        });

        btnBackGroup.setOnClickListener(v -> onBackGroupClicked());
        btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
            btnBackGroup.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnBackGroup.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
        btnBackGroup.setOnKeyListener((v, keyCode, event) -> {
            android.util.Log.d("ChannelPanel", "btnBackGroup onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    onBackGroupClicked();
                    return true;
                }
            }
            return false;
        });
    }

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        groupListManager.setGroups(channels);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        }

        if (channelListManager != null && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int targetPos = -1;
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    targetPos = i;
                    break;
                }
            }
            if (targetPos >= 0) {
                lvChannelList.setSelection(targetPos);
            } else {
                lvChannelList.setSelection(0);
            }
        }
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            Log.w("ChannelPanelController", "playPrev: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            Log.w("ChannelPanelController", "playNext: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playNext();
        } else {
            playPrev();
        }
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playPrev();
        } else {
            playNext();
        }
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        channelListManagerEpg.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        restoreChannelListInteractivity();

        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    private void restoreChannelListInteractivity() {
        if (lvChannelList != null) {
            lvChannelList.post(() -> {
                lvChannelList.setClickable(true);
                lvChannelList.setFocusable(true);
                lvChannelList.setFocusableInTouchMode(true);
                lvChannelList.requestFocus();
                lvChannelList.setSelection(getChannelListSelection());
            });
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.post(() -> {
                lvChannelListEpg.setClickable(true);
                lvChannelListEpg.setFocusable(true);
                lvChannelListEpg.setFocusableInTouchMode(true);
            });
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        return false;
    }

    public boolean toggleCurrentFavorite() {
        return false;
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(globalIndex);
                togglePanel();
            }
        } else {
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(position);
                togglePanel();
            }
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public void togglePanel() {
        boolean willOpen = !isPanelOpen();

        if (willOpen) {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;

            if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || currentGroupName.isEmpty()
                    || currentGroupChannelList.isEmpty()) {
                currentGroupName = GroupListManager.GROUP_ALL;
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            } else {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        }

        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        panelLayout.postDelayed(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.d("ChannelPanelController", "togglePanel postDelayed: Activity已销毁，取消操作");
                return;
            }

            if (isPanelOpen()) {
                restoreChannelListInteractivity();
            }
        }, 100);

        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(willOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    public void handleFirstLaunch() {
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            return;
        }
        if (!rightPanelOpen) {
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.GONE);
            }
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
            panelLayout.post(() -> {
                lvChannelListEpg.requestFocus();
                android.util.Log.d("ChannelPanel", "Right panel opened, focus on channel list");
            });
        } else {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;
            // ✅ 切回左侧时，把焦点交给频道列表
            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean backFromRightPanel() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            panelLayout.post(() -> {
                if (lvChannelList != null) lvChannelList.requestFocus();
            });
            return true;
        }
        return false;
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            // ✅ 切回左侧时，把焦点交给频道列表
            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else {
            if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                return 0;
            }
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }

    public void onPlaySuccess() {
        isSwitchingChannel = false;
        autoSkipCount = 0;
    }

    public boolean canAutoSkip() {
        return isSwitchingChannel
                && !"".equals(lastSwitchDirection)
                && autoSkipCount < MAX_AUTO_SKIP;
    }

    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) {
            return false;
        }
        autoSkipCount++;
        if ("up".equals(lastSwitchDirection)) {
            if (isReverse) {
                playNext();
            } else {
                playPrev();
            }
        } else if ("down".equals(lastSwitchDirection)) {
            if (isReverse) {
                playPrev();
            } else {
                playNext();
            }
        }
        return true;
    }

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    public boolean isReverse() {
        return isReverse;
    }

    // dispatchKeyEvent 已删除，现在由 MainActivity 转发

    public void clearPanelFocus() {
        // ❌ 不再清除焦点，避免遥控器断连
        // if (panelLayout != null) {
        //     panelLayout.clearFocus();
        // }
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        Log.d("ChannelPanelController", "release: 级联清理所有组件引用");

        if (groupListManager != null) {
            groupListManager.release();
            groupListManager = null;
        }
        if (channelListManager != null) {
            channelListManager.release();
            channelListManager = null;
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.release();
            channelListManagerEpg = null;
        }
        if (dateListManager != null) {
            dateListManager.release();
            dateListManager = null;
        }
        if (epgManagerWrapper != null) {
            epgManagerWrapper.release();
            epgManagerWrapper = null;
        }
        if (panelManager != null) {
            panelManager = null;
        }

        channelChangeListener = null;
        panelStateListener = null;

        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList = null;
        }
        if (currentGroupChannelList != null) {
            currentGroupChannelList.clear();
            currentGroupChannelList = null;
        }

        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
        }
        if (lvChannelList != null) {
            lvChannelList.setAdapter(null);
            lvChannelList.setOnItemClickListener(null);
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setAdapter(null);
            lvChannelListEpg.setOnItemClickListener(null);
        }
        if (lvDate != null) {
            lvDate.setAdapter(null);
            lvDate.setOnItemClickListener(null);
        }
        if (lvEpg != null) {
            lvEpg.setAdapter(null);
            lvEpg.setOnItemClickListener(null);
        }
        if (btnShowEpg != null) {
            btnShowEpg.setOnClickListener(null);
        }
        if (btnBackGroup != null) {
            btnBackGroup.setOnClickListener(null);
        }

        this.activity = null;
        this.context = null;
    }
}
