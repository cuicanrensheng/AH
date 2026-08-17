package com.tv.live;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;

public class ActivityExitManager {
    private static final String TAG = "ActivityExitManager";

    private final MainActivity activity;
    private final Handler mainHandler;
    private AlertDialog exitMenuDialog;

    public ActivityExitManager(MainActivity activity, Handler mainHandler) {
        this.activity = activity;
        this.mainHandler = mainHandler;
    }

    public void showExitMenu() {
        if (exitMenuDialog != null && exitMenuDialog.isShowing()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_exit_menu, null);
        builder.setView(view);

        Button btnRest = view.findViewById(R.id.btn_rest);
        Button btnSettings = view.findViewById(R.id.btn_settings);

        exitMenuDialog = builder.create();

        if (exitMenuDialog != null) {
            if (exitMenuDialog.getWindow() != null) {
                exitMenuDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = exitMenuDialog.getWindow().getAttributes();
                lp.dimAmount = 0.5f;
                exitMenuDialog.getWindow().setAttributes(lp);
                exitMenuDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            exitMenuDialog.setOnDismissListener(dialog -> exitMenuDialog = null);
            exitMenuDialog.show();

            btnRest.setFocusable(true);
            btnRest.setFocusableInTouchMode(true);
            btnSettings.setFocusable(true);
            btnSettings.setFocusableInTouchMode(true);

            btnRest.post(() -> btnRest.requestFocus());
        }

        btnRest.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            activity.finishAffinity();
        });

        btnSettings.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            mainHandler.postDelayed(() -> {
                activity.getSettingsManager().setOpeningSettings(false);
                activity.openSettings();
            }, 100);
        });
    }

    public boolean isExitMenuShowing() {
        return exitMenuDialog != null && exitMenuDialog.isShowing();
    }

    public void dismissExitMenu() {
        if (exitMenuDialog != null) {
            try {
                if (exitMenuDialog.isShowing()) {
                    exitMenuDialog.dismiss();
                }
                exitMenuDialog = null;
            } catch (Exception e) {
                Log.e(TAG, "dismissExitMenu 异常", e);
                exitMenuDialog = null;
            }
        }
    }

    public void cleanup() {
        dismissExitMenu();
    }
}
