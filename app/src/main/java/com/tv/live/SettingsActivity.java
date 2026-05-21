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

        Switch rev = findViewById(R.id.sw_rev);
        Switch boot = findViewById(R.id.sw_boot);
        Switch source = findViewById(R.id.sw_source);
        Switch epg = findViewById(R.id.sw_epg);

        rev.setChecked(sp.getBoolean("reverse", false));
        boot.setChecked(sp.getBoolean("boot", false));
        source.setChecked(sp.getInt("source", 0) == 1);
        epg.setChecked(sp.getBoolean("epg", true));

        rev.setOnCheckedChangeListener((b, v) -> ed.putBoolean("reverse", v).apply());
        boot.setOnCheckedChangeListener((b, v) -> ed.putBoolean("boot", v).apply());
        source.setOnCheckedChangeListener((b, v) -> ed.putInt("source", v ? 1 : 0).apply());
        epg.setOnCheckedChangeListener((b, v) -> ed.putBoolean("epg", v).apply());
    }
}
