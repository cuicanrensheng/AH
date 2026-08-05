package com.tv.live.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.tv.live.R;

import java.util.List;
import java.util.function.Consumer;

/**
 * 设置对话框辅助类
 * 封装通用的对话框创建和显示逻辑
 */
public class SettingsDialogHelper {
    private Context context;
    private Handler mainHandler;

    public SettingsDialogHelper(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void release() {
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler = null;
        }
        context = null;
    }

    /**
     * 显示数字输入对话框
     */
    public void showNumberInputDialog(int currentValue, Consumer<Integer> onConfirmed) {
        if (context == null) return;
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setBackgroundResource(R.drawable.dialog_bg_corner);
        dialogView.setPadding(24, 24, 24, 24);

        TextView titleView = new TextView(context);
        titleView.setText("请输入数字");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 24);
        dialogView.addView(titleView);

        final TextView displayView = new TextView(context);
        displayView.setText(String.valueOf(currentValue));
        displayView.setTextColor(Color.WHITE);
        displayView.setTextSize(48);
        displayView.setGravity(Gravity.CENTER);
        displayView.setBackgroundColor(0xFF333545);
        displayView.setPadding(16, 16, 16, 16);
        dialogView.addView(displayView);

        LinearLayout keysLayout = new LinearLayout(context);
        keysLayout.setOrientation(LinearLayout.VERTICAL);
        keysLayout.setPadding(16, 24, 16, 0);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "确认"};
        final TextView[] keyViews = new TextView[keys.length];

        for (int row = 0; row < 4; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                TextView keyView = new TextView(context);
                keyView.setText(keys[index]);
                keyView.setTextColor(Color.WHITE);
                keyView.setTextSize(20);
                keyView.setGravity(Gravity.CENTER);
                keyView.setBackgroundColor(0xFF333545);
                keyView.setPadding(32, 20, 32, 20);
                keyView.setFocusable(true);
                keyView.setFocusableInTouchMode(true);
                keyView.setTag(keys[index]);
                keyViews[index] = keyView;

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(8, 8, 8, 8);
                rowLayout.addView(keyView, params);
            }
            keysLayout.addView(rowLayout);
        }
        dialogView.addView(keysLayout);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final StringBuilder inputValue = new StringBuilder(String.valueOf(currentValue));

        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            final String key = keys[i];
            TextView keyView = keyViews[i];

            keyView.setOnClickListener(v -> {
                if ("清空".equals(key)) {
                    inputValue.setLength(0);
                    displayView.setText("");
                } else if ("确认".equals(key)) {
                    int value = 0;
                    try {
                        value = Integer.parseInt(inputValue.toString());
                    } catch (NumberFormatException e) {
                        value = currentValue;
                    }
                    if (value < 1) value = 1;
                    if (value > 20) value = 20;
                    onConfirmed.accept(value);
                    dialog.dismiss();
                } else {
                    if (inputValue.length() < 2) {
                        inputValue.append(key);
                        int tempValue = Integer.parseInt(inputValue.toString());
                        if (tempValue <= 20) {
                            displayView.setText(inputValue.toString());
                        } else {
                            inputValue.setLength(0);
                            displayView.setText("");
                            Toast.makeText(context, "请输入1-20的数字", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(context, "最多两位数", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        dialog.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    View focusedView = dialog.getCurrentFocus();
                    if (focusedView != null && focusedView.getTag() != null) {
                        String tag = (String) focusedView.getTag();
                        for (int i = 0; i < keys.length; i++) {
                            if (keys[i].equals(tag)) {
                                keyViews[i].performClick();
                                break;
                            }
                        }
                    }
                    return true;
                }
            }
            return false;
        });

        dialog.show();
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        mainHandler.postDelayed(() -> {
            if (keyViews[0] != null) {
                keyViews[0].requestFocus();
            }
        }, 100);
    }

    /**
     * 显示通用选择对话框
     */
    public void showCommonSelectionDialog(String title, String[] items, int checkedItem, Consumer<Integer> onSelected) {
        if (context == null) return;
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setBackgroundResource(R.drawable.dialog_bg_corner);
        dialogView.setPadding(16, 16, 16, 16);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(16, 16, 16, 16);
        dialogView.addView(titleView);

        ListView listView = new ListView(context);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setDivider(null);
        listView.setSelector(R.drawable.list_item_selector);

        CustomAdapter adapter = new CustomAdapter(context, items, checkedItem);
        listView.setAdapter(adapter);
        listView.setSelection(checkedItem);
        listView.requestFocus();

        dialogView.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        dialog.show();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            adapter.setSelectedPos(position);
            onSelected.accept(position);
            dialog.dismiss();
        });

        listView.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                adapter.setSelectedPos(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        mainHandler.postDelayed(() -> {
            if (listView != null) {
                listView.requestFocus();
                listView.setSelection(checkedItem);
            }
        }, 200);
    }

    /**
     * 自定义适配器
     */
    public static class CustomAdapter extends ArrayAdapter<String> {
        private int selectedPos;

        public CustomAdapter(Context context, String[] items, int initialPos) {
            super(context, android.R.layout.simple_list_item_1, items);
            this.selectedPos = initialPos;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = view.findViewById(android.R.id.text1);
            if (textView != null) {
                if (position == selectedPos) {
                    textView.setTextColor(Color.parseColor("#FF40A9FF"));
                    textView.setTypeface(null, Typeface.BOLD);
                } else {
                    textView.setTextColor(Color.WHITE);
                    textView.setTypeface(null, Typeface.NORMAL);
                }
            }
            view.setBackgroundColor(position == selectedPos ? 0xFF272B3A : Color.TRANSPARENT);
            return view;
        }

        public void setSelectedPos(int pos) {
            this.selectedPos = pos;
            notifyDataSetChanged();
        }

        public int getSelectedPos() {
            return selectedPos;
        }
    }
}
