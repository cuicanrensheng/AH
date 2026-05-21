package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private Switch sw_reverse,sw_boot,sw_source,sw_epg;
    private SharedPreferences sp;
    private SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("setting",MODE_PRIVATE);
        editor = sp.edit();

        sw_reverse = findViewById(R.id.sw_reverse);
        sw_boot = findViewById(R.id.sw_boot);
        sw_source = findViewById(R.id.sw_source);
        sw_epg = findViewById(R.id.sw_epg);

        // 读取状态
        sw_reverse.setChecked(sp.getBoolean("reverse",false));
        sw_boot.setChecked(sp.getBoolean("boot",false));
        sw_source.setChecked(sp.getInt("source",0)==1);
        sw_epg.setChecked(sp.getBoolean("epg",true));

        // 频道反转
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) ->
                editor.putBoolean("reverse",isChecked).apply());

        // 开机自启
        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) ->
                editor.putBoolean("boot",isChecked).apply());

        // 直播源切换
        sw_source.setOnCheckedChangeListener((buttonView, isChecked) ->{
            if(isChecked){
                editor.putInt("source",1);
            }else {
                editor.putInt("source",0);
            }
            editor.apply();
        });

        // EPG开关
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) ->
                editor.putBoolean("epg",isChecked).apply());
    }
}
