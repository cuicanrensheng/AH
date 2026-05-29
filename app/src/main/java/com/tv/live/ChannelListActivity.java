package com.tv.live;
import com.tv.live.Channel;
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

        // 安全判断
        if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList == null
            || MainActivity.mInstance.channelSourceList.isEmpty()) {
            finish();
            return;
        }

        // 用当前真正播放的下标定位
        final List<Channel> channelList = MainActivity.mInstance.channelSourceList;
        final int currentRealIndex = MainActivity.mInstance.currentPlayIndex;

        List<String> names = new ArrayList<>();
        for (Channel c : channelList) names.add(c.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
        listView.setSelection(currentRealIndex);

        // 点击就用当前列表真实position，100%准
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (MainActivity.mInstance != null) {
                MainActivity.mInstance.playChannel(position);
            }
            finish();
        });
    }
}
