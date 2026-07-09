package com.tv.live;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log; // 🟢 添加原生日志
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * 多源对话框管理器
 *
 * 【职责】
 * 负责所有多源管理对话框的 UI 交互，包括：
 * 1. 显示多源列表对话框
 * 2. 搜索功能
 * 3. 添加/编辑/删除/排序
 * 4. 导入/导出
 * 5. 设为默认/刷新/切换自动更新
 *
 * 【为什么拆分？】
 * 原来的 showHistoryDialog() 方法有 200+ 行，
 * 是 SettingsActivity 里最大的一个方法，代码太臃肿。
 * 拆出来后职责清晰，更好维护。
 *
 * 【业务逻辑委托】
 * 所有数据操作都委托给 SourceManager，
 * 这里只负责 UI 展示和用户交互。
 *
 * 【使用方式】
 * SourceDialogManager dialogManager = new SourceDialogManager(context, sp);
 * dialogManager.showHistoryDialog("直播源历史", "live_history");
 */
public class SourceDialogManager {
    // 🟢 添加 TAG 常量，用于原生日志记录
    private static final String TAG = "SourceDialogManager";
    
    // ====================== 常量 ======================
    /** 自定义直播源地址 Key */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 Key */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;
    /** SharedPreferences */
    private final SharedPreferences sp;
    /** 多源列表适配器 */
    private SourceAdapter adapter;
    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     * @param sp SharedPreferences 实例
     */
    public SourceDialogManager(Context context, SharedPreferences sp) {
        this.context = context;
        this.sp = sp;
    }
    // ====================================================================
    // 1. 显示多源管理对话框
    // ====================================================================
    /**
     * 显示多源管理对话框
     *
     * 【功能】
     * 搜索、添加、编辑、删除、设为默认、排序、导入导出、刷新
     *
     * @param title 对话框标题
     * @param key SP 存储的 key
     */
    public void showHistoryDialog(String title, final String key) {
        final SourceManager sourceManager = new SourceManager(context, key);
        final ArrayList<SourceManager.SourceItem> displayItems =
                new ArrayList<>(sourceManager.getAllSources());
        if (displayItems.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage("暂无记录，是否添加一个？")
                    // 修复参数不匹配
                    .setPositiveButton("添加", (d, w) -> {
                        SourceManager tempMgr = new SourceManager(context, key);
                        SourceAdapter tempAd = new SourceAdapter(context, new ArrayList<>());
                        showAddSourceDialog(title, key, tempMgr, tempAd, "");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        adapter = new SourceAdapter(context, displayItems);
        // ===== 删除按钮点击事件 =====
        adapter.setOnDeleteClickListener(position -> {
            if (position < 0 || position >= displayItems.size()) return;
            SourceManager.SourceItem item = displayItems.get(position);
            int realPos = sourceManager.indexOfUrl(item.url);
            new AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要删除「" + item.name + "」吗？")
                    .setPositiveButton("删除", (d, w) -> {
                        sourceManager.removeSource(realPos);
                        refreshDisplayList(sourceManager, displayItems, adapter, "");
                        adapter.setSelectedPosition(-1);
                        // 🟢 替换为原生日志
                        Log.d(TAG, "【设置】删除源：" + item.name);
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        // 找到当前使用的源，设置为选中状态
        String currentUrl = sp.getString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, "");
        int selectedIndex = sourceManager.indexOfUrl(currentUrl);
        if (selectedIndex >= 0) {
            adapter.setSelectedPosition(selectedIndex);
        }
        final String finalTitle = title + "（共" + displayItems.size() + "个）";
        
        // 搜索框
        final EditText searchEt = new EditText(context);
        searchEt.setHint("🔍 搜索源名称或地址");
        searchEt.setTextSize(14);
        searchEt.setSingleLine(true);
        searchEt.setPadding(40, 20, 40, 20);
        searchEt.setBackgroundColor(0xFFEEEEEE);
        
        // 🟢【修复1】给搜索框底部增加间距，确保第一项不被遮挡、点击不被截获
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) searchEt.getLayoutParams();
        if (params == null) {
            params = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            searchEt.setLayoutParams(params);
        }
        params.bottomMargin = 20; // 拉开间距
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(finalTitle);
        builder.setCustomTitle(searchEt);
        builder.setAdapter(adapter, null);
        
        // ===== 添加按钮 修复参数 =====
        builder.setPositiveButton("➕ 添加", (dialog, which) -> {
            showAddSourceDialog(title, key, sourceManager, adapter, searchEt.getText().toString());
        });
        // ===== 操作菜单 =====
        builder.setNeutralButton("⚙ 操作", (dialog, which) -> {
            final int pos = adapter.getSelectedPosition();
            if (pos < 0 || pos >= displayItems.size()) {
                Toast.makeText(context, "请先选择一项", Toast.LENGTH_SHORT).show();
                return;
            }
            final SourceManager.SourceItem selectedItem = displayItems.get(pos);
            final String[] options = {
                    "✏️ 编辑",
                    "⭐ 设为默认",
                    "⬆ 移到顶部",
                    "⬇ 移到底部",
                    "🔄 刷新此源",
                    selectedItem.autoUpdate ? "🔕 关闭自动更新" : "🔔 开启自动更新",
                    "🗑 删除",
                    "📋 导出全部",
                    "📥 导入",
                    "🧹 清空全部"
            };
            new AlertDialog.Builder(context)
                    .setTitle("操作")
                    .setItems(options, (d, w) -> {
                        int realPos = sourceManager.indexOfUrl(selectedItem.url);
                        switch (w) {
                            case 0: // 编辑 补齐参数
                                showEditSourceDialog(title, key, realPos, selectedItem, sourceManager, adapter, searchEt.getText().toString());
                                break;
                            case 1: // 设为默认
                                sourceManager.setDefault(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】设为默认源：" + selectedItem.name);
                                Toast.makeText(context, "已设为默认源", Toast.LENGTH_SHORT).show();
                                break;
                            case 2: // 移到顶部
                                sourceManager.moveToTop(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                adapter.setSelectedPosition(0);
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】移到顶部：" + selectedItem.name);
                                Toast.makeText(context, "已移到顶部", Toast.LENGTH_SHORT).show();
                                break;
                            case 3: // 移到底部
                                sourceManager.moveToBottom(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】移到底部：" + selectedItem.name);
                                Toast.makeText(context, "已移到底部", Toast.LENGTH_SHORT).show();
                                break;
                            case 4: // 刷新此源
                                sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, selectedItem.url).apply();
                                context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】刷新单个源：" + selectedItem.name);
                                Toast.makeText(context, "正在刷新…", Toast.LENGTH_SHORT).show();
                                break;
                            case 5: // 切换自动更新
                                boolean newState = sourceManager.toggleAutoUpdate(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】" + selectedItem.name + " 自动更新：" + (newState ? "开启" : "关闭"));
                                Toast.makeText(context, "自动更新已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                                break;
                            case 6: // 删除
                                new AlertDialog.Builder(context)
                                        .setTitle("确认删除")
                                        .setMessage("确定要删除「" + selectedItem.name + "」吗？")
                                        .setPositiveButton("删除", (dd, ww) -> {
                                            sourceManager.removeSource(realPos);
                                            refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                            adapter.setSelectedPosition(-1);
                                            // 🟢 替换为原生日志
                                            Log.d(TAG, "【设置】删除源：" + selectedItem.name);
                                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                            case 7: // 导出全部
                                String exportText = sourceManager.exportToText();
                                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("sources", exportText));
                                // 🟢 替换为原生日志
                                Log.d(TAG, "【设置】导出 " + sourceManager.size() + " 个源到剪贴板");
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                                break;
                            case 8: // 导入
                                showImportDialog(title, key, sourceManager, displayItems, adapter, searchEt);
                                break;
                            case 9: // 清空全部
                                new AlertDialog.Builder(context)
                                        .setTitle("确认清空")
                                        .setMessage("确定要清空全部吗？此操作不可恢复！")
                                        .setPositiveButton("全部清空", (dd, ww) -> {
                                            sourceManager.clearAll();
                                            displayItems.clear();
                                            adapter.notifyDataSetChanged();
                                            // 🟢 替换为原生日志
                                            Log.d(TAG, "【设置】清空全部" + title);
                                            Toast.makeText(context, "已全部清空", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                        }
                    })
                    .show();
        });
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        // ===== 搜索功能 =====
        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshDisplayList(sourceManager, displayItems, adapter, s.toString());
                dialog.setTitle(title + "（共" + sourceManager.search(s.toString()).size() + "个）");
            }
        });
        // ===== 列表项点击 =====
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            SourceManager.SourceItem item = displayItems.get(position);
            String saveKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
            sp.edit().putString(saveKey, item.url).apply();
            int realPos = sourceManager.indexOfUrl(item.url);
            
            // 🟢【修复2】无论是否第一项，都进行 moveToTop，增强界面交互反馈
            if (realPos >= 0) {
                sourceManager.moveToTop(realPos);
            }
            
            context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
            adapter.setSelectedPosition(0);
            // 🟢 替换为原生日志
            Log.d(TAG, "【设置】切换" + title + "：" + item.name);
            Toast.makeText(context, "已切换，正在刷新…", Toast.LENGTH_SHORT).show();
        });
    }
    // ====================================================================
    // 2. 刷新显示列表
    // ====================================================================
    private void refreshDisplayList(SourceManager sourceManager,
                                    ArrayList<SourceManager.SourceItem> displayItems,
                                    SourceAdapter adapter, String keyword) {
        displayItems.clear();
        displayItems.addAll(sourceManager.search(keyword));
        adapter.notifyDataSetChanged();
    }
    // ====================================================================
    // 3. 添加弹窗（修复入参+Editable.trim报错）
    // ====================================================================
    private void showAddSourceDialog(String title, final String key, SourceManager sourceManager, SourceAdapter adapter, String searchKey) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        final EditText nameEt = new EditText(context);
        nameEt.setHint("源名称（如：主源、备用源）");
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);
        final EditText urlEt = new EditText(context);
        urlEt.setHint("源地址 URL");
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);
        new AlertDialog.Builder(context)
                .setTitle("添加" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("添加", (dialog, which) -> {
                    // 修复：Editable转String再trim
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean success = sourceManager.addSource(name, url);
                    if (!success) {
                        Toast.makeText(context, "该源已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String saveKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
                    sp.edit().putString(saveKey, url).apply();
                    context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    refreshDisplayList(sourceManager, new ArrayList<>(sourceManager.getAllSources()), adapter, searchKey);
                    Toast.makeText(context, "已添加，正在刷新…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // ====================================================================
    // 4. 编辑弹窗（修复入参+Editable.trim报错）
    // ====================================================================
    private void showEditSourceDialog(String title, final String key, final int position, SourceManager.SourceItem oldItem, SourceManager sourceManager, SourceAdapter adapter, String searchKey) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        final EditText nameEt = new EditText(context);
        nameEt.setText(oldItem.name);
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);
        final EditText urlEt = new EditText(context);
        urlEt.setText(oldItem.url);
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);
        new AlertDialog.Builder(context)
                .setTitle("编辑" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    // 修复 Editable.trim
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (TextUtils.isEmpty(name)) {
                        name = "未命名";
                    }
                    sourceManager.updateSource(position, name, url);
                    String currentKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
                    String currentUrl = sp.getString(currentKey, "");
                    if (currentUrl.equals(oldItem.url)) {
                        sp.edit().putString(currentKey, url).apply();
                        context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    }
                    refreshDisplayList(sourceManager, new ArrayList<>(sourceManager.getAllSources()), adapter, searchKey);
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // ====================================================================
    // 5. 导入弹窗（修复ClipData.getText()报错）
    // ====================================================================
    private void showImportDialog(String title, final String key, final SourceManager sourceManager,
                                   final ArrayList<SourceManager.SourceItem> displayItems,
                                   final SourceAdapter adapter, final EditText searchEt) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clipData = cm.getPrimaryClip();
        ClipData.Item clipItem = clipData.getItemAt(0);
        CharSequence clipSeq = clipItem.getText();
        if (clipSeq == null || clipSeq.toString().trim().isEmpty()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final String text = clipSeq.toString().trim();
        final String[] lines = text.split("\n");
        int count = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (line.contains("http")) count++;
        }
        final int importCount = count;
        new AlertDialog.Builder(context)
                .setTitle("确认导入")
                .setMessage("检测到 " + importCount + " 个源，是否导入？")
                .setPositiveButton("导入", (dialog, which) -> {
                    int added = sourceManager.importFromText(text);
                    refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                    Toast.makeText(context, "成功导入 " + added + " 个源", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
