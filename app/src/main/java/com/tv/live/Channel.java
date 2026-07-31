package com.tv.live;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class Channel implements Parcelable {
    private String name;
    // 主播放地址
    private String mainPlayUrl;
    // 备用播放地址列表
    private List<String> backupUrls;
    private String group;
    private String channelId;

    // 🟢【新增】记录当前选中的线路索引 (0=主源, 1及以上=备用源)
    private int currentLineIndex = 0;
    
    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
    }

    // 添加备用源，自动去重
    public void addBackupUrl(String url) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // ====== 根据选中的线路索引返回对应的播放地址 ======
    public String getPlayUrl() {
        if (currentLineIndex > 0 && currentLineIndex - 1 < backupUrls.size()) {
            return backupUrls.get(currentLineIndex - 1);
        }
        return mainPlayUrl;
    }

    public void setCurrentLineIndex(int index) {
        this.currentLineIndex = index;
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public String getMainPlayUrl() {
        return mainPlayUrl;
    }

    public void setMainPlayUrl(String mainPlayUrl) {
        this.mainPlayUrl = mainPlayUrl;
    }

    public List<String> getBackupUrls() {
        return backupUrls;
    }

    public String getName() {
        return name;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getChannelId() {
        return channelId;
    }

    // ==================== Parcelable 实现 ====================
    protected Channel(Parcel in) {
        name = in.readString();
        mainPlayUrl = in.readString();
        backupUrls = in.createStringArrayList();
        group = in.readString();
        channelId = in.readString();
        currentLineIndex = in.readInt();
    }

    public static final Creator<Channel> CREATOR = new Creator<Channel>() {
        @Override
        public Channel createFromParcel(Parcel in) {
            return new Channel(in);
        }

        @Override
        public Channel[] newArray(int size) {
            return new Channel[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(mainPlayUrl);
        dest.writeStringList(backupUrls);
        dest.writeString(group);
        dest.writeString(channelId);
        dest.writeInt(currentLineIndex);
    }

    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public boolean isPlaying;

        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
