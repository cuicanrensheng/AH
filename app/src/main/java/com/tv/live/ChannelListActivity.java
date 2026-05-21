package com.tv.live;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

public class ChannelListActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ListView listView = new ListView(this);
        setContentView(listView);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                new String[]{"频道1","频道2","频道3"}
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(22);
                tv.setPadding(30, 20, 30, 20);
                return tv;
            }
        };

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.mInstance.play(position);
            finish();
        });
    }
}
