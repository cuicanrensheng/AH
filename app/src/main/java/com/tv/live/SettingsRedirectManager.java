package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

class SettingsRedirectManager {
    private final Context context;
    private final SharedPreferences sp;
    private final android.os.Handler mainHandler;

    static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    static final String KEY_USER_AGENT_MODE = "user_agent_mode";

    SettingsRedirectManager(Context context, SharedPreferences sp, android.os.Handler mainHandler) {
        this.context = context;
        this.sp = sp;
        this.mainHandler = mainHandler;
    }

    void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, 5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, false);
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
        }
    }

    void updateRedirectSettingText(TextView tvRedirectSetting) {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain ? "开" : "关").append(" | ");
        sb.append("跨协议：").append(crossProto ? "开" : "关").append("\n");
        sb.append("携带请求头：").append(followHeader ? "开" : "关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl ? "开" : "关").append(" | ");
        sb.append("授权令牌：").append(sendCookie ? "开" : "关").append(" | ");
        sb.append("UA：").append(uaLabel);
        tvRedirectSetting.setText(sb.toString());
    }

    void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = {sp.getString(KEY_USER_AGENT_MODE, "exo")};

        LayoutInflater inflater = LayoutInflater.from(
                new android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        Button btnCancel = dialogView.findViewById(R.id.btn_redirect_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_redirect_save);

        LinearLayout llMaxCount = dialogView.findViewById(R.id.ll_max_count);
        LinearLayout llCrossDomain = dialogView.findViewById(R.id.ll_cross_domain);
        LinearLayout llCrossProto = dialogView.findViewById(R.id.ll_cross_proto);
        LinearLayout llFollowHeader = dialogView.findViewById(R.id.ll_follow_header);
        LinearLayout llSendCookie = dialogView.findViewById(R.id.ll_send_cookie);
        LinearLayout llIgnoreSsl = dialogView.findViewById(R.id.ll_ignore_ssl);

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        etMax.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_ENTER) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llCrossDomain.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llMaxCount.requestFocus();
                    return true;
                }
            }
            return false;
        });
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        final int[] pendingPos = {0};
        final LinearLayout[] items = {llMaxCount, llCrossDomain, llCrossProto, llFollowHeader, llSendCookie, llIgnoreSsl, llUserAgent};
        final String[] itemNames = {"maxCount", "crossDomain", "crossProto", "followHeader", "sendCookie", "ignoreSsl", "userAgent"};

        llMaxCount.setBackgroundColor(0x3340A9FF);
        for (int i = 0; i < llMaxCount.getChildCount(); i++) {
            View child = llMaxCount.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(0xFF40A9FF);
            } else if (child instanceof EditText) {
                ((EditText) child).setTextColor(0xFF40A9FF);
            }
        }

        SettingsDialogHelper helper = new SettingsDialogHelper(context, sp, mainHandler);

        View.OnClickListener clickListener = v -> {
            int currentPos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    currentPos = i;
                    break;
                }
            }

            if (pendingPos[0] == currentPos) {
                if (currentPos == 0) {
                    int currentVal = Integer.parseInt(etMax.getText().toString());
                    helper.showNumberInputDialog(currentVal, newVal -> etMax.setText(String.valueOf(newVal)));
                } else if (currentPos == 1) {
                    swCrossDomain.setChecked(!swCrossDomain.isChecked());
                } else if (currentPos == 2) {
                    swCrossProto.setChecked(!swCrossProto.isChecked());
                } else if (currentPos == 3) {
                    swFollowHeader.setChecked(!swFollowHeader.isChecked());
                } else if (currentPos == 4) {
                    swSendCookie.setChecked(!swSendCookie.isChecked());
                } else if (currentPos == 5) {
                    swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                } else if (currentPos == 6) {
                    final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                    final String[] uaValues = {"exo", "vlc"};
                    int checkedItem = 0;
                    for (int i = 0; i < uaValues.length; i++) {
                        if (uaValues[i].equals(currentUaMode[0])) {
                            checkedItem = i;
                            break;
                        }
                    }
                    helper.showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                        currentUaMode[0] = uaValues[which];
                        tvUserAgentStatus.setText(uaOptions[which]);
                    });
                }
            } else {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0xFF272B3A);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = currentPos;
            }
        };

        View.OnKeyListener keyListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0) {
                        if (currentPos == 0) {
                            int currentVal = Integer.parseInt(etMax.getText().toString());
                            helper.showNumberInputDialog(currentVal, newVal -> etMax.setText(String.valueOf(newVal)));
                        } else if (currentPos == 1) {
                            swCrossDomain.setChecked(!swCrossDomain.isChecked());
                        } else if (currentPos == 2) {
                            swCrossProto.setChecked(!swCrossProto.isChecked());
                        } else if (currentPos == 3) {
                            swFollowHeader.setChecked(!swFollowHeader.isChecked());
                        } else if (currentPos == 4) {
                            swSendCookie.setChecked(!swSendCookie.isChecked());
                        } else if (currentPos == 5) {
                            swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                        } else if (currentPos == 6) {
                            final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                            final String[] uaValues = {"exo", "vlc"};
                            int checkedItem = 0;
                            for (int i = 0; i < uaValues.length; i++) {
                                if (uaValues[i].equals(currentUaMode[0])) {
                                    checkedItem = i;
                                    break;
                                }
                            }
                            helper.showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                                currentUaMode[0] = uaValues[which];
                                tvUserAgentStatus.setText(uaOptions[which]);
                            });
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == items.length - 1 && btnCancel != null) {
                        btnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == 0) {
                        return true;
                    }
                }
            }
            return false;
        };

        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            int pos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    pos = i;
                    break;
                }
            }
            if (hasFocus && pos >= 0) {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0x00000000);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = pos;
            } else {
                v.setBackgroundColor(0x00000000);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(Color.WHITE);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(Color.WHITE);
                    }
                }
            }
        };

        llMaxCount.setOnClickListener(clickListener);
        llMaxCount.setOnKeyListener(keyListener);
        llMaxCount.setOnFocusChangeListener(focusListener);
        llMaxCount.setNextFocusDownId(R.id.ll_cross_domain);
        llCrossDomain.setNextFocusUpId(R.id.ll_max_count);
        llCrossDomain.setOnClickListener(clickListener);
        llCrossDomain.setOnKeyListener(keyListener);
        llCrossDomain.setOnFocusChangeListener(focusListener);
        llCrossProto.setOnClickListener(clickListener);
        llCrossProto.setOnKeyListener(keyListener);
        llCrossProto.setOnFocusChangeListener(focusListener);
        llFollowHeader.setOnClickListener(clickListener);
        llFollowHeader.setOnKeyListener(keyListener);
        llFollowHeader.setOnFocusChangeListener(focusListener);
        llSendCookie.setOnClickListener(clickListener);
        llSendCookie.setOnKeyListener(keyListener);
        llSendCookie.setOnFocusChangeListener(focusListener);
        llIgnoreSsl.setOnClickListener(clickListener);
        llIgnoreSsl.setOnKeyListener(keyListener);
        llIgnoreSsl.setOnFocusChangeListener(focusListener);
        llUserAgent.setOnClickListener(clickListener);
        llUserAgent.setOnKeyListener(keyListener);
        llUserAgent.setOnFocusChangeListener(focusListener);
        llUserAgent.setNextFocusDownId(R.id.btn_redirect_cancel);
        btnCancel.setNextFocusUpId(R.id.ll_user_agent);
        btnCancel.setNextFocusRightId(R.id.btn_redirect_save);
        btnSave.setNextFocusLeftId(R.id.btn_redirect_cancel);
        btnSave.setNextFocusUpId(R.id.ll_user_agent);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * 0.85),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        final View finalBtnCancel = btnCancel;
        final View finalBtnSave = btnSave;
        final View finalLlUserAgent = llUserAgent;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalLlUserAgent) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && (focused.equals(finalBtnCancel) || focused.equals(finalBtnSave)) && finalLlUserAgent != null) {
                        finalLlUserAgent.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnCancel) && finalBtnSave != null) {
                        finalBtnSave.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnSave) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        });
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (llMaxCount != null && dialog.isShowing()) {
                llMaxCount.requestFocus();
            }
        }, 200);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        applyClickOnFirstTouch(btnCancel);
        btnCancel.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && llUserAgent != null) {
                    llUserAgent.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return true;
                }
            }
            return false;
        });

        btnSave.setOnClickListener(v -> {
            String maxStr = etMax.getText().toString().trim();
            int newMax = 5;
            if (!TextUtils.isEmpty(maxStr)) {
                try {
                    newMax = Integer.parseInt(maxStr);
                    if (newMax < 1) newMax = 1;
                    if (newMax > 20) newMax = 20;
                } catch (Exception ignored) { newMax = 5; }
            }
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
            editor.putString(KEY_USER_AGENT_MODE, currentUaMode[0]);
            editor.apply();
            updateRedirectSettingText(dialogView.findViewById(R.id.tv_redirect_status));
            Toast.makeText(context, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        applyClickOnFirstTouch(btnSave);
    }

    static void applyClickOnFirstTouch(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (!v.isFocused()) v.requestFocus();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
                if (!v.isFocused()) v.requestFocus();
                return true;
            }
            return false;
        });
    }
}