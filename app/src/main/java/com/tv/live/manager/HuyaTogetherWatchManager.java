package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.Channel;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import okhttp3.Response;

public class HuyaTogetherWatchManager {
    private static final String TAG = "HuyaTogetherWatch";
    private static volatile HuyaTogetherWatchManager sInstance;

    private static final String API_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList";
    private static final String API_CACHE_LIST = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=";

    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    private static final int[] MOVIE_TMP_IDS = {2067, 2069, 2071, 2073, 2075, 2077, 2068, 2070, 2072, 2074, 2076};
    private static final int[] TV_TMP_IDS = {2079, 2081, 2083, 2085, 2087, 2089, 2080, 2082, 2084, 2086, 2088, 2090};
    private static final int SUB_CATEGORY_ANIME = 6861;
    private static final int SUB_CATEGORY_VARIETY = 1011;

    private static final int ANIME_CACHE_PAGES = 8;
    private static final int VARIETY_CACHE_PAGES = 8;
    private static final int GENERAL_CACHE_PAGES = 5;

    private static final String[][] MOVIE_CATEGORY_KEYWORDS = {
        {"电影_动作", "成龙|李连杰|洪金宝|元彪|甄子丹|吴京|赵文卓|功夫|醉拳|警察故事|黄飞鸿|方世玉|霍元甲|战狼|红海|敢死队|特种兵|狙击|军事|枪战|赛车|速度|激情|飙车|头文字D"},
        {"电影_喜剧", "沈腾|黄渤|开心麻花|西虹市|夏洛特|疯狂的|腾哥|渤哥|许冠文|许冠英|许冠杰|许氏|星爷|周星|周星驰|周星星|星驰|搞笑|幽默|陈翔六点半|陈翔|六点半|搞笑解说|吐槽|恶搞"},
        {"电影_科幻", "漫威|钢铁侠|复仇者|复联|蜘蛛侠|美国队长|绿巨人|雷神|黑豹|蚁人|奇异博士|银河护卫队|X战警|金刚狼|死侍|毒液|变形金刚|擎天柱|宇宙|星际|星球大战|星际穿越|火星|太空|银河|科幻|未来|外星|玄幻|修仙|修真|仙侠|诛仙|斗破|斗罗|科幻梦工场"},
        {"电影_悬疑", "悬疑|推理|烧脑|反转|谍战|特工|间谍|卧底|007|碟中谍|碟影重重|恐怖|惊悚|鬼片|丧尸|活死人|生化危机|死神来了|盗墓|鬼吹灯|胡八一|盗墓笔记|古墓|探险|寻宝|警匪|犯罪|黑帮|古惑仔|恐怖电影解说|恐怖解说"},
        {"电影_经典", "周润发|发哥|刘德华|华仔|梁朝伟|梁家辉|古天乐|张家辉|港片|香港|港产|TVB|赌神|无间道|英雄本色|武侠|金庸|古龙|张三丰|太极|笑傲|神雕|射雕|天龙八部|倚天|古装|宫廷|历史|怪兽|哥斯拉|金刚|恐龙|侏罗纪|灾难|末日|英叔|林正英|僵尸|经典|解说|解读|影评|扁豆|乌贼|大象|亮哥|越哥|小冉|嫦娥|阿翔|虎妞|顾久|涵哥|俗哥|老皮|默爷|冷君|老炮|刘老师|鹿哥|斌哥|续哥|小川|阿钙|阿良|鱼丸|阿斗|电影狂人|疯狂解说"},
    };

    private static final String[][] TV_CATEGORY_KEYWORDS = {
        {"剧集_古装", "古装|宫廷|历史|清朝|唐朝|汉朝|三国|西游记|红楼梦|神雕|射雕|天龙八部|倚天|庆余年|范闲|武林外传|同福客栈|佟湘玉|白展堂"},
        {"剧集_现代", "爱情公寓|曾小贤|胡一菲|家有儿女|刘星|夏雪|夏雨|电视剧|都市|情感|家庭|年代|伦理"},
        {"剧集_悬疑", "悬疑|推理|犯罪|破案|刑侦|法医|谍战|卧底|暗战"},
        {"剧集_军旅", "军旅|战争|抗战|特种兵|士兵|亮剑|士兵突击|许三多|王宝强|战战"},
        {"剧集_经典", "新三国|三国新|三国演义|老三国|94三国|水浒|水浒传|纪晓岚|铁齿铜牙|和珅|雍正王朝|康熙王朝|乾隆王朝|搞笑|喜剧|爆笑"},
    };

    private static final String[][] VARIETY_CATEGORY_KEYWORDS = {
        {"综艺_音乐", "音乐|唱歌|歌手|乐队|好声音|我是歌手|演唱会"},
        {"综艺_搞笑", "搞笑|喜剧|脱口秀|相声|小品|爆笑"},
        {"综艺_选秀", "选秀|创造|偶像|练习生|101|青春有你"},
        {"综艺_真人秀", "真人秀|奔跑吧|极限挑战|王牌对王牌"},
    };

    private static final String[][] ANIME_CATEGORY_KEYWORDS = {
        {"动漫_热血", "热血|火影|海贼|龙珠|进击的巨人|一拳超人|鬼灭|咒术|我的英雄学院"},
        {"动漫_国漫", "国漫|国产|斗罗|斗破|完美世界|秦时明月|画江湖"},
        {"动漫_日常", "日常|治愈|蜡笔小新|哆啦A梦|樱桃小丸子|海绵宝宝"},
        {"动漫_少女", "少女|恋爱|校园|魔卡少女樱|美少女战士"},
    };

    private static final PrecompiledCategory[] PRECOMPILED_MOVIE;
    private static final PrecompiledCategory[] PRECOMPILED_TV;
    private static final PrecompiledCategory[] PRECOMPILED_VARIETY;
    private static final PrecompiledCategory[] PRECOMPILED_ANIME;

    static {
        PRECOMPILED_MOVIE = precompile(MOVIE_CATEGORY_KEYWORDS);
        PRECOMPILED_TV = precompile(TV_CATEGORY_KEYWORDS);
        PRECOMPILED_VARIETY = precompile(VARIETY_CATEGORY_KEYWORDS);
        PRECOMPILED_ANIME = precompile(ANIME_CATEGORY_KEYWORDS);
    }

    private static PrecompiledCategory[] precompile(String[][] raw) {
        PrecompiledCategory[] result = new PrecompiledCategory[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = new PrecompiledCategory(raw[i][0], raw[i][1]);
        }
        return result;
    }

    private static class PrecompiledCategory {
        final String categoryName;
        final String[] keywords;

        PrecompiledCategory(String name, String rawKeywords) {
            this.categoryName = name;
            String[] parts = rawKeywords.split("\\|");
            int valid = 0;
            for (String p : parts) if (!p.isEmpty()) valid++;
            this.keywords = new String[valid];
            int idx = 0;
            for (String p : parts) {
                if (!p.isEmpty()) this.keywords[idx++] = p.toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile List<TogetherWatchRoom> mRoomList = new ArrayList<>();
    private volatile long mLastFetchTime = 0;
    private static final long CACHE_VALID_MS = 5 * 60 * 1000;

    private Context mAppContext;
    private static final String CACHE_FILE_NAME = "huya_together_watch_rooms.json";
    private static final long LOCAL_DATA_VALID_MS = 24 * 60 * 60 * 1000;

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

    public void setContext(Context context) {
        mAppContext = context.getApplicationContext();
    }

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
                List<TogetherWatchRoom> localRooms = loadRoomsFromLocal();
                if (localRooms != null && !localRooms.isEmpty()) {
                    Log.d(TAG, "从本地缓存加载 " + localRooms.size() + " 个频道");
                    mRoomList = localRooms;
                    mLastFetchTime = System.currentTimeMillis();
                    mMainHandler.post(() -> listener.onSuccess(localRooms));

                    refreshRoomsInBackground();
                    return;
                }

                fetchRoomsFromNetwork(listener);
            } catch (Exception e) {
                mMainHandler.post(() -> listener.onFailed("加载频道失败: " + e.getMessage()));
            }
        });
    }

    private void refreshRoomsInBackground() {
        mExecutor.execute(() -> {
            try {
                List<TogetherWatchRoom> freshRooms = fetchAllAndClassify();
                if (!freshRooms.isEmpty()) {
                    saveRoomsToLocal(freshRooms);
                    mMainHandler.post(() -> {
                        mRoomList = freshRooms;
                        mLastFetchTime = System.currentTimeMillis();
                    });
                    Log.d(TAG, "后台刷新完成: " + freshRooms.size() + " 个频道");
                }
            } catch (Exception e) {
                Log.d(TAG, "后台刷新失败（保留本地数据）: " + e.getMessage());
            }
        });
    }

    private void fetchRoomsFromNetwork(OnFetchListener listener) {

        mExecutor.execute(() -> {
            try {
                List<TogetherWatchRoom> rooms = fetchAllAndClassify();

                if (rooms.isEmpty()) {
                    rooms = getFallbackRooms();
                }

                if (rooms.isEmpty()) {
                    postFailed(listener, "未获取到一起看内容");
                    return;
                }

                saveRoomsToLocal(rooms);
                mRoomList = rooms;
                mLastFetchTime = System.currentTimeMillis();
                postSuccess(listener, rooms);

            } catch (IOException e) {
                Log.d(TAG, "网络请求异常，使用内置备用数据");
                List<TogetherWatchRoom> rooms = getFallbackRooms();
                if (!rooms.isEmpty()) {
                    mRoomList = rooms;
                    mLastFetchTime = System.currentTimeMillis();
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

    private List<TogetherWatchRoom> fetchAllAndClassify() throws IOException {
        long startMs = System.currentTimeMillis();
        final List<TogetherWatchRoom> movieRooms = new ArrayList<>();
        final List<TogetherWatchRoom> tvRooms = new ArrayList<>();
        final List<TogetherWatchRoom> animeRooms = new ArrayList<>();
        final List<TogetherWatchRoom> varietyRooms = new ArrayList<>();
        final List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch latch = new CountDownLatch(4);

        mExecutor.execute(() -> {
            try { movieRooms.addAll(fetchMovieRooms()); } catch (Exception e) { errors.add(e); }
            finally { latch.countDown(); }
        });
        mExecutor.execute(() -> {
            try { tvRooms.addAll(fetchTvRooms()); } catch (Exception e) { errors.add(e); }
            finally { latch.countDown(); }
        });
        mExecutor.execute(() -> {
            try { animeRooms.addAll(fetchAnimeRooms()); } catch (Exception e) { errors.add(e); }
            finally { latch.countDown(); }
        });
        mExecutor.execute(() -> {
            try { varietyRooms.addAll(fetchVarietyRooms()); } catch (Exception e) { errors.add(e); }
            finally { latch.countDown(); }
        });

        try {
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<TogetherWatchRoom> rooms = new ArrayList<>();
        rooms.addAll(classifyRoomsByKeywords(movieRooms, PRECOMPILED_MOVIE, "电影_经典"));
        rooms.addAll(classifyRoomsByKeywords(tvRooms, PRECOMPILED_TV, "剧集_经典"));
        rooms.addAll(classifyRoomsByKeywords(animeRooms, PRECOMPILED_ANIME, "动漫_热血"));
        rooms.addAll(classifyRoomsByKeywords(varietyRooms, PRECOMPILED_VARIETY, "综艺_真人秀"));

        long elapsed = System.currentTimeMillis() - startMs;
        Log.d(TAG, "并行加载完成: 电影=" + movieRooms.size() + " 剧集=" + tvRooms.size()
                + " 动漫=" + animeRooms.size() + " 综艺=" + varietyRooms.size()
                + " 总计=" + rooms.size() + " 耗时=" + elapsed + "ms"
                + (errors.isEmpty() ? "" : " 错误=" + errors.size()));

        if (!errors.isEmpty() && rooms.isEmpty()) {
            throw new IOException("所有分类请求失败", errors.get(0));
        }

        return rooms;
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
        try {
            List<TogetherWatchRoom> rooms = fetchBySubCategory(SUB_CATEGORY_ANIME, "动漫");
            allRooms.addAll(rooms);
        } catch (Exception e) {
            Log.d(TAG, "获取动漫子分类失败: " + e.getMessage());
        }
        try {
            List<TogetherWatchRoom> cacheRooms = fetchFromCacheApi(ANIME_CACHE_PAGES, "动漫");
            allRooms.addAll(cacheRooms);
        } catch (Exception e) {
            Log.d(TAG, "从cache获取动漫失败: " + e.getMessage());
        }
        Log.d(TAG, "动漫类总共获取到 " + allRooms.size() + " 个房间");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchVarietyRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        try {
            List<TogetherWatchRoom> rooms = fetchBySubCategory(SUB_CATEGORY_VARIETY, "综艺");
            allRooms.addAll(rooms);
        } catch (Exception e) {
            Log.d(TAG, "获取综艺子分类失败: " + e.getMessage());
        }
        try {
            List<TogetherWatchRoom> cacheRooms = fetchFromCacheApi(VARIETY_CACHE_PAGES, "综艺");
            allRooms.addAll(cacheRooms);
        } catch (Exception e) {
            Log.d(TAG, "从cache获取综艺失败: " + e.getMessage());
        }
        Log.d(TAG, "综艺类总共获取到 " + allRooms.size() + " 个房间");
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

            boolean isPlaceholder = "精彩节目".equals(roomName)
                    || roomName.startsWith("精彩节目")
                    || "精彩节目".equals(nickName);

            if (!isPlaceholder) {
                TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, roomId, roomName, nickName,
                        coverUrl, onlineCount, categoryName);
                twRoom.isLive = isLive;
                rooms.add(twRoom);
            }
        }

        return rooms;
    }

    private List<TogetherWatchRoom> deduplicateRooms(List<TogetherWatchRoom> rooms) {
        List<TogetherWatchRoom> result = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (TogetherWatchRoom room : rooms) {
            if (seenIds.add(room.roomId)) {
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

        for (TogetherWatchRoom room : rooms) {
            boolean isPlaceholder = "精彩节目".equals(room.roomName)
                    || room.roomName.startsWith("精彩节目")
                    || "精彩节目".equals(room.nickName);

            if (isPlaceholder) continue;

            if (room.onlineCount > 5 || room.isLive) {
                validRooms.add(room);
            }
        }

        validRooms.sort((a, b) -> {
            if (b.isLive != a.isLive) {
                return b.isLive ? 1 : -1;
            }
            return b.onlineCount - a.onlineCount;
        });

        Log.d(TAG, "过滤排序后房间数: " + validRooms.size() + " (原: " + rooms.size() + ")");
        return validRooms;
    }

    private List<TogetherWatchRoom> classifyRoomsByKeywords(List<TogetherWatchRoom> sourceRooms,
                                                           PrecompiledCategory[] precompiledCategories,
                                                           String defaultCategory) {
        if (sourceRooms.isEmpty()) return new ArrayList<>();

        for (TogetherWatchRoom room : sourceRooms) {
            String searchText = (room.roomName + " " + room.nickName).toLowerCase(java.util.Locale.ROOT);
            String matchedCategory = defaultCategory;

            for (PrecompiledCategory pc : precompiledCategories) {
                boolean found = false;
                for (String kw : pc.keywords) {
                    if (searchText.contains(kw)) {
                        matchedCategory = pc.categoryName;
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }

            room.category = matchedCategory;
        }

        return sourceRooms;
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

                boolean isPlaceholder = "精彩节目".equals(roomName)
                        || roomName.startsWith("精彩节目")
                        || "精彩节目".equals(nickName);

                if (!isPlaceholder) {
                    TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, profileRoomId, roomName, nickName,
                            coverUrl, onlineCount, categoryName);
                    twRoom.isLive = isLive;
                    rooms.add(twRoom);
                }
            }
        }

        Log.d(TAG, "解析到 " + rooms.size() + " 个" + categoryName + "房间（已过滤失效）");
        return rooms;
    }

    private List<TogetherWatchRoom> getFallbackRooms() {
        List<TogetherWatchRoom> rooms = new ArrayList<>();

        rooms.add(new TogetherWatchRoom(1394575534, 11342412, "【周星星】星爷经典不间断", "周星星", "", 5000, "电影_星爷"));
        rooms.add(new TogetherWatchRoom(1394575543, 11342421, "英叔护体 | 林正英搞笑僵尸系列", "7喜先生", "", 4500, "电影_英叔"));
        rooms.add(new TogetherWatchRoom(1524439855, 880261, "我摊牌啦 一起看热门大片", "虎牙八点档", "", 6000, "电影_推荐"));
        rooms.add(new TogetherWatchRoom(616112, 616112, "动作大片", "虎牙一起看", "", 4500, "电影_动作电影"));
        rooms.add(new TogetherWatchRoom(616113, 616113, "惊悚悬疑", "虎牙一起看", "", 4000, "电影_高分动作"));
        rooms.add(new TogetherWatchRoom(616114, 616114, "科幻世界", "虎牙一起看", "", 3500, "电影_宇宙"));
        rooms.add(new TogetherWatchRoom(616115, 616115, "古装巨制", "虎牙一起看", "", 3000, "电影_武侠"));

        rooms.add(new TogetherWatchRoom(616121, 616121, "古装剧集", "虎牙一起看", "", 4500, "剧集_古装"));
        rooms.add(new TogetherWatchRoom(616122, 616122, "军旅题材", "虎牙一起看", "", 4000, "剧集_军旅"));
        rooms.add(new TogetherWatchRoom(616123, 616123, "搞笑剧集", "虎牙一起看", "", 3500, "剧集_搞笑"));
        rooms.add(new TogetherWatchRoom(616124, 616124, "悬疑推理", "虎牙一起看", "", 3000, "剧集_悬疑"));
        rooms.add(new TogetherWatchRoom(616125, 616125, "都市情感", "虎牙一起看", "", 2500, "剧集_都市"));
        rooms.add(new TogetherWatchRoom(616126, 616126, "剧情精选", "虎牙一起看", "", 2000, "剧集_剧情"));

        rooms.add(new TogetherWatchRoom(660005, 660005, "动漫剧场", "虎牙一起看", "", 4500, "动漫_热门"));
        rooms.add(new TogetherWatchRoom(660004, 660004, "热门综艺", "虎牙一起看", "", 6000, "综艺_热门"));
        rooms.add(new TogetherWatchRoom(660006, 660006, "体育赛事", "虎牙一起看", "", 3000, "综艺_热门"));
        rooms.add(new TogetherWatchRoom(660007, 660007, "纪录片", "虎牙一起看", "", 2500, "电影_推荐"));
        rooms.add(new TogetherWatchRoom(660008, 660008, "演唱会", "虎牙一起看", "", 5000, "综艺_音乐"));
        rooms.add(new TogetherWatchRoom(660009, 660009, "游戏回放", "虎牙一起看", "", 3500, "电影_推荐"));
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
        // 虎牙一起看：使用 HuyaSDKParser 解析（SDK 全量解析）
        Log.d(TAG, "getPlayUrl 开始解析, roomId=" + roomId + ", SDK可用=" + HuyaSDKParser.isSDKAvailable());
        if (!HuyaSDKParser.isSDKAvailable()) {
            Log.e(TAG, "getPlayUrl SDK 不可用");
            listener.onFailed("SDK 不可用");
            return;
        }

        HuyaSDKParser.parseFull(roomId, new HuyaSDKParser.OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaSDKParser.HuyaStreamInfo defaultStream,
                                  java.util.List<HuyaSDKParser.HuyaStreamInfo> allStreams,
                                  java.util.List<String> lines) {
                Log.d(TAG, "getPlayUrl SDK 解析成功: defaultStream=" + (defaultStream != null ? defaultStream.toString() : "null")
                        + ", 总流数=" + (allStreams != null ? allStreams.size() : 0)
                        + ", 线路数=" + (lines != null ? lines.size() : 0));
                if (defaultStream != null && !TextUtils.isEmpty(defaultStream.getPlayUrl())) {
                    Log.d(TAG, "getPlayUrl 返回 HLS=" + defaultStream.hlsUrl + " FLV=" + defaultStream.flvUrl);
                    listener.onSuccess(defaultStream.hlsUrl, defaultStream.flvUrl);
                } else {
                    Log.e(TAG, "getPlayUrl SDK 返回空地址");
                    listener.onFailed("SDK 返回空地址");
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "getPlayUrl SDK 解析失败: " + error);
                listener.onFailed(error);
            }
        });
    }

    private void postSuccess(OnFetchListener listener, List<TogetherWatchRoom> rooms) {
        mMainHandler.post(() -> listener.onSuccess(rooms));
    }

    private void postFailed(OnFetchListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    private void saveRoomsToLocal(List<TogetherWatchRoom> rooms) {
        if (mAppContext == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis());
            JSONArray array = new JSONArray();
            for (TogetherWatchRoom room : rooms) {
                JSONObject obj = new JSONObject();
                obj.put("roomId", room.roomId);
                obj.put("profileRoom", room.profileRoom);
                obj.put("roomName", room.roomName);
                obj.put("nickName", room.nickName);
                obj.put("coverUrl", room.coverUrl != null ? room.coverUrl : "");
                obj.put("onlineCount", room.onlineCount);
                obj.put("category", room.category != null ? room.category : "");
                obj.put("isLive", room.isLive);
                array.put(obj);
            }
            root.put("rooms", array);

            File file = new File(mAppContext.getFilesDir(), CACHE_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(root.toString());
            writer.close();
            fos.close();
            Log.d(TAG, "保存 " + rooms.size() + " 个频道到本地");
        } catch (Exception e) {
            Log.d(TAG, "保存本地频道失败: " + e.getMessage());
        }
    }

    private List<TogetherWatchRoom> loadRoomsFromLocal() {
        if (mAppContext == null) return null;
        try {
            File file = new File(mAppContext.getFilesDir(), CACHE_FILE_NAME);
            if (!file.exists()) return null;

            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            fis.close();

            JSONObject root = new JSONObject(sb.toString());
            long timestamp = root.optLong("timestamp", 0);
            if (System.currentTimeMillis() - timestamp > LOCAL_DATA_VALID_MS) {
                Log.d(TAG, "本地缓存已过期（超过24小时）");
                return null;
            }

            JSONArray array = root.optJSONArray("rooms");
            if (array == null || array.length() == 0) return null;

            List<TogetherWatchRoom> rooms = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                int roomId = obj.optInt("roomId", 0);
                if (roomId <= 0) continue;

                TogetherWatchRoom room = new TogetherWatchRoom(
                        roomId,
                        obj.optInt("profileRoom", roomId),
                        obj.optString("roomName", ""),
                        obj.optString("nickName", ""),
                        obj.optString("coverUrl", ""),
                        obj.optInt("onlineCount", 0),
                        obj.optString("category", "")
                );
                room.isLive = obj.optBoolean("isLive", false);
                rooms.add(room);
            }

            Log.d(TAG, "从本地加载 " + rooms.size() + " 个频道, 缓存时间: " + timestamp);
            return rooms;
        } catch (Exception e) {
            Log.d(TAG, "加载本地频道失败: " + e.getMessage());
            return null;
        }
    }

    public void release() {
        mExecutor.shutdownNow();
        mRoomList.clear();
    }
}