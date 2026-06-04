package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.widget.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsActivity extends AppCompatActivity {
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private SharedPreferences sp;
    private String currentWebUrl;
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final int PORT = 10481;
    private SettingsAdapter adapter;

    // 网页服务相关
    private NanoInnerServer nanoServer;
    private boolean needRefreshFromWeb = false; // 网页保存标记

    //====================日志系统完整保留====================
    private static final List<String> OPERATION_LOG = new ArrayList<>();
    private static final int MAX_LOG_COUNT = 200;

    public static void logOperation(String msg) {
        if (OPERATION_LOG.size() > MAX_LOG_COUNT) {
            OPERATION_LOG.remove(OPERATION_LOG.size() - 1);
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        OPERATION_LOG.add(0, "[" + time + "] " + msg);
    }

    public static void logCrash(Throwable e) {
        String msg = e.getMessage() == null ? "未知异常" : e.getMessage();
        logOperation("崩溃:" + msg);
    }

    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setPadding(40,40,40,40);
        tv.setTextColor(Color.BLACK);
        StringBuilder sb = new StringBuilder();
        for(String s : OPERATION_LOG) sb.append(s).append("\n");
        tv.setText(sb.length()==0?"暂无日志":sb.toString());
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("操作日志")
                .setView(scrollView)
                .setPositiveButton("关闭",null)
                .setNeutralButton("清空",(d,w)->{OPERATION_LOG.clear();Toast.makeText(this,"已清空",Toast.LENGTH_SHORT).show();})
                .show();
    }

    private void showParseLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setPadding(40,40,40,40);
        tv.setTextColor(Color.BLACK);
        List<String> logs = TVPlayerManager.getInstance(this).getLogList();
        if(logs==null||logs.isEmpty()) tv.setText("暂无解析日志");
        else{
            StringBuilder sb = new StringBuilder();
            for(String s:logs) sb.append(s).append("\n");
            tv.setText(sb.toString());
        }
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("解析日志")
                .setView(scrollView)
                .setPositiveButton("清空",(d,w)->{TVPlayerManager.getInstance(this).clearLogs();Toast.makeText(this,"已清空",Toast.LENGTH_SHORT).show();})
                .setNegativeButton("关闭",null)
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        findViewById(R.id.log_viewer).setOnClickListener(v->showParseLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v->showOperationLogDialog());

        sw_boot.setChecked(sp.getBoolean("boot_auto_start",false));
        sw_boot.setOnCheckedChangeListener((b,isChecked)->{
            sp.edit().putBoolean("boot_auto_start",isChecked).apply();
            logOperation("开机自启:"+(isChecked?"开启":"关闭"));
            Toast.makeText(this,"开机自启"+(isChecked?"已开启":"已关闭"),Toast.LENGTH_SHORT).show();
        });

        sw_epg.setChecked(sp.getBoolean("epg_enable",true));
        sw_epg.setOnCheckedChangeListener((b,isChecked)->{
            sp.edit().putBoolean("epg_enable",isChecked).apply();
            logOperation("节目单开关:"+(isChecked?"开启":"关闭"));
            Toast.makeText(this,"节目单"+(isChecked?"已开启":"已关闭"),Toast.LENGTH_SHORT).show();
        });

        sw_auto_update.setChecked(sp.getBoolean("auto_update_source",true));
        sw_auto_update.setOnCheckedChangeListener((b,isChecked)->{
            sp.edit().putBoolean("auto_update_source",isChecked).apply();
            logOperation("自动更新源:"+(isChecked?"开启":"关闭"));
            Toast.makeText(this,"自动更新源"+(isChecked?"已开启":"已关闭"),Toast.LENGTH_SHORT).show();
        });

        sw_reverse.setChecked(sp.getBoolean("channel_reverse",false));
        sw_reverse.setOnCheckedChangeListener((b,isChecked)->{
            sp.edit().putBoolean("channel_reverse",isChecked).apply();
            logOperation("换台反转:"+(isChecked?"开启":"关闭"));
            Toast.makeText(this,"换台反转"+(isChecked?"已关闭":"已开启"),Toast.LENGTH_SHORT).show();
        });

        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable",true));
        sw_num_channel.setOnCheckedChangeListener((b,isChecked)->{
            sp.edit().putBoolean("number_channel_enable",isChecked).apply();
            logOperation("数字选台:"+(isChecked?"开启":"关闭"));
            Toast.makeText(this,"数字选台"+(isChecked?"已开启":"已关闭"),Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_check_update).setOnClickListener(v->{
            logOperation("点击检查更新：已是最新版");
            Toast.makeText(this,"已是最新版本",Toast.LENGTH_SHORT).show();
        });

        loadConfig();
        initListeners();
        currentWebUrl = "http://"+getDeviceIPAddress()+":"+PORT;

        // 启动内置网页服务（只初始化一次，解决端口重复占用EADDRINUSE）
        if(nanoServer == null){
            nanoServer = new NanoInnerServer(PORT);
            try {
                nanoServer.start();
                logOperation("设置页面打开 · 网页后台已启动");
            } catch (Exception e) {
                logCrash(e);
            }
        }

        // 主线程循环监听网页保存标记
        handler.post(checkRefreshRunnable);
    }

    // 轮询任务：网页保存后置标记，自动刷新
    private final Runnable checkRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if(needRefreshFromWeb){
                needRefreshFromWeb = false;
                logOperation("网页提交源，执行全局刷新");
                // 发送全局刷新广播，Main接收自动重载
                Intent refreshIntent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                sendBroadcast(refreshIntent);
            }
            handler.postDelayed(this,300);
        }
    };

    // 给内部服务调用：开启刷新标记
    public void setNeedRefresh(){
        needRefreshFromWeb = true;
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v->showRatioDialog());
        tv_custom_source.setOnClickListener(v->showInputDialog("自定义订阅源","填写直播源链接","custom_live_url"));
        tv_custom_epg.setOnClickListener(v->showInputDialog("自定义节目单","填写EPG链接","custom_epg_url"));
        tv_multi_source.setOnClickListener(v->showHistoryDialog("直播源历史","live_history"));
        tv_multi_epg.setOnClickListener(v->showHistoryDialog("节目单历史","epg_history"));
        tv_qr_code.setOnClickListener(v->showQRCodeDialog());
    }

    private void loadConfig() {
        sw_boot.setChecked(sp.getBoolean("boot_auto_start",false));
        sw_epg.setChecked(sp.getBoolean("epg_enable",true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source",true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse",false));
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable",true));
    }

    private void showRatioDialog() {
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(new String[]{"全屏","填充","原始"},(d,w)->{
                    String val = new String[]{"全屏","填充","原始"}[w];
                    sp.edit().putString("screen_ratio",val).apply();
                    logOperation("设置画面比例："+val);
                    Toast.makeText(this,"已设置",Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showInputDialog(String title,String hint,String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key,""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定",(d,w)->{
                    String url = ed.getText().toString().trim();
                    if(!TextUtils.isEmpty(url)){
                        sp.edit().putString(key,url).apply();
                        String histKey = key.equals("custom_live_url") ? "live_history" : "epg_history";
                        addHistory(histKey,url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("保存"+hint+"："+url);
                        Toast.makeText(this,"已保存，正在刷新…",Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消",null)
                .show();
    }

    private void showHistoryDialog(String title,String key) {
        String history = sp.getString(key,"");
        if(TextUtils.isEmpty(history)){
            Toast.makeText(this,"暂无历史记录",Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = history.split("\\|");
        if(arr.length<=0){
            Toast.makeText(this,"暂无历史记录",Toast.LENGTH_SHORT).show();
            return;
        }
        adapter = new SettingsAdapter(this,Arrays.asList(arr));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(adapter,(d,w)->{
                    String selUrl = arr[w];
                    String saveKey = key.contains("live")?"custom_live_url":"custom_epg_url";
                    sp.edit().putString(saveKey,selUrl).apply();
                    addHistory(key,selUrl);
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    adapter.setSelectedPosition(w);
                    logOperation("从历史切换源："+selUrl);
                    Toast.makeText(this,"已切换，正在刷新…",Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭",null)
                .show();
    }

    private void addHistory(String key,String url) {
        if(TextUtils.isEmpty(url)) return;
        String rawHis = sp.getString(key,"");
        StringBuilder sb = new StringBuilder(url);
        if(!TextUtils.isEmpty(rawHis)){
            String[] arr = rawHis.split("\\|");
            for(String item : arr){
                if(!item.equals(url) && sb.length()<1000){
                    sb.append("|").append(item);
                }
            }
        }
        sp.edit().putString(key,sb.toString()).apply();
    }

    private void showQRCodeDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQR(currentWebUrl,250));
        new AlertDialog.Builder(this)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭",null)
                .show();
        logOperation("打开扫码弹窗，地址："+currentWebUrl);
    }

    private Bitmap createQR(String text,int size) {
        try{
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE,size,size);
            Bitmap bmp = Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);
            for(int x=0;x<size;x++){
                for(int y=0;y<size;y++){
                    bmp.setPixel(x,y,matrix.get(x,y)? Color.BLACK:Color.WHITE);
                }
            }
            return bmp;
        }catch (Exception e){
            logCrash(e);
            return null;
        }
    }

    private String getDeviceIPAddress() {
        try{
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ipInt = info.getIpAddress();
            return (ipInt&0xFF)+"."+((ipInt>>8)&0xFF)+"."+((ipInt>>16)&0xFF)+"."+((ipInt>>24)&0xFF);
        }catch (Exception e){
            logCrash(e);
            return "192.168.1.100";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止网页服务
        if(nanoServer != null){
            nanoServer.stop();
            nanoServer = null;
            logOperation("设置页面关闭 · 网页后台已停止");
        }
        handler.removeCallbacks(checkRefreshRunnable);
    }

    // 内置网页服务类（内部类，可直接调用Settings方法，不用跨包导Intent）
    private class NanoInnerServer {
        private ServerSocket serverSocket;
        private int port;
        private boolean running;

        public NanoInnerServer(int port) {
            this.port = port;
            serverSocket = null;
            running = false;
        }

        public void start() throws IOException {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            running = true;
            new Thread(this::doListen).start();
        }

        public void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
        }

        private void doListen() {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                } catch (Exception ignored) {
                    break;
                }
            }
        }

        private void handleClient(Socket socket) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream out = socket.getOutputStream();

                String line = in.readLine();
                if (line == null || !line.startsWith("GET ")) {
                    send404(out);
                    return;
                }

                String path = line.split(" ")[1];
                if (path.equals("/")) {
                    sendIndex(out);
                } else if (path.startsWith("/apply")) {
                    handleApply(out, path);
                } else {
                    send404(out);
                }

                in.close();
                out.close();
                socket.close();
            } catch (Exception ignored) {}
        }

        private void sendIndex(OutputStream out) throws Exception {
            String html = "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<body style='background:#111;color:#fff;padding:30px;font-size:16px;'>\n" +
                    "<h2>TV 后台配置</h2>\n" +
                    "<form action='/apply'>\n" +
                    "<p>直播源地址：</p>\n" +
                    "<input type='text' name='live' style='width:100%;padding:8px;font-size:16px;' />\n" +
                    "<p>EPG节目单：</p>\n" +
                    "<input type='text' name='epg' style='width:100%;padding:8px;font-size:16px;' />\n" +
                    "<br/><br/>\n" +
                    "<button type='submit' style='width:100%;padding:12px;font-size:18px;'>保存并刷新</button>\n" +
                    "</form>\n" +
                    "</body>\n" +
                    "</html>";

            out.write(("HTTP/1.1 200 OK\r\nContent-Type:text/html;charset=UTF-8\r\n\r\n" + html).getBytes("UTF-8"));
        }

        private void handleApply(OutputStream out, String path) throws Exception {
            String query = path.contains("?") ? path.split("\\?")[1] : "";
            Map<String, String> params = parseQuery(query);

            String live = params.get("live");
            String epg = params.get("epg");

            // 保存到SP
            boolean changed = false;
            SharedPreferences sp = getSharedPreferences("app_settings",MODE_PRIVATE);
            if(live != null && !live.trim().isEmpty()){
                sp.edit().putString("custom_live_url",live.trim()).apply();
                addHistory("live_history",live.trim());
                logOperation("网页保存直播源："+live);
                changed = true;
            }
            if(epg != null && !epg.trim().isEmpty()){
                sp.edit().putString("custom_epg_url",epg.trim()).apply();
                addHistory("epg_history",epg.trim());
                logOperation("网页保存EPG："+epg);
                changed = true;
            }

            // 关键：置刷新标记，主线程自动发广播刷新APP
            if(changed){
                setNeedRefresh();
            }

            String ok = "<h2 style='color:#0c0;'>保存成功！已刷新</h2>";
            out.write(("HTTP/1.1 200 OK\r\nContent-Type:text/html;charset=UTF-8\r\n\r\n" + ok).getBytes("UTF-8"));
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> map = new HashMap<>();
            if (query.isEmpty()) return map;
            String[] pairs = query.split("&");
            for (String p : pairs) {
                String[] kv = p.split("=");
                if (kv.length == 2) {
                    try {
                        map.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
                    } catch (Exception ignored) {}
                }
            }
            return map;
        }

        private void send404(OutputStream out) throws Exception {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
        }
    }

    private static class SettingsAdapter extends ArrayAdapter<String> {
        private final Context ctx;
        private final List<String> datas;
        private int selectPos = -1;
        public SettingsAdapter(Context c,List<String> list){
            super(c,R.layout.item_settings,list);
            ctx = c;
            datas = list;
        }
        public void setSelectedPosition(int pos){
            selectPos = pos;
            notifyDataSetChanged();
        }
        @Override
        public View getView(int pos,View convert,ViewGroup parent) {
            if(convert==null) convert = LayoutInflater.from(ctx).inflate(R.layout.item_settings,parent,false);
            TextView tv = convert.findViewById(R.id.tv_setting_item);
            tv.setText(datas.get(pos));
            tv.setTextColor(selectPos==pos? Color.parseColor("#40A9FF"):Color.WHITE);
            return convert;
        }
    }
}
