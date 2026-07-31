package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.Channel;
import com.tv.live.util.HuyaParser;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Response;

public class HuyaTogetherWatchManager {
    private static final String TAG = "HuyaTogetherWatch";
    private static volatile HuyaTogetherWatchManager sInstance;
    
    private static final String API_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList";
    
    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;
    
    private static final int SUB_CATEGORY_MOVIE = 2067;
    private static final int SUB_CATEGORY_TV = 2079;
    private static final int SUB_CATEGORY_ANIME = 6861;
    private static final int SUB_CATEGORY_VARIETY = 1011;
    
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    
    private List<TogetherWatchRoom> mRoomList = new ArrayList<>();
    private long mLastFetchTime = 0;
    private static final long CACHE_VALID_MS = 5 * 60 * 1000;

    public interface OnFetchListener {
        void onSuccess(List<TogetherWatchRoom> rooms);
        void onFailed(String errorMsg);
    }

    public interface OnPlayUrlListener {
        void onSuccess(String hlsUrl, String flvUrl);
        void onFailed(String errorMsg);
    }

    public interface OnChannelsFetchedListener {
        void onSuccess(List<Channel> channels);
        void onFailed(String errorMsg);
    }

    public static class TogetherWatchRoom {
        public int roomId;
        public String roomName;
        public String nickName;
        public String coverUrl;
        public int onlineCount;
        public String playUrl;
        public boolean isLive;
        public String category;

        public TogetherWatchRoom(int roomId, String roomName, String nickName, 
                                String coverUrl, int onlineCount, String category) {
            this.roomId = roomId;
            this.roomName = roomName;
            this.nickName = nickName;
            this.coverUrl = coverUrl;
            this.onlineCount = onlineCount;
            this.category = category;
        }

        public Channel toChannel() {
            String displayName = roomName;
            Channel channel = new Channel(displayName, String.valueOf(roomId), 
                                         category, String.valueOf(roomId), true, roomId);
            return channel;
        }
    }

    private HuyaTogetherWatchManager() {}

    public static HuyaTogetherWatchManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaTogetherWatchManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaTogetherWatchManager();
                }
            }
        }
        return sInstance;
    }

    public void fetchTogetherWatchRooms(OnFetchListener listener) {
        long now = System.currentTimeMillis();
        if (now - mLastFetchTime < CACHE_VALID_MS && !mRoomList.isEmpty()) {
            mMainHandler.post(() -> listener.onSuccess(mRoomList));
            return;
        }

        mExecutor.execute(() -> {
            try {
                List<TogetherWatchRoom> rooms = new ArrayList<>();
                
                rooms.addAll(fetchBySubCategory(SUB_CATEGORY_MOVIE, "一起看电影"));
                rooms.addAll(fetchBySubCategory(SUB_CATEGORY_TV, "一起看剧"));
                rooms.addAll(fetchBySubCategory(SUB_CATEGORY_ANIME, "一起看动画"));
                rooms.addAll(fetchBySubCategory(SUB_CATEGORY_VARIETY, "一起看综艺"));
                
                if (rooms.isEmpty()) {
                    rooms = getFallbackRooms();
                }
                
                if (rooms.isEmpty()) {
                    postFailed(listener, "未获取到一起看内容");
                    return;
                }
                
                mRoomList = rooms;
                mLastFetchTime = now;
                postSuccess(listener, rooms);
                
            } catch (IOException e) {
                Log.d(TAG, "网络请求异常，使用内置备用数据");
                List<TogetherWatchRoom> rooms = getFallbackRooms();
                if (!rooms.isEmpty()) {
                    mRoomList = rooms;
                    mLastFetchTime = now;
                    postSuccess(listener, rooms);
                } else {
                    postFailed(listener, "网络请求异常：" + e.getMessage());
                }
            } catch (Exception e) {
                e.printStackTrace();
                postFailed(listener, "解析数据异常：" + e.getMessage());
            }
        });
    }
    
    private List<TogetherWatchRoom> fetchBySubCategory(int subCategoryId, String categoryName) throws IOException {
        String url = API_TMP_LIST + "?iGid=" + CATEGORY_ID_TOGETHER_WATCH + 
                     "&iTmpId=" + subCategoryId + "&iPageNo=1&iPageSize=50";
        
        Response response = NetUtil.getInstance().syncGet(url);
        if (!response.isSuccessful() || response.body() == null) {
            Log.d(TAG, "API请求失败，响应码：" + response.code() + ", category=" + categoryName);
            return new ArrayList<>();
        }
        
        String resStr = response.body().string();
        Log.d(TAG, "API响应长度：" + resStr.length() + ", category=" + categoryName);
        
        try {
            return parseRoomList(resStr, categoryName);
        } catch (Exception e) {
            Log.d(TAG, "解析失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    private List<TogetherWatchRoom> parseRoomList(String jsonStr, String categoryName) throws Exception {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        JSONObject json = new JSONObject(jsonStr);
        
        JSONArray vList = json.optJSONArray("vList");
        if (vList != null) {
            Log.d(TAG, "找到vList数组，长度：" + vList.length() + ", category=" + categoryName);
            for (int i = 0; i < vList.length(); i++) {
                JSONObject room = vList.getJSONObject(i);
                
                long lUid = room.optLong("lUid", 0);
                int roomId = (int) lUid;
                if (roomId <= 0) continue;
                
                String roomName = room.optString("sRoomName", "");
                if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";
                
                String sIntroduction = room.optString("sIntroduction", "");
                String nickName = TextUtils.isEmpty(sIntroduction) ? "精彩节目" : sIntroduction;
                String coverUrl = room.optString("sScreenshot", "");
                
                rooms.add(new TogetherWatchRoom(roomId, roomName, nickName, 
                                               coverUrl, 0, categoryName));
                
                if (rooms.size() >= 50) break;
            }
        }
        
        Log.d(TAG, "解析到 " + rooms.size() + " 个" + categoryName + "房间");
        return rooms;
    }
    
    private List<TogetherWatchRoom> getFallbackRooms() {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        rooms.add(new TogetherWatchRoom(660002, "一起看电影", "虎牙一起看", "", 12000, "一起看电影"));
        rooms.add(new TogetherWatchRoom(660003, "经典剧集", "虎牙一起看", "", 8000, "一起看剧"));
        rooms.add(new TogetherWatchRoom(660004, "热门综艺", "虎牙一起看", "", 6000, "一起看综艺"));
        rooms.add(new TogetherWatchRoom(660005, "动漫剧场", "虎牙一起看", "", 4500, "一起看动画"));
        rooms.add(new TogetherWatchRoom(660006, "体育赛事", "虎牙一起看", "", 3000, "一起看综艺"));
        rooms.add(new TogetherWatchRoom(660007, "纪录片", "虎牙一起看", "", 2500, "一起看电影"));
        rooms.add(new TogetherWatchRoom(660008, "演唱会", "虎牙一起看", "", 5000, "一起看综艺"));
        rooms.add(new TogetherWatchRoom(660009, "游戏回放", "虎牙一起看", "", 3500, "一起看游戏"));
        return rooms;
    }

    public void fetchTogetherWatchChannels(OnChannelsFetchedListener listener) {
        fetchTogetherWatchRooms(new OnFetchListener() {
            @Override
            public void onSuccess(List<TogetherWatchRoom> rooms) {
                List<Channel> channels = new ArrayList<>();
                for (TogetherWatchRoom room : rooms) {
                    channels.add(room.toChannel());
                }
                listener.onSuccess(channels);
            }

            @Override
            public void onFailed(String errorMsg) {
                listener.onFailed(errorMsg);
            }
        });
    }

    public void getPlayUrl(int roomId, OnPlayUrlListener listener) {
        HuyaParser.parse(roomId, new HuyaParser.OnParseResultListener() {
            @Override
            public void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch) {
                String playUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                if (!TextUtils.isEmpty(playUrl)) {
                    listener.onSuccess(hlsUrl, flvUrl);
                } else {
                    listener.onFailed("未获取到播放地址");
                }
            }

            @Override
            public void onFailed(String errorMsg) {
                listener.onFailed(errorMsg);
            }
        });
    }

    private void postSuccess(OnFetchListener listener, List<TogetherWatchRoom> rooms) {
        mMainHandler.post(() -> listener.onSuccess(rooms));
    }

    private void postFailed(OnFetchListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    public void release() {
        mExecutor.shutdownNow();
        mRoomList.clear();
    }
}