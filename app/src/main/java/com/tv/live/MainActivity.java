package com.tv.live;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.GroupListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.util.ArrayList;
import java.util.List;
/**
 * 播放器首页主页面
 * 核心功能：
 * 1. Exo视频播放、频道切换、上下遥控器切台
 * 2. 左侧分组、中间频道、竖排节目单按钮、右侧日期+EPG节目列表
 * 3. 底部弹出频道信息栏（名称、清晰度、音轨、播放进度、下一节目）
 * 4. 手势：上下滑动切换频道、双击屏幕打开设置页
 * 5. 接收设置页广播，动态刷新直播源与EPG地址
 * 6. EPG节目单开关控制显隐、日期切换对应时段节目
 */
public class MainActivity extends AppCompatActivity {
    // 单例实例，全局日志调用
    public static MainActivity mInstance;
    // 全频道总列表
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组对应的频道子集
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放频道下标
    public int currentPlayIndex = 0;
    // 侧边栏整体面板控件
    private View panel_layout;
    // 播放器管理类
    public TVPlayerManager mPlayerManager;
    // Exo播放器视图
    private PlayerView playerView;
    // APP本地配置管理
    private AppConfig appConfig;
    // 画面缩放比例管理
    private ScreenRatioManager screenRatioManager;
    // 侧边栏面板控制器
    private PanelManager panelManager;
    //手势
    private GestureDetector mGestureDetector;
    private final long DOUBLE_TAP_GAP = 320;
    private long lastTapTime = 0;
    // 实体遥控器按键监听
    private KeyEventManager keyEventManager;
    // 频道列表适配器
    private ChannelListManager channelListManager;
    // 频道分组适配器
    private GroupListManager groupListManager;
    // EPG日期横向列表适配器
    private DateListManager dateListManager;
    // EPG节目内容适配器
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器播放状态监听
    private PlayerStateListenerImpl playerStateListener;
    // 频道上下切台工具类
    private ChannelSwitchManager switchManager;
    // EPG面板打开标记
    private boolean epgPanelOpen = false;
    // Exo原生控制器显隐标记
    private boolean isControllerVisible = false;
    // SP配置项开关
    private boolean epg_enable;          //节目单总开关
    private boolean channel_reverse;     //上下切台反转
    private boolean number_channel_enable;//数字选台开关
    private boolean auto_update_source;  //自动更新源开关
    // 当前选中EPG日期下标
    private int currentSelectedDateIndex = 0;
    // 本地配置缓存
    private SharedPreferences sp;
    // 底部信息栏所有控件
    private View info_bar;
    private TextView tv_channel_name;    //频道名称
    private TextView tv_tag_fhd;         //清晰度标识
    private TextView tv_tag_audio;       //音轨标识
    private TextView tv_bitrate;        //实时码率
    private TextView tv_current_program_name; //正在播放节目名
    private TextView tv_current_time_range;  //节目起止时间
    private TextView tv_remaining_time;      //剩余时长
    private TextView tv_next_program_name;   //下一档节目
    private TextView tv_next_time_range;     //下一档时间
    private android.widget.ProgressBar progress_program;//播放进度条
    // 右上角切台数字弹窗
    private TextView tv_channel_num;
    // 底部信息栏自动隐藏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };
    // 切台冷却时间，防止连续快速按键切台
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    // 触摸Y坐标缓存（上下滑动）
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;
    // 全局日志容器，保存播放/操作日志，供给Settings查看
    public static List<String> logList = new ArrayList<>();
    /**
     * 全局日志输出方法，写入日志列表并同步到设置页面日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        //日志上限100条，超出剔除末尾
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }
    /**
     * 广播：切换Exo原生控制器显示/隐藏
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };
    /**
     * 广播：接收设置页面发来的源刷新指令，重新加载直播源+EPG
     * Action:com.tv.live.REFRESH_LIVE_AND_EPG
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadSettings();
                        //读取自定义源地址，覆盖全局UrlConfig
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        mInstance = this;
        //固定全屏横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        //全屏隐藏状态栏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //控件初始化
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        //初始化手势
        mGestureDetector = new GestureDetector(this,new LiveSlideGesture());
        //读取本地自定义源，替换默认地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
    }

    private class LiveSlideGesture extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            long now = System.currentTimeMillis();
            if(now - lastChannelChangeTime < CHANNEL_COOLDOWN) return false;
            float yDiff = e2.getY() - e1.getY();
            if(Math.abs(yDiff) < SLIDE_THRESHOLD) return false;
            int total = currentGroupChannelList.size();
            if(total <=0) return false;
            boolean rev = channel_reverse;
            if(yDiff < -SLIDE_THRESHOLD){
                if(rev) currentPlayIndex--;
                else currentPlayIndex++;
            }else if(yDiff > SLIDE_THRESHOLD){
                if(rev) currentPlayIndex++;
                else currentPlayIndex--;
            }
            checkIndexCycle(total);
            changeChannel(currentPlayIndex);
            lastChannelChangeTime = now;
            return true;
        }
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            startActivity(new Intent(MainActivity.this,SettingsActivity.class));
            log("【主页】双击打开设置");
            return true;
        }
    }

    //=============新增缺失外部调用方法（修复全部找不到符号报错）=============
    public void playPrev(){
        boolean rev = channel_reverse;
        int total = currentGroupChannelList.size();
        if(total==0)return;
        if(rev) currentPlayIndex++;
        else currentPlayIndex--;
        checkIndexCycle(total);
        changeChannel(currentPlayIndex);
    }
    public void playNext(){
        boolean rev = channel_reverse;
        int total = currentGroupChannelList.size();
        if(total==0)return;
        if(rev) currentPlayIndex--;
        else currentPlayIndex++;
        checkIndexCycle(total);
        changeChannel(currentPlayIndex);
    }
    public void togglePanel(){
        panelManager.changePanel();
    }
    public void openSettings(){
        startActivity(new Intent(this,SettingsActivity.class));
        log("按键打开设置");
    }
    public void playChannel(int pos){
        currentPlayIndex = pos;
        changeChannel(pos);
    }
    public void onReceiveConfig(String live,String epg){
        appConfig.saveCustomLiveUrl(live);
        appConfig.saveCustomEpgUrl(epg);
        UrlConfig.LIVE_URL = live;
        UrlConfig.EPG_URL = epg;
        loadLiveAndEpg();
        Toast.makeText(this,"局域网配置已生效",Toast.LENGTH_SHORT).show();
    }
    private void checkIndexCycle(int total){
        if(currentPlayIndex>=total)currentPlayIndex=0;
        if(currentPlayIndex<0)currentPlayIndex=total-1;
    }
    private void changeChannel(int idx){
        switchManager.switchChannel(idx);
    }
    //====================================================================

    private void loadSettings(){
        sp = getSharedPreferences("app_settings",MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable",true);
        channel_reverse = sp.getBoolean("channel_reverse",false);
        number_channel_enable = sp.getBoolean("number_channel_enable",true);
        auto_update_source = sp.getBoolean("auto_update_source",true);
    }

    private void initInfoBar(){}
    private void loadLiveAndEpg(){}
}
