package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NetUtil {
    private static volatile NetUtil sInstance;
    private static Context sAppContext;
    private final OkHttpClient mClient;
    
    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;
    private static final long WRITE_TIMEOUT = 10000L;

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private NetUtil() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        Request newRequest = request.newBuilder()
                                .header("Accept-Encoding", "identity")
                                .build();
                        return chain.proceed(newRequest);
                    }
                })
                .build();
    }

    public static NetUtil getInstance() {
        if (sInstance == null) {
            synchronized (NetUtil.class) {
                if (sInstance == null) {
                    sInstance = new NetUtil();
                }
            }
        }
        return sInstance;
    }

    public Headers createCommonHeaders(String url) {
        Map<String, String> headerMap = new HashMap<>();

        String userAgent = "ExoPlayer";
        if (sAppContext != null) {
            SharedPreferences sp = sAppContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            
            // 🟢 优先读取网页后台推送的自定义 UA
            String customUA = sp.getString("custom_user_agent", "");
            if (!TextUtils.isEmpty(customUA)) {
                userAgent = customUA;
            } else {
                String uaMode = sp.getString("user_agent_mode", "exo"); 
                if ("vlc".equals(uaMode)) {
                    userAgent = "VLC/3.0.21 LibVLC/3.0.21";
                }
            }
        }
        
        Log.d("NetUtil", "【UA检测】当前正在使用的请求头 User-Agent: " + userAgent);

        headerMap.put("User-Agent", userAgent);
        headerMap.put("Accept", "*");
        headerMap.put("Connection", "keep-alive");
        headerMap.put("Icy-MetaData", "1"); 
        headerMap.put("Accept-Language", "zh-CN,zh;q=0.9");

        String referer, origin;
        if (url.contains("huya.com") || url.contains("huya.cn")) {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        } else if (url.contains("douyu.com") || url.contains("douyucdn.cn")) {
            referer = "https://www.douyu.com";
            origin = "https://www.douyu.com";
        } else {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        }
        headerMap.put("Referer", referer);
        headerMap.put("Origin", origin);
        return Headers.of(headerMap);
    }

    public Headers createHuyaFixedHeaders() {
        return createCommonHeaders("https://www.huya.com");
    }

    public Response syncGet(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        Call call = mClient.newCall(request);
        return call.execute();
    }

    public String syncGetText(String url) throws IOException {
        try (Response response = syncGet(url)) {
            int code = response.code();
            if (code == 403) {
                throw new IOException("HTTP 403 防盗链拦截 url=" + url);
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("请求失败 code=" + code);
            }
            return response.body().string();
        }
    }

    public OkHttpClient getClient() {
        return mClient;
    }
}
