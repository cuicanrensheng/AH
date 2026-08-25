package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多源管理器
 *
 * 【职责】
 * 负责订阅源的所有业务逻辑，包括：
 * 1. 源的增删改查
 * 2. 排序（移到顶部/底部）
 * 3. 默认源管理
 * 4. 自动更新开关
 * 5. 搜索筛选
 * 6. 导入导出
 * 7. 持久化存储（SharedPreferences）
 *
 * 【为什么拆分出来？】
 * 原来 SettingsActivity 里塞了太多源管理的代码，
 * 拆分后职责更清晰：
 * - SourceManager：只管业务逻辑（数据）
 * - SettingsActivity：只管 UI 展示和用户交互
 *
 * 【存储格式】
 * 名称##URL##isDefault##autoUpdate##addTime
 * 多个源用 || 分隔
 *
 * 【使用方式】
 * SourceManager manager = new SourceManager(context, "live_history");
 * manager.addSource("主源", "http://xxx.com/list.m3u");
 * List<SourceManager.SourceItem> list = manager.getAllSources();
 */
public class SourceManager {

    // ====================== 常量 ======================

    /** SP 文件名 */
    private static final String SP_NAME = "app_settings";

    // 🔒 内置源的「固定名称」——用于去重/识别，即使 URL 还没解密出来也能识别
    public static final String BUILTIN_NAME_LIVE_1 = "内置源1 (GitHub)";
    public static final String BUILTIN_NAME_LIVE_2 = "内置源2 (Gitee)";
    public static final String BUILTIN_NAME_LIVE_3 = "内置源3 (本地666)";
    public static final String BUILTIN_NAME_EPG_1  = "内置节目单1 (Catvod)";
    public static final String BUILTIN_NAME_EPG_2  = "内置节目单2 (ERW)";

    /**
     * 快速判断某个项是否是内置源（按「名称精确匹配」或「URL匹配」双重规则）
     * 名称优先：用于启动期 UrlConfig.LIVE_URL 还没解密的情况去重
     */
    public static boolean isBuiltin(SourceItem si, String spKey) {
        if (si == null) return false;
        String n = si.name == null ? "" : si.name;
        if ("live_history".equals(spKey)) {
            if (BUILTIN_NAME_LIVE_1.equals(n) || BUILTIN_NAME_LIVE_2.equals(n) || BUILTIN_NAME_LIVE_3.equals(n)) return true;
            if (si.url != null && (UrlConfig.LIVE_URL.equals(si.url) || UrlConfig.LIVE_URL_2.equals(si.url) || UrlConfig.LIVE_URL_3.equals(si.url))) return true;
        } else if ("epg_history".equals(spKey)) {
            if (BUILTIN_NAME_EPG_1.equals(n) || BUILTIN_NAME_EPG_2.equals(n)) return true;
            if (si.url != null && (UrlConfig.EPG_URL.equals(si.url) || UrlConfig.EPG_URL_2.equals(si.url))) return true;
        }
        return false;
    }

    // ====================== 成员变量 ======================

    /** 上下文 */
    private Context context;
    /** SharedPreferences */
    private SharedPreferences sp;
    /** 当前管理的 SP key（live_history / epg_history） */
    private String spKey;

    // ====================== 构造函数 ======================

    /**
     * 构造函数
     * @param context 上下文
     * @param spKey 存储的 SP key（live_history 或 epg_history）
     */
    public SourceManager(Context context, String spKey) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        this.spKey = spKey;
    }

    // ====================== 源信息实体类 ======================

    /**
     * 订阅源信息实体
     * 封装一个源的所有属性
     */
    public static class SourceItem {
        /** 源名称 */
        public String name;
        /** 源地址 */
        public String url;
        /** 是否为默认源 */
        public boolean isDefault;
        /** 是否自动更新 */
        public boolean autoUpdate;
        /** 添加时间（时间戳） */
        public long addTime;

        public SourceItem(String name, String url) {
            this.name = name;
            this.url = url;
            this.isDefault = false;
            this.autoUpdate = true;
            this.addTime = System.currentTimeMillis();
        }
    }

    // ====================== 增删改查 ======================

    /**
     * 获取所有源
     * @return 源列表
     */
    public List<SourceItem> getAllSources() {
        return parseSourceList();
    }

    /**
     * 获取指定位置的源
     * @param position 位置
     * @return 源信息，越界返回 null
     */
    public SourceItem get(int position) {
        List<SourceItem> list = getAllSources();
        if (position >= 0 && position < list.size()) {
            return list.get(position);
        }
        return null;
    }

    /**
     * 获取源的总数量
     */
    public int size() {
        return getAllSources().size();
    }

    /**
     * 根据 URL 查找位置
     * @param url 源地址
     * @return 位置，找不到返回 -1
     */
    public int indexOfUrl(String url) {
        List<SourceItem> list = getAllSources();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url.equals(url)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 添加新源（添加到最前面）
     * @param name 源名称
     * @param url 源地址
     * @return 是否添加成功（重复返回 false）
     */
    public boolean addSource(String name, String url) {
        if (TextUtils.isEmpty(url)) return false;

        List<SourceItem> list = getAllSources();

        // 去重
        for (SourceItem si : list) {
            if (si.url.equals(url)) {
                return false;
            }
        }

        if (TextUtils.isEmpty(name)) {
            name = "源" + (list.size() + 1);
        }

        SourceItem newItem = new SourceItem(name, url);
        // 第一个源自动设为默认
        if (list.isEmpty()) {
            newItem.isDefault = true;
        }
        list.add(0, newItem);
        saveSourceList(list);
        return true;
    }

    /**
     * 删除指定位置的源
     * 🔒 内置源禁止删除——保证核心源始终可用，同时避免误删后下次自动注入"看似重复"的项
     * @param position 位置
     * @return 是否删除成功
     */
    public boolean removeSource(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        SourceItem target = list.get(position);
        if (isBuiltin(target, spKey)) {
            return false; // 内置源拒绝删除
        }

        list.remove(position);

        // 如果删掉的是默认源，把第一个设为默认
        boolean hasDefault = false;
        for (SourceItem si : list) {
            if (si.isDefault) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault && !list.isEmpty()) {
            // 默认源优先从剩余内置源中挑一个；没有内置源就用第一个
            for (SourceItem si : list) {
                if (isBuiltin(si, spKey)) {
                    si.isDefault = true;
                    hasDefault = true;
                    break;
                }
            }
            if (!hasDefault) {
                list.get(0).isDefault = true;
            }
        }

        saveSourceList(list);
        return true;
    }

    /**
     * 更新指定位置的源
     * @param position 位置
     * @param newName 新名称
     * @param newUrl 新地址
     * @return 是否更新成功
     */
    public boolean updateSource(int position, String newName, String newUrl) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        if (!TextUtils.isEmpty(newName)) {
            list.get(position).name = newName;
        }
        if (!TextUtils.isEmpty(newUrl)) {
            list.get(position).url = newUrl;
        }

        saveSourceList(list);
        return true;
    }

    /**
     * 清空所有源
     */
    public void clearAll() {
        sp.edit().putString(spKey, "").apply();
    }

    // ====================== 排序 ======================

    /**
     * 把指定位置的源移到顶部
     * @param position 位置
     * @return 是否移动成功
     */
    public boolean moveToTop(int position) {
        List<SourceItem> list = getAllSources();
        if (position <= 0 || position >= list.size()) return false;

        list.add(0, list.remove(position));
        saveSourceList(list);
        return true;
    }

    /**
     * 把指定位置的源移到底部
     * @param position 位置
     * @return 是否移动成功
     */
    public boolean moveToBottom(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size() - 1) return false;

        list.add(list.remove(position));
        saveSourceList(list);
        return true;
    }

    // ====================== 默认源管理 ======================

    /**
     * 设置指定位置的源为默认源
     * @param position 位置
     * @return 是否设置成功
     */
    public boolean setDefault(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        // 先把所有的默认标记去掉
        for (SourceItem si : list) {
            si.isDefault = false;
        }
        // 再设置选中的为默认
        list.get(position).isDefault = true;
        saveSourceList(list);
        return true;
    }

    /**
     * 获取默认源的 URL
     * 如果没有设置默认源，返回第一个
     * @return 默认源 URL，没有则返回空字符串
     */
    public String getDefaultUrl() {
        SourceItem item = getDefaultSource();
        return item != null ? item.url : "";
    }

    /**
     * 获取默认源
     * @return 默认源，没有则返回 null
     */
    public SourceItem getDefaultSource() {
        List<SourceItem> list = getAllSources();
        if (list.isEmpty()) return null;

        // 先找默认源
        for (SourceItem si : list) {
            if (si.isDefault) {
                return si;
            }
        }
        // 没有默认源就返回第一个
        return list.get(0);
    }

    // ====================== 自动更新管理 ======================

    /**
     * 切换指定源的自动更新开关
     * @param position 位置
     * @return 切换后的状态
     */
    public boolean toggleAutoUpdate(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        list.get(position).autoUpdate = !list.get(position).autoUpdate;
        saveSourceList(list);
        return list.get(position).autoUpdate;
    }

    /**
     * 获取所有需要自动更新的源
     * @return 需要自动更新的源列表
     */
    public List<SourceItem> getAutoUpdateSources() {
        List<SourceItem> all = getAllSources();
        List<SourceItem> result = new ArrayList<>();
        for (SourceItem si : all) {
            if (si.autoUpdate) {
                result.add(si);
            }
        }
        return result;
    }

    // ====================== 搜索筛选 ======================

    /**
     * 搜索源（按名称或地址模糊匹配）
     * @param keyword 关键词
     * @return 匹配的源列表
     */
    public List<SourceItem> search(String keyword) {
        List<SourceItem> all = getAllSources();
        if (TextUtils.isEmpty(keyword)) {
            return all;
        }

        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        List<SourceItem> result = new ArrayList<>();
        for (SourceItem si : all) {
            if (si.name.toLowerCase(Locale.ROOT).contains(lowerKeyword)
                    || si.url.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                result.add(si);
            }
        }
        return result;
    }

    // ====================== 导入导出 ======================

    /**
     * 导出所有源为文本格式
     * 格式：名称,URL（每行一个）
     * @return 导出的文本
     */
    public String exportToText() {
        List<SourceItem> list = getAllSources();
        StringBuilder sb = new StringBuilder();
        for (SourceItem si : list) {
            sb.append(si.name).append(",").append(si.url).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从文本批量导入源
     * 支持格式：
     * - 名称,URL（每行一个）
     * - 直接 URL（每行一个）
     * @param text 导入的文本
     * @return 成功导入的数量
     */
    public int importFromText(String text) {
        if (TextUtils.isEmpty(text)) return 0;

        String[] lines = text.split("\n");
        int added = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("http")) continue;

            String name = "";
            String url = line;

            // 支持格式：名称,URL
            if (line.contains(",") && line.indexOf(",") < line.indexOf("http")) {
                int commaIdx = line.indexOf(",");
                name = line.substring(0, commaIdx).trim();
                url = line.substring(commaIdx + 1).trim();
            }

            // 去重
            if (indexOfUrl(url) >= 0) continue;

            if (TextUtils.isEmpty(name)) {
                name = "导入源" + (size() + added + 1);
            }

            // 直接添加，不经过 addSource 的去重（前面已经检查过了）
            List<SourceItem> list = getAllSources();
            SourceItem newItem = new SourceItem(name, url);
            if (list.isEmpty()) {
                newItem.isDefault = true;
            }
            list.add(newItem);
            saveSourceList(list);
            added++;
        }

        return added;
    }

    // ====================== 工具方法 ======================

    /**
     * 格式化时间戳为可读格式
     */
    public static String formatTime(long timeMs) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timeMs));
    }

    // ====================== 内部存储方法（私有） ======================

    /**
     * 从 SP 解析源列表
     * 兼容旧格式（只有 URL 的老数据）
     * 如果列表为空且是 live_history / epg_history，则自动写入两个内置源
     */
    private List<SourceItem> parseSourceList() {
        List<SourceItem> list = new ArrayList<>();
        String data = sp.getString(spKey, "");

        if (TextUtils.isEmpty(data)) {
            // 列表为空：自动注入两个内置源供用户切换
            List<SourceItem> builtin = buildBuiltinSources();
            if (!builtin.isEmpty()) {
                saveSourceList(builtin);
                return builtin;
            }
            return list;
        }

        boolean isOldFormat = !data.contains("##");
        boolean sanitizedAnyUrl = false; // 记录 SP 中是否存在失效 URL 被我们迁移替换

        if (isOldFormat) {
            // 兼容旧格式：如果是用 | 分隔的纯 URL（老数据）
            String[] urls = data.split("\\|");
            for (String url : urls) {
                if (!url.trim().isEmpty()) {
                    String shortName = url.length() > 10 ? url.substring(0, 10) + "..." : url;
                    String u = ("live_history".equals(spKey)) ? UrlConfig.sanitizeLiveUrl(url) : url;
                    if (u == null) u = url;
                    if (!u.equals(url)) sanitizedAnyUrl = true;
                    list.add(new SourceItem(shortName, u));
                }
            }
        } else {
            // 新格式：名称##URL##isDefault##autoUpdate##addTime || ...
            String[] items = data.split("\\|\\|");
            for (String item : items) {
                if (item.trim().isEmpty()) continue;
                String[] fields = item.split("##");
                if (fields.length >= 2) {
                    String rawUrl = fields[1];
                    String fixedUrl = ("live_history".equals(spKey)) ? UrlConfig.sanitizeLiveUrl(rawUrl) : rawUrl;
                    if (fixedUrl == null) fixedUrl = rawUrl;
                    if (!fixedUrl.equals(rawUrl)) sanitizedAnyUrl = true;
                    SourceItem si = new SourceItem(fields[0], fixedUrl);
                    if (fields.length >= 3) {
                        si.isDefault = "1".equals(fields[2]);
                    }
                    if (fields.length >= 4) {
                        si.autoUpdate = "1".equals(fields[3]);
                    }
                    if (fields.length >= 5) {
                        try {
                            si.addTime = Long.parseLong(fields[4]);
                        } catch (Exception ignored) {}
                    }
                    list.add(si);
                }
            }
        }

        // 补齐缺失的内置源 + 合并重复（升级兼容：老版本用户 / URL 还没解密的占位空记录）
        List<SourceItem> before = deepCopyList(list);
        list = ensureBuiltinSourcesPresent(list);

        // 🔧 关键修复：如果 ensureBuiltinSourcesPresent 对 list 做了改动（合并重复/补 URL/补内置源/补默认）
        // 或者我们把 SP 中的某个永久 404 失效 URL sanitize 替换成了可用镜像，
        // 必须立即持久化，否则内存里改对了 isDefault/URL，下次重启读 SP 又回到旧状态。
        boolean dirty = isOldFormat || sanitizedAnyUrl;
        if (!dirty) dirty = !listsEquivalent(before, list);
        if (dirty) saveSourceList(list);

        return list;
    }

    private static List<SourceItem> deepCopyList(List<SourceItem> src) {
        List<SourceItem> r = new ArrayList<>(src.size());
        for (SourceItem s : src) {
            SourceItem c = new SourceItem(s.name, s.url);
            c.isDefault = s.isDefault;
            c.autoUpdate = s.autoUpdate;
            c.addTime = s.addTime;
            r.add(c);
        }
        return r;
    }
    private static boolean listsEquivalent(List<SourceItem> a, List<SourceItem> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SourceItem x = a.get(i), y = b.get(i);
            if (x == null && y == null) continue;
            if (x == null || y == null) return false;
            if (!eq(x.name, y.name)) return false;
            if (!eq(x.url, y.url)) return false;
            if (x.isDefault != y.isDefault) return false;
            if (x.autoUpdate != y.autoUpdate) return false;
        }
        return true;
    }
    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /**
     * 根据当前 spKey 生成两个内置源条目 + 第3套本地asset源
     * 🔒 读 URL 永远用 UrlConfig.*_RAW（永久不变备份），不能读 LIVE_URL 等会被 UI 切源覆盖的字段。
     */
    private List<SourceItem> buildBuiltinSources() {
        List<SourceItem> result = new ArrayList<>();
        if ("live_history".equals(spKey)) {
            SourceItem src1 = new SourceItem(BUILTIN_NAME_LIVE_1, rawOrFallback(UrlConfig.LIVE_URL_1_RAW, UrlConfig.LIVE_URL));
            src1.isDefault = true;
            src1.autoUpdate = true;
            result.add(src1);

            SourceItem src2 = new SourceItem(BUILTIN_NAME_LIVE_2, rawOrFallback(UrlConfig.LIVE_URL_2_RAW, UrlConfig.LIVE_URL_2));
            src2.isDefault = false;
            src2.autoUpdate = true;
            result.add(src2);

            SourceItem src3 = new SourceItem(BUILTIN_NAME_LIVE_3, UrlConfig.LIVE_URL_3_RAW);
            src3.isDefault = false;
            src3.autoUpdate = false;
            result.add(src3);
        } else if ("epg_history".equals(spKey)) {
            SourceItem src1 = new SourceItem(BUILTIN_NAME_EPG_1, rawOrFallback(UrlConfig.EPG_URL_1_RAW, UrlConfig.EPG_URL));
            src1.isDefault = true;
            src1.autoUpdate = true;
            result.add(src1);

            SourceItem src2 = new SourceItem(BUILTIN_NAME_EPG_2, rawOrFallback(UrlConfig.EPG_URL_2_RAW, UrlConfig.EPG_URL_2));
            src2.isDefault = false;
            src2.autoUpdate = true;
            result.add(src2);
        }
        return result;
    }

    /** RAW 优先；RAW 还没解密出来（启动期）就 fallback 到当前字段，避免空 URL 漏注入 */
    private static String rawOrFallback(String raw, String fallback) {
        if (raw != null && !raw.isEmpty()) return raw;
        return fallback == null ? "" : fallback;
    }

    /**
     * 内置源描述对象（数据内聚，避免重复声明多组 url/name/autoUpdate）
     */
    private static final class BuiltinSpec {
        final String name;
        final String url;
        final boolean autoUpdate;
        BuiltinSpec(String n, String u, boolean au) { this.name = n; this.url = u; this.autoUpdate = au; }
    }

    private BuiltinSpec[] getBuiltinSpecs() {
        if ("live_history".equals(spKey)) {
            return new BuiltinSpec[]{
                    new BuiltinSpec(BUILTIN_NAME_LIVE_1, rawOrFallback(UrlConfig.LIVE_URL_1_RAW, UrlConfig.LIVE_URL), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_2, rawOrFallback(UrlConfig.LIVE_URL_2_RAW, UrlConfig.LIVE_URL_2), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_3, UrlConfig.LIVE_URL_3_RAW, false),
            };
        } else if ("epg_history".equals(spKey)) {
            return new BuiltinSpec[]{
                    new BuiltinSpec(BUILTIN_NAME_EPG_1, rawOrFallback(UrlConfig.EPG_URL_1_RAW, UrlConfig.EPG_URL), true),
                    new BuiltinSpec(BUILTIN_NAME_EPG_2, rawOrFallback(UrlConfig.EPG_URL_2_RAW, UrlConfig.EPG_URL_2), true),
            };
        }
        return new BuiltinSpec[0];
    }

    /**
     * 确保当前列表中包含所有内置源
     * 🔧 修复重复/默认源丢失：
     *   - 名称精确匹配 或 URL 匹配 → 视为同一个内置源，不重复注入
     *   - 老数据修复：名称匹配但 URL 为空/占位符 → 用当前 url 覆盖
     *   - 🔴 关键：firstMatch 选择优先级：isDefault=true > URL 非空 > 遍历顺序，
     *            否则遇到 SP 里先写了个空URL占位条，后面才是真正 isDefault=true 的条，
     *            保留占位条删了真·默认条 → 下次重启读 SP 会丢默认源标记。
     */
    private List<SourceItem> ensureBuiltinSourcesPresent(List<SourceItem> existing) {
        BuiltinSpec[] specs = getBuiltinSpecs();
        if (specs.length == 0) return existing;

        // Step 1: 对每个内置 spec，在 existing 中查找所有匹配项（可能有多条重复）
        //   🔒 匹配命中规则：
        //      A. name 相等 无条件命中（最可靠，spec.name 是 BUILTIN_NAME_* 常量，不会被污染）
        //      B. url 相等 仅当 「名称本身也属于该内置源的名称」时才命中
        //         —— 这层安全网防止：UrlConfig.LIVE_URL 被外部改成源3地址、RAW 又还没回填的极端情况下，
        //            LIVE_1 spec 通过 url 相等把源3 的条目拉进来，跨 spec 合并后删了源1/源2。
        for (BuiltinSpec spec : specs) {
            SourceItem best = null;
            List<SourceItem> all = new ArrayList<>();
            for (SourceItem si : existing) {
                boolean nameHit = spec.name != null && spec.name.equals(si.name);
                boolean urlMatch = spec.url != null && !spec.url.isEmpty() && spec.url.equals(si.url);
                boolean urlHit = urlMatch && isBuiltinNameForSpec(si.name, spec);
                if (!nameHit && !urlHit) continue;
                all.add(si);
                if (best == null) {
                    best = si;
                } else {
                    best = pickBetterMatch(best, si);
                }
            }
            if (best != null) {
                // 除 best 之外的其他匹配项，合并 isDefault 标记后删除
                boolean hadOtherDefault = false;
                for (SourceItem si : all) {
                    if (si == best) continue;
                    if (si.isDefault) hadOtherDefault = true;
                    existing.remove(si);
                }
                if (hadOtherDefault) best.isDefault = true;
                // 老数据修复：名称正确但 URL 为空 → 补上当前 spec.url
                if ((best.url == null || best.url.isEmpty()) && spec.url != null && !spec.url.isEmpty()) {
                    best.url = spec.url;
                }
                // 🔧 内置源 URL 失效地址强制迁移（不管 URL 是否为空）：
                //     之前某些版本 SharedPreferences 里写入了 永久 404 的 URL（如 gitee qf_1111 的 iptvedqu.m3u），
                //     但因为「best.url 非空」所以上面的老数据修复不会覆盖。
                //     这里统一跑一次 UrlConfig.sanitizeLiveUrl：只要命中黑名单 → 直接替换成修正后的 URL
                //     （live_history 走迁移，epg_history 暂不做；同时 log 便于追踪）
                if ("live_history".equals(spKey) || "epg_history".equals(spKey)) {
                    String beforeUrl = best.url;
                    String fixedUrl = ("live_history".equals(spKey)) ? UrlConfig.sanitizeLiveUrl(best.url) : beforeUrl;
                    if (fixedUrl != null && !fixedUrl.equals(beforeUrl)) {
                        android.util.Log.w("SourceManager", "内置源" + best.name + " URL 已从失效地址迁移: "
                            + (beforeUrl != null ? beforeUrl : "null") + " -> " + fixedUrl);
                        best.url = fixedUrl;
                    }
                }
                // 同步 autoUpdate 标记（第3套源要保持 false）
                best.autoUpdate = spec.autoUpdate;
                // 名称修正：如果 URL 正确命中，但名称不对（用户曾自定义），保持用户名称不变。
            }
        }

        // Step 2: 再做一次存在性检查，如果仍然缺失（一次都没命中），就追加新条目
        for (BuiltinSpec spec : specs) {
            boolean found = false;
            for (SourceItem si : existing) {
                boolean nameHit = spec.name != null && spec.name.equals(si.name);
                boolean urlMatch = spec.url != null && !spec.url.isEmpty() && spec.url.equals(si.url);
                boolean urlHit   = urlMatch && isBuiltinNameForSpec(si.name, spec);
                if (nameHit || urlHit) { found = true; break; }
            }
            if (!found) {
                SourceItem s = new SourceItem(spec.name, spec.url);
                s.autoUpdate = spec.autoUpdate;
                existing.add(s);
            }
        }

        // Step 3: 如果原本没有默认源，优先把第 1 个内置源设为默认
        boolean hasDefault = false;
        for (SourceItem si : existing) {
            if (si.isDefault) { hasDefault = true; break; }
        }
        if (!hasDefault && !existing.isEmpty()) {
            // 先找内置源1（GitHub / Catvod）
            SourceItem firstBuiltin1 = null;
            for (SourceItem si : existing) {
                if (BUILTIN_NAME_LIVE_1.equals(si.name) || BUILTIN_NAME_EPG_1.equals(si.name)) {
                    firstBuiltin1 = si; break;
                }
            }
            if (firstBuiltin1 != null) firstBuiltin1.isDefault = true;
            else existing.get(0).isDefault = true;
        }

        return existing;
    }

    /**
     * 合并重复内置源时，挑一个更"值得保留"的条目。
     * 优先级：isDefault=true > URL 非空 > addTime 更新
     * （这样就不会把空URL占位条当作 firstMatch，而把真·isDefault=true 的条当重复项删掉）
     */
    private static SourceItem pickBetterMatch(SourceItem a, SourceItem b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.isDefault != b.isDefault) return a.isDefault ? a : b;
        boolean aUrlOk = a.url != null && !a.url.isEmpty();
        boolean bUrlOk = b.url != null && !b.url.isEmpty();
        if (aUrlOk != bUrlOk) return aUrlOk ? a : b;
        return a.addTime >= b.addTime ? a : b;
    }

    /**
     * 🔒 URL 命中时的安全网：只有「name 本身属于这个 spec 对应的内置源名集合」，
     * 才允许通过 URL 相等视为同一条内置源的重复。
     * —— 这样就算 UrlConfig.LIVE_URL 被 UI 切源污染成源3的地址，LIVE_1 spec 也不会
     *    把「name=内置源3」但 URL 与 LIVE_1.url 相等的项当成 LIVE_1 的重复项合并删除。
     */
    private static boolean isBuiltinNameForSpec(String siName, BuiltinSpec spec) {
        if (siName == null) return false;
        if (BUILTIN_NAME_LIVE_1.equals(spec.name)) return BUILTIN_NAME_LIVE_1.equals(siName);
        if (BUILTIN_NAME_LIVE_2.equals(spec.name)) return BUILTIN_NAME_LIVE_2.equals(siName);
        if (BUILTIN_NAME_LIVE_3.equals(spec.name)) return BUILTIN_NAME_LIVE_3.equals(siName);
        if (BUILTIN_NAME_EPG_1.equals(spec.name))  return BUILTIN_NAME_EPG_1.equals(siName);
        if (BUILTIN_NAME_EPG_2.equals(spec.name))  return BUILTIN_NAME_EPG_2.equals(siName);
        return false;
    }

    /**
     * 把源列表保存到 SP
     */
    private void saveSourceList(List<SourceItem> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            SourceItem si = list.get(i);
            if (i > 0) sb.append("||");
            sb.append(si.name).append("##")
              .append(si.url).append("##")
              .append(si.isDefault ? "1" : "0").append("##")
              .append(si.autoUpdate ? "1" : "0").append("##")
              .append(si.addTime);
        }
        sp.edit().putString(spKey, sb.toString()).apply();
    }
}
