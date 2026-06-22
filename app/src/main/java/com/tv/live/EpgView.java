package com.tv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

public class EpgView extends FrameLayout {
    private TextView tvEpg;

    public EpgView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_epg, this);
        tvEpg = findViewById(R.id.tv_epg_info);
    }

    public void setEpgText(String text) {
        tvEpg.setText(text);
    }
}
