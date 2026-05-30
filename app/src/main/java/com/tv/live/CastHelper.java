package com.tv.live;

import android.content.Context;
import android.widget.Toast;

public class CastHelper {
    public static void toggleCast(Context context, OnCastStateChangeListener listener) {
        CastManager cm = CastManager.getInstance(context);
        if (cm.isCasting()) {
            cm.disconnect();
            Toast.makeText(context, "已断开投屏", Toast.LENGTH_SHORT).show();
        } else {
            cm.openCastPicker();
            Toast.makeText(context, "请选择投屏设备", Toast.LENGTH_SHORT).show();
        }
        if (listener != null) listener.onCastStateChanged();
    }

    public interface OnCastStateChangeListener {
        void onCastStateChanged();
    }
}
