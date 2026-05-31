import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HuyaLivePlayer {
    private static final String TAG = "HuyaLivePlayer";

    private final OkHttpClient mOkHttpClient;
    private final ExoPlayer mExoPlayer;
    private final BasicCookieJar mCookieJar;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public HuyaLivePlayer(Context context) {
        // 初始化 Cookie 容器
        mCookieJar = new BasicCookieJar();

        // OkHttp 全局配置：Cookie + 防盗链请求头（Referer + UA）
        mOkHttpClient = new OkHttpClient.Builder()
                .cookieJar(mCookieJar)
                .addInterceptor((Interceptor) chain -> {
                    Request original = chain.request();
                    Request newRequest = original.newBuilder()
                            .header("Referer", "https://www.huya.com/")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android TV 10) AppleWebKit/537.36 Chrome/114.0.0 Safari/537.36")
                            .build();
                    return chain.proceed(newRequest);
                })
                .build();

        // 初始化 ExoPlayer（适配电视端）
        mExoPlayer = new ExoPlayer.Builder(context).build();
    }

    /**
     * 传入虎牙直播间ID，自动拉流并播放
     * @param roomId 直播间ID
     */
    public void startPlay(String roomId) {
        // 网络请求放在子线程，避免主线程阻塞
        new Thread(() -> {
            try {
                String apiUrl = "https://www.huya.com/cache.php?m=Live&do=room&roomid=" + roomId;
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .build();

                Response response = mOkHttpClient.newCall(request).execute();
                if (!response.isSuccessful()) {
                    Log.e(TAG, "接口请求失败，状态码：" + response.code());
                    return;
                }

                String jsonStr = response.body().string();
                JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
                JsonObject data = root.getAsJsonObject("data");
                JsonObject streamInfo = data.getAsJsonArray("gameStreamInfoList")
                        .get(0)
                        .getAsJsonObject();

                // 解析直播流核心参数
                String sFlvUrl = streamInfo.get("sFlvUrl").getAsString();
                String sStreamName = streamInfo.get("sStreamName").getAsString();
                String sFlvAntiCode = streamInfo.get("sFlvAntiCode").getAsString();

                // 拼接最终 FLV 播放地址
                final String playUrl = sFlvUrl + "/" + sStreamName + "_" + sFlvAntiCode + ".flv";
                Log.d(TAG, "最终播放地址：" + playUrl);

                // 切回主线程操作播放器
                mMainHandler.post(() -> {
                    DataSource.Factory dataFactory = new OkHttpDataSource.Factory(mOkHttpClient);
                    MediaItem mediaItem = MediaItem.fromUri(playUrl);
                    MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataFactory)
                            .createMediaSource(mediaItem);

                    mExoPlayer.setMediaSource(mediaSource);
                    mExoPlayer.prepare();
                    mExoPlayer.play();
                });

            } catch (IOException e) {
                Log.e(TAG, "网络请求异常", e);
            } catch (Exception e) {
                Log.e(TAG, "数据解析/播放异常", e);
            }
        }).start();
    }

    /**
     * 清空 Cookie（切换直播间调用）
     */
    public void clearCookie() {
        if (mCookieJar != null) {
            mCookieJar.clearCookie();
        }
    }

    /**
     * 暂停播放
     */
    public void pausePlay() {
        if (mExoPlayer != null) {
            mExoPlayer.pause();
        }
    }

    /**
     * 恢复播放
     */
    public void resumePlay() {
        if (mExoPlayer != null) {
            mExoPlayer.play();
        }
    }

    /**
     * 释放播放器资源（页面销毁/退出必须调用，防止内存泄漏）
     */
    public void release() {
        if (mExoPlayer != null) {
            mExoPlayer.stop();
            mExoPlayer.release();
        }
    }

    /**
     * 获取播放器实例，绑定到布局 PlayerView
     */
    public ExoPlayer getExoPlayer() {
        return mExoPlayer;
    }
}
