package com.tv.live;

import android.content.Context;
import android.media.MediaRouter;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

public class CastManager {
    private static CastManager instance;
    private final MediaRouter mediaRouter;
    private MediaRouter.RouteInfo currentRoute;
    private final Context appContext;

    private CastManager(Context context) {
        appContext = context.getApplicationContext();
        mediaRouter = (MediaRouter) appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
    }

    public static CastManager getInstance(Context context) {
        if (instance == null) {
            instance = new CastManager(context);
        }
        return instance;
    }

    public void openCastPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mediaRouter.openRouteSelector(appContext);
        }
    }

    public void selectRoute(MediaRouter.RouteInfo route) {
        if (currentRoute != null) currentRoute.disconnect();
        currentRoute = route;
        if (route != null) route.connect();
    }

    public void disconnect() {
        if (currentRoute != null) {
            currentRoute.disconnect();
            currentRoute = null;
        }
    }

    public boolean isCasting() {
        return currentRoute != null && currentRoute.isConnected();
    }

    public String getCastDeviceName() {
        if (isCasting()) return currentRoute.getName().toString();
        return "未连接";
    }
}
