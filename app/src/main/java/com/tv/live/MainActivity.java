package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public static class Channel {
        public String name;
        public String url;

        public Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
    // 直播源 & EPG地址
    private static final String LIVE_M3U_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private static final String EPG_XML_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    // ExoPlayer
    private ExoPlayer exoPlayer;
    private PlayerView playerView;

    // 设置参数
    private boolean channelReverse;
    private boolean epgOpen;
    private int sourceSelectIndex;

    // 频道数据
    private final List<String> channelNameList = new ArrayList<>();
    private final List<String> channelUrlList = new ArrayList<>();
    private int currentPlayIndex = 0;

    // 手势
    private GestureDetector gestureDetector;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // EPG控件
    private View epgLayout;
    private TextView tvEpgInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        initExoPlayer();
        readAllSetting();
        initGestureTouch();
        loadNetM3ULiveSource();
    }

    // 初始化控件
    private void initView(){
        playerView = findViewById(R.id.player_view);
        epgLayout = findViewById(R.id.epg_layout);
        tvEpgInfo = findViewById(R.id.tv_epg_info);
    }

    // 初始化ExoPlayer
    private void initExoPlayer(){
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        // 关闭默认控制栏
        playerView.setUseController(false);
    }

    // 读取本地设置
    private void readAllSetting(){
        SharedPreferences sp = getSharedPreferences("setting",MODE_PRIVATE);
        channelReverse = sp.getBoolean("reverse",false);
        epgOpen = sp.getBoolean("epg",true);
        sourceSelectIndex = sp.getInt("source",0);
        // 控制EPG显示隐藏
        epgLayout.setVisibility(epgOpen ? View.VISIBLE : View.GONE);
    }

    // 网络加载M3U直播源自动解析频道
    private void loadNetM3ULiveSource(){
        new Thread(()->{
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(LIVE_M3U_URL).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

                String line;
                String tempName = null;
                channelNameList.clear();
                channelUrlList.clear();

                while ((line = reader.readLine()) != null){
                    if(line.startsWith("#EXTINF:")){
                        int nameIndex = line.lastIndexOf(",");
                        if(nameIndex != -1){
                            tempName = line.substring(nameIndex+1);
                        }
                    }else if(line.startsWith("http")){
                        if(tempName != null){
                            channelNameList.add(tempName);
                            channelUrlList.add(line);
                            tempName = null;
                        }
                    }
                }
                reader.close();
                connection.disconnect();

                mainHandler.post(()->{
                    if(!channelUrlList.isEmpty()){
                        playTargetChannel(0);
                    }
                    Toast.makeText(MainActivity.this,"加载成功 共"+channelNameList.size()+"个频道",Toast.LENGTH_SHORT).show();
                });
            }catch (Exception e){
                mainHandler.post(()-> Toast.makeText(MainActivity.this,"直播源加载失败",Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ExoPlayer播放指定频道
    private void playTargetChannel(int pos){
        if(pos<0 || pos>=channelUrlList.size())return;
        currentPlayIndex = pos;
        String playUrl = channelUrlList.get(pos);
        String showName = channelNameList.get(pos);

        MediaItem mediaItem = MediaItem.fromUri(playUrl);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        tvEpgInfo.setText("当前播放："+showName+"\nEPG地址："+EPG_XML_URL);
    }

    // 上一个频道
    private void switchLastChannel(){
        if(channelReverse){
            switchNextChannel();
            return;
        }
        currentPlayIndex--;
        if(currentPlayIndex<0)currentPlayIndex = channelUrlList.size()-1;
        playTargetChannel(currentPlayIndex);
    }

    // 下一个频道
    private void switchNextChannel(){
        if(channelReverse){
            switchLastChannel();
            return;
        }
        currentPlayIndex++;
        if(currentPlayIndex>=channelUrlList.size())currentPlayIndex=0;
        playTargetChannel(currentPlayIndex);
    }

    // 弹出全部频道列表选择
    private void showAllChannelPop(){
        if(channelNameList.isEmpty())return;
        String[] channelArr = channelNameList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("频道列表")
                .setItems(channelArr,(dialog,which)-> playTargetChannel(which))
                .show();
    }

    // 跳转设置页面
    private void jumpToSettingPage(){
        startActivity(new Intent(this,SettingsActivity.class));
    }

    // 初始化手机触摸手势
    private void initGestureTouch(){
        gestureDetector = new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            // 单击弹出频道列表
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showAllChannelPop();
                return true;
            }
            // 双击打开设置
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                jumpToSettingPage();
                return true;
            }
            // 上下滑动切频道
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float yDistance = e2.getY()-e1.getY();
                if(Math.abs(yDistance)>80){
                    if(yDistance<0){
                        switchNextChannel();
                    }else {
                        switchLastChannel();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event)||super.onTouchEvent(event);
    }

    // 电视遥控器按键监听
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode){
            case KeyEvent.KEYCODE_DPAD_UP:
                switchLastChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchNextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showAllChannelPop();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_HELP:
                jumpToSettingPage();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 从设置页返回刷新配置
    @Override
    protected void onResume() {
        super.onResume();
        readAllSetting();
    }

    // 销毁释放播放器
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(exoPlayer!=null){
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer=null;
        }
    }
}
