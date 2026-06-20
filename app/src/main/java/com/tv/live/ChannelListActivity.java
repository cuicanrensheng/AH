package com.tv.live;

import com.tv.live.Channel;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表 Activity
 * 
 * 【功能说明】
 * 显示所有频道的列表，点击可以切换到对应的频道。
 * 
 * 【2026-06-20 修改说明】
 * 把所有 MainActivity.mInstance 改成 MainActivity.getInstance()
 * 
 * 【为什么要改？】
 * 原来的代码直接访问 MainActivity.mInstance，但是 mInstance 是 private 的，
 * 外部类不能直接访问 private 成员变量，会编译报错。
 * 
 * 【修改方案】
 * 通过 public 的 getInstance() 方法来获取单例实例，这是标准的单例模式写法。
 * 
 * 【修改了几处？】
 * 一共改了 5 处：
 * 1. 安全判断里的 null 检查
 * 2. 获取 channelSourceList
 * 3. 获取 currentPlayIndex
 * 4. 点击事件里的 null 检查
 * 5. 点击事件里的 playChannel 调用
 */
public class ChannelListActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        ListView listView = new ListView(this);
        setContentView(listView);

        // ================================================================
        // ✅ 修改 1：安全判断
        // 【原来的代码】
        // if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList == null
        //         || MainActivity.mInstance.channelSourceList.isEmpty())
        //
        // 【修改后的代码】
        // 用 MainActivity.getInstance() 代替 MainActivity.mInstance
        // ================================================================
        if (MainActivity.getInstance() == null 
                || MainActivity.getInstance().channelSourceList == null
                || MainActivity.getInstance().channelSourceList.isEmpty()) {
            finish();
            return;
        }

        // ================================================================
        // ✅ 修改 2：获取频道列表
        // 【原来的代码】
        // final List<Channel> channelList = MainActivity.mInstance.channelSourceList;
        //
        // 【修改后的代码】
        // 用 MainActivity.getInstance() 代替 MainActivity.mInstance
        // ================================================================
        final List<Channel> channelList = MainActivity.getInstance().channelSourceList;

        // ================================================================
        // ✅ 修改 3：获取当前播放索引
        // 【原来的代码】
        // final int currentRealIndex = MainActivity.mInstance.currentPlayIndex;
        //
        // 【修改后的代码】
        // 用 MainActivity.getInstance() 代替 MainActivity.mInstance
        // ================================================================
        final int currentRealIndex = MainActivity.getInstance().currentPlayIndex;

        List<String> names = new ArrayList<>();
        for (Channel c : channelList) names.add(c.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
        listView.setSelection(currentRealIndex);

        // 点击就用当前列表真实position，100%准
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // ============================================================
            // ✅ 修改 4：点击事件里的 null 检查
            // 【原来的代码】
            // if (MainActivity.mInstance != null)
            //
            // 【修改后的代码】
            // 用 MainActivity.getInstance() 代替 MainActivity.mInstance
            // ============================================================
            if (MainActivity.getInstance() != null) {
                // ========================================================
                // ✅ 修改 5：点击事件里的 playChannel 调用
                // 【原来的代码】
                // MainActivity.mInstance.playChannel(position);
                //
                // 【修改后的代码】
                // 用 MainActivity.getInstance() 代替 MainActivity.mInstance
                // ========================================================
                MainActivity.getInstance().playChannel(position);
            }
            finish();
        });
    }
}
