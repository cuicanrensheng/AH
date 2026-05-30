package com.tv.live;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView btn_cast;
    private SharedPreferences sp;
    private String currentWebUrl;
    private ServerSocket serverSocket;
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final int PORT = 10481;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().getAttributes().dimAmount = 0.6f;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);

        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        btn_cast = findViewById(R.id.btn_cast);

        updateCastBtn();

// ========== 开机自启开关绑定 ==========
// 加载用户之前保存的状态，让开关打开时显示正确
boolean isAutoStartEnabled = sp.getBoolean("boot_auto_start", false);
sw_boot.setChecked(isAutoStartEnabled);

// 绑定开关事件：用户点击时，保存状态
sw_boot.setOnCheckedChangeListener((buttonView, isChecked) -> {
    sp.edit().putBoolean("boot_auto_start", isChecked).apply();
    Toast.makeText(this, "开机自启" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
});
// ====================================

btn_cast.setOnClickListener(v -> {
    CastHelper.toggleCast(this, this::updateCastBtn);
});

        btn_cast.setOnClickListener(v -> {
            CastHelper.toggleCast(this, this::updateCastBtn);
        });

        findViewById(R.id.btn_check_update).setOnClickListener(v -> {
            UpdateHelper.checkUpdate(this, new UpdateHelper.UpdateCallback() {
                @Override
                public void onNewVersionFound(String versionName, String downloadUrl) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("发现新版本")
                            .setMessage("最新版：" + versionName)
                            .setPositiveButton("立即更新", (d, w) -> {
                                UpdateHelper.downloadAndInstallApk(SettingsActivity.this, downloadUrl);
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                }
                @Override
                public void onNoUpdate() {
                    Toast.makeText(SettingsActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onError(String msg) {
                    Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        });

        loadConfig();
        initListeners();
        currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
        startPushServer();
    }

    private void updateCastBtn() {
        if (CastManager.getInstance(this).isCasting()) {
            btn_cast.setText("断开投屏｜" + CastManager.getInstance(this).getCastDeviceName());
        } else {
            btn_cast.setText("开始投屏");
        }
    }

    private void initListeners() {
        sw_boot.setOnCheckedChangeListener((b, v) -> save("boot_auto_start", v));
        sw_epg.setOnCheckedChangeListener((b, v) -> save("epg_enable", v));
        sw_auto_update.setOnCheckedChangeListener((b, v) -> save("auto_update_source", v));
        sw_reverse.setOnCheckedChangeListener((b, v) -> save("channel_reverse", v));
        sw_num_channel.setOnCheckedChangeListener((b, v) -> save("number_channel_enable", v));

        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url"));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义节目单", "请输入EPG地址", "custom_epg_url"));
        tv_multi_source.setOnClickListener(v -> showHistoryDialog("直播源历史", "live_history"));
        tv_multi_epg.setOnClickListener(v -> showHistoryDialog("节目单历史", "epg_history"));
        tv_qr_code.setOnClickListener(v -> showQRCodeDialog());
    }

    private void save(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    private void loadConfig() {
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
    }

    private void showRatioDialog() {
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(new String[]{"全屏","填充","原始"}, (d,w)->{
                    sp.edit().putString("screen_ratio", new String[]{"全屏","填充","原始"}[w]).apply();
                    Toast.makeText(this,"已设置",Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key,""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定",(d,w)->{
                    String url = ed.getText().toString().trim();
                    if(!url.isEmpty()){
                        sp.edit().putString(key,url).apply();
                        addHistory(key.contains("live")?"live_history":"epg_history",url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消",null)
                .show();
    }

    private void showHistoryDialog(String title, String key) {
        List<String> list = getHistory(key);
        if(list.isEmpty()){
            Toast.makeText(this,"无记录",Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(list.toArray(new String[0]),(d,w)->{
                    String url = list.get(w);
                    sp.edit().putString(key.contains("live")?"custom_live_url":"custom_epg_url",url).apply();
                    addHistory(key.contains("live")?"live_history":"epg_history",url);
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    Toast.makeText(this, "已切换，正在刷新…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭",null)
                .show();
    }

    private List<String> getHistory(String key) {
        String s = sp.getString(key,"");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\\|")));
    }

    private void addHistory(String key, String url) {
        List<String> list = getHistory(key);
        list.remove(url);
        list.add(0,url);
        while(list.size()>10) list.remove(10);
        sp.edit().putString(key,String.join("|",list)).apply();
    }

    private void showQRCodeDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQR(currentWebUrl,250));
        new AlertDialog.Builder(this)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭",null)
                .show();
    }

    private Bitmap createQR(String text, int size) {
        try{
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE,size,size);
            Bitmap bmp = Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);
            for(int x=0;x<size;x++) for(int y=0;y<size;y++)
                bmp.setPixel(x,y,m.get(x,y)? Color.BLACK:Color.WHITE);
            return bmp;
        }catch (Exception e){ return null; }
    }

    private String getDeviceIPAddress() {
        try{
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip&0xFF)+"."+((ip>>8)&0xFF)+"."+((ip>>16)&0xFF)+"."+((ip>>24)&0xFF);
        }catch (Exception e){ return "192.168.1.100"; }
    }

    private void startPushServer() {
        new Thread(()->{
            try{
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                while(!serverSocket.isClosed()){
                    Socket socket = serverSocket.accept();
                    new Thread(()->{
                        try{
                            InputStreamReader r = new InputStreamReader(socket.getInputStream());
                            char[] buf = new char[2048];
                            int len = r.read(buf);
                            JSONObject json = new JSONObject(new String(buf,0,len));
                            handler.post(()->{
                                if(!json.optString("live_url").isEmpty()){
                                    sp.edit().putString("custom_live_url",json.optString("live_url")).apply();
                                    addHistory("live_history",json.optString("live_url"));
                                }
                                if(!json.optString("epg_url").isEmpty()){
                                    sp.edit().putString("custom_epg_url",json.optString("epg_url")).apply();
                                    addHistory("epg_history",json.optString("epg_url"));
                                }
                                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                            });
                            socket.close();
                        }catch (Exception e){}
                    }).start();
                }
            }catch (Exception e){}
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCastBtn();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try{ if(serverSocket!=null) serverSocket.close(); }catch (Exception ignored){}
    }
}
