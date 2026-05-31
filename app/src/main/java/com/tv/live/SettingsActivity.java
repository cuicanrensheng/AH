package com.tv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面：带蓝色高亮选中效果
 */
public class SettingsActivity extends AppCompatActivity {

    private ListView lvSettings;
    private SettingsAdapter adapter;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // 开启单选模式，为高亮做准备
        lvSettings.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 初始化设置项
        List<SettingItem> settingList = new ArrayList<>();
        settingList.add(new SettingItem("开机自启", "开/关"));
        settingList.add(new SettingItem("节目单开关", "开/关"));
        settingList.add(new SettingItem("自动更新源", "开/关"));
        settingList.add(new SettingItem("换台反转", "开/关"));
        settingList.add(new SettingItem("数字选台", "开/关"));
        settingList.add(new SettingItem("屏幕比例", "选择"));
        settingList.add(new SettingItem("自定义直播源", "输入地址"));
        settingList.add(new SettingItem("自定义节目单", "输入地址"));
        settingList.add(new SettingItem("直播源历史", "查看/管理"));
        settingList.add(new SettingItem("节目单历史", "查看/管理"));

        // 初始化适配器
        adapter = new SettingsAdapter(this, settingList);
        lvSettings.setAdapter(adapter);

        // 列表点击事件：点击后文字变蓝，并执行对应设置逻辑
        lvSettings.setOnItemClickListener((parent, view, position, id) -> {
            // 1. 更新选中状态（文字变蓝）
            lvSettings.setItemChecked(position, true);
            adapter.setSelectedPosition(position);

            // 2. 执行对应设置逻辑
            handleSettingClick(position);
        });
    }

    /**
     * 处理设置项点击逻辑
     */
    private void handleSettingClick(int position) {
        switch (position) {
            case 0: // 开机自启
                toggleSwitch("boot_auto_start");
                break;
            case 1: // 节目单开关
                toggleSwitch("epg_enable");
                break;
            case 2: // 自动更新源
                toggleSwitch("auto_update_source");
                break;
            case 3: // 换台反转
                toggleSwitch("channel_reverse");
                break;
            case 4: // 数字选台
                toggleSwitch("number_channel_enable");
                break;
            case 5: // 屏幕比例
                showScreenRatioDialog();
                break;
            case 6: // 自定义直播源
                showInputDialog("自定义直播源", "live_url");
                break;
            case 7: // 自定义节目单
                showInputDialog("自定义节目单", "epg_url");
                break;
            case 8: // 直播源历史
                showHistoryDialog("live_history", "直播源历史");
                break;
            case 9: // 节目单历史
                showHistoryDialog("epg_history", "节目单历史");
                break;
        }
    }

    /**
     * 开关类设置项（true/false）
     */
    private void toggleSwitch(String key) {
        boolean current = sp.getBoolean(key, true);
        sp.edit().putBoolean(key, !current).apply();
        Toast.makeText(this, "已" + (!current ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
    }

    /**
     * 屏幕比例选择弹窗
     */
    private void showScreenRatioDialog() {
        String[] ratios = {"默认", "16:9", "4:3", "全屏"};
        new AlertDialog.Builder(this)
                .setTitle("选择屏幕比例")
                .setItems(ratios, (dialog, which) -> {
                    sp.edit().putInt("screen_ratio", which).apply();
                    Toast.makeText(this, "已选择：" + ratios[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * 自定义地址输入弹窗
     */
    private void showInputDialog(String title, String key) {
        EditText et = new EditText(this);
        et.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (dialog, which) -> {
                    String url = et.getText().toString().trim();
                    sp.edit().putString(key, url).apply();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 历史记录弹窗
     */
    private void showHistoryDialog(String key, String title) {
        String history = sp.getString(key, "");
        if (TextUtils.isEmpty(history)) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] list = history.split("\n");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(list, null)
                .setPositiveButton("清空", (dialog, which) -> {
                    sp.edit().putString(key, "").apply();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 设置项数据类
     */
    static class SettingItem {
        String title;
        String desc;

        SettingItem(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    /**
     * 设置列表适配器（带蓝色高亮逻辑）
     */
    static class SettingsAdapter extends BaseAdapter {
        private final Context context;
        private final List<SettingItem> items;
        private int selectedPosition = -1; // 当前选中的项

        SettingsAdapter(Context context, List<SettingItem> items) {
            this.context = context;
            this.items = items;
        }

        /**
         * 外部调用：更新选中位置
         */
        void setSelectedPosition(int position) {
            this.selectedPosition = position;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
            }
            tv = (TextView) convertView;

            SettingItem item = items.get(position);
            tv.setText(item.title);

            // 关键：选中项文字变蓝色，其他保持白色
            if (position == selectedPosition) {
                tv.setTextColor(0xFF40A9FF); // 蓝色高亮
            } else {
                tv.setTextColor(0xFFFFFFFF); // 默认白色
            }

            return convertView;
        }
    }
}
