package com.tv.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "player_setting";
    public static final String KEY_PLAYER = "player_type";
    public static final int PLAYER_EXO = 0;
    public static final int PLAYER_VLC = 1;

    private RadioGroup radioGroup;
    private RadioButton radioExo, radioVlc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        radioGroup = findViewById(R.id.radio_player_group);
        radioExo = findViewById(R.id.radio_exo);
        radioVlc = findViewById(R.id.radio_vlc);
        Button btnSave = findViewById(R.id.btn_save);

        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int type = sp.getInt(KEY_PLAYER, PLAYER_EXO);
        if (type == PLAYER_EXO) radioExo.setChecked(true);
        else radioVlc.setChecked(true);

        btnSave.setOnClickListener(v -> {
            int selected = radioGroup.getCheckedRadioButtonId();
            SharedPreferences.Editor editor = sp.edit();
            if (selected == R.id.radio_exo) {
                editor.putInt(KEY_PLAYER, PLAYER_EXO);
            } else {
                editor.putInt(KEY_PLAYER, PLAYER_VLC);
            }
            editor.apply();
            setResult(RESULT_OK);
            finish();
        });
    }
}
