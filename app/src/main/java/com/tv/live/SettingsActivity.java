package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        Switch swBoot = findViewById(R.id.sw_boot);
        Switch swEpg = findViewById(R.id.sw_epg);
        Switch swAuto = findViewById(R.id.sw_auto_update);
        Switch swReverse = findViewById(R.id.sw_reverse);
        Switch swNum = findViewById(R.id.sw_num_channel);

        swBoot.setChecked(sp.getBoolean("boot", false));
        swEpg.setChecked(sp.getBoolean("epg_enable", true));
        swAuto.setChecked(sp.getBoolean("auto_update", true));
        swReverse.setChecked(sp.getBoolean("channel_reverse", false));
        swNum.setChecked(sp.getBoolean("num_channel", true));

        swBoot.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("boot", c).apply());
        swEpg.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("epg_enable", c).apply());
        swAuto.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("auto_update", c).apply());
        swReverse.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("channel_reverse", c).apply());
        swNum.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("num_channel", c).apply());

        // 关闭页面
        findViewById(R.id.log_viewer).setOnClickListener(v -> finish());
    }
}
