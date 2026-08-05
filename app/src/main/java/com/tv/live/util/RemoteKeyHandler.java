package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 远程按键处理器
 * 封装按键类型判断、数字键输入、长按按键等逻辑
 */
public class RemoteKeyHandler {
    public interface OnKeyAction {
        void onMenuKey();
        void onOkKey();
        void onChannelUp();
        void onChannelDown();
        void onSeekBackward();
        void onSeekForward();
        void onPlayPause();
        void onStop();
        void onBackKey();
    }

    public interface OnNumberInput {
        void onNumberInput(String input);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long okKeyDownTime = 0;
    private boolean okKeyTriggered = false;
    private boolean okKeyLongPressed = false;
    private static final long OK_LONG_PRESS_DURATION = 1500;

    private boolean isInCatchUpMode = false;
    private int longPressKeyCode = -1;

    private static final long SEEK_REPEAT_DELAY_MS = 400;
    private static final long SEEK_REPEAT_INTERVAL_MS = 200;

    private final StringBuilder numberInputBuffer = new StringBuilder();
    private Runnable numberInputConfirmTask;
    private OnNumberInput numberInputCallback;
    private boolean numberInputEnabled = true;

    private OnKeyAction keyAction;

    private final Runnable longPressSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressKeyCode == -1 || !isInCatchUpMode) return;
            if (keyAction == null) {
                longPressKeyCode = -1;
                return;
            }
            if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                keyAction.onSeekBackward();
            } else if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                keyAction.onSeekForward();
            }
            mainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };

    public RemoteKeyHandler(OnKeyAction keyAction) {
        this.keyAction = keyAction;
    }

    public void setNumberInputCallback(OnNumberInput callback) {
        this.numberInputCallback = callback;
    }

    public void setNumberInputEnabled(boolean enabled) {
        this.numberInputEnabled = enabled;
    }

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
        if (!enabled) {
            longPressKeyCode = -1;
            mainHandler.removeCallbacks(longPressSeekRunnable);
        }
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    public void resetOkKeyState() {
        okKeyLongPressed = false;
        okKeyTriggered = false;
        okKeyDownTime = 0;
    }

    public void clearNumberInput() {
        numberInputBuffer.setLength(0);
        if (numberInputConfirmTask != null) {
            mainHandler.removeCallbacks(numberInputConfirmTask);
        }
    }

    public String getNumberInputBuffer() {
        return numberInputBuffer.toString();
    }

    /**
     * 判断是否为OK键
     */
    public static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == 100;
    }

    /**
     * 判断是否为菜单键
     */
    public static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_HELP
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == 101;
    }

    /**
     * 判断是否为频道上键
     */
    public static boolean isChannelUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    /**
     * 判断是否为频道下键
     */
    public static boolean isChannelDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    /**
     * 处理按键事件
     * @return 如果事件已处理返回true
     */
    public boolean handleKeyEvent(KeyEvent event, boolean panelOpen) {
        if (event == null || keyAction == null) {
            return false;
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        try {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    return handleKeyDown(keyCode, panelOpen);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                return handleKeyUp(keyCode, panelOpen);
            }
        } catch (Exception e) {
            android.util.Log.e("RemoteKeyHandler", "handleKeyEvent 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
        }

        return false;
    }

    private boolean handleKeyDown(int keyCode, boolean panelOpen) {
        if (isMenuKey(keyCode)) {
            keyAction.onMenuKey();
            return true;
        }

        if (isOkKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onOkKey();
                return true;
            }
        }

        if (isChannelUpKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onChannelUp();
                return true;
            }
        } else if (isChannelDownKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onChannelDown();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (isInCatchUpMode) {
                keyAction.onSeekBackward();
                longPressKeyCode = keyCode;
                mainHandler.removeCallbacks(longPressSeekRunnable);
                mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                return true;
            }
            if (!panelOpen) {
                keyAction.onOkKey();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (isInCatchUpMode) {
                keyAction.onSeekForward();
                longPressKeyCode = keyCode;
                mainHandler.removeCallbacks(longPressSeekRunnable);
                mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                return true;
            }
            if (!panelOpen) {
                keyAction.onMenuKey();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            keyAction.onPlayPause();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            keyAction.onStop();
            return true;
        } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberKey(keyCode);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            keyAction.onBackKey();
            return true;
        }

        return false;
    }

    private boolean handleKeyUp(int keyCode, boolean panelOpen) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (longPressKeyCode == keyCode) {
                longPressKeyCode = -1;
                mainHandler.removeCallbacks(longPressSeekRunnable);
            }
        }
        if (isOkKey(keyCode)) {
            resetOkKeyState();
            if (!panelOpen) {
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        return false;
    }

    private void handleNumberKey(int keyCode) {
        if (!numberInputEnabled) return;
        if (numberInputCallback == null) return;

        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;

        if (numberInputConfirmTask != null) {
            mainHandler.removeCallbacks(numberInputConfirmTask);
        }

        numberInputBuffer.append(num);

        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }

        numberInputCallback.onNumberInput(numberInputBuffer.toString());

        numberInputConfirmTask = () -> {
            String input = numberInputBuffer.toString();
            numberInputBuffer.setLength(0);
            numberInputCallback.onNumberInput(input);
        };
        mainHandler.postDelayed(numberInputConfirmTask, 1500);
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        longPressKeyCode = -1;
        numberInputBuffer.setLength(0);
        numberInputConfirmTask = null;
        numberInputCallback = null;
        keyAction = null;
    }
}
