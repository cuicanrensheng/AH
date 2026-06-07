package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import java.util.List;

public class LiveSourceLoader {
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;

    public interface LoadCallback {
        void onSuccess(List<Channel> channels);
        void onError(String errorMsg);
    }

    private LiveSourceLoader(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static LiveSourceLoader getInstance(Context context) {
        if (instance == null) {
            instance = new LiveSourceLoader(context.getApplicationContext());
        }
        return instance;
    }

    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                List<Channel> channels = PlaylistParser.parse(UrlConfig.LIVE_URL);
                mainHandler.post(() -> callback.onSuccess(channels));
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }
}
