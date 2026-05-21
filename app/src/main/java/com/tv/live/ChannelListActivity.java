package com.tv.live;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ChannelListActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ListView listView = new ListView(this);
        setContentView(listView);

        List<String> names = new ArrayList<>();
        for (MainActivity.Channel c : MainActivity.mInstance.channels) {
            names.add(c.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            this,
        android.R.layout.simple_list_item_1,
        names
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
        listView.setOnItemClickListener((p, v, pos, id) -> {
            MainActivity.mInstance.play(pos);
            finish();
        });
    }
}
