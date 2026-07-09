package com.tv.live.manager;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.config.AppConfig;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 * 
 * 【2026-07-08 修改：面板开启 5 秒防呆自动隐藏】
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private static final long FIRST_LAUNCH_HIDE_DELAY_MS = 5000;
    private static final long NORMAL_HIDE_DELAY_MS = 5000; // 🟢 修改为 5 秒防呆

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

    private Handler mAutoHideHandler;
    private Runnable mAutoHideRunnable;
    private long mAutoHideDelayMs = 5000; // 🟢 默认为 5 秒
    
    // 🟢【恢复】开启自动隐藏功能
    private boolean mAutoHideEnabled = true;

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String currentFocusPanel = "left";
    private String leftFocusView = "channel";
    private String rightFocusView = "channel";

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
            Context context,
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
        this.context = context.getApplicationContext();
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
        initFocusListeners();
        initAutoHide();
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });
        lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });
        channelListManager.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                return handleChannelLongClick(channelName, false);
            }
        });
        channelListManagerEpg.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                return handleChannelLongClick(channelName, true);
            }
        });
        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });
        btnBackGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackGroupClicked();
            }
        });
    }

    private void initFocusListeners() {
        lvGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "group";
                    syncFocusStyle();
                }
            }
        });
        lvChannelList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                }
            }
        });
        btnShowEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "epgBtn";
                    syncFocusStyle();
                }
                if (!rightPanelOpen) {
                    lvChannelList.requestFocus();
                }
            }
        });
        lvChannelListEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
                }
            }
        });
        lvDate.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "date";
                    syncFocusStyle();
                }
            }
        });
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "epg";
                    syncFocusStyle();
                }
            }
        });
        btnBackGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "backBtn";
                    syncFocusStyle();
                }
            }
        });
    }

    private void initAutoHide() {
        mAutoHideHandler = new Handler(Looper.getMainLooper());
        mAutoHideRunnable = new Runnable() {
            @Override
            public void run() {
                hidePanel();
            }
        };
        // 🟢【恢复】开启自动隐藏，并设定为 5 秒防呆
        mAutoHideEnabled = true;
        mAutoHideDelayMs = 5000;
    }

    private void clearAllFocusStyles() {
        groupListManager.setFocused(false);
        channelListManager.setFocused(false);
        channelListManagerEpg.setFocused(false);
        dateListManager.setFocused(false);
        btnShowEpg.setTextColor(0xFFFFFFFF);
        btnShowEpg.setTypeface(null, Typeface.NORMAL);
        btnShowEpg.setBackgroundColor(0x00000000);
        btnBackGroup.setTextColor(0xFFFFFFFF);
        btnBackGroup.setTypeface(null, Typeface.NORMAL);
        btnBackGroup.setBackgroundColor(0x00000000);
    }

    private void syncFocusStyle() {
        clearAllFocusStyles();
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                groupListManager.setFocused(true);
            } else if ("channel".equals(leftFocusView)) {
                channelListManager.setFocused(true);
            } else if ("epgBtn".equals(leftFocusView)) {
                btnShowEpg.setTextColor(0xFFFFFFFF);
                btnShowEpg.setTypeface(null, Typeface.BOLD);
                btnShowEpg.setBackgroundColor(0x3340A9FF);
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView)) {
                channelListManagerEpg.setFocused(true);
            } else if ("date".equals(rightFocusView)) {
                dateListManager.setFocused(true);
            } else if ("backBtn".equals(rightFocusView)) {
                btnBackGroup.setTextColor(0xFFFFFFFF);
                btnBackGroup.setTypeface(null, Typeface.BOLD);
                btnBackGroup.setBackgroundColor(0x3340A9FF);
            }
        }
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
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
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
            }
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public void setTotalChannelCount(int count) {
    }

    public void togglePanel() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        }
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        if (!isOpen) {
            panelLayout.post(new Runnable() {
                @Override
                public void run() {
                    clearAllFocusStyles();
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                    resetAutoHide();
                }
            });
        } else {
            cancelAutoHide();
        }
        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            cancelAutoHide();
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    public void resetAutoHide() {
        if (!mAutoHideEnabled) return;
        if (mAutoHideHandler != null && mAutoHideRunnable != null) {
            mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
            if (isPanelOpen()) {
                mAutoHideHandler.postDelayed(mAutoHideRunnable, mAutoHideDelayMs);
            }
        }
    }

    public void cancelAutoHide() {
        if (mAutoHideHandler != null && mAutoHideRunnable != null) {
            mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
        }
    }

    public void setAutoHideDelay(long delayMs) {
        this.mAutoHideDelayMs = delayMs;
    }

    public void setAutoHideEnabled(boolean enabled) {
        this.mAutoHideEnabled = enabled;
        if (!enabled) {
            cancelAutoHide();
        }
    }

    public void handleFirstLaunch() {
        if (!mIsFirstLaunch) return;
        setAutoHideDelay(FIRST_LAUNCH_HIDE_DELAY_MS);
        resetAutoHide();
        setAutoHideDelay(NORMAL_HIDE_DELAY_MS);
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
            if (llRightPanel != null) {
                llRightPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "right";
                        rightFocusView = "channel";
                        syncFocusStyle();
                        lvChannelListEpg.requestFocus();
                        lvChannelListEpg.setSelection(currentPlayIndex);
                    }
                });
            }
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                llLeftPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "left";
                        leftFocusView = "channel";
                        syncFocusStyle();
                        lvChannelList.requestFocus();
                        lvChannelList.setSelection(getChannelListSelection());
                    }
                });
            }
        }
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                llLeftPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "left";
                        leftFocusView = "channel";
                        syncFocusStyle();
                        lvChannelList.requestFocus();
                        lvChannelList.setSelection(getChannelListSelection());
                    }
                });
            }
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

    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            if (rightPanelOpen) {
                onBackGroupClicked();
                return true;
            }
            hidePanel();
            return true;
        }
        return false;
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

    public boolean dispatchKeyEvent(int keyCode) {
        View currentFocus = panelLayout.findFocus();
        if (currentFocus == null) return false;

        if (rightPanelOpen) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (currentFocus == lvGroup) {
                    int selectedPos = lvGroup.getSelectedItemPosition();
                    if (selectedPos == lvGroup.getAdapter().getCount() - 1) {
                        lvChannelList.requestFocus();
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (currentFocus == lvChannelList) {
                    int selectedPos = lvChannelList.getSelectedItemPosition();
                    if (selectedPos == 0) {
                        lvGroup.requestFocus();
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (currentFocus == btnShowEpg) {
                    lvChannelList.requestFocus();
                    return true;
                }
                break;
        }
        return false;
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        cancelAutoHide();
    }
}
