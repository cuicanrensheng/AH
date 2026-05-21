package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SharedPreferences sp = getSharedPreferences("setting", MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();

        Switch swRev = findViewById(R.id.sw_rev);
        Switch swBoot = findViewById(R.id.sw_boot);
        Switch swSource = findViewById(R.id.sw_source);
        Switch swEpg = findViewById(R.id.sw_epg);

        swRev.setChecked(sp.getBoolean("reverse", false));
        swBoot.setChecked(sp.getBoolean("boot", false));
        swSource.setChecked(sp.getInt("source",0)==1);
        swEpg.setChecked(sp.getBoolean("epg",true));

        swRev.setOnCheckedChangeListener((b, v)-> ed.putBoolean("reverse",v).apply());
        swBoot.setOnCheckedChangeListener((b, v)-> ed.putBoolean("boot",v).apply());
        swSource.setOnCheckedChangeListener((b, v)-> ed.putInt("source",v?1:0).apply());
        swEpg.setOnCheckedChangeListener((b, v)-> ed.putBoolean("epg",v).apply());
    }
}
