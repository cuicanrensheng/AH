package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

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
     * @param position 位置
     * @return 是否删除成功
     */
    public boolean removeSource(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

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
            list.get(0).isDefault = true;
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

        String lowerKeyword = keyword.toLowerCase();
        List<SourceItem> result = new ArrayList<>();
        for (SourceItem si : all) {
            if (si.name.toLowerCase().contains(lowerKeyword)
                    || si.url.toLowerCase().contains(lowerKeyword)) {
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
     */
    private List<SourceItem> parseSourceList() {
        List<SourceItem> list = new ArrayList<>();
        String data = sp.getString(spKey, "");
        if (TextUtils.isEmpty(data)) return list;

        // 兼容旧格式：如果是用 | 分隔的纯 URL（老数据）
        if (!data.contains("##")) {
            String[] urls = data.split("\\|");
            for (String url : urls) {
                if (!url.trim().isEmpty()) {
                    String shortName = url.length() > 10 ? url.substring(0, 10) + "..." : url;
                    list.add(new SourceItem(shortName, url));
                }
            }
            return list;
        }

        // 新格式：名称##URL##isDefault##autoUpdate##addTime || ...
        String[] items = data.split("\\|\\|");
        for (String item : items) {
            if (item.trim().isEmpty()) continue;
            String[] fields = item.split("##");
            if (fields.length >= 2) {
                SourceItem si = new SourceItem(fields[0], fields[1]);
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
        return list;
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
