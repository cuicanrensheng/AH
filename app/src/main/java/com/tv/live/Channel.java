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
    // 虎牙线路名称列表（与 backupUrls 一一对应，如 "线路2", "线路3"）
    private List<String> huyaLineLabels;
    private String group;
    private String channelId;

    // 🟢【新增】记录当前选中的线路索引 (0=主源, 1及以上=备用源)
    private int currentLineIndex = 0;

    // 🟢【虎牙一起看】标识是否为虎牙一起看频道，及对应房间号
    private boolean isTogetherWatch = false;
    private int huyaRoomId = 0;
    // 🟢 一起看/游戏直播频道：完整长整型 uid（presenterUid），用作该频道的开播 key
    private long huyaUid = 0;

    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
        this.huyaLineLabels = new ArrayList<>();
    }

    public Channel(String name, String mainPlayUrl, String group, String channelId,
                   boolean isTogetherWatch, int huyaRoomId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
        this.huyaLineLabels = new ArrayList<>();
        this.isTogetherWatch = isTogetherWatch;
        this.huyaRoomId = huyaRoomId;
    }

    // 添加备用源，自动去重
    public void addBackupUrl(String url) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // 添加备用源+线路名
    public void addBackupUrl(String url, String lineLabel) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
            huyaLineLabels.add(lineLabel != null ? lineLabel : "");
        }
    }

    public List<String> getHuyaLineLabels() {
        return huyaLineLabels;
    }

    public void setHuyaLineLabels(List<String> labels) {
        this.huyaLineLabels = labels != null ? labels : new ArrayList<>();
    }

    public void clearBackupUrls() {
        backupUrls.clear();
        huyaLineLabels.clear();
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

    public boolean isTogetherWatch() {
        return isTogetherWatch;
    }

    public void setTogetherWatch(boolean togetherWatch) {
        isTogetherWatch = togetherWatch;
    }

    public int getHuyaRoomId() {
        return huyaRoomId;
    }

    public void setHuyaRoomId(int huyaRoomId) {
        this.huyaRoomId = huyaRoomId;
    }

    public long getHuyaUid() {
        return huyaUid;
    }

    public void setHuyaUid(long huyaUid) {
        this.huyaUid = huyaUid;
    }

    // ==================== Parcelable 实现 ====================
    protected Channel(Parcel in) {
        name = in.readString();
        mainPlayUrl = in.readString();
        backupUrls = in.createStringArrayList();
        huyaLineLabels = in.createStringArrayList();
        group = in.readString();
        channelId = in.readString();
        currentLineIndex = in.readInt();
        isTogetherWatch = in.readByte() != 0;
        huyaRoomId = in.readInt();
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
        dest.writeStringList(huyaLineLabels);
        dest.writeString(group);
        dest.writeString(channelId);
        dest.writeInt(currentLineIndex);
        dest.writeByte((byte) (isTogetherWatch ? 1 : 0));
        dest.writeInt(huyaRoomId);
    }

    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public boolean isPlaying;
        // 🔧 新增：精确日期键 YYYYMMDD（如 20260815），用于日期匹配时绕过中文周几标签的不一致问题
        public int dateYMD;

        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
            this.dateYMD = 0;
        }

        public EpgItem(String dayName, String time, String title, boolean isPlaying, int dateYMD) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
            this.dateYMD = dateYMD;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
