package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    public static List<String> logList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        Switch sw_boot = findViewById(R.id.sw_boot);
        Switch sw_epg = findViewById(R.id.sw_epg);
        Switch sw_auto_update = findViewById(R.id.sw_auto_update);
        Switch sw_reverse = findViewById(R.id.sw_reverse);
        Switch sw_num_channel = findViewById(R.id.sw_num_channel);

        sw_boot.setChecked(sp.getBoolean("boot", false));
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update", true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_num_channel.setChecked(sp.getBoolean("num_channel", true));

        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("boot", isChecked).apply()
        );
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("epg_enable", isChecked).apply()
        );
        sw_auto_update.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("auto_update", isChecked).apply()
        );
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("channel_reverse", isChecked).apply()
        );
        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("num_channel", isChecked).apply()
        );

        findViewById(R.id.log_viewer).setOnClickListener(v -> finish());
    }

    public static void log(String msg) {
        if (logList != null) {
            logList.add(0, msg);
        }
    }
}
