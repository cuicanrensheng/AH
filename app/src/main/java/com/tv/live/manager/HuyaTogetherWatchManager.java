package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.Channel;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.HuyaPureParser;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Response;

public class HuyaTogetherWatchManager {
    private static final String TAG = "HuyaTogetherWatch";
    private static volatile HuyaTogetherWatchManager sInstance;

    private static final String API_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList";
    private static final String API_CACHE_LIST = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=";

    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    private static final int[] MOVIE_TMP_IDS = {2067, 2069, 2071, 2073, 2075, 2077, 2068, 2070, 2072, 2074, 2076};
    private static final int[] TV_TMP_IDS = {2079, 2081, 2083, 2085, 2087, 2089, 2080, 2082, 2084, 2086, 2088, 2090};
    // 🟢 虎牙一起看下的动漫子分类 ID（多个 ID 兼容不同版本接口）
    private static final int[] ANIME_TMP_IDS = {6861, 2543, 2544, 2545, 2546, 2547, 2548};
    private static final int SUB_CATEGORY_VARIETY = 1011;
    private static final int[] VARIETY_TMP_IDS = {1011, 2091, 2093, 2095};

    // 🟢 大幅增加 cache 页数，确保动漫频道充足
    private static final int ANIME_CACHE_PAGES = 20;
    private static final int VARIETY_CACHE_PAGES = 16;
    private static final int GENERAL_CACHE_PAGES = 5;

    // ======================【精简后关键词分组】======================
    private static final String[][] MOVIE_CATEGORY_KEYWORDS = {
        {"电影_星爷英叔", "周星驰|星爷|英叔|林正英|僵尸"},
        {"电影_动作港片", "成龙|李连杰|甄子丹|洪金宝|周润发|刘德华|港片|无间道|英雄本色"},
        {"电影_喜剧", "沈腾|黄渤|开心麻花|喜剧"},
        {"电影_科幻漫威", "漫威|复仇者|星际|宇宙|科幻|变形金刚"},
        {"电影_悬疑恐怖", "悬疑|推理|恐怖|惊悚|丧尸|鬼片"},
        {"电影_战争武侠", "战争|武侠|金庸|古龙|古装|二战"},
        {"电影_冒险盗墓", "盗墓|鬼吹灯|古墓|探险"},
        {"电影_其他", ""},
    };

    private static final String[][] COMMENTARY_CATEGORY_KEYWORDS = {
        {"解说_影视解说", "扁豆|乌贼|大象|亮哥|越哥|解说|影评|讲电影|聊电影"},
        {"解说_搞笑短片", "陈翔六点半|陈翔|六点半"},
        {"解说_其他", ""},
    };

    private static final String[][] TV_CATEGORY_KEYWORDS = {
        {"剧集_经典古装", "三国|水浒传|西游记|红楼梦|庆余年|雍正王朝|古装|宫廷|历史"},
        {"剧集_军旅抗战", "亮剑|士兵突击|军旅|战争|抗战|谍战"},
        {"剧集_情景喜剧", "武林外传|爱情公寓|家有儿女|搞笑"},
        {"剧集_悬疑刑侦", "悬疑|破案|刑侦|法医|犯罪"},
        {"剧集_其他", ""},
    };

    private static final String[][] VARIETY_CATEGORY_KEYWORDS = {
        {"综艺_热门综艺", "综艺|热门|真人秀|奔跑吧|极限挑战"},
        {"综艺_音乐歌舞", "音乐|唱歌|歌手|演唱会|好声音|K歌"},
        {"综艺_搞笑娱乐", "搞笑|脱口秀|相声|小品"},
        {"综艺_其他", ""},
    };

    private static final String[][] ANIME_CATEGORY_KEYWORDS = {
        {"动漫_热血日漫", "火影|海贼|龙珠|鬼灭|咒术|进击的巨人|一拳超人|电锯人"},
        {"动漫_精品国漫", "国漫|斗罗|斗破|秦时明月|画江湖|狐妖|一人之下|吞噬星空"},
        {"动漫_日常治愈", "日常|蜡笔小新|哆啦A梦|海绵宝宝|治愈"},
        {"动漫_奇幻异世界", "异世界|转生|史莱姆|Re0|魔法|刀剑神域"},
        {"动漫_怀旧经典", "怀旧|灌篮高手|圣斗士|数码宝贝|游戏王|童年"},
        {"动漫_其他", ""},
    };
    // ==============================================================

    /**
     * 🟢 cache API 动漫关键词过滤集合
     * cache API（gameId=2135 一起看）返回的是所有一起看频道（电影+剧集+综艺+动漫混合），
     * 不能全部标记为"动漫"，必须根据 roomName/nickName 内容判断是否真的是动漫频道。
     * 列表包含：通用动漫术语 + 经典/热门日漫作品 + 经典/热门国漫作品 + 怀旧动画。
     */
    private static final String[] ANIME_RELATED_KEYWORDS = {
        // 通用动漫术语
        "动漫", "动画", "番剧", "国漫", "日漫", "二次元", "剧场版", "ova", "新番",
        // 经典日漫作品
        "火影", "海贼", "龙珠", "鬼灭", "咒术", "柯南", "犬夜叉", "死神",
        "银魂", "蜡笔小新", "哆啦A梦", "樱桃小丸子", "海绵宝宝", "进击的巨人",
        "一拳超人", "妖尾", "妖精的尾巴", "七大罪", "黑色五叶草", "电锯人",
        "间谍过家家", "芙莉莲", "葬送", "国王排名", "勇者",
        // 经典国漫作品
        "斗罗", "斗破", "秦时明月", "画江湖", "狐妖", "全职高手", "武庚纪",
        "吞噬星空", "武动乾坤", "遮天", "仙逆", "凡人修仙", "灵笼", "元尊",
        "星辰变", "天行九歌", "不良人", "镇魂街", "一人之下", "魔道祖师",
        "天官赐福", "凹凸世界", "罗小黑", "刺客伍六七", "雾山五行", "时光代理人",
        "少年歌行", "盘龙", "雪鹰领主", "神印王座",
        // 机战/科幻
        "高达", "eva", "新世纪福音战士", "机甲", "机动战士",
        // 怀旧经典
        "圣斗士", "灌篮高手", "幽游白书", "乱马", "网球王子", "棋魂",
        "夏目", "虫师", "通灵王", "游戏王", "数码宝贝", "神奇宝贝", "宠物小精灵",
        "中华小当家", "机器猫", "铁臂阿童木", "黑猫警长", "葫芦娃",
        // 少女/恋爱
        "魔卡少女", "百变小樱", "美少女战士", "魔法少女",
        // 异世界/奇幻
        "异世界", "转生", "史莱姆", "无职转生", "re:0", "re0", "从零开始",
        "刀剑神域", "overlord",
        // 国产动画电影
        "魁拔", "大鱼海棠", "白蛇", "哪吒", "姜子牙", "深海", "长安三万里",
        "雄狮少年", "新神榜",
        // 热门IP
        "喜羊羊", "熊出没", "猪猪侠", "铠甲勇士"
    };

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
        public int profileRoom;
        public String roomName;
        public String nickName;
        public String coverUrl;
        public int onlineCount;
        public String playUrl;
        public boolean isLive;
        public String category;

        public TogetherWatchRoom(int roomId, int profileRoom, String roomName, String nickName,
                                String coverUrl, int onlineCount, String category) {
            this.roomId = roomId;
            this.profileRoom = profileRoom > 0 ? profileRoom : roomId;
            this.roomName = roomName;
            this.nickName = nickName;
            this.coverUrl = coverUrl;
            this.onlineCount = onlineCount;
            this.category = category;
        }

        public Channel toChannel() {
            String displayName = roomName;
            // 🟢 适配当前项目 huya://room/ 协议方案，TVPlayerManager 通过 isHuyaProtocolUrl 识别
            Channel channel = new Channel(displayName, "huya://room/" + profileRoom,
                    category, "huya_" + roomId, true, profileRoom);
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

                List<TogetherWatchRoom> movieRooms = fetchMovieRooms();
                // 🟢 修改兜底为 解说_其他
                List<TogetherWatchRoom> commentaryRooms = classifyRoomsByKeywords(movieRooms, COMMENTARY_CATEGORY_KEYWORDS, "解说_其他");
                List<Integer> commentaryRoomIds = new ArrayList<>();
                for (TogetherWatchRoom room : commentaryRooms) {
                    commentaryRoomIds.add(room.roomId);
                }
                rooms.addAll(commentaryRooms);

                List<TogetherWatchRoom> nonCommentaryMovieRooms = new ArrayList<>();
                for (TogetherWatchRoom room : movieRooms) {
                    if (!commentaryRoomIds.contains(room.roomId)) {
                        nonCommentaryMovieRooms.add(room);
                    }
                }
                // 🟢 修改兜底为 电影_其他
                rooms.addAll(classifyRoomsByKeywords(nonCommentaryMovieRooms, MOVIE_CATEGORY_KEYWORDS, "电影_其他"));

                List<TogetherWatchRoom> tvRooms = fetchTvRooms();
                // 🟢 修改兜底为 剧集_其他
                rooms.addAll(classifyRoomsByKeywords(tvRooms, TV_CATEGORY_KEYWORDS, "剧集_其他"));

                List<TogetherWatchRoom> animeRooms = fetchAnimeRooms();
                // 🟢 修改兜底为 动漫_其他
                rooms.addAll(classifyRoomsByKeywords(animeRooms, ANIME_CATEGORY_KEYWORDS, "动漫_其他"));

                List<TogetherWatchRoom> varietyRooms = fetchVarietyRooms();
                // 🟢 修改兜底为 综艺_其他
                rooms.addAll(classifyRoomsByKeywords(varietyRooms, VARIETY_CATEGORY_KEYWORDS, "综艺_其他"));

                // 🟢 兜底：如果最终动漫/综艺频道数太少，补充静态 fallback
                int animeCount = 0, varietyCount = 0;
                for (TogetherWatchRoom r : rooms) {
                    if (r.category != null && r.category.startsWith("动漫_")) animeCount++;
                    if (r.category != null && r.category.startsWith("综艺_")) varietyCount++;
                }
                if (animeCount < 5 || varietyCount < 5) {
                    Log.d(TAG, "动漫/综艺频道过少（动漫=" + animeCount + ", 综艺=" + varietyCount + "），追加静态兜底");
                    rooms.addAll(getFallbackRooms());
                }

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

    private List<TogetherWatchRoom> fetchMovieRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        for (int tmpId : MOVIE_TMP_IDS) {
            try {
                List<TogetherWatchRoom> rooms = fetchBySubCategory(tmpId, "电影");
                allRooms.addAll(rooms);
            } catch (Exception e) {
                Log.d(TAG, "获取电影子分类失败: tmpId=" + tmpId + ", " + e.getMessage());
            }
        }
        Log.d(TAG, "电影类总共获取到 " + allRooms.size() + " 个房间");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchTvRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        for (int tmpId : TV_TMP_IDS) {
            try {
                List<TogetherWatchRoom> rooms = fetchBySubCategory(tmpId, "剧集");
                allRooms.addAll(rooms);
            } catch (Exception e) {
                Log.d(TAG, "获取剧集子分类失败: tmpId=" + tmpId + ", " + e.getMessage());
            }
        }
        Log.d(TAG, "剧集类总共获取到 " + allRooms.size() + " 个房间");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchAnimeRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        // 🟢 循环抓取多个 anime 子分类 ID，增加动漫频道来源
        for (int tmpId : ANIME_TMP_IDS) {
            try {
                List<TogetherWatchRoom> rooms = fetchBySubCategory(tmpId, "动漫");
                Log.d(TAG, "ANIME tmpId=" + tmpId + " 获取到 " + rooms.size() + " 个房间");
                allRooms.addAll(rooms);
            } catch (Exception e) {
                Log.d(TAG, "获取动漫子分类失败: tmpId=" + tmpId + ", " + e.getMessage());
            }
        }
        try {
            List<TogetherWatchRoom> cacheRooms = fetchFromCacheApi(ANIME_CACHE_PAGES, "动漫");
            // 🟢 cache API（gameId=2135 一起看）返回的是所有一起看频道（电影+剧集+综艺+动漫混合），
            // 不能全部标记为"动漫"，必须根据 roomName/nickName 内容过滤出真正的动漫频道
            List<TogetherWatchRoom> animeCacheRooms = new ArrayList<>();
            for (TogetherWatchRoom room : cacheRooms) {
                if (isAnimeRelatedRoom(room.roomName, room.nickName)) {
                    animeCacheRooms.add(room);
                }
            }
            Log.d(TAG, "cache API 返回 " + cacheRooms.size() + " 个房间，过滤出 "
                    + animeCacheRooms.size() + " 个动漫房间");
            allRooms.addAll(animeCacheRooms);
        } catch (Exception e) {
            Log.d(TAG, "从cache获取动漫失败: " + e.getMessage());
        }
        Log.d(TAG, "动漫类总共获取到 " + allRooms.size() + " 个房间（去重前）");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    /**
     * 🟢 判断房间是否是动漫相关（用于 cache API 过滤）
     * cache API 返回的是一起看全分类房间，需根据 roomName/nickName 关键词判断。
     */
    private boolean isAnimeRelatedRoom(String roomName, String nickName) {
        if (TextUtils.isEmpty(roomName) && TextUtils.isEmpty(nickName)) return false;
        String text = (roomName + " " + nickName).toLowerCase();
        for (String kw : ANIME_RELATED_KEYWORDS) {
            if (!TextUtils.isEmpty(kw) && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<TogetherWatchRoom> fetchVarietyRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        // 🟢 循环抓取多个综艺子分类 ID
        for (int tmpId : VARIETY_TMP_IDS) {
            try {
                List<TogetherWatchRoom> rooms = fetchBySubCategory(tmpId, "综艺");
                Log.d(TAG, "VARIETY tmpId=" + tmpId + " 获取到 " + rooms.size() + " 个房间");
                allRooms.addAll(rooms);
            } catch (Exception e) {
                Log.d(TAG, "获取综艺子分类失败: tmpId=" + tmpId + ", " + e.getMessage());
            }
        }
        try {
            List<TogetherWatchRoom> cacheRooms = fetchFromCacheApi(VARIETY_CACHE_PAGES, "综艺");
            allRooms.addAll(cacheRooms);
        } catch (Exception e) {
            Log.d(TAG, "从cache获取综艺失败: " + e.getMessage());
        }
        Log.d(TAG, "综艺类总共获取到 " + allRooms.size() + " 个房间（去重前）");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchFromCacheApi(int maxPages, String categoryName) throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        int pageSize = 120;

        for (int page = 1; page <= maxPages; page++) {
            String url = API_CACHE_LIST + page;
            try {
                Response response = NetUtil.getInstance().syncGet(url);
                if (!response.isSuccessful() || response.body() == null) {
                    Log.d(TAG, "Cache API请求失败，响应码：" + response.code() + ", page=" + page);
                    break;
                }

                String resStr = response.body().string();
                List<TogetherWatchRoom> pageRooms = parseCacheRoomList(resStr, categoryName);
                if (pageRooms.isEmpty()) break;
                allRooms.addAll(pageRooms);
                if (pageRooms.size() < pageSize) break;
            } catch (Exception e) {
                Log.d(TAG, "Cache API解析失败: " + e.getMessage());
                break;
            }
        }

        Log.d(TAG, "从Cache API获取到 " + allRooms.size() + " 个" + categoryName + "房间");
        return allRooms;
    }

    private List<TogetherWatchRoom> parseCacheRoomList(String jsonStr, String categoryName) throws Exception {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        JSONObject json = new JSONObject(jsonStr);

        JSONObject data = json.optJSONObject("data");
        if (data == null) return rooms;

        JSONArray datas = data.optJSONArray("datas");
        if (datas == null || datas.length() == 0) return rooms;

        if (datas.length() > 0) {
            JSONObject firstRoom = datas.getJSONObject(0);
            Log.d(TAG, "cache第一个房间所有字段：" + firstRoom.keys());
        }

        for (int i = 0; i < datas.length(); i++) {
            JSONObject room = datas.getJSONObject(i);

            long roomNo = room.optLong("roomNo", 0);
            long uid = room.optLong("uid", 0);
            int roomId = (int) (roomNo > 0 ? roomNo : uid);
            if (roomId <= 0) continue;

            String roomName = room.optString("roomName", "");
            if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";

            String nickName = room.optString("nick", "");
            if (TextUtils.isEmpty(nickName)) nickName = "精彩节目";

            // 🟢 加强过滤：只要 roomName 或 nickName 是"精彩节目"占位就过滤
            // 这些通常是已下播/无效频道的占位条目，无播放价值
            if ("精彩节目".equals(roomName) || "精彩节目".equals(nickName)) {
                Log.d(TAG, "过滤精彩节目占位: roomId=" + roomId);
                continue;
            }

            String coverUrl = room.optString("screenshot", "");

            String totalCountStr = room.optString("totalCount", "0");
            int onlineCount = 0;
            try {
                onlineCount = Integer.parseInt(totalCountStr);
            } catch (Exception e) {}

            int bIsLive = room.optInt("isLive", -1);
            boolean isLive = (bIsLive == 1 || onlineCount > 0);

            if (!isLive && onlineCount == 0) {
                continue;
            }

            TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, roomId, roomName, nickName,
                    coverUrl, onlineCount, categoryName);
            twRoom.isLive = isLive;
            rooms.add(twRoom);
        }

        return rooms;
    }

    private List<TogetherWatchRoom> deduplicateRooms(List<TogetherWatchRoom> rooms) {
        List<TogetherWatchRoom> result = new ArrayList<>();
        List<Integer> seenIds = new ArrayList<>();
        for (TogetherWatchRoom room : rooms) {
            if (!seenIds.contains(room.roomId)) {
                seenIds.add(room.roomId);
                result.add(room);
            }
        }
        if (result.size() < rooms.size()) {
            Log.d(TAG, "去重后房间数: " + result.size() + " (原: " + rooms.size() + ")");
        }
        return result;
    }

    private List<TogetherWatchRoom> filterAndSortRooms(List<TogetherWatchRoom> rooms) {
        List<TogetherWatchRoom> validRooms = new ArrayList<>();
        List<TogetherWatchRoom> suspectRooms = new ArrayList<>();

        for (TogetherWatchRoom room : rooms) {
            if (room.onlineCount > 10 || room.isLive) {
                validRooms.add(room);
            } else if (room.onlineCount > 0) {
                suspectRooms.add(room);
            }
        }

        validRooms.addAll(suspectRooms);

        Collections.sort(validRooms, (a, b) -> {
            if (b.isLive != a.isLive) {
                return b.isLive ? 1 : -1;
            }
            return b.onlineCount - a.onlineCount;
        });

        Log.d(TAG, "过滤排序后房间数: " + validRooms.size() + " (原: " + rooms.size() + ")");
        return validRooms;
    }

    private List<TogetherWatchRoom> classifyRoomsByKeywords(List<TogetherWatchRoom> sourceRooms,
                                                           String[][] categoryKeywords,
                                                           String defaultCategory) {
        List<TogetherWatchRoom> result = new ArrayList<>();
        if (sourceRooms.isEmpty()) return result;

        // 🟢 统计各分组数量 + 收集兜底频道名（用于调试关键词覆盖）
        java.util.Map<String, Integer> categoryCount = new java.util.LinkedHashMap<>();
        List<String> defaultRoomNames = new ArrayList<>();

        for (TogetherWatchRoom room : sourceRooms) {
            String searchText = (room.roomName + " " + room.nickName).toLowerCase();
            String matchedCategory = defaultCategory;

            for (String[] ck : categoryKeywords) {
                String categoryName = ck[0];
                String keywords = ck[1];
                if (TextUtils.isEmpty(keywords)) continue;

                String[] keywordArr = keywords.split("\\|");
                for (String kw : keywordArr) {
                    if (!TextUtils.isEmpty(kw) && searchText.contains(kw.toLowerCase())) {
                        matchedCategory = categoryName;
                        break;
                    }
                }
                if (matchedCategory != null && !matchedCategory.equals(defaultCategory)) break;
            }

            if (matchedCategory != null) {
                result.add(new TogetherWatchRoom(room.roomId, room.profileRoom, room.roomName, room.nickName,
                        room.coverUrl, room.onlineCount, matchedCategory));
                categoryCount.merge(matchedCategory, 1, Integer::sum);
                if (matchedCategory.equals(defaultCategory)) {
                    defaultRoomNames.add(room.roomName);
                }
            }
        }

        Log.d(TAG, "按关键词分类完成: " + result.size() + " 个房间，默认分类: " + defaultCategory);
        // 🟢 打印各分组分布
        for (java.util.Map.Entry<String, Integer> e : categoryCount.entrySet()) {
            Log.d(TAG, "  分组[" + e.getKey() + "] = " + e.getValue() + " 个频道");
        }
        // 🟢 打印兜底分组频道名（前30个），用于分析关键词覆盖缺口
        if (!defaultRoomNames.isEmpty()) {
            int limit = Math.min(30, defaultRoomNames.size());
            Log.d(TAG, "  兜底[" + defaultCategory + "]频道名(" + defaultRoomNames.size() + "个): "
                    + String.join(" | ", defaultRoomNames.subList(0, limit)));
        }
        return result;
    }

    private List<TogetherWatchRoom> fetchBySubCategory(int subCategoryId, String categoryName) throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        int maxPages = 10;
        int pageSize = 500;

        for (int page = 1; page <= maxPages; page++) {
            String url = API_TMP_LIST + "?iGid=" + CATEGORY_ID_TOGETHER_WATCH +
                    "&iTmpId=" + subCategoryId + "&iPageNo=" + page + "&iPageSize=" + pageSize;

            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d(TAG, "API请求失败，响应码：" + response.code() + ", category=" + categoryName + ", page=" + page);
                break;
            }

            String resStr = response.body().string();
            Log.d(TAG, "API响应长度：" + resStr.length() + ", category=" + categoryName + ", page=" + page);

            try {
                List<TogetherWatchRoom> pageRooms = parseRoomList(resStr, categoryName);
                if (pageRooms.isEmpty()) break;
                allRooms.addAll(pageRooms);
                if (pageRooms.size() < pageSize) break;
            } catch (Exception e) {
                Log.d(TAG, "解析失败：" + e.getMessage());
                break;
            }
        }

        Log.d(TAG, "总共获取到 " + allRooms.size() + " 个" + categoryName + "房间");
        return allRooms;
    }

    private List<TogetherWatchRoom> parseRoomList(String jsonStr, String categoryName) throws Exception {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        JSONObject json = new JSONObject(jsonStr);

        JSONArray vList = json.optJSONArray("vList");
        if (vList != null) {
            Log.d(TAG, "找到vList数组，长度：" + vList.length() + ", category=" + categoryName);
            if (vList.length() > 0) {
                JSONObject firstRoom = vList.getJSONObject(0);
                Log.d(TAG, "第一个房间所有字段：" + firstRoom.keys());
            }
            for (int i = 0; i < vList.length(); i++) {
                JSONObject room = vList.getJSONObject(i);

                long lRoomId = room.optLong("lRoomId", 0);
                long lUid = room.optLong("lUid", 0);
                long lProfileRoom = room.optLong("lProfileRoom", 0);
                int roomId = (int) (lRoomId > 0 ? lRoomId : lUid);
                int profileRoomId = (int) (lProfileRoom > 0 ? lProfileRoom : roomId);
                if (roomId <= 0) continue;

                String roomName = room.optString("sRoomName", "");
                if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";

                String sIntroduction = room.optString("sIntroduction", "");
                String nickName = TextUtils.isEmpty(sIntroduction) ? "精彩节目" : sIntroduction;

                // 🟢 加强过滤：只要 roomName 或 nickName 是"精彩节目"占位就过滤
                if ("精彩节目".equals(roomName) || "精彩节目".equals(nickName)) {
                    Log.d(TAG, "过滤精彩节目占位: roomId=" + roomId);
                    continue;
                }

                String coverUrl = room.optString("sScreenshot", "");

                long userCount = room.optLong("lUserCount", 0);
                long totalCount = room.optLong("lTotalCount", 0);
                int onlineCount = (int) Math.max(userCount, totalCount);

                int liveStatus = room.optInt("iLiveStatus", -1);
                int bIsLive = room.optInt("bIsLive", -1);
                boolean isLive = (liveStatus == 1 || bIsLive == 1 || onlineCount > 0);

                if (!isLive && onlineCount == 0) {
                    continue;
                }

                TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, profileRoomId, roomName, nickName,
                        coverUrl, onlineCount, categoryName);
                twRoom.isLive = isLive;
                rooms.add(twRoom);
            }
        }

        Log.d(TAG, "解析到 " + rooms.size() + " 个" + categoryName + "房间（已过滤失效）");
        return rooms;
    }

    private List<TogetherWatchRoom> getFallbackRooms() {
        List<TogetherWatchRoom> rooms = new ArrayList<>();

        rooms.add(new TogetherWatchRoom(1394575534, 11342412, "【周星星】星爷经典不间断", "周星星", "", 5000, "电影_星爷英叔"));
        rooms.add(new TogetherWatchRoom(1394575543, 11342421, "英叔护体 | 林正英搞笑僵尸系列", "7喜先生", "", 4500, "电影_星爷英叔"));
        rooms.add(new TogetherWatchRoom(1524439855, 880261, "我摊牌啦 一起看热门大片", "虎牙八点档", "", 6000, "电影_其他"));
        rooms.add(new TogetherWatchRoom(616112, 616112, "动作大片", "虎牙一起看", "", 4500, "电影_动作港片"));
        rooms.add(new TogetherWatchRoom(616113, 616113, "惊悚悬疑", "虎牙一起看", "", 4000, "电影_悬疑恐怖"));
        rooms.add(new TogetherWatchRoom(616114, 616114, "科幻世界", "虎牙一起看", "", 3500, "电影_科幻漫威"));
        rooms.add(new TogetherWatchRoom(616115, 616115, "古装巨制", "虎牙一起看", "", 3000, "电影_战争武侠"));

        rooms.add(new TogetherWatchRoom(616121, 616121, "古装剧集", "虎牙一起看", "", 4500, "剧集_经典古装"));
        rooms.add(new TogetherWatchRoom(616122, 616122, "军旅题材", "虎牙一起看", "", 4000, "剧集_军旅抗战"));
        rooms.add(new TogetherWatchRoom(616123, 616123, "搞笑剧集", "虎牙一起看", "", 3500, "剧集_情景喜剧"));
        rooms.add(new TogetherWatchRoom(616124, 616124, "悬疑推理", "虎牙一起看", "", 3000, "剧集_悬疑刑侦"));
        rooms.add(new TogetherWatchRoom(616125, 616125, "都市情感", "虎牙一起看", "", 2500, "剧集_其他"));
        rooms.add(new TogetherWatchRoom(616126, 616126, "剧情精选", "虎牙一起看", "", 2000, "剧集_其他"));

        // 🟢 扩充动漫频道兜底（匹配精简后分组）
        rooms.add(new TogetherWatchRoom(96000001, 96000001, "热血动漫专播", "热血动漫专播", "", 1200, "动漫_热血日漫"));
        rooms.add(new TogetherWatchRoom(96000002, 96000002, "经典国漫 24h", "经典国漫 24h", "", 1000, "动漫_精品国漫"));
        rooms.add(new TogetherWatchRoom(96000003, 96000003, "搞笑日常精选", "搞笑日常精选", "", 800, "动漫_日常治愈"));
        rooms.add(new TogetherWatchRoom(96000004, 96000004, "少女向治愈系", "少女向治愈系", "", 600, "动漫_日常治愈"));
        rooms.add(new TogetherWatchRoom(96000005, 96000005, "动漫剧场版合集", "动漫剧场版合集", "", 700, "动漫_奇幻异世界"));
        rooms.add(new TogetherWatchRoom(96000006, 96000006, "怀旧经典动画", "怀旧经典动画", "", 500, "动漫_怀旧经典"));
        rooms.add(new TogetherWatchRoom(96000007, 96000007, "热门新番速递", "热门新番速递", "", 1500, "动漫_其他"));
        rooms.add(new TogetherWatchRoom(96000008, 96000008, "柯南/死神/犬夜叉", "柯南/死神/犬夜叉", "", 900, "动漫_热血日漫"));

        rooms.add(new TogetherWatchRoom(660005, 660005, "动漫剧场", "虎牙一起看", "", 4500, "动漫_其他"));
        rooms.add(new TogetherWatchRoom(660004, 660004, "热门综艺", "虎牙一起看", "", 6000, "综艺_热门综艺"));
        rooms.add(new TogetherWatchRoom(660006, 660006, "体育赛事", "虎牙一起看", "", 3000, "综艺_其他"));
        rooms.add(new TogetherWatchRoom(660007, 660007, "纪录片", "虎牙一起看", "", 2500, "电影_其他"));
        rooms.add(new TogetherWatchRoom(660008, 660008, "演唱会", "虎牙一起看", "", 5000, "综艺_音乐歌舞"));
        rooms.add(new TogetherWatchRoom(660009, 660009, "游戏回放", "虎牙一起看", "", 3500, "电影_其他"));
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
        // 优先级：Pure 原生解析 → SDK 解析
        // Pure 解析不依赖 SDK，纯 OkHttp + 正则，速度最快
        HuyaPureParser.parse(roomId, new HuyaPureParser.OnParseResultListener() {
            @Override
            public void onSuccess(String hlsUrl, String flvUrl, Map<String, String> headers) {
                String playUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                if (!TextUtils.isEmpty(playUrl)) {
                    listener.onSuccess(hlsUrl, flvUrl);
                } else {
                    // Pure 成功但无 URL，回退 SDK
                    fallbackToSDK(roomId, listener);
                }
            }

            @Override
            public void onFailed(String errorMsg) {
                Log.d(TAG, "Pure 解析失败: " + errorMsg + "，回退 SDK");
                fallbackToSDK(roomId, listener);
            }
        });
    }

    private void fallbackToSDK(int roomId, OnPlayUrlListener listener) {
        if (HuyaSDKParser.isSDKAvailable()) {
            HuyaSDKParser.parse(roomId, new HuyaSDKParser.OnSDKResultListener() {
                @Override
                public void onSuccess(String hlsUrl, String flvUrl, boolean isHls) {
                    String playUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                    if (!TextUtils.isEmpty(playUrl)) {
                        listener.onSuccess(hlsUrl, flvUrl);
                    } else {
                        listener.onFailed("未获取到播放地址");
                    }
                }

                @Override
                public void onError(String error) {
                    Log.d(TAG, "SDK 解析失败: " + error + "，解析失败");
                    listener.onFailed(error);
                }
            });
        } else {
            listener.onFailed("SDK 解析不可用");
        }
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
