package com.tv.live;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class ChannelListActivity extends AppCompatActivity {
    // 修复：适配项目正确的ListView ID + Channel类正确方法名
    private ListView lvChannel;
    private List<String> channelNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 修复1：替换为项目正确的控件ID lv_channel
        lvChannel = findViewById(R.id.lv_channel);
        
        if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList == null || lvChannel == null) {
            finish();
            return;
        }

        for (Channel channel : MainActivity.mInstance.channelSourceList) {
            // 修复2：Channel类正确的获取名称方法 getName()
            channelNames.add(channel.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, channelNames);
        lvChannel.setAdapter(adapter);
        lvChannel.setSelection(MainActivity.mInstance.currentPlayIndex);

        lvChannel.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.mInstance.playChannel(position);
            finish();
        });
    }
}
