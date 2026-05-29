package com.tv.live;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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

        // 空值防护，避免闪退
      if (MainActivity.mInstance == null || MainActivity.mInstance.channelSourceList.isEmpty())
{
            finish();
            return;
        }

       final List<Channel> channelList = MainActivity.mInstance.channelSourceList;
        List<String> names = new ArrayList<>();
        for (Channel c : channelList) {
            names.add(c.name);
        }

        // 带高亮的列表适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                // 当前播放频道高亮显示
               if (position == MainActivity.mInstance.currentChannelIndex) {
                    view.setBackgroundColor(Color.parseColor("#FFE0E0E0"));
                } else {
                    view.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };

        listView.setAdapter(adapter);

        // 双重定位，保证一定滚动到当前播放频道
        int currentPos = MainActivity.mInstance.currentChannelIndex;
        listView.setSelection(currentPos);
        listView.smoothScrollToPositionFromTop(currentPos, 50);

        // 修复点击事件语法
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 点击后关闭当前页面
                finish();
            }
        });
    }
}
