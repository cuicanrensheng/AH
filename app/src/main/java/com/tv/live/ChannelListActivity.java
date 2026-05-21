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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);

        ListView listView = findViewById(R.id.channel_list);

        // 读取MainActivity的频道列表
        final List<MainActivity.Channel> channelList = MainActivity.mInstance.channels;
        List<String> names = new ArrayList<>();
        for (MainActivity.Channel c : channelList) {
            names.add(c.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            // 调用MainActivity的play方法
            MainActivity.mInstance.play(position);
            finish(); // 播放后关闭频道列表页
        });
    }
}
