package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

public class SettingActivity extends AppCompatActivity {

    private Switch swReverse, swBoot, swEpg;
    private Button btnSourceSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);

        SharedPreferences sp = getSharedPreferences("tv_setting", MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();

        swReverse = findViewById(R.id.sw_reverse);
        swBoot = findViewById(R.id.sw_boot);
        swEpg = findViewById(R.id.sw_epg);
        btnSourceSwitch = findViewById(R.id.btn_source);

        swReverse.setChecked(sp.getBoolean("reverse", false));
        swBoot.setChecked(sp.getBoolean("boot", false));
        swEpg.setChecked(sp.getBoolean("epg", true));
        int source = sp.getInt("source",0);
        btnSourceSwitch.setText(source==0 ? "当前：默认源" : "当前：备用源");

        swReverse.setOnCheckedChangeListener((v, b)-> editor.putBoolean("reverse",b).apply());
        swBoot.setOnCheckedChangeListener((v, b)-> editor.putBoolean("boot",b).apply());
        swEpg.setOnCheckedChangeListener((v, b)-> editor.putBoolean("epg",b).apply());

        btnSourceSwitch.setOnClickListener(v->{
            int newSource = (sp.getInt("source",0)+1)%2;
            editor.putInt("source",newSource).apply();
            btnSourceSwitch.setText(newSource==0 ? "当前：默认源" : "当前：备用源");
        });
    }
}
