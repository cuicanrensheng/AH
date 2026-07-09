package com.tv.live;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class ChannelListActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        ListView listView = new ListView(this);
        setContentView(listView);

        // 🟢【核心修复】改用弱引用获取 MainActivity 实例，彻底阻断空指针崩溃和内存泄漏
        MainActivity activity = MainActivity.getRunningInstance();

        // 如果 MainActivity 已销毁（被系统回收），直接关闭当前页面，防止闪退
        if (activity == null) {
            finish();
            return;
        }

        // 检查频道列表是否有效
        if (activity.channelSourceList == null || activity.channelSourceList.isEmpty()) {
            finish();
            return;
        }

        // 用当前真正播放的下标定位
        final List<Channel> channelList = activity.channelSourceList;
        final int currentRealIndex = activity.currentPlayIndex;

        List<String> names = new ArrayList<>();
        for (Channel c : channelList) names.add(c.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
        listView.setSelection(currentRealIndex);

        // 点击就用当前列表真实position，100%准
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // 🟢【优化】在回调中再次进行空安全判断，防止用户点击瞬间 MainActivity 被销毁
            MainActivity runningActivity = MainActivity.getRunningInstance();
            if (runningActivity != null) {
                runningActivity.playChannel(position);
            }
            finish();
        });
    }
}
