package com.tv.live.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;

public class HuyaWebViewParser {
    private static final String TAG = "HuyaWebViewParser";
    private static final long TIMEOUT_MS = 30000;
    private static final String MOBILE_URL = "https://m.huya.com/%d";
    
    private WeakReference<Context> mContextRef;
    private WebView mWebView;
    private OnParseResultListener mListener;
    private Handler mTimeoutHandler;
    private boolean mResultReceived = false;
    
    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl);
        void onFailed(String errorMsg);
    }
    
    public HuyaWebViewParser(Context context) {
        mContextRef = new WeakReference<>(context);
    }
    
    public void parse(int roomId, OnParseResultListener listener) {
        mListener = listener;
        mResultReceived = false;
        
        Context context = mContextRef.get();
        if (context == null) {
            listener.onFailed("Context为空");
            return;
        }
        
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (mWebView != null) {
                    mWebView.stopLoading();
                    mWebView.destroy();
                }
                
                mWebView = new WebView(context);
                mWebView.setVisibility(android.view.View.INVISIBLE);
                
                WebSettings settings = mWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
                settings.setUserAgentString("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148");
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                
                mWebView.addJavascriptInterface(new HuyaJSInterface(), "huyaParser");
                
                mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        Log.d(TAG, "页面加载完成: " + url);
                        injectJavaScript();
                    }
                    
                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        super.onReceivedError(view, errorCode, description, failingUrl);
                        Log.d(TAG, "页面加载错误: " + errorCode + ", " + description);
                        if (!mResultReceived) {
                            postFailed("页面加载失败: " + description);
                        }
                    }
                });
                
                String url = String.format(MOBILE_URL, roomId);
                Log.d(TAG, "开始加载页面: " + url);
                mWebView.loadUrl(url);
                
                mTimeoutHandler = new Handler(Looper.getMainLooper());
                mTimeoutHandler.postDelayed(() -> {
                    if (!mResultReceived) {
                        Log.d(TAG, "获取播放地址超时");
                        postFailed("获取播放地址超时");
                        cleanup();
                    }
                }, TIMEOUT_MS);
                
            } catch (Exception e) {
                Log.d(TAG, "创建WebView失败: " + e.getMessage());
                listener.onFailed("创建WebView失败: " + e.getMessage());
            }
        });
    }
    
    private void injectJavaScript() {
        if (mWebView == null) return;
        
        String js = "(function() {" +
            "var result = {};" +
            "result.hls = '';" +
            "result.flv = '';" +
            
            "try {" +
                "if (window.hyPlayerConfig && window.hyPlayerConfig.stream) {" +
                    "var stream = window.hyPlayerConfig.stream;" +
                    "if (stream.data && stream.data.length > 0) {" +
                        "var liveInfo = stream.data[0];" +
                        "if (liveInfo.gameLiveInfo && liveInfo.gameLiveInfo.liveStreamInfo) {" +
                            "var streamInfo = liveInfo.gameLiveInfo.liveStreamInfo;" +
                            "if (streamInfo.sHlsUrl) result.hls = streamInfo.sHlsUrl;" +
                            "if (streamInfo.sFlvUrl) result.flv = streamInfo.sFlvUrl;" +
                        "}" +
                    "}" +
                    "if (stream.hls) result.hls = stream.hls;" +
                    "if (stream.flv) result.flv = stream.flv;" +
                "}" +
            "} catch(e) {}" +
            
            "try {" +
                "if (window.__INITIAL_STATE__) {" +
                    "var state = window.__INITIAL_STATE__;" +
                    "if (state.room && state.room.stream) {" +
                        "var roomStream = state.room.stream;" +
                        "if (roomStream.hls) result.hls = roomStream.hls;" +
                        "if (roomStream.flv) result.flv = roomStream.flv;" +
                    "}" +
                "}" +
            "} catch(e) {}" +
            
            "try {" +
                "if (window.HNF_GLOBAL_INIT && window.HNF_GLOBAL_INIT.stream) {" +
                    "var hnfStream = window.HNF_GLOBAL_INIT.stream;" +
                    "if (hnfStream.hls) result.hls = hnfStream.hls;" +
                    "if (hnfStream.flv) result.flv = hnfStream.flv;" +
                "}" +
            "} catch(e) {}" +
            
            "try {" +
                "var html = document.documentElement.innerHTML;" +
                "var hlsMatch = html.match(/sHlsUrl[^,]+\"([^\"]+)\"/);" +
                "if (hlsMatch && hlsMatch[1]) result.hls = hlsMatch[1];" +
                "var flvMatch = html.match(/sFlvUrl[^,]+\"([^\"]+)\"/);" +
                "if (flvMatch && flvMatch[1]) result.flv = flvMatch[1];" +
                "var m3u8Match = html.match(/https?:\\/\\/[^\"'\\s,]+\\.m3u8[^\"'\\s,]*/);" +
                "if (m3u8Match && !result.hls) result.hls = m3u8Match[0];" +
                "var flvHttpMatch = html.match(/https?:\\/\\/[^\"'\\s,]+\\.flv[^\"'\\s,]*/);" +
                "if (flvHttpMatch && !result.flv) result.flv = flvHttpMatch[0];" +
            "} catch(e) {}" +
            
            "window.huyaParser.onResult(JSON.stringify(result));" +
            "})();";
        
        try {
            mWebView.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.d(TAG, "执行JS失败: " + e.getMessage());
        }
    }
    
    private class HuyaJSInterface {
        @JavascriptInterface
        public void onResult(String json) {
            if (mResultReceived) return;
            mResultReceived = true;
            
            if (mTimeoutHandler != null) {
                mTimeoutHandler.removeCallbacksAndMessages(null);
            }
            
            Log.d(TAG, "收到JS返回结果: " + json);
            
            String hlsUrl = "";
            String flvUrl = "";
            
            try {
                org.json.JSONObject result = new org.json.JSONObject(json);
                hlsUrl = result.optString("hls", "");
                flvUrl = result.optString("flv", "");
            } catch (Exception e) {
                Log.d(TAG, "解析JS返回结果失败: " + e.getMessage());
            }
            
            if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                Log.d(TAG, "获取播放地址成功: hls=" + hlsUrl + ", flv=" + flvUrl);
                postSuccess(hlsUrl, flvUrl);
            } else {
                Log.d(TAG, "JS返回空播放地址");
                postFailed("未获取到播放地址");
            }
            
            cleanup();
        }
    }
    
    private void postSuccess(final String hlsUrl, final String flvUrl) {
        if (mListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> mListener.onSuccess(hlsUrl, flvUrl));
        }
    }
    
    private void postFailed(final String errorMsg) {
        if (mListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> mListener.onFailed(errorMsg));
        }
    }
    
    public void cleanup() {
        if (mTimeoutHandler != null) {
            mTimeoutHandler.removeCallbacksAndMessages(null);
            mTimeoutHandler = null;
        }
        
        if (mWebView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                mWebView.stopLoading();
                mWebView.removeJavascriptInterface("huyaParser");
                mWebView.destroy();
                mWebView = null;
            });
        }
    }
}