package com.tv.live;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
    }

    public void refreshPlaylist(View v) {
        Toast.makeText(this, "刷新成功", Toast.LENGTH_SHORT).show();
        finish();
    }

    public void goBack(View v) { finish(); }
}
