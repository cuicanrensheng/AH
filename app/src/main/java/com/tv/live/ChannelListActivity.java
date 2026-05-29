package com.tv.live;

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
        ListView listView = new ListView(this);
        setContentView(listView);

        if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList.isEmpty()) {
            finish();
            return;
        }

        List<Channel> channelList = MainActivity.mInstance.channelSourceList;
        List<String> names = new ArrayList<>();
        for (Channel c : channelList) names.add(c.name);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);

        int currentPos = MainActivity.mInstance.currentChannelIndex;
        listView.setSelection(currentPos);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.mInstance.playChannel(position);
            finish();
        });
    }
}
