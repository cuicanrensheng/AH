package com.tv.live;
import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String SP_NAME = "tv_live_setting";
    private static SettingsManager instance;
    private final SharedPreferences sp;

    public static final String KEY_LINE_INDEX = "line_index";
    public static final String KEY_VIDEO_SCALE = "video_scale";
    public static final String KEY_DECODE_MODE = "decode_mode";
    public static final String KEY_TIMEOUT_OPEN = "timeout_open";
    public static final String KEY_TIMEOUT_TIME = "timeout_time";
    public static final String KEY_SUB_URL = "sub_m3u_url";

    // 画面比例常量
    public static final int SCALE_FIT = 0;
    public static final int SCALE_16_9 = 1;
    public static final int SCALE_FILL = 2;

    // 解码模式常量
    public static final int DECODE_AUTO = 0;
    public static final int DECODE_HARD = 1;
    public static final int DECODE_SOFT = 2;

    private SettingsManager(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SettingsManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new SettingsManager(ctx);
        }
        return instance;
    }

    // 线路
    public void setLine(int pos) {
        sp.edit().putInt(KEY_LINE_INDEX, pos).apply();
    }
    public int getLine() {
        return sp.getInt(KEY_LINE_INDEX, 0);
    }

    // 画面比例
    public void setScale(int scale) {
        sp.edit().putInt(KEY_VIDEO_SCALE, scale).apply();
    }
    public int getScale() {
        return sp.getInt(KEY_VIDEO_SCALE, SCALE_FIT);
    }

    // 解码模式
    public void setDecode(int mode) {
        sp.edit().putInt(KEY_DECODE_MODE, mode).apply();
    }
    public int getDecode() {
        return sp.getInt(KEY_DECODE_MODE, DECODE_AUTO);
    }

    // 超时开关
    public void setTimeoutEnable(boolean open) {
        sp.edit().putBoolean(KEY_TIMEOUT_OPEN, open).apply();
    }
    public boolean isTimeoutEnable() {
        return sp.getBoolean(KEY_TIMEOUT_OPEN, true);
    }
    public boolean getTimeoutEnable() {
        return isTimeoutEnable();
    }

    // 超时秒数
    public void setTimeoutSec(int sec) {
        sp.edit().putInt(KEY_TIMEOUT_TIME, sec).apply();
    }
    public int getTimeoutSec() {
        return sp.getInt(KEY_TIMEOUT_TIME, 6);
    }

    // 订阅地址
    public void setSubUrl(String url) {
        sp.edit().putString(KEY_SUB_URL, url).apply();
    }
    public String getSubUrl() {
        return sp.getString(KEY_SUB_URL, "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
    }
}
