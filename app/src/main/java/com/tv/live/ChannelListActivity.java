package com.tv.live;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.model.Channel;
import com.tv.live.utils.PlaylistManager;
import java.util.ArrayList;
import java.util.List;

public class ChannelListActivity extends AppCompatActivity {
    public static MainActivity mainInstance;
    private ListView listView;
    private List<Channel> channelList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);
        listView = findViewById(R.id.listView);
        loadChannels();

        listView.setOnItemClickListener((ad, v, pos, id) -> {
            Channel ch = channelList.get(pos);
            mainInstance.playChannel(ch);
            finish();
        });
    }

    private void loadChannels() {
        channelList = PlaylistManager.getCurrentPlaylistChannels();
        List<String> names = new ArrayList<>();
        for (Channel c : channelList) names.add(c.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                View v = super.getView(pos, cv, p);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(18);
                tv.setPadding(40, 24, 40, 24);
                return v;
            }
        };
        listView.setAdapter(adapter);
    }

    public void goBack(View v) { finish(); }
}
