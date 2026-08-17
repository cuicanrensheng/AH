package com.tv.live;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.manager.PlayerControlManager;

import java.util.List;

public class ActivityKeyHandler {
    private static final String TAG = "ActivityKeyHandler";

    private static final long SEEK_REPEAT_DELAY_MS = 400;
    private static final long SEEK_REPEAT_INTERVAL_MS = 200;
    private static final long OK_LONG_PRESS_DURATION = 1500;

    private final MainActivity activity;
    private final Handler mainHandler;

    private final StringBuilder numberInputBuffer = new StringBuilder();
    private final Runnable numberInputConfirmTask;

    private int longPressKeyCode = -1;
    private final Runnable longPressSeekRunnable;

    private boolean okKeyLongPressed = false;
    private boolean okKeyTriggered = false;
    private long okKeyDownTime = 0;

    private boolean panelOpenOnBackDown = false;

    public ActivityKeyHandler(MainActivity activity, Handler mainHandler) {
        this.activity = activity;
        this.mainHandler = mainHandler;

        this.numberInputConfirmTask = () -> confirmNumberInputJump();

        this.longPressSeekRunnable = new Runnable() {
            @Override
            public void run() {
                if (longPressKeyCode == -1 || !activity.isInCatchUpMode()) return;
                PlayerControlManager playerControlManager = activity.getPlayerControlManager();
                if (playerControlManager == null) {
                    longPressKeyCode = -1;
                    return;
                }
                if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    playerControlManager.seekBackward();
                } else if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    playerControlManager.seekForward();
                }
                mainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
            }
        };
    }

    public static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == 100;
    }

    public static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_HELP
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == 101;
    }

    public static boolean isChannelUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    public static boolean isChannelDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        ChannelPanelController channelPanelController = activity.getChannelPanelController();
        TVPlayerManager playerManager = activity.getPlayerManager();

        if (channelPanelController == null || playerManager == null || event == null) {
            return activity.superDispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean panelOpen;
        try {
            panelOpen = channelPanelController.isPanelOpen();
        } catch (Exception e) {
            Log.e(TAG, "isPanelOpen 异常: " + e.getMessage(), e);
            panelOpen = false;
        }

        try {
            Log.d("KEY_DEBUG", "keyCode=" + keyCode + " action=" + action + " repeat=" + event.getRepeatCount());

            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (isMenuKey(keyCode)) {
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        activity.openSettings();
                        return true;
                    }

                    if (isOkKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    }

                    if (isChannelUpKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchUp();
                            return true;
                        }
                    } else if (isChannelDownKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchDown();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (activity.isInCatchUpMode()) {
                            PlayerControlManager playerControlManager = activity.getPlayerControlManager();
                            if (playerControlManager != null) {
                                if (event.getRepeatCount() == 0) {
                                    playerControlManager.seekBackward();
                                    longPressKeyCode = keyCode;
                                    mainHandler.removeCallbacks(longPressSeekRunnable);
                                    mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                                }
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (activity.isInCatchUpMode()) {
                            PlayerControlManager playerControlManager = activity.getPlayerControlManager();
                            if (playerControlManager != null) {
                                if (event.getRepeatCount() == 0) {
                                    playerControlManager.seekForward();
                                    longPressKeyCode = keyCode;
                                    mainHandler.removeCallbacks(longPressSeekRunnable);
                                    mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                                }
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            activity.openSettings();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (playerManager.isPlaying()) {
                            playerManager.pause();
                        } else {
                            playerManager.resume();
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                        playerManager.pause();
                        return true;
                    } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                        handleNumberKey(keyCode);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        panelOpenOnBackDown = panelOpen;
                        Log.d("KEY_DEBUG", "Back DOWN: panelOpen=" + panelOpen + ", panelOpenOnBackDown=" + panelOpenOnBackDown);
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        if (activity.isSettingsDialogShowing()) {
                            return activity.superDispatchKeyEvent(event);
                        }
                        return true;
                    }
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (longPressKeyCode == keyCode) {
                        longPressKeyCode = -1;
                        mainHandler.removeCallbacks(longPressSeekRunnable);
                    }
                }
                if (isOkKey(keyCode)) {
                    okKeyLongPressed = false;
                    okKeyTriggered = false;
                    okKeyDownTime = 0;
                    if (!panelOpen) {
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    Log.d("KEY_DEBUG", "Back UP: panelOpenOnBackDown=" + panelOpenOnBackDown + ", panelOpen=" + panelOpen);
                    if (activity.isSettingsDialogShowing()) {
                        return activity.superDispatchKeyEvent(event);
                    }
                    if (!panelOpenOnBackDown && !panelOpen) {
                        Log.d("KEY_DEBUG", "Calling onBackPressed()");
                        activity.performBackPressed();
                    }
                    return true;
                }
            }

            if (panelOpen && activity.hasPanelLayout()) {
                if (activity.dispatchPanelKeyEvent(event)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatchKeyEvent 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
        }

        return activity.superDispatchKeyEvent(event);
    }

    private void handleNumberKey(int keyCode) {
        List<Channel> channelSourceList = activity.getChannelSourceList();
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (!activity.isNumberChannelEnabled()) return;

        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;

        mainHandler.removeCallbacks(numberInputConfirmTask);
        numberInputBuffer.append(num);

        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }

        InfoDisplayManager infoDisplayManager = activity.getInfoDisplayManager();
        if (infoDisplayManager != null) {
            infoDisplayManager.showChannelNumInput(numberInputBuffer.toString());
        }

        mainHandler.postDelayed(numberInputConfirmTask, 1500);
    }

    private void confirmNumberInputJump() {
        if (numberInputBuffer.length() == 0) return;

        try {
            int channelNum = Integer.parseInt(numberInputBuffer.toString());
            numberInputBuffer.setLength(0);

            if (channelNum <= 0) return;

            int targetIndex = channelNum - 1;
            List<Channel> channelSourceList = activity.getChannelSourceList();
            if (targetIndex < 0) targetIndex = 0;
            if (targetIndex >= channelSourceList.size()) {
                targetIndex = channelSourceList.size() - 1;
            }

            activity.playChannel(channelSourceList.get(targetIndex), targetIndex);
        } catch (NumberFormatException e) {
            numberInputBuffer.setLength(0);
        }
    }

    public void setLongPressKeyCode(int keyCode) {
        this.longPressKeyCode = keyCode;
    }

    public void resetOkKeyState() {
        okKeyLongPressed = false;
        okKeyTriggered = false;
        okKeyDownTime = 0;
    }

    public void clearNumberInputBuffer() {
        numberInputBuffer.setLength(0);
        mainHandler.removeCallbacks(numberInputConfirmTask);
    }

    public void cleanup() {
        mainHandler.removeCallbacks(longPressSeekRunnable);
        mainHandler.removeCallbacks(numberInputConfirmTask);
    }
}
