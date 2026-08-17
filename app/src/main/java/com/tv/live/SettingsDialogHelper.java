package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.function.Consumer;

class SettingsDialogHelper {
    private final Context context;
    private final SharedPreferences sp;
    private final android.os.Handler mainHandler;

    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";

    SettingsDialogHelper(Context context, SharedPreferences sp, android.os.Handler mainHandler) {
        this.context = context;
        this.sp = sp;
        this.mainHandler = mainHandler;
    }

    Context getContext() {
        return context;
    }

    SharedPreferences getSp() {
        return sp;
    }

    int dp2px(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    void showVersionInfoDialog() {
        String versionName = BuildConfig.VERSION_NAME;
        int versionCode = BuildConfig.VERSION_CODE;
        String updateNotes = "暂无更新内容";
        String userAgent = sp.getString("custom_user_agent", "");
        if (TextUtils.isEmpty(userAgent)) {
            String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
            if ("vlc".equals(uaMode)) {
                userAgent = "VLC/3.0.21 LibVLC/3.0.21";
            } else {
                userAgent = "ExoPlayer";
            }
        }
        String sdkVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String playerVersion = "androidx.media3 1.7.1";

        StringBuilder sb = new StringBuilder();
        sb.append("版本信息: v").append(versionName).append(" (").append(versionCode).append(")\n\n");
        sb.append("UA: ").append(userAgent).append("\n\n");
        sb.append("SDK 版本: ").append(sdkVersion).append("\n\n");
        sb.append("播放器版本: ").append(playerVersion).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n");
        sb.append("更新内容:\n").append(updateNotes);

        String message = sb.toString();
        android.text.SpannableString spannableString = new android.text.SpannableString(message);

        int p;
        p = message.indexOf("版本信息");
        if (p != -1) spannableString.setSpan(new StyleSpan(Typeface.BOLD), p, p + 4, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("UA:");
        if (p != -1) spannableString.setSpan(new StyleSpan(Typeface.BOLD), p, p + 3, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("SDK 版本");
        if (p != -1) spannableString.setSpan(new StyleSpan(Typeface.BOLD), p, p + 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("播放器版本");
        if (p != -1) spannableString.setSpan(new StyleSpan(Typeface.BOLD), p, p + 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("更新内容");
        if (p != -1) spannableString.setSpan(new StyleSpan(Typeface.BOLD), p, p + 4, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        int pad = dp2px(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(context);
        titleView.setText("📱 应用详情");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp2px(8));
        layout.addView(titleView);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setFillViewport(true);

        TextView msgView = new TextView(context);
        msgView.setText(spannableString);
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(16);
        msgView.setLineSpacing(0, 1.25f);
        msgView.setFocusable(true);
        msgView.setFocusableInTouchMode(true);

        scrollView.addView(msgView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(380)
        ));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (msgView != null && dialog.isShowing()) {
                msgView.requestFocus();
            }
        }, 200);
    }

    void showNumberInputDialog(int currentValue, Consumer<Integer> onConfirmed) {
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
                            inputValue.setLength(inputValue.length() - 1);
                        }
                    }
                }
            });

            keyView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
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
                                    inputValue.setLength(inputValue.length() - 1);
                                }
                            }
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        dialog.dismiss();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && idx >= 3) {
                        keyViews[idx - 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && idx < 9) {
                        keyViews[idx + 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && idx % 3 > 0) {
                        keyViews[idx - 1].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && idx % 3 < 2) {
                        keyViews[idx + 1].requestFocus();
                        return true;
                    }
                }
                return false;
            });

            keyView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    keyView.setBackgroundColor(0x3340A9FF);
                    keyView.setTextColor(0xFF40A9FF);
                } else {
                    keyView.setBackgroundColor(0xFF333545);
                    keyView.setTextColor(Color.WHITE);
                }
            });
        }

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss();
                return true;
            }
            return false;
        });

        dialog.show();

        mainHandler.postDelayed(() -> {
            if (keyViews.length > 0 && dialog.isShowing()) {
                keyViews[4].requestFocus();
            }
        }, 200);
    }

    void showCommonSelectionDialog(String title, String[] items, int checkedItem, Consumer<Integer> onSelected) {
        ListView listView = new ListView(context);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        final int[] pendingPos = {checkedItem};
        final AlertDialog[] dialogHolder = new AlertDialog[1];

        class CustomAdapter extends ArrayAdapter<String> {
            private int selectedPos;

            CustomAdapter(android.content.Context context, String[] items, int initialPos) {
                super(context, android.R.layout.simple_list_item_single_choice, items);
                selectedPos = initialPos;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(16, 16, 16, 16);

                if (position == selectedPos) {
                    tv.setTextColor(0xFF40A9FF);
                    view.setBackgroundColor(0x3340A9FF);
                } else {
                    tv.setTextColor(Color.WHITE);
                    view.setBackgroundColor(0x00000000);
                }
                return view;
            }

            void setSelectedPos(int pos) {
                selectedPos = pos;
                notifyDataSetChanged();
            }
        }

        CustomAdapter adapter = new CustomAdapter(context, items, checkedItem);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            pendingPos[0] = position;
            adapter.setSelectedPos(position);
            listView.setItemChecked(position, true);
            onSelected.accept(position);
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (pendingPos[0] != position) {
                    pendingPos[0] = position;
                    adapter.setSelectedPos(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        listView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int selected = listView.getSelectedItemPosition();
                    if (selected >= 0 && selected < items.length) {
                        if (pendingPos[0] == selected) {
                            onSelected.accept(selected);
                            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                        } else {
                            pendingPos[0] = selected;
                            adapter.setSelectedPos(selected);
                            listView.setItemChecked(selected, true);
                            Toast.makeText(context, "再次按确认键选择", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    return true;
                }
            }
            return false;
        });

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        layout.setPadding(24, 24, 24, 24);

        layout.addView(titleView);
        layout.addView(listView);

        dialogHolder[0] = new AlertDialog.Builder(context)
                .setView(layout)
                .create();
        if (dialogHolder[0].getWindow() != null) {
            dialogHolder[0].getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialogHolder[0].show();
        mainHandler.postDelayed(() -> {
            if (listView != null && dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                listView.requestFocus();
            }
        }, 200);
    }

    void showChannelLineDialog(TVPlayerManager playerManager, Channel currentChannel,
                               TextView tvChannelLine, Consumer<Integer> onLineSwitched) {
        if (currentChannel == null) {
            Toast.makeText(context, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }

        String channelKey = currentChannel.getChannelId();
        if (TextUtils.isEmpty(channelKey)) {
            channelKey = currentChannel.getName();
        }
        String prefKey = "channel_line_index_" + channelKey;
        int currentLineIndex = sp.getInt(prefKey, 0);

        List<String> lineList = playerManager.getAvailableLines();
        if (lineList.isEmpty()) {
            lineList.add("主源");
            List<String> labels = currentChannel.getHuyaLineLabels();
            List<String> backups = currentChannel.getBackupUrls();
            int n = (labels != null && !labels.isEmpty()) ? labels.size() : (backups != null ? backups.size() : 0);
            for (int i = 0; i < n; i++) {
                String label = (labels != null && i < labels.size()) ? labels.get(i) : "源" + (i + 1);
                lineList.add(label);
            }
        }

        if (currentLineIndex >= lineList.size()) currentLineIndex = 0;

        String[] lineArray = lineList.toArray(new String[0]);

        showCommonSelectionDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(prefKey, which).apply();
            sp.edit().putInt(SettingsDialog.KEY_CHANNEL_LINE_INDEX, which).apply();
            tvChannelLine.setText(lineArray[which]);

            if (playerManager != null && currentChannel != null) {
                if (playerManager.isHuyaSource(currentChannel.getMainPlayUrl())) {
                    playerManager.switchToHuyaLine(which);
                } else {
                    String playUrl;
                    if (which == 0) {
                        playUrl = currentChannel.getMainPlayUrl();
                    } else {
                        List<String> backups = currentChannel.getBackupUrls();
                        int backupIndex = which - 1;
                        if (backupIndex >= 0 && backupIndex < backups.size()) {
                            playUrl = backups.get(backupIndex);
                        } else {
                            playUrl = currentChannel.getMainPlayUrl();
                        }
                    }
                    playerManager.playUrl(playUrl, currentChannel.getName(), currentChannel);
                }
            }
            onLineSwitched.accept(which);
            Toast.makeText(context, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    void showResolutionDialog(TVPlayerManager playerManager, Channel currentChannel,
                              TextView tvResolutionStatus, Consumer<String> onResolutionChanged) {
        if (playerManager == null) return;

        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(context, "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = resolutions.toArray(new String[0]);

        String savedRes = "";
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "resolution_" + channelKey;
            savedRes = sp.getString(prefKey, "");
        } else {
            savedRes = sp.getString("resolution", "");
        }
        String currentResLabel = savedRes.isEmpty() ? playerManager.getCurrentResolutionLabel() : savedRes;
        int initialPos = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(currentResLabel)) {
                initialPos = i;
                break;
            }
        }

        showCommonSelectionDialog("清晰度选择", items, initialPos, (which) -> {
            String selectedLabel = items[which];
            int targetHeight = 0;
            if (selectedLabel.contains("4K")) targetHeight = 2160;
            else if (selectedLabel.contains("1080p")) targetHeight = 1080;
            else if (selectedLabel.contains("720p")) targetHeight = 720;
            else {
                try {
                    targetHeight = Integer.parseInt(selectedLabel.replace("p", ""));
                } catch (Exception ignored) {
                }
            }

            playerManager.switchToResolution(targetHeight, selectedLabel);
            if (currentChannel != null) {
                String channelKey = currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = currentChannel.getName();
                }
                String prefKey = "resolution_" + channelKey;
                sp.edit().putString(prefKey, selectedLabel).apply();
            } else {
                sp.edit().putString("resolution", selectedLabel).apply();
            }
            tvResolutionStatus.setText(selectedLabel);
            onResolutionChanged.accept(selectedLabel);
            Toast.makeText(context, "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
        });
    }

    void showDecoderModeDialog(TextView tvDecoderMode) {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog("解码器选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("decoder_mode", selectedMode).apply();
            updateDecoderModeText(tvDecoderMode, selectedMode);

            Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            Toast.makeText(context, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
    }

    static void updateDecoderModeText(TextView tvDecoderMode, String mode) {
        if (tvDecoderMode == null) return;
        switch (mode) {
            case "hard": tvDecoderMode.setText("硬解"); break;
            case "soft": tvDecoderMode.setText("软解"); break;
            case "auto": default: tvDecoderMode.setText("自动"); break;
        }
    }

    void showRendererModeDialog(TextView tvRendererType) {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog(context.getString(R.string.render_mode_select), modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("renderer_type", selectedMode).apply();
            updateRendererModeText(tvRendererType, selectedMode);

            Intent intent = new Intent("com.tv.live.RENDERER_TYPE_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            Toast.makeText(context, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
    }

    static void updateRendererModeText(TextView tvRendererType, String mode) {
        if (tvRendererType == null) return;
        switch (mode) {
            case "texture": tvRendererType.setText(tvRendererType.getContext().getString(R.string.texture_view)); break;
            case "surface": default: tvRendererType.setText(tvRendererType.getContext().getString(R.string.surface_view)); break;
        }
    }

    void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        String currentMode = sp.getString("screen_ratio", "全屏");
        int checkedItem = 0;
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog("屏幕比例", ratios, checkedItem, (which) -> {
            sp.edit().putString("screen_ratio", ratios[which]).apply();
            Toast.makeText(context, "已设置", Toast.LENGTH_SHORT).show();
        });
    }
}