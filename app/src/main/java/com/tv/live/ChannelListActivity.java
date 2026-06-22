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
    private ListView listView;
    private List<String> channelNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        listView = findViewById(R.id.list_view);
        if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList == null) {
            finish();
            return;
        }

        for (Channel channel : MainActivity.mInstance.channelSourceList) {
            channelNames.add(channel.getChannelName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, channelNames);
        listView.setAdapter(adapter);
        listView.setSelection(MainActivity.mInstance.currentPlayIndex);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            // ✅ 修复：调用重载方法
            MainActivity.mInstance.playChannel(position);
            finish();
        });
    }
}
