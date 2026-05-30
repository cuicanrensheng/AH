package com.tv.live.widget;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            showEmpty();
            return;
        }
        new Thread(() -> {
            try {
                List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
                List<String> data = new ArrayList<>();
                if (epgList != null && !epgList.isEmpty()) {
                    for (Channel.EpgItem item : new ArrayList<>(epgList)) {
                        data.add(item.dayName + " " + item.time + " " + item.title);
                    }
                } else {
                    data.add("暂无节目单");
                }
                updateUi(data);
            } catch (Exception e) {
                updateUi(Collections.singletonList("暂无节目单"));
            }
        }).start();
    }

    private void showEmpty() {
        updateUi(Collections.singletonList("暂无节目单"));
    }

    private void updateUi(List<String> data) {
        lvEpg.post(() -> {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, data);
            lvEpg.setAdapter(adapter);
        });
    }

    public void onBackPressed() {
    }
}
