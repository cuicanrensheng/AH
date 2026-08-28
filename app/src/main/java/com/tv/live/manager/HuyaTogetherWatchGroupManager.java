package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.LogBridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 虎牙一起看分组管理器（SDK 独立路线）
 *
 * <p><b>定位：</b>本管理器是「虎牙一起看」频道列表的 <b>独立获取路线</b>——
 * 所有频道的 <b>唯一来源是虎牙 SDK 内部 API</b>：
 * <pre>
 *   ① HuyaSDKParser.getTagList()            → SDK 分类 TagInfo(id,name)
 *   ② HuyaSDKParser.getLiveListByTag(tagId) → 按 tagId 拉取 LiveListInfo 频道
 * </pre>
 * 不读 HTTP 接口、不读本地兜底文件、不做静态兜底、不合并任何其他路线的数据，
 * 因此<b>不受其他路线分组的干扰</b>。
 *
 * <p><b>分组：</b>固定 5 组，顺序即展示顺序：
 * <pre>
 *   虎牙电影 / 虎牙电视剧 / 虎牙动漫 / 虎牙综艺 / 虎牙一起看
 * </pre>
 * 每个 tag 按名称关键词归入对应分组；「虎牙一起看」为混合组（含"一起看"主 tag
 * 及未被细分命中的影视类 tag）。
 *
 * <p><b>频道量策略（SDK 范围内提升频道数）：</b>
 * <ol>
 *   <li>单 tag 翻页 {@link #MAX_TAG_PAGES} 页（每页约 20 个，SDK isMore 翻到底自动停止）；</li>
 *   <li>宽匹配 {@link #GROUP_KEYWORDS} 聚合同一分组下多个 tag 的频道；</li>
 *   <li>未命中细分分组的 tag 一律收容到「虎牙一起看」兜底组，不再丢弃。</li>
 * </ol>
 *
 * <p><b>频道标记：</b>本管理器产出的 Channel 一律
 * {@link Channel#setHuyaSdkTogetherWatch(boolean)}=true，标识其来自
 * SDK 内部一起看列表（独立分组，不受其他源影响）。
 */
public class HuyaTogetherWatchGroupManager {

    private static final String TAG = "TogetherWatchGroup";

    private static volatile HuyaTogetherWatchGroupManager sInstance;

    /** 单 tag 拉取超时（秒），与 SDK 内部兜底逻辑保持一致 */
    private static final int SDK_TAG_TIMEOUT_SEC = 8;

    /** 单 tag 翻页上限（每页约 20 个频道，10 页 ≈ 200 个；SDK isMore 翻到无更多会自动提前停止） */
    private static final int MAX_TAG_PAGES = 10;

    /** 并发拉取 tag 的线程池 */
    private static final int CONCURRENCY = 4;

    // ==================== 固定分组 ====================
    public static final String GROUP_MOVIE    = "虎牙电影";
    public static final String GROUP_TV       = "虎牙电视剧";
    public static final String GROUP_ANIME    = "虎牙动漫";
    public static final String GROUP_VARIETY  = "虎牙综艺";
    public static final String GROUP_TOGETHER = "虎牙一起看";

    /** 分组展示顺序（虎牙一起看在最后） */
    public static final String[] GROUP_NAMES = {
            GROUP_MOVIE, GROUP_TV, GROUP_ANIME, GROUP_VARIETY, GROUP_TOGETHER
    };

    /**
     * SDK TagInfo 名称关键词 → 分组。
     * 顺序即优先级：细分（电影/剧集/动漫/综艺）优先于混合（一起看）。
     * 一个名称命中多组时归入先命中的细分分组。
     */
    private static final String[][] GROUP_KEYWORDS = new String[][] {
            { GROUP_MOVIE,    "电影,影视,纪录片,经典剧场,喜剧,恐怖,爱情,动作,武侠,科幻,大片" },
            { GROUP_TV,       "电视剧,剧集,连续剧,新剧,热播,高清,国语,古装,悬疑" },
            { GROUP_ANIME,    "动漫,动画,番剧,卡通,漫画" },
            { GROUP_VARIETY,  "综艺,真人秀,脱口秀,相声,演唱会,音乐会,春晚,小品" },
            { GROUP_TOGETHER, "一起看" },
    };

    /**
     * SDK 内部"一起看"分类 gameId（见 huya_sdk 文档 getRecListByGame(gameId, tag)）。
     * A1 推荐列表 getLiveListData(isMore) 返回的 LiveListInfo.gameId 等于该值时，
     * 说明该频道属于"一起看"，才纳入分组；否则（如王者荣耀推荐直播）直接丢弃。
     */
    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    private final ExecutorService mExecutor =
            Executors.newFixedThreadPool(CONCURRENCY);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** 防抖：一次只允许一个拉取任务在跑 */
    private final AtomicBoolean mFetching = new AtomicBoolean(false);

    private HuyaTogetherWatchGroupManager() {
    }

    public static HuyaTogetherWatchGroupManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaTogetherWatchGroupManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaTogetherWatchGroupManager();
                }
            }
        }
        return sInstance;
    }

    // ==================== 对外回调接口 ====================

    public interface OnGroupsListener {
        /** 拉取完成，groups 恒为 5 组（可能含空组） */
        void onSuccess(List<Group> groups);

        void onError(String errMsg);
    }

    public interface OnChannelsListener {
        void onSuccess(List<Channel> channels);

        void onError(String errMsg);
    }

    /** 一个分组的频道列表 */
    public static class Group {
        public final String name;
        public final List<Channel> channels = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        public boolean isEmpty() {
            return channels.isEmpty();
        }
    }

    // ==================== 对外 API ====================

    /**
     * 拉取全部 5 个分组（异步，主线程回调）。
     * 任一 tag 失败只丢弃该 tag 数据，不影响其他分组；仅当
     * SDK 分类列表整体失败时才回调 onError。
     */
    public void fetchAllGroups(final OnGroupsListener listener) {
        if (listener == null) return;
        if (!HuyaSDKParser.isSDKAvailable()) {
            postOnError(listener, "SDK 未初始化完成");
            return;
        }
        if (!mFetching.compareAndSet(false, true)) {
            postOnError(listener, "正在获取中，请稍候");
            return;
        }
        mExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<Group> result = doFetchAllGroupsBlocking();
                    if (result == null) {
                        postOnError(listener, "获取分组失败（SDK 分类列表为空或超时）");
                    } else {
                        postOnSuccess(listener, result);
                    }
                } catch (Throwable t) {
                    LogBridge.w(TAG, "fetchAllGroups 异常: " + t.getMessage());
                    postOnError(listener, "获取分组异常");
                } finally {
                    mFetching.set(false);
                }
            }
        });
    }

    /**
     * 拉取单个分组（异步，主线程回调）。
     * groupName 必须是 {@link #GROUP_NAMES} 之一。
     */
    public void fetchGroup(final String groupName, final OnChannelsListener listener) {
        if (listener == null) return;
        if (!isValidGroupName(groupName)) {
            postOnError(listener, "未知分组: " + groupName);
            return;
        }
        if (!HuyaSDKParser.isSDKAvailable()) {
            postOnError(listener, "SDK 未初始化完成");
            return;
        }
        if (!mFetching.compareAndSet(false, true)) {
            postOnError(listener, "正在获取中，请稍候");
            return;
        }
        mExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<Channel> channels = doFetchGroupBlocking(groupName);
                    if (channels == null) {
                        postOnError(listener, "获取分组失败（SDK 分类列表为空或超时）");
                    } else {
                        postOnSuccess(listener, channels);
                    }
                } catch (Throwable t) {
                    LogBridge.w(TAG, "fetchGroup(" + groupName + ") 异常: " + t.getMessage());
                    postOnError(listener, "获取分组异常");
                } finally {
                    mFetching.set(false);
                }
            }
        });
    }

    // ==================== 内部实现 ====================

    /** 全部分组的阻塞实现（在工作线程调用） */
    private List<Group> doFetchAllGroupsBlocking() throws Exception {
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;   // tag 列表整体失败 → 视为获取失败

        // A1 推荐全量（getLiveListData 翻页）作为补充来源，按标题关键词分入各分组
        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();

        // 🆕 预加载优化：5 大分组并行拉取，总耗时由各组之和压到 ≈ 单组最慢（原串行 20~40s → 并行 ~10s）
        // ⚠️ 组级并行必须用独立线程池：若与组内 tag 拉取共用 mExecutor(4线程)，
        //    外层任务占满线程池会导致内层 tag 任务排队超时（线程池饥饿）。
        ExecutorService groupPool = Executors.newFixedThreadPool(GROUP_NAMES.length);
        try {
            List<Future<Group>> groupFutures = new ArrayList<>();
            for (final String gName : GROUP_NAMES) {
                groupFutures.add(groupPool.submit(new Callable<Group>() {
                    @Override public Group call() {
                        Group g = new Group(gName);
                        try {
                            List<TagSpec> tags = byGroup.get(gName);
                            if (tags != null && !tags.isEmpty()) {
                                fetchChannelsIntoGroup(tags, g);
                            } else if (GROUP_TOGETHER.equals(gName)) {
                                g.channels.addAll(fetchFallbackTogetherBlocking(byGroup));
                            }
                            // 合并推荐全量（去重），增加每组频道量
                            if (recommend != null) {
                                mergeChannels(g.channels, recommend.get(gName));
                            }
                        } catch (Throwable t) {
                            LogBridge.w(TAG, "分组[" + gName + "]并行拉取异常: " + t.getMessage());
                        }
                        return g;
                    }
                }));
            }
            List<Group> groups = new ArrayList<>();
            for (Future<Group> f : groupFutures) {
                try {
                    groups.add(f.get(SDK_TAG_TIMEOUT_SEC + 5, TimeUnit.SECONDS));
                } catch (Throwable t) {
                    LogBridge.w(TAG, "分组并行拉取超时/中断: " + t.getMessage());
                }
            }
            return groups;
        } finally {
            groupPool.shutdownNow();
        }
    }

    /** 单个分组的阻塞实现（在工作线程调用） */
    private List<Channel> doFetchGroupBlocking(String groupName) throws Exception {
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;

        Group g = new Group(groupName);
        List<TagSpec> tags = byGroup.get(groupName);
        if (tags != null && !tags.isEmpty()) {
            fetchChannelsIntoGroup(tags, g);
        } else if (GROUP_TOGETHER.equals(groupName)) {
            // 一起看组无 tag 时，兜底收容所有未分配频道（防分组空）
            g.channels.addAll(fetchFallbackTogetherBlocking(byGroup));
        }
        // A1 推荐全量作为补充来源，按标题关键词分入本组（去重）
        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();
        if (recommend != null) {
            mergeChannels(g.channels, recommend.get(groupName));
        }
        return g.channels;
    }

    /**
     * 虎牙一起看 = 兜底收容未分配的频道。
     * 所有 tag（全部→电影 / 最新→电视剧 / UP→动漫 / 综艺→综艺）频道并集去重后，
     * 扣除已分配给 电视剧/动漫/综艺 的频道（即：只在"全部"中出现、未被其他显式组收走的频道）。
     */
    private List<Channel> fetchFallbackTogetherBlocking(Map<String, List<TagSpec>> byGroup) {
        List<Channel> fallback = new ArrayList<>();
        List<TagSpec> allTags = new ArrayList<>();
        for (List<TagSpec> list : byGroup.values()) {
            if (list != null) allTags.addAll(list);
        }
        if (allTags.isEmpty()) {
            LogBridge.w(TAG, "【固定分组】虎牙一起看兜底: 无任何 tag");
            return fallback;
        }

        // 并行拉取所有 tag 的频道
        List<Future<List<Channel>>> futures = new ArrayList<>();
        for (final TagSpec t : allTags) {
            futures.add(mExecutor.submit(new Callable<List<Channel>>() {
                @Override public List<Channel> call() {
                    return fetchTagBlocking(t);
                }
            }));
        }
        Map<TagSpec, List<Channel>> tagChannels = new LinkedHashMap<>();
        for (int i = 0; i < allTags.size(); i++) {
            try {
                List<Channel> chs = futures.get(i).get(SDK_TAG_TIMEOUT_SEC + 2, TimeUnit.SECONDS);
                tagChannels.put(allTags.get(i), chs == null ? new ArrayList<Channel>() : chs);
            } catch (Throwable ignored) {
                tagChannels.put(allTags.get(i), new ArrayList<Channel>());
            }
        }

        // 被显式组收走的频道 ID（电视剧=最新、动漫=UP、综艺=综艺；"全部"=电影为全集，不排除）
        LinkedHashSet<String> assignedIds = new LinkedHashSet<>();
        for (TagSpec t : allTags) {
            if (!GROUP_TV.equals(t.groupName) && !GROUP_ANIME.equals(t.groupName) && !GROUP_VARIETY.equals(t.groupName)) continue;
            for (Channel c : tagChannels.get(t)) {
                if (c == null) continue;
                assignedIds.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
            }
        }

        // 兜底 = 所有 tag 并集中未被显式组收走的频道
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (TagSpec t : allTags) {
            for (Channel c : tagChannels.get(t)) {
                if (c == null) continue;
                String key = c.getChannelId() != null ? c.getChannelId() : c.getName();
                if (assignedIds.contains(key)) continue;
                if (!seenId.add(key)) continue;
                try {
                    c.setGroup(GROUP_TOGETHER);
                    fallback.add(c);
                } catch (Throwable ignored) { /* 坏字段跳过 */ }
            }
        }
        LogBridge.i(TAG, "【固定分组】虎牙一起看兜底 频道数=" + fallback.size());
        if (fallback.isEmpty()) {
            // 兜底为空通常是"全部"（电影全集）tag 拉取失败所致：并集被 电视剧/动漫/综艺 显式组全部收走
            LogBridge.w(TAG, "【固定分组】虎牙一起看兜底为空: 全部 tag 可能拉取失败, 已依赖推荐列表收容");
        }
        return fallback;
    }

    /** 并发拉取一组内所有 tag 的频道，按 roomId 去重后填入 Group */
    private void fetchChannelsIntoGroup(List<TagSpec> tags, final Group g) {
        List<Future<List<Channel>>> futures = new ArrayList<>();
        for (TagSpec t : tags) {
            futures.add(mExecutor.submit(new Callable<List<Channel>>() {
                @Override public List<Channel> call() {
                    return fetchTagBlocking(t);
                }
            }));
        }
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Future<List<Channel>> f : futures) {
            try {
                List<Channel> chs = f.get(SDK_TAG_TIMEOUT_SEC + 2, TimeUnit.SECONDS);
                if (chs == null || chs.isEmpty()) continue;
                for (Channel c : chs) {
                    if (c == null) continue;
                    boolean dup;
                    if (c.getHuyaRoomId() > 0) {
                        dup = !seenRoom.add(c.getHuyaRoomId());
                    } else {
                        dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
                    }
                    if (!dup) g.channels.add(c);
                }
            } catch (Throwable ignored) {
                // 单个 tag 失败/超时不拖累整组
            }
        }
    }

    /** 单分组内去重合并补充频道（按 huyaRoomId / channelId），增加频道量 */
    private static void mergeChannels(List<Channel> target, List<Channel> extra) {
        if (extra == null || extra.isEmpty()) return;
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Channel c : target) {
            if (c == null) continue;
            if (c.getHuyaRoomId() > 0) seenRoom.add(c.getHuyaRoomId());
            else seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
        }
        for (Channel c : extra) {
            if (c == null) continue;
            boolean dup;
            if (c.getHuyaRoomId() > 0) {
                dup = !seenRoom.add(c.getHuyaRoomId());
            } else {
                dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
            }
            if (!dup) target.add(c);
        }
    }

    /**
     * 文档 A1：SDK 不带 tag 的推荐列表 {@code getLiveListData(isMore)}（内部走
     * {@code getRecListByGame(isRefresh, gameId=2135, tag=null)}），翻页拉取"一起看"全量频道，
     * 作为 tag 路线的补充来源，显著增加分组频道量。
     *
     * <p>安全过滤：只接受 {@code LiveListInfo.gameId == 2135}（一起看）的频道，
     * 防止该接口返回"推荐直播"(其他 gameId) 时污染分组；随后按标题/昵称关键词
     * {@link #assignGroupByKeywords} 归入 5 个固定分组，未命中细分关键词的收容到
     * 「虎牙一起看」兜底组（A1 推荐本身即"一起看"全量，天然属于该组），不再丢弃。
     *
     * @return 分组名 → 频道列表；失败或无有效频道返回 null
     */
    private Map<String, List<Channel>> fetchRecommendGroupedBlocking() {
        List<com.huya.berry.client.customui.model.LiveListInfo> allList = new ArrayList<>();
        LinkedHashSet<Long> seenUids = new LinkedHashSet<>();
        int page = 0;
        while (page < MAX_TAG_PAGES) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult = new AtomicReference<>();
            final AtomicReference<String> refErr = new AtomicReference<>();
            final boolean isMore = page > 0;
            try {
                HuyaSDKParser.getLiveList(isMore, new HuyaSDKParser.OnLiveListResultListener() {
                    @Override public void onSuccess(List<com.huya.berry.client.customui.model.LiveListInfo> list) {
                        refResult.set(list);
                        latch.countDown();
                    }
                    @Override public void onError(String err) {
                        refErr.set(err);
                        latch.countDown();
                    }
                });
                if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    LogBridge.w(TAG, "【推荐列表】第" + (page + 1) + "页超时");
                    break;
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【推荐列表】第" + (page + 1) + "页异常: " + t.getMessage());
                break;
            }
            if (refErr.get() != null) {
                LogBridge.i(TAG, "【推荐列表】第" + (page + 1) + "页结束(无更多): " + refErr.get());
                break;
            }
            List<com.huya.berry.client.customui.model.LiveListInfo> list = refResult.get();
            if (list == null || list.isEmpty()) break;
            int newCount = 0;
            for (com.huya.berry.client.customui.model.LiveListInfo info : list) {
                if (info == null) continue;
                long key = info.uid > 0 ? info.uid : info.channelId;
                if (key > 0 && seenUids.add(key)) {
                    allList.add(info);
                    newCount++;
                }
            }
            if (newCount == 0) break;
            page++;
        }
        if (allList.isEmpty()) return null;
        LogBridge.i(TAG, "【推荐列表】原始频道数=" + allList.size());

        Map<String, List<Channel>> grouped = new LinkedHashMap<>();
        for (String g : GROUP_NAMES) grouped.put(g, new ArrayList<Channel>());
        for (com.huya.berry.client.customui.model.LiveListInfo info : allList) {
            try {
                // 只接受"一起看"分类频道（gameId=2135）；gameId 未填充(0)时靠关键词过滤兜底，
                // 防止该接口混入王者荣耀等推荐直播时污染分组
                if (info.gameId != 0 && info.gameId != CATEGORY_ID_TOGETHER_WATCH) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                if (TextUtils.isEmpty(title) && TextUtils.isEmpty(nick)) continue;
                String display = TextUtils.isEmpty(title) ? nick : title;
                if ("精彩直播".equals(display) || "精彩节目".equals(display)) continue;
                String gName = assignGroupByKeywords(title, nick);
                if (gName == null) gName = GROUP_TOGETHER;   // 未命中细分关键词 → 收容到「虎牙一起看」兜底组（A1 推荐本身即一起看 gameId=2135 全量）
                Channel ch = buildChannelFromInfo(info, gName);
                if (ch == null) continue;
                grouped.get(gName).add(ch);
            } catch (Throwable ignored) { /* 坏字段跳过 */ }
        }
        int total = 0;
        for (List<Channel> v : grouped.values()) total += v.size();
        LogBridge.i(TAG, "【推荐列表】分类后频道数=" + total);
        if (total == 0) return null;
        return grouped;
    }

    /** 按 标题/昵称 关键词将 A1 推荐频道归入固定分组；未命中返回 null（由调用方收容到「虎牙一起看」兜底组） */
    private static String assignGroupByKeywords(String title, String nick) {
        String low = (safeStr(title) + " " + safeStr(nick)).toLowerCase();
        for (String[] row : GROUP_KEYWORDS) {
            String gName = row[0];
            for (String kw : row[1].split(",")) {
                if (!TextUtils.isEmpty(kw) && low.contains(kw.trim().toLowerCase())) {
                    return gName;
                }
            }
        }
        return null;
    }

    /** 将 A1 推荐 LiveListInfo 构建为 Channel（与 fetchTagBlocking 相同构建逻辑） */
    private static Channel buildChannelFromInfo(com.huya.berry.client.customui.model.LiveListInfo info, String groupName) {
        try {
            long channelId = info.channelId;
            long subId = info.subId;
            long uid = info.uid;
            if (channelId <= 0) return null;
            String title = safeStr(info.title);
            String nick = safeStr(info.nickName);
            String display = TextUtils.isEmpty(title)
                    ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title;
            if ("精彩直播".equals(display) || "精彩节目".equals(display)
                    || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                return null;
            }
            long realProfileRoom = subId > 0 ? subId : channelId;
            int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE)
                    ? (int) realProfileRoom : 0;
            String channelIdStr;
            if (uid > 0) channelIdStr = "huya_uid_" + uid;
            else if (profileRoom > 0) channelIdStr = "huya_" + profileRoom;
            else channelIdStr = "huya_long_" + channelId;
            Channel ch;
            if (uid > 0) {
                ch = new Channel(display, "huya://uid/" + uid, groupName, channelIdStr, true, profileRoom);
                ch.setHuyaUid(uid);
            } else if (profileRoom > 0) {
                ch = new Channel(display, "huya://room/" + profileRoom, groupName, channelIdStr, true, profileRoom);
            } else {
                return null;
            }
            ch.setHuyaSdkTogetherWatch(true);
            return ch;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 阻塞拉取单个 tag 的频道列表（SDK 分页翻页，最多 MAX_TAG_PAGES 页）。
     * 返回 null 表示该 tag 失败/超时。
     */
    private List<Channel> fetchTagBlocking(final TagSpec tag) {
        // ===== 分页拉取原始 LiveListInfo（isMore=true 翻下一页，防重复防死循环）=====
        List<com.huya.berry.client.customui.model.LiveListInfo> allList = new ArrayList<>();
        LinkedHashSet<Long> seenUids = new LinkedHashSet<>();   // 去重基准（uid优先）
        int page = 0;
        while (page < MAX_TAG_PAGES) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult =
                    new AtomicReference<>();
            final AtomicReference<String> refErr = new AtomicReference<>();
            final boolean isMore = page > 0;
            try {
                // ⚠️ 传的是 tagId（数字字符串），不是中文标签名
                HuyaSDKParser.getLiveListByTag(tag.tagId, isMore,
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
                if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    LogBridge.w(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页超时");
                    break;
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页异常: " + t.getMessage());
                break;
            }
            if (refErr.get() != null) {
                LogBridge.i(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页结束(无更多): " + refErr.get());
                break;
            }
            List<com.huya.berry.client.customui.model.LiveListInfo> list = refResult.get();
            if (list == null || list.isEmpty()) break;
            int newCount = 0;
            for (com.huya.berry.client.customui.model.LiveListInfo info : list) {
                if (info == null) continue;
                long uid = info.uid;
                long channelId = info.channelId;
                long key = uid > 0 ? uid : channelId;
                if (seenUids.add(key)) {
                    allList.add(info);
                    newCount++;
                }
            }
            if (newCount == 0) {
                LogBridge.i(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页无新频道，停止翻页");
                break;
            }
            page++;
        }
        if (allList.isEmpty()) return null;
        LogBridge.i(TAG, "【tag=" + tag.tagName + "】原始列表=" + allList.size());

        List<Channel> result = new ArrayList<>();
        for (com.huya.berry.client.customui.model.LiveListInfo info : allList) {
            try {
                long channelId = info.channelId;
                long subId = info.subId;
                long uid = info.uid;
                if (channelId <= 0) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                String display = (TextUtils.isEmpty(title)
                        ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title);
                // 🟢 长整型ID溢出修复（参考增强版 HuyaCategorySwitchManager.buildChannel）：
                // 只有 <=Integer.MAX_VALUE 的才是真正短房间号，13位长ID是uid不能强转int
                int roomId = (channelId > 0 && channelId <= Integer.MAX_VALUE) ? (int) channelId : 0;
                long realProfileRoom = subId > 0 ? subId : channelId;
                int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE) ? (int) realProfileRoom : 0;
                // 🟢 过滤"精彩直播"/"精彩节目"占位条目（通常是已下播/无效频道）
                if (TextUtils.isEmpty(display)
                        || "精彩直播".equals(display) || "精彩节目".equals(display)
                        || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                    continue;
                }
                // 去重/标识key：uid存在用uid，否则用有效roomId，都没有用channelId long值
                String channelIdStr;
                if (uid > 0) channelIdStr = "huya_uid_" + uid;
                else if (roomId > 0) channelIdStr = "huya_" + roomId;
                else channelIdStr = "huya_long_" + channelId;
                Channel ch;
                if (uid > 0) {
                    // 🟢 主播UID存在 → huya://uid/ 协议（SDK getLiveData(uid) 播放成功率最高）
                    ch = new Channel(display, "huya://uid/" + uid,
                            tag.groupName, channelIdStr, true, profileRoom);
                    ch.setHuyaUid(uid);
                } else if (profileRoom > 0) {
                    // 无UID但有有效短房间号 → huya://room/ 协议
                    ch = new Channel(display, "huya://room/" + profileRoom,
                            tag.groupName, channelIdStr, true, profileRoom);
                } else {
                    continue;
                }
                // 🆕 SDK 独立一起看分组标记：来源仅为 SDK 内部列表，不受其他路线分组影响
                ch.setHuyaSdkTogetherWatch(true);
                result.add(ch);
            } catch (Throwable ignored) {
                // 坏字段跳过
            }
        }
        LogBridge.i(TAG, "【tag=" + tag.tagName + "(" + tag.tagId + ")】解析频道数=" + result.size());
        return result;
    }

    /**
     * 阻塞拉取 SDK 分类列表，并按名称归入 5 个分组。
     * 返回 null 表示整体失败（SDK 未就绪 / 超时 / 异常）。
     */
    private Map<String, List<TagSpec>> resolveSDKTagSpecsGrouped() {
        List<TagSpec> specs = resolveSDKTagSpecsBlocking();
        if (specs == null) return null;
        // LinkedHashMap 保证分组顺序稳定
        Map<String, List<TagSpec>> byGroup = new LinkedHashMap<>();
        for (String gName : GROUP_NAMES) {
            byGroup.put(gName, new ArrayList<TagSpec>());
        }
        for (TagSpec t : specs) {
            List<TagSpec> list = byGroup.get(t.groupName);
            if (list != null) list.add(t);
        }
        // 日志：分组分布
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<TagSpec>> e : byGroup.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
        }
        LogBridge.i(TAG, "【SDK→分组】分类分布: " + sb.toString().trim());
        return byGroup;
    }

    /** 同步调用 getTagList → 反射提取每个 TagInfo 的 id/name → 归入分组 */
    private List<TagSpec> resolveSDKTagSpecsBlocking() {
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
            if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                LogBridge.w(TAG, "getTagList 超时");
                return null;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "getTagList 异常: " + t.getMessage());
            return null;
        }
        if (refErr.get() != null) {
            LogBridge.w(TAG, "getTagList 失败: " + refErr.get());
            return null;
        }
        List<Object> rawTags = refTags.get();
        if (rawTags == null || rawTags.isEmpty()) {
            LogBridge.w(TAG, "getTagList 返回空列表");
            return null;
        }

        List<TagSpec> result = new ArrayList<>();
        for (Object tagObj : rawTags) {
            if (tagObj == null) continue;
            Pair pair = extractTagIdAndName(tagObj);
            if (pair == null) continue;
            String groupName = matchGroupName(pair.name);
            if (groupName == null) {
                // 未命中细分分组的影视类 tag → 收容到「虎牙一起看」兜底组，避免频道被丢弃
                groupName = GROUP_TOGETHER;
            }
            result.add(new TagSpec(pair.id, pair.name, groupName));
        }
        return result;
    }

    /**
     * SDK TagInfo 名称 → 固定分组：
     * ① 用户固定映射优先（全部→电影、最新→电视剧、UP→动漫、综艺→综艺）；
     * ② 再按 {@link #GROUP_KEYWORDS} 宽匹配细分（电影/剧集/动漫/综艺/一起看）；
     * ③ 均未命中返回 null → 调用方收容到「虎牙一起看」兜底组，避免丢弃频道。
     */
    private static String matchGroupName(String tagName) {
        if (TextUtils.isEmpty(tagName)) return null;
        String n = tagName.trim();
        String low = n.toLowerCase();
        // ① 用户固定映射（精确）
        if ("全部".equals(n)) return GROUP_MOVIE;
        if ("最新".equals(n)) return GROUP_TV;
        if ("up".equals(low)) return GROUP_ANIME;
        if ("综艺".equals(n)) return GROUP_VARIETY;
        // ② 宽匹配细分（顺序即优先级：细分分组优先于混合一起看）
        for (String[] row : GROUP_KEYWORDS) {
            String groupName = row[0];
            String kws = row[1];
            for (String kw : kws.split(",")) {
                if (!TextUtils.isEmpty(kw) && low.contains(kw.trim().toLowerCase())) {
                    return groupName;
                }
            }
        }
        return null;
    }

    /** 通用反射：从 SDK TagInfo 对象中提取 id(String) 和 name(String) */
    private static Pair extractTagIdAndName(Object tagObj) {
        try {
            Class<?> c = tagObj.getClass();
            Field[] fields = c.getFields();
            String bestId = null;
            String bestName = null;
            for (Field f : fields) {
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
            if (bestName == null) {
                for (Field f : fields) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(tagObj);
                        if (v instanceof String && !TextUtils.isEmpty((String) v)
                                && !isNumericString((String) v) && ((String) v).length() >= 2) {
                            bestName = (String) v;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            if (bestId == null || bestName == null) return null;
            return new Pair(bestId, bestName);
        } catch (Throwable t) {
            LogBridge.w(TAG, "反射Tag失败(" + tagObj.getClass().getSimpleName() + "): " + t.getMessage());
            return null;
        }
    }

    // ==================== 辅助 ====================

    private static boolean isValidGroupName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        for (String g : GROUP_NAMES) {
            if (g.equals(name)) return true;
        }
        return false;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static boolean isNumericString(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private void postOnSuccess(final OnGroupsListener listener, final List<Group> groups) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(groups);
            }
        });
    }

    private void postOnError(final OnGroupsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    private void postOnSuccess(final OnChannelsListener listener, final List<Channel> channels) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(channels);
            }
        });
    }

    private void postOnError(final OnChannelsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    /** TagInfo(id,name,groupName) 三元组 */
    private static final class TagSpec {
        final String tagId;
        final String tagName;
        final String groupName;

        TagSpec(String tagId, String tagName, String groupName) {
            this.tagId = tagId;
            this.tagName = tagName;
            this.groupName = groupName;
        }
    }

    private static final class Pair {
        final String id;
        final String name;

        Pair(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
