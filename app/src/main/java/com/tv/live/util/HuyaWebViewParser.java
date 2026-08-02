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
    private static final long TIMEOUT_MS = 45000;
    private static final long RETRY_INTERVAL_MS = 1500;
    private static final int MAX_RETRIES = 10;
    private static final String PC_URL = "https://www.huya.com/%d";

    private WeakReference<Context> mContextRef;
    private WebView mWebView;
    private OnParseResultListener mListener;
    private Handler mTimeoutHandler;
    private Handler mRetryHandler;
    private boolean mResultReceived = false;
    private int mRetryCount = 0;

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
        mRetryCount = 0;

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
                settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setLoadWithOverviewMode(true);
                settings.setUseWideViewPort(true);

                mWebView.addJavascriptInterface(new HuyaJSInterface(), "huyaParser");

                mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        Log.d(TAG, "页面加载完成: " + url);
                        scheduleRetry();
                    }

                    @Override
                    public void onLoadResource(WebView view, String url) {
                        super.onLoadResource(view, url);
                        // 页面加载过程中也尝试注入JS，越早获取到流地址越好
                        if (!mResultReceived && mRetryCount == 0) {
                            Log.d(TAG, "资源加载中，尝试提前注入JS");
                            doInjectAndRetry();
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        super.onReceivedError(view, errorCode, description, failingUrl);
                        Log.d(TAG, "页面加载错误: " + errorCode + ", " + description + ", url: " + failingUrl);
                        // 即使页面加载出错，也尝试注入JS（可能部分DOM已加载）
                        if (!mResultReceived && errorCode != -1) {
                            Log.d(TAG, "页面出错，尝试注入JS");
                            injectJavaScript();
                        }
                    }
                });

                String url = String.format(PC_URL, roomId);
                Log.d(TAG, "开始加载PC端页面: " + url);
                mWebView.loadUrl(url);

                // 初始化Handler（在loadUrl之后，因为loadUrl会触发onLoadResource）
                mTimeoutHandler = new Handler(Looper.getMainLooper());
                mRetryHandler = new Handler(Looper.getMainLooper());
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

    private void scheduleRetry() {
        if (mResultReceived) return;
        if (mRetryCount >= MAX_RETRIES) {
            Log.d(TAG, "已达最大重试次数，仍未获取到播放地址");
            postFailed("未获取到播放地址");
            cleanup();
            return;
        }

        if (mRetryHandler == null) {
            mRetryHandler = new Handler(Looper.getMainLooper());
        }

        long delay = mRetryCount == 0 ? 1500 : RETRY_INTERVAL_MS;
        mRetryHandler.postDelayed(() -> {
            if (mResultReceived) return;
            mRetryCount++;
            Log.d(TAG, "第" + mRetryCount + "次尝试提取播放地址");
            injectJavaScript();
        }, delay);
    }

    /**
     * 立即执行JS注入并设置递归重试
     * 用于 onLoadResource 中提前尝试获取流地址
     */
    private void doInjectAndRetry() {
        if (mResultReceived) return;
        injectJavaScript();
        mRetryCount++;
        if (mRetryCount < MAX_RETRIES && mRetryHandler != null) {
            mRetryHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mResultReceived) return;
                    Log.d(TAG, "重试注入JS #" + (mRetryCount + 1));
                    injectJavaScript();
                    mRetryCount++;
                    if (mRetryCount < MAX_RETRIES && !mResultReceived && mRetryHandler != null) {
                        mRetryHandler.postDelayed(this, RETRY_INTERVAL_MS);
                    }
                }
            }, RETRY_INTERVAL_MS);
        }
    }

    private void injectJavaScript() {
        if (mWebView == null || mResultReceived) return;

        String js = "(function() {" +
            "var result = {};" +
            "result.hls = '';" +
            "result.flv = '';" +
            "result.found = false;" +

            "try {" +
                "if (window.hyPlayerConfig && window.hyPlayerConfig.stream) {" +
                    "var stream = window.hyPlayerConfig.stream;" +
                    "if (stream.data && stream.data.length > 0) {" +
                        "var dataItem = stream.data[0];" +
                        "if (dataItem.gameLiveInfo && dataItem.gameLiveInfo.liveStreamInfo) {" +
                            "var lsi = dataItem.gameLiveInfo.liveStreamInfo;" +
                            "if (lsi.sHlsUrl && lsi.sHlsAntiCode) { result.hls = lsi.sHlsUrl + '?' + lsi.sHlsAntiCode; result.found = true; }" +
                            "else if (lsi.sHlsUrl) { result.hls = lsi.sHlsUrl; result.found = true; }" +
                            "if (lsi.sFlvUrl && lsi.sFlvAntiCode) { result.flv = lsi.sFlvUrl + '?' + lsi.sFlvAntiCode; result.found = true; }" +
                            "else if (lsi.sFlvUrl) { result.flv = lsi.sFlvUrl; result.found = true; }" +
                        "}" +
                    "}" +
                "}" +
            "} catch(e) {}" +

            "try {" +
                "var scripts = document.querySelectorAll('script');" +
                "for (var i = 0; i < scripts.length; i++) {" +
                    "var txt = scripts[i].textContent || '';" +
                    "if (txt.indexOf('sHlsUrl') > -1 || txt.indexOf('liveStreamInfo') > -1) {" +
                        "var hlsMatch = txt.match(/\"sHlsUrl\"\\s*:\\s*\"([^\"]+)\"/);" +
                        "if (hlsMatch && hlsMatch[1] && !result.hls) { result.hls = hlsMatch[1]; result.found = true; }" +
                        "var flvMatch = txt.match(/\"sFlvUrl\"\\s*:\\s*\"([^\"]+)\"/);" +
                        "if (flvMatch && flvMatch[1] && !result.flv) { result.flv = flvMatch[1]; result.found = true; }" +
                        "var antiMatch = txt.match(/\"sHlsAntiCode\"\\s*:\\s*\"([^\"]+)\"/);" +
                        "if (antiMatch && antiMatch[1] && result.hls && result.hls.indexOf('?') === -1) { result.hls = result.hls + '?' + antiMatch[1]; }" +
                    "}" +
                "}" +
            "} catch(e) {}" +

            "try {" +
                "var html = document.documentElement.innerHTML;" +
                "if (!result.hls) {" +
                    "var m3u8Match = html.match(/https?:\\/\\/[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*/);" +
                    "if (m3u8Match && m3u8Match[0]) { result.hls = m3u8Match[0]; result.found = true; }" +
                "}" +
                "if (!result.flv) {" +
                    "var flvMatch2 = html.match(/https?:\\/\\/[^\"'\\s<>]+\\.flv[^\"'\\s<>]*/);" +
                    "if (flvMatch2 && flvMatch2[0]) { result.flv = flvMatch2[0]; result.found = true; }" +
                "}" +
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

            Log.d(TAG, "第" + mRetryCount + "次JS返回: " + json.substring(0, Math.min(200, json.length())));

            String hlsUrl = "";
            String flvUrl = "";
            boolean found = false;

            try {
                org.json.JSONObject result = new org.json.JSONObject(json);
                hlsUrl = result.optString("hls", "");
                flvUrl = result.optString("flv", "");
                found = result.optBoolean("found", false);
            } catch (Exception e) {
                Log.d(TAG, "解析JS返回结果失败: " + e.getMessage());
            }

            if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                mResultReceived = true;
                if (mTimeoutHandler != null) {
                    mTimeoutHandler.removeCallbacksAndMessages(null);
                }
                if (mRetryHandler != null) {
                    mRetryHandler.removeCallbacksAndMessages(null);
                }
                Log.d(TAG, "获取播放地址成功: hls=" + hlsUrl + ", flv=" + flvUrl);
                postSuccess(hlsUrl, flvUrl);
                cleanup();
            } else {
                if (mRetryCount < MAX_RETRIES) {
                    scheduleRetry();
                } else {
                    mResultReceived = true;
                    Log.d(TAG, "所有重试完成，仍未获取到播放地址");
                    postFailed("未获取到播放地址");
                    cleanup();
                }
            }
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
        if (mRetryHandler != null) {
            mRetryHandler.removeCallbacksAndMessages(null);
            mRetryHandler = null;
        }
        if (mWebView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    mWebView.stopLoading();
                    mWebView.removeJavascriptInterface("huyaParser");
                    mWebView.destroy();
                } catch (Exception e) {}
                mWebView = null;
            });
        }
    }
}