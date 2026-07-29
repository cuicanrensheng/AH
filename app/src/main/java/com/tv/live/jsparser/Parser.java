package com.tv.live.jsparser;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Parser {
    private static final String SALT = "com.yuntu.a8.info.encrypt";
    private static Context appContext;
    private static String extendJsDir = null;
    private static String jsDir = "";
    private static Set<String> jsMap = Collections.synchronizedSet(new HashSet<>());
    private static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    public static void init(Context context) {
        JsLayer.init(context);
        setJsDir(context);
    }

    public static boolean isInit() {
        return JsLayer.isInit();
    }

    public static void parse(final String str, final JsLayer.JsCallback jsCallback) {
        try {
            JsLayer.evaluate(getParseJs("yt_source_root.js") + ("getParser('" + str + "');"), new JsLayer.JsCallback() {
                @Override
                public void onResult(String str2) {
                    try {
                        List<String> parseArray = new ArrayList<>();
                        JSONArray array = new JSONArray(str2);
                        for (int i = 0; i < array.length(); i++) {
                            parseArray.add(array.getString(i));
                        }
                        if (!parseArray.isEmpty()) {
                            JsLayer.evaluate(Parser.getParseJs(parseArray.get(0)) + ";getParseResult('" + parseArray.get(1) + "','" + str + "');", jsCallback);
                        } else {
                            jsCallback.onError("no js parser");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        jsCallback.onError("java exception");
                    }
                }

                @Override
                public void onError(String str2) {
                    jsCallback.onError("js error");
                }
            });
        } catch (Exception unused) {
            jsCallback.onError("java exception");
        }
    }

    private static String getParseJs(String str) {
        String str2 = getJs(str);
        if (TextUtils.isEmpty(str2)) {
            String decryptJs = getDecryptJs(str);
            jsMap.add(str);
            return decryptJs;
        }
        return str2;
    }

    private static String getJs(String str) {
        try {
            File file = new File(getExtendJsDir() + str);
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[(int) file.length()];
                fis.read(buffer);
                fis.close();
                return new String(buffer, "UTF-8");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private static String getDecryptJs(String str) {
        String readFileToString = "";
        try {
            File file = new File(jsDir + str);
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[(int) file.length()];
                fis.read(buffer);
                fis.close();
                readFileToString = new String(buffer, "UTF-8");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (TextUtils.isEmpty(readFileToString)) {
            readFileToString = JsLayer.assetFileToString("js/" + str);
        }

        String decrypt = decrypt(readFileToString, SALT);
        return decrypt;
    }

    public static String decrypt(String str, String str2) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        if (TextUtils.isEmpty(str2)) {
            str2 = SALT;
        }
        try {
            byte[] decode = Base64.decode(str, 0);
            StringBuilder sb = new StringBuilder();
            for (byte b : decode) {
                sb.append((char) ((b - 1) % 256));
            }
            return new String(Base64.decode(sb.toString(), 0)).replace(str2, "");
        } catch (Exception e) {
            e.printStackTrace();
            return str;
        }
    }

    public static void updatePlugin(final Context context) {
        String url = "http://nowtv.xiaoyouzb.net/list_android_js_meta_v2.php?version=4.1.6";
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String str2 = response.body().string();
                    JSONObject parseObject = new JSONObject(str2);
                    if (parseObject.getInt("error_code") != 0) {
                        return;
                    }
                    JSONArray jSONArray = parseObject.getJSONArray("data");
                    for (int i = 0; i < jSONArray.length(); i++) {
                        JSONObject pluginData = jSONArray.getJSONObject(i);
                        getUpdateFile(context, pluginData.getString("url"), pluginData.getString("md5"));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void getUpdateFile(final Context context, final String url, final String md5) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String fileName = url.substring(url.lastIndexOf("encrypt_") + 8);
                    String filePath = jsDir + fileName;
                    FileOutputStream fos = new FileOutputStream(new File(filePath));
                    fos.write(response.body().bytes());
                    fos.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void setJsDir(Context context) {
        appContext = context;
        jsDir = context.getFilesDir().getAbsolutePath() + File.separator + "js" + File.separator + "parser" + File.separator;
        File dir = new File(jsDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private static String getExtendJsDir() {
        if (extendJsDir == null && appContext != null) {
            extendJsDir = appContext.getExternalFilesDir(null) + File.separator + "parser" + File.separator;
            File dir = new File(extendJsDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }
        return extendJsDir;
    }
}