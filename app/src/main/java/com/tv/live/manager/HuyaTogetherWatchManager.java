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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Response;

public class HuyaTogetherWatchManager {
    private static final String TAG = "HuyaTogetherWatch";
    private static volatile HuyaTogetherWatchManager sInstance;

    private static final String API_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList";
    private static final String API_CACHE_LIST = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=";
    /** 🟢 本地永久兜底文件名：网络全失败时继续可用（文件存储在 filesDir，不随缓存清理） */
    private static final String FALLBACK_FILE = "huya_together_fallback.json";
    /** 🟢 写入兜底最少房间数：防止虎牙返回空数据把好兜底覆盖掉 */
    private static final int MIN_ROOMS_FOR_FALLBACK = 10;

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

    // ======================【用户指定 16 个分组】======================
    // 🟢 电影（5个）：喜剧 > 动作 > 悬疑 > 科幻 > 经典（兜底）
    // 🟢 剧集（5个）：古装 > 军旅 > 悬疑 > 现代 > 经典（兜底）
    // 🟢 动漫（4个）：少女 > 国漫 > 日常 > 热血（兜底）
    // 🟢 综艺（2个）：搞笑 > 真人秀（兜底）
    // ====================================================================
    private static final String[][] MOVIE_CATEGORY_KEYWORDS = {
        {"电影_喜剧", "沈腾|黄渤|王宝强|周星驰|星爷|开心麻花|爆笑|喜剧|小品|相声|陈翔六点半|陈翔|六点半"},
        {"电影_动作", "成龙|李连杰|甄子丹|吴京|洪金宝|周润发|刘德华|梁朝伟|动作|武打|警匪|枪战|犯罪|追车|格斗|功夫|李小龙|杀破狼|叶问|碟中谍|谍影|无间道|英雄本色|港片"},
        {"电影_悬疑", "悬疑|推理|恐怖|惊悚|鬼|灵异|烧脑|侦探|犯罪心理|汉尼拔|电锯|鬼片|丧尸|午夜凶铃|招魂|山村老尸|林正英|僵尸|英叔|盗墓|鬼吹灯|古墓|盗墓笔记"},
        {"电影_科幻", "漫威|蜘蛛侠|钢铁侠|复仇者|雷神|美国队长|银河护卫队|DC|蝙蝠侠|超人|神奇女侠|正义联盟|科幻|星际|星球大战|宇宙|外星人|末世|赛博朋克|侏罗纪|恐龙|哥斯拉|金刚|变形金刚|x战警"},
        {"电影_经典", "扁豆|乌贼|大象|亮哥|越哥|解说|影评|讲电影|聊电影|金庸|古龙|武侠|古装|战争|二战|历史|抗日|谍战|冒险|探险|纪录片|奥斯卡|豆瓣|高评分|怀旧|老电影|经典|"},
    };

    private static final String[][] TV_CATEGORY_KEYWORDS = {
        {"剧集_古装", "古装|宫廷|后宫|甄嬛|如懿|延禧|乾隆|康熙|雍正|清朝|唐朝|明朝|汉服|仙剑|仙侠|武侠剧|金庸剧|古龙剧|封神|琅琊榜|庆余年|赘婿|知否|陈情令|山河令|三生三世|花千骨|步步惊心|宫锁"},
        {"剧集_军旅", "军旅|战争|特种兵|亮剑|士兵突击|抗战|抗日|谍战|潜伏|伪装者|风筝|悬崖|雪豹|我的团长|部队|军人|长津湖|跨过鸭绿江"},
        {"剧集_悬疑", "悬疑|推理|刑侦|破案|法医|犯罪|心理罪|白夜追凶|隐秘的角落|沉默的真相|无证之罪|狂飙|他是谁|法医秦明|盗墓剧|鬼吹灯剧|盗墓笔记剧"},
        {"剧集_现代", "现代|都市|爱情|职场|家庭|生活|偶像剧|青春|校园|恋爱|韩剧|日剧|美剧|情感|都市情感|欢乐颂|三十而已|我的前半生|都挺好|小欢喜|小别离|少年派"},
        {"剧集_经典", "西游记|三国演义|水浒传|红楼梦|武林外传|爱情公寓|家有儿女|还珠格格|琼瑶|金庸|四大名著|经典剧集|怀旧剧集|90年代|老剧|新白娘子传奇|射雕英雄传|天龙八部|神雕侠侣|鹿鼎记|笑傲江湖|"},
    };

    private static final String[][] ANIME_CATEGORY_KEYWORDS = {
        {"动漫_少女", "少女|乙女|恋爱|魔法少女|美少女战士|魔卡少女|百变小樱|后宫番|逆后宫|女性向|少女番|恋爱番|紫罗兰|辉夜大小姐|五等分|青春恋爱|月刊少女|堀与宫村|更衣人偶|莉可丽丝|彻夜之歌|租借女友"},
        {"动漫_国漫", "国漫|国产动画|中国风|秦时明月|武庚纪|斗罗大陆|斗破苍穹|完美世界|遮天|仙逆|凡人修仙|吞噬星空|武动乾坤|画江湖|不良人|狐妖|一人之下|镇魂街|灵笼|天官赐福|魔道祖师|凹凸世界|罗小黑|刺客伍六七|雾山五行|时光代理人|少年歌行|盘龙|雪鹰领主|神印王座|星辰变|天行九歌|全职高手|元尊|魁拔|白蛇|哪吒|姜子牙|深海|长安三万里|雄狮少年|新神榜|大鱼海棠"},
        {"动漫_日常", "日常|治愈|蜡笔小新|哆啦A梦|机器猫|樱桃小丸子|海绵宝宝|夏目友人帐|虫师|玉子爱情故事|摇曳露营|轻音少女|k-on|工作细胞|日常番|搞笑动漫|萌系|萌番|校园日常|生活|轻松|悠哉日常大王|向山进发|比宇宙更远的地方|四月是你的谎言|未闻花名|clannad"},
        {"动漫_热血", "火影|海贼王|one piece|龙珠|鬼灭|咒术|进击的巨人|一拳超人|电锯人|柯南|犬夜叉|死神|妖尾|妖精的尾巴|七大罪|黑色五叶草|间谍过家家|芙莉莲|葬送|国王排名|勇者|高达|eva|新世纪福音战士|机甲|灌篮高手|幽游白书|乱马|网球王子|棋魂|通灵王|游戏王|数码宝贝|神奇宝贝|宠物小精灵|中华小当家|铁臂阿童木|黑猫警长|葫芦娃|喜羊羊|熊出没|猪猪侠|铠甲勇士|圣斗士|热血|战斗|运动番|体育番|"},
    };

    private static final String[][] VARIETY_CATEGORY_KEYWORDS = {
        {"综艺_搞笑", "搞笑|脱口秀|吐槽大会|奇葩说|相声|小品|欢乐喜剧人|笑傲江湖|喜剧总动员|跨界喜剧王|麻花|开心|快乐大本营|天天向上|非诚勿扰|我们都爱笑|百变大咖秀|王牌对王牌|今夜百乐门|德云社|辽宁民间艺术团"},
        {"综艺_真人秀", "奔跑吧|跑男|极限挑战|真人秀|歌手|好声音|中国好声音|中国新歌声|创造营|青春有你|乘风破浪的姐姐|披荆斩棘的哥哥|爸爸去哪儿|中餐厅|向往的生活|花儿与少年|花样姐姐|亲爱的客栈|我是歌手|舞蹈生|这就是街舞|中国有嘻哈|说唱新世代|选秀|偶像练习生|王牌|运动吧少年|声临其境|最强大脑|一站到底|非你莫属|音乐|演唱会|唱歌|"},
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
    /** 🟢 Application Context，用于本地永久兜底文件读写 */
    private final Context mAppContext;

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

    private HuyaTogetherWatchManager() {
        this.mAppContext = getAppContext();
    }

    /** 🟢 安全获取 Application Context（无需调用方传参，内部反射取） */
    private static Context getAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(thread);
            return ((Context) app).getApplicationContext();
        } catch (Throwable t) {
            Log.w(TAG, "反射取Application失败：" + t.getMessage());
            return null;
        }
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
            List<TogetherWatchRoom> rooms = new ArrayList<>();
            boolean networkOk = true;
            // 🟢 标记：网络是否真的成功获取过任意房间（排除静态兜底/追加兜底填充的）
            boolean networkReturnedValidRooms = false;
            String lastError = null;
            try {
                int beforeMovie = rooms.size();
                List<TogetherWatchRoom> movieRooms = fetchMovieRooms();
                rooms.addAll(classifyRoomsByKeywords(movieRooms, MOVIE_CATEGORY_KEYWORDS, "电影_经典"));
                if (rooms.size() > beforeMovie) networkReturnedValidRooms = true;

                int beforeTv = rooms.size();
                List<TogetherWatchRoom> tvRooms = fetchTvRooms();
                rooms.addAll(classifyRoomsByKeywords(tvRooms, TV_CATEGORY_KEYWORDS, "剧集_经典"));
                if (rooms.size() > beforeTv) networkReturnedValidRooms = true;

                int beforeAnime = rooms.size();
                List<TogetherWatchRoom> animeRooms = fetchAnimeRooms();
                rooms.addAll(classifyRoomsByKeywords(animeRooms, ANIME_CATEGORY_KEYWORDS, "动漫_热血"));
                if (rooms.size() > beforeAnime) networkReturnedValidRooms = true;

                int beforeVar = rooms.size();
                List<TogetherWatchRoom> varietyRooms = fetchVarietyRooms();
                rooms.addAll(classifyRoomsByKeywords(varietyRooms, VARIETY_CATEGORY_KEYWORDS, "综艺_真人秀"));
                if (rooms.size() > beforeVar) networkReturnedValidRooms = true;
            } catch (IOException e) {
                networkOk = false;
                lastError = "网络异常：" + e.getMessage();
                Log.d(TAG, "网络请求异常，开始兜底：" + e.getMessage());
            } catch (Exception e) {
                networkOk = false;
                lastError = "解析异常：" + e.getMessage();
                e.printStackTrace();
            }

            // —— 动漫/综艺过少补充静态兜底 ——
            int animeCount = 0, varietyCount = 0;
            for (TogetherWatchRoom r : rooms) {
                if (r.category != null && r.category.startsWith("动漫_")) animeCount++;
                if (r.category != null && r.category.startsWith("综艺_")) varietyCount++;
            }
            if (animeCount < 5 || varietyCount < 5) {
                Log.d(TAG, "动漫/综艺频道过少（动漫=" + animeCount + ", 综艺=" + varietyCount + "），追加静态兜底");
                rooms.addAll(getFallbackRooms());
            }

            // 🟢 网络真正成功（HTTP有效） → 回调成功 + 写入永久兜底
            if (networkOk && networkReturnedValidRooms) {
                mRoomList = rooms;
                mLastFetchTime = now;
                postSuccess(listener, rooms);
                // 房间数足够才覆盖永久兜底，避免网络返回空/异常数据把好兜底覆盖
                if (rooms.size() >= MIN_ROOMS_FOR_FALLBACK) {
                    saveFallbackToDisk(rooms);
                } else {
                    Log.w(TAG, "网络房间数过少（" + rooms.size() + " < " + MIN_ROOMS_FOR_FALLBACK + "），不覆盖永久兜底");
                }
                return;
            }

            // ================================================================
            // 🟢🟢 二级兜底：SDK 内部 API（getLiveListByTag("一起看" / "电影" ...)）
            //   - 用途：当 HTTP(live.cdn.huya.com / cache.php) 服务器故障时，
            //     改用虎牙 SDK 内部封装的 CustomUI 接口继续取到一起看频道。
            //   - SDK 的 getLiveListData(isMore) 是"推荐直播"(王者荣耀游戏)，
            //     必须用 getLiveListDataByTag 按 一起看/电影/剧集/综艺/动漫 标签分别拉才对。
            //   - 文档来源：D:\ASDF\文档\_DIRECT_METHOD_CALLS.md 1.5节 + 4.3 LiveListInfo 字段
            // ================================================================
            boolean sdkReturnedValidRooms = false;
            if (rooms.size() < 30 || !networkOk || !networkReturnedValidRooms) {
                Log.i(TAG, "【兜底→SDK】HTTP房间=" + rooms.size() + " networkOk=" + networkOk
                        + " → 改用 SDK getLiveListByTag 拉取一起看频道");
                try {
                    List<TogetherWatchRoom> sdkRooms = fetchTogetherWatchFromSDKBlocking();
                    if (sdkRooms != null && !sdkRooms.isEmpty()) {
                        if (rooms.isEmpty()) {
                            rooms = sdkRooms;
                        } else {
                            // 合并去重（按 roomId）
                            java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
                            for (TogetherWatchRoom r : rooms) seen.add(r.roomId);
                            for (TogetherWatchRoom r : sdkRooms) {
                                if (seen.add(r.roomId)) rooms.add(r);
                            }
                        }
                        sdkReturnedValidRooms = true;
                        Log.i(TAG, "【兜底→SDK】拉取完成，SDK提供房间数=" + sdkRooms.size()
                                + "，合并后总房间数=" + rooms.size());
                    } else {
                        Log.w(TAG, "【兜底→SDK】SDK返回空列表");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "【兜底→SDK】SDK兜底失败：" + t.getMessage());
                }
            }

            // SDK兜底有效（或+HTTP合并后）≥ 阈值 → 当成功处理
            if ((networkReturnedValidRooms || sdkReturnedValidRooms) && rooms.size() >= 10) {
                mRoomList = rooms;
                mLastFetchTime = now;
                postSuccess(listener, rooms);
                if (rooms.size() >= MIN_ROOMS_FOR_FALLBACK) {
                    saveFallbackToDisk(rooms);
                } else {
                    Log.w(TAG, "SDK兜底后房间数过少（" + rooms.size() + " < " + MIN_ROOMS_FOR_FALLBACK + "），不覆盖永久兜底");
                }
                return;
            }

            // 🟢 仍不够 → 读【本地永久兜底 JSON】（上次成功序列化下来的）
            if (rooms.isEmpty() || rooms.size() < 10) {
                List<TogetherWatchRoom> diskFb = loadFallbackFromDisk();
                if (diskFb != null && !diskFb.isEmpty()) {
                    Log.i(TAG, "【兜底】启用本地永久兜底 " + FALLBACK_FILE + "，房间数=" + diskFb.size());
                    rooms = diskFb;
                } else {
                    Log.w(TAG, "【兜底】本地永久兜底无数据，启用静态内置兜底");
                    if (rooms.isEmpty()) rooms = new ArrayList<>(getFallbackRooms());
                }
            }

            if (rooms.isEmpty()) {
                postFailed(listener, (lastError != null ? lastError : "未获取到一起看内容") + "（且无本地兜底）");
                return;
            }

            mRoomList = rooms;
            mLastFetchTime = now;
            postSuccess(listener, rooms);
        });
    }

    // ====================================================================
    // ✅ SDK 内部 API 兜底：通过 getLiveListDataByTag("一起看"/"电影"...) 拉一起看频道
    //   - 全程直接调用（无反射），文档见 D:\ASDF\文档\_DIRECT_METHOD_CALLS.md
    //   - SDK 回调是异步，这里用 CountDownLatch 转同步阻塞（mExecutor 线程里跑安全）
    // ====================================================================
    /** 单个分类标签超时 */
    private static final int SDK_TAG_TIMEOUT_SEC = 8;

    /** 🟢 优先命中的分类名称（和SDK TagInfo里的显示名做 contains 匹配） */
    private static final String[][] SDK_CATEGORY_NAME_TO_DEFAULT = new String[][] {
            // { 匹配关键词（或/逗号分隔多条）, 默认TogetherWatch分类 }
            { "一起看",            "电影_经典"   },
            { "电影,影视",         "电影_经典"   },
            { "电视剧,剧集",       "剧集_经典"   },
            { "综艺,真人秀",       "综艺_真人秀" },
            { "动漫,动画,番剧",    "动漫_热血"   },
            { "纪录片",            "电影_经典"   },
            { "经典剧场",          "剧集_经典"   },
    };

    /**
     * 同步阻塞：SDK 内部 API 兜底拉取虎牙一起看/影视分类
     *   流程：① getTagList 拉取 SDK 分类 TagInfo(id,name)
     *         ② 根据名称匹配出"一起看/电影/电视剧/综艺/动漫/纪录片/经典剧场"对应的 tagId
     *         ③ 并发 getLiveListDataByTag(tagId) 拉取 LiveListInfo 列表（⚠️参数是tagId数字字符串，不是中文）
     *         ④ 映射为 TogetherWatchRoom，分类去重。
     */
    private List<TogetherWatchRoom> fetchTogetherWatchFromSDKBlocking() throws Exception {
        // ===== ① 拉取 Tag 列表并解析出 (tagId, tagName, 默认分类) 三元组 =====
        List<TagSpec> targetTags = resolveSDKTagIdsBlocking();
        if (targetTags == null || targetTags.isEmpty()) {
            Log.w(TAG, "【SDK→TagList】未能解析出任何目标 tagId，SDK兜底跳过");
            return Collections.emptyList();
        }
        Log.i(TAG, "【SDK→TagList】解析命中目标分类 tag 数=" + targetTags.size());
        for (TagSpec t : targetTags) {
            Log.i(TAG, "    ↳ tagId=" + t.tagId + " tagName=" + t.tagName + " 默认分类=" + t.defaultCategory);
        }

        // ===== ② 并发 getLiveListDataByTag(tagId) 拉取 =====
        List<TogetherWatchRoom> all = Collections.synchronizedList(new ArrayList<TogetherWatchRoom>());
        List<CompletableFutureStub> futures = new ArrayList<>();
        for (final TagSpec tag : targetTags) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult =
                    new AtomicReference<>();
            final AtomicReference<String> refErr = new AtomicReference<>();
            try {
                // ⚠️ 传的是 tagId（数字字符串），不是中文标签名！
                HuyaSDKParser.getLiveListByTag(tag.tagId, false,
                        new HuyaSDKParser.OnLiveListResultListener() {
                            @Override public void onSuccess(List<com.huya.berry.client.customui.model.LiveListInfo> list) {
                                refResult.set(list);
                                latch.countDown();
                            }
                            @Override public void onError(String err) {
                                refErr.set(err);
                                latch.countDown();
                            }
                        });
                futures.add(new CompletableFutureStub(tag, latch, refResult, refErr));
            } catch (Throwable t) {
                Log.w(TAG, "【SDK→tag=" + tag.tagName + "(" + tag.tagId + ")】提交失败：" + t.getMessage());
            }
        }

        long totalTimeout = (long) SDK_TAG_TIMEOUT_SEC * Math.max(1, futures.size()) * 1000L;
        long deadline = System.currentTimeMillis() + totalTimeout;
        for (CompletableFutureStub f : futures) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            boolean ok = f.latch.await(Math.min(remain, (long)SDK_TAG_TIMEOUT_SEC * 1000L), TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "【SDK→tag=" + f.tag.tagName + "(" + f.tag.tagId + ")】超时，跳过");
                continue;
            }
            if (f.refErr.get() != null) {
                Log.w(TAG, "【SDK→tag=" + f.tag.tagName + "(" + f.tag.tagId + ")】失败: " + f.refErr.get());
                continue;
            }
            List<com.huya.berry.client.customui.model.LiveListInfo> list = f.refResult.get();
            if (list == null || list.isEmpty()) continue;

            // ===== ③ LiveListInfo → TogetherWatchRoom 映射 =====
            for (com.huya.berry.client.customui.model.LiveListInfo info : list) {
                try {
                    long channelId = info.channelId;
                    long subId     = info.subId;
                    if (channelId <= 0) continue;
                    String title    = safeStr(info.title);
                    String nick     = safeStr(info.nickName);
                    String cover    = safeStr(info.coverUrl);
                    int    online   = parseAudienceCount(info.audienceCount);
                    String display  = (TextUtils.isEmpty(title) ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title);
                    TogetherWatchRoom r = new TogetherWatchRoom(
                            (int) channelId,
                            subId > 0 ? (int) subId : (int) channelId,
                            display,
                            TextUtils.isEmpty(nick) ? display : nick,
                            cover,
                            online,
                            f.tag.defaultCategory
                    );
                    r.isLive = true;
                    all.add(r);
                } catch (Throwable ignore) { /* 坏字段跳过 */ }
            }
            Log.i(TAG, "【SDK→tag=" + f.tag.tagName + "(" + f.tag.tagId + ")】解析房间数=" + list.size());
        }

        // ===== ④ 对"一起看"混合分类(默认是电影_经典但实际含剧集/动漫/综艺)重新按关键词细分 =====
        List<TogetherWatchRoom> mixedList = new ArrayList<>();
        List<TogetherWatchRoom> fineList  = new ArrayList<>();
        for (TogetherWatchRoom r : all) {
            boolean found = false;
            if (r.category != null && r.category.startsWith("电影_")) {
                // 如果分类名明确包含"纪录片/电影"命中 MOVIE_CATEGORY_KEYWORDS
                // 对一起看混合分类，交给 classifyRoomsByKeywords 重新分配（名称里有电视剧→剧集，有动漫→动漫）
                if (MOVIE_CATEGORY_KEYWORDS != null && MOVIE_CATEGORY_KEYWORDS.length > 0) {
                    // 直接走下一轮关键词分类器统一分配
                    mixedList.add(r);
                    found = true;
                }
            }
            if (!found) fineList.add(r);
        }
        if (!mixedList.isEmpty()) {
            fineList.addAll(classifyRoomsByKeywords(mixedList, MOVIE_CATEGORY_KEYWORDS, "电影_经典"));
        }

        // ===== ⑤ 去重（按 roomId） =====
        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
        List<TogetherWatchRoom> result = new ArrayList<>();
        for (TogetherWatchRoom r : fineList) {
            if (seen.add(r.roomId)) result.add(r);
        }
        return result;
    }

    /** TagInfo(id,name,defaultCategory) 三元组 */
    private static final class TagSpec {
        final String tagId;           // SDK 需要的数字字符串 tagId
        final String tagName;         // 显示名（用于日志/调试）
        final String defaultCategory; // TogetherWatch 默认分类
        TagSpec(String tagId, String tagName, String defaultCategory) {
            this.tagId = tagId; this.tagName = tagName; this.defaultCategory = defaultCategory;
        }
    }

    /** 同步调用 getTagList → 反射提取每个 TagInfo 的id和name → 匹配 SDK_CATEGORY_NAME_TO_DEFAULT */
    private List<TagSpec> resolveSDKTagIdsBlocking() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<Object>> refTags = new AtomicReference<>();
        final AtomicReference<String> refErr = new AtomicReference<>();
        try {
            HuyaSDKParser.getTagList(new HuyaSDKParser.OnTagListResultListener() {
                @Override public void onSuccess(List<Object> tagList) {
                    refTags.set(tagList);
                    latch.countDown();
                }
                @Override public void onError(String err) {
                    refErr.set(err);
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "【SDK→TagList】提交失败：" + t.getMessage());
            return Collections.emptyList();
        }
        try {
            boolean ok = latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!ok) { Log.w(TAG, "【SDK→TagList】超时"); return Collections.emptyList(); }
        } catch (InterruptedException ie) { return Collections.emptyList(); }
        if (refErr.get() != null) {
            Log.w(TAG, "【SDK→TagList】失败: " + refErr.get());
            return Collections.emptyList();
        }
        List<Object> rawTags = refTags.get();
        if (rawTags == null || rawTags.isEmpty()) {
            Log.w(TAG, "【SDK→TagList】空列表");
            return Collections.emptyList();
        }

        List<TagSpec> result = new ArrayList<>();
        for (Object tagObj : rawTags) {
            if (tagObj == null) continue;
            Pair pair = extractTagIdAndName(tagObj);
            if (pair == null) continue;
            String id = pair.id;
            String name = pair.name;
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
            String defaultCat = matchDefaultCategory(name);
            if (defaultCat != null) {
                result.add(new TagSpec(id, name, defaultCat));
            }
        }
        return result;
    }

    /** 匹配名称 → 默认TogetherWatch分类；不命中返回 null（即被SDK兜底忽略该tag） */
    private static String matchDefaultCategory(String tagName) {
        if (TextUtils.isEmpty(tagName)) return null;
        String low = tagName.toLowerCase();
        for (String[] row : SDK_CATEGORY_NAME_TO_DEFAULT) {
            String[] keywords = row[0].split(",");
            for (String kw : keywords) {
                if (!TextUtils.isEmpty(kw) && low.contains(kw.toLowerCase())) {
                    return row[1];
                }
            }
        }
        return null;
    }

    /** 通用反射：从 SDK TagInfo 对象中提取 id(String) 和 name(String) —— 兼容不同字段名组合 */
    private static Pair extractTagIdAndName(Object tagObj) {
        try {
            Class<?> c = tagObj.getClass();
            java.lang.reflect.Field[] fields = c.getFields();
            String bestId = null;
            String bestName = null;
            // 先找 public String 字段 + public long/int id 字段（长id转String）
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    String fname = f.getName();
                    Object val = f.get(tagObj);
                    if (val == null) continue;
                    String lower = fname.toLowerCase();
                    if (bestId == null) {
                        if ("id".equals(lower) || "tagid".equals(lower) || "categoryid".equals(lower)) {
                            bestId = String.valueOf(val);
                            continue;
                        }
                        if (val instanceof String) {
                            String sval = (String) val;
                            if (!sval.isEmpty() && isNumericString(sval)) {
                                // 形如 "2135" 这种纯数字String字段 → 优先当id
                                if (fname.contains("id") || fname.contains("Id") || fname.length() <= 4) {
                                    bestId = sval;
                                    continue;
                                }
                            }
                        }
                    }
                    if (bestName == null && val instanceof String) {
                        String sval = (String) val;
                        if (!sval.isEmpty()) {
                            if ("name".equals(lower) || "tagname".equals(lower) || "cname".equals(lower)
                                    || "title".equals(lower) || "displayname".equals(lower) || "categoryname".equals(lower)) {
                                bestName = sval;
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
            // 兜底：还没找到 name → 扫描所有 String 字段，挑最长且不是id的第一个
            if (bestName == null) {
                for (java.lang.reflect.Field f : fields) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(tagObj);
                        if (v instanceof String && !TextUtils.isEmpty((String)v)
                                && !isNumericString((String)v) && ((String)v).length() >= 2) {
                            bestName = (String) v;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            if (bestId == null || bestName == null) return null;
            Log.i(TAG, "【SDK→TagList】解析 Tag: id=" + bestId + " name=" + bestName + " class=" + c.getSimpleName());
            return new Pair(bestId, bestName);
        } catch (Throwable t) {
            Log.w(TAG, "【SDK→TagList】反射Tag失败(" + tagObj.getClass().getSimpleName() + "): " + t.getMessage());
            return null;
        }
    }

    private static final class Pair {
        final String id;
        final String name;
        Pair(String id, String name) { this.id = id; this.name = name; }
    }

    private static boolean isNumericString(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** CountDownLatch + result/err 的轻量 holder（不用 Java8 CompletableFuture，兼容 minSdk=21）*/
    private static final class CompletableFutureStub {
        final TagSpec tag;
        final CountDownLatch latch;
        final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult;
        final AtomicReference<String> refErr;
        CompletableFutureStub(TagSpec tag, CountDownLatch latch,
                              AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> r,
                              AtomicReference<String> e) {
            this.tag = tag; this.latch = latch; this.refResult = r; this.refErr = e;
        }
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    private static int parseAudienceCount(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        try {
            String x = s.trim().replace(",", "");
            if (x.endsWith("万") || x.endsWith("w") || x.endsWith("W")) {
                double d = Double.parseDouble(x.substring(0, x.length() - 1));
                return (int) (d * 10000.0);
            }
            if (x.endsWith("亿")) {
                double d = Double.parseDouble(x.substring(0, x.length() - 1));
                return (int) (d * 100000000.0);
            }
            return (int) Double.parseDouble(x);
        } catch (Throwable t) {
            return 0;
        }
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

        rooms.add(new TogetherWatchRoom(1394575534, 11342412, "【周星星】星爷经典不间断", "周星星", "", 5000, "电影_喜剧"));
        rooms.add(new TogetherWatchRoom(1394575543, 11342421, "英叔护体 | 林正英搞笑僵尸系列", "7喜先生", "", 4500, "电影_悬疑"));
        rooms.add(new TogetherWatchRoom(1524439855, 880261, "我摊牌啦 一起看热门大片", "虎牙八点档", "", 6000, "电影_经典"));
        rooms.add(new TogetherWatchRoom(616112, 616112, "动作大片", "虎牙一起看", "", 4500, "电影_动作"));
        rooms.add(new TogetherWatchRoom(616113, 616113, "惊悚悬疑", "虎牙一起看", "", 4000, "电影_悬疑"));
        rooms.add(new TogetherWatchRoom(616114, 616114, "科幻世界", "虎牙一起看", "", 3500, "电影_科幻"));
        rooms.add(new TogetherWatchRoom(616115, 616115, "古装巨制", "虎牙一起看", "", 3000, "剧集_古装"));

        rooms.add(new TogetherWatchRoom(616121, 616121, "古装剧集", "虎牙一起看", "", 4500, "剧集_古装"));
        rooms.add(new TogetherWatchRoom(616122, 616122, "军旅题材", "虎牙一起看", "", 4000, "剧集_军旅"));
        rooms.add(new TogetherWatchRoom(616123, 616123, "搞笑剧集", "虎牙一起看", "", 3500, "剧集_经典"));
        rooms.add(new TogetherWatchRoom(616124, 616124, "悬疑推理", "虎牙一起看", "", 3000, "剧集_悬疑"));
        rooms.add(new TogetherWatchRoom(616125, 616125, "都市情感", "虎牙一起看", "", 2500, "剧集_现代"));
        rooms.add(new TogetherWatchRoom(616126, 616126, "剧情精选", "虎牙一起看", "", 2000, "剧集_经典"));

        // 🟢 匹配新 16 分组兜底（动漫 4 个 / 综艺 2 个）
        rooms.add(new TogetherWatchRoom(96000001, 96000001, "热血动漫专播", "热血动漫专播", "", 1200, "动漫_热血"));
        rooms.add(new TogetherWatchRoom(96000002, 96000002, "经典国漫 24h", "经典国漫 24h", "", 1000, "动漫_国漫"));
        rooms.add(new TogetherWatchRoom(96000003, 96000003, "搞笑日常精选", "搞笑日常精选", "", 800, "动漫_日常"));
        rooms.add(new TogetherWatchRoom(96000004, 96000004, "少女向治愈系", "少女向治愈系", "", 600, "动漫_少女"));
        rooms.add(new TogetherWatchRoom(96000005, 96000005, "动漫剧场版合集", "动漫剧场版合集", "", 700, "动漫_热血"));
        rooms.add(new TogetherWatchRoom(96000006, 96000006, "怀旧经典动画", "怀旧经典动画", "", 500, "动漫_热血"));
        rooms.add(new TogetherWatchRoom(96000007, 96000007, "热门新番速递", "热门新番速递", "", 1500, "动漫_热血"));
        rooms.add(new TogetherWatchRoom(96000008, 96000008, "柯南/死神/犬夜叉", "柯南/死神/犬夜叉", "", 900, "动漫_热血"));

        rooms.add(new TogetherWatchRoom(660005, 660005, "动漫剧场", "虎牙一起看", "", 4500, "动漫_热血"));
        rooms.add(new TogetherWatchRoom(660004, 660004, "热门综艺", "虎牙一起看", "", 6000, "综艺_真人秀"));
        rooms.add(new TogetherWatchRoom(660006, 660006, "体育赛事", "虎牙一起看", "", 3000, "综艺_真人秀"));
        rooms.add(new TogetherWatchRoom(660007, 660007, "纪录片", "虎牙一起看", "", 2500, "电影_经典"));
        rooms.add(new TogetherWatchRoom(660008, 660008, "演唱会", "虎牙一起看", "", 5000, "综艺_真人秀"));
        rooms.add(new TogetherWatchRoom(660009, 660009, "游戏回放", "虎牙一起看", "", 3500, "电影_经典"));
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
        // 所有场景（含一起看 / 自定义房间）统一直走 HuyaSDKParser 解析，无任何其他解析或回退
        if (!HuyaSDKParser.isSDKAvailable()) {
            listener.onFailed("SDK 解析不可用");
            return;
        }
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
                Log.d(TAG, "SDK 解析失败: " + error);
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

    public void release() {
        mExecutor.shutdownNow();
        mRoomList.clear();
    }

    // ====================================================================
    // ✅ 本地永久兜底（filesDir huya_together_fallback.json，不随缓存清理）
    //   - 每次网络成功+房间数>=10，序列化分类结果（含category、profileRoom）覆盖写入
    //   - 虎牙API服务器故障时，反序列化继续可用
    // ====================================================================
    private File getFallbackFile() {
        if (mAppContext == null) return null;
        return new File(mAppContext.getFilesDir(), FALLBACK_FILE);
    }

    /** 序列化 List<TogetherWatchRoom> → JSON Array → filesDir */
    private void saveFallbackToDisk(List<TogetherWatchRoom> rooms) {
        File file = getFallbackFile();
        if (file == null || rooms == null || rooms.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (TogetherWatchRoom r : rooms) {
                JSONObject o = new JSONObject();
                o.put("rid", r.roomId);
                o.put("prid", r.profileRoom);
                o.put("rname", r.roomName == null ? "" : r.roomName);
                o.put("nname", r.nickName == null ? "" : r.nickName);
                o.put("cover", r.coverUrl == null ? "" : r.coverUrl);
                o.put("online", r.onlineCount);
                o.put("cat", r.category == null ? "" : r.category);
                o.put("live", r.isLive);
                arr.put(o);
            }
            byte[] data = arr.toString().getBytes("UTF-8");
            FileOutputStream fos = new FileOutputStream(file);
            try { fos.write(data); fos.flush(); } finally { fos.close(); }
            Log.i(TAG, "【兜底】已写入永久兜底，房间数=" + rooms.size() + "，字节=" + data.length);
        } catch (Throwable t) {
            Log.e(TAG, "【兜底】写入失败：" + t.getMessage());
        }
    }

    /** 反序列化 filesDir → List<TogetherWatchRoom> */
    private List<TogetherWatchRoom> loadFallbackFromDisk() {
        File file = getFallbackFile();
        if (file == null || !file.exists() || file.length() <= 0) return null;
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            List<TogetherWatchRoom> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int rid = o.optInt("rid", 0);
                int prid = o.optInt("prid", rid);
                if (rid <= 0) continue;
                TogetherWatchRoom r = new TogetherWatchRoom(
                        rid, prid,
                        o.optString("rname", "精彩节目"),
                        o.optString("nname", "精彩节目"),
                        o.optString("cover", ""),
                        o.optInt("online", 0),
                        o.optString("cat", "")
                );
                r.isLive = o.optBoolean("live", true);
                list.add(r);
            }
            return list;
        } catch (Throwable t) {
            Log.e(TAG, "【兜底】读取失败：" + t.getMessage());
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
    }
}
