package com.tv.live;

import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

class SettingsItemManager {
    private final SettingsDialog dialog;
    private final android.os.Handler mainHandler;

    private View[] items;
    private int selectedItemPosition = 0;

    SettingsItemManager(SettingsDialog dialog, android.os.Handler mainHandler) {
        this.dialog = dialog;
        this.mainHandler = mainHandler;
    }

    void setItems(View[] items) {
        this.items = items;
    }

    int getSelectedItemPosition() {
        return selectedItemPosition;
    }

    void initSettingsItemList(ScrollView scrollView) {
        if (items == null) return;

        for (View item : items) {
            item.setBackgroundResource(R.drawable.item_settings_bg);
            item.setFocusable(true);
            item.setClickable(true);
        }

        for (int i = 0; i < items.length; i++) {
            View item = items[i];
            int finalI = i;
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    if (selectedItemPosition != finalI) {
                        items[selectedItemPosition].setSelected(false);
                        items[finalI].setSelected(true);
                        selectedItemPosition = finalI;
                    }
                    v.post(() -> postRefreshAllItemBold());
                }
            });
        }

        View.OnClickListener clickListener = v -> {
            int clickedIndex = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    clickedIndex = i;
                    break;
                }
            }
            if (clickedIndex == -1) return;

            if (clickedIndex == selectedItemPosition) {
                dialog.performItemAction(clickedIndex);
            } else {
                items[selectedItemPosition].setSelected(false);
                items[clickedIndex].setSelected(true);
                selectedItemPosition = clickedIndex;
                v.post(() -> postRefreshAllItemBold());
            }
        };

        for (View item : items) {
            item.setOnClickListener(clickListener);
        }

        items[0].setSelected(true);
        selectedItemPosition = 0;
        items[0].post(() -> postRefreshAllItemBold());

        if (scrollView != null) {
            scrollView.setFocusable(false);
            scrollView.setFocusableInTouchMode(false);
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scrollView.scrollTo(0, 0);
        }

        mainHandler.postDelayed(() -> {
            items[0].requestFocus();
            android.util.Log.d("Settings", "First item focused");
        }, 100);
    }

    void requestFocusFirstItem() {
        if (items != null && items.length > 0 && items[0] != null) {
            items[0].requestFocus();
            android.util.Log.d("SettingsDialog", "Focus requested to first item");
        }
    }

    boolean handleKeyDown(int keyCode, KeyEvent event, long showTime, long ignoreKeyDelayMs) {
        if (items == null) return false;

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int nextPos = selectedItemPosition + 1;
            if (nextPos < items.length && items[nextPos] != null) {
                items[nextPos].requestFocus();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            int prevPos = selectedItemPosition - 1;
            if (prevPos >= 0 && items[prevPos] != null) {
                items[prevPos].requestFocus();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (System.currentTimeMillis() - showTime < ignoreKeyDelayMs) {
                android.util.Log.d("SettingsDialog", "忽略打开后短时间内的按键，防止误触");
                return true;
            }
            dialog.performItemAction(selectedItemPosition);
            return true;
        }
        return false;
    }

    private void postRefreshAllItemBold() {
        if (items == null) return;
        mainHandler.post(() -> mainHandler.post(() -> {
            for (int j = 0; j < items.length; j++) {
                setAllTextViewsBold(items[j], j == selectedItemPosition);
            }
        }));
    }

    static void setAllTextViewsBold(View root, boolean bold) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface base = tv.getTypeface();
            if (base == null) base = Typeface.DEFAULT;
            tv.setTypeface(Typeface.create(base,
                    bold ? Typeface.BOLD : Typeface.NORMAL));
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                setAllTextViewsBold(group.getChildAt(i), bold);
            }
        }
    }
}