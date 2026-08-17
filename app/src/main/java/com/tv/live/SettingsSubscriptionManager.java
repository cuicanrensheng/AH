package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

class SettingsSubscriptionManager {
    private final Context context;
    private final QRCodeManager qrCodeManager;
    private final android.os.Handler mainHandler;
    private final String currentWebUrl;

    SettingsSubscriptionManager(Context context, QRCodeManager qrCodeManager,
                                android.os.Handler mainHandler, String currentWebUrl) {
        this.context = context;
        this.qrCodeManager = qrCodeManager;
        this.mainHandler = mainHandler;
        this.currentWebUrl = currentWebUrl;
    }

    void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(context, spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        LayoutInflater inflater = LayoutInflater.from(
                new android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_subscription, null);

        ListView lvSourceList = dialogView.findViewById(R.id.lv_source_list);
        ImageView ivQrCode = dialogView.findViewById(R.id.iv_qr_code);
        TextView tvIpAddress = dialogView.findViewById(R.id.tv_ip_address);
        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llScanHeader = dialogView.findViewById(R.id.ll_scan_header);
        EditText etName = dialogView.findViewById(R.id.et_name);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        Button btnClear = dialogView.findViewById(R.id.btn_clear);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        boolean isLive = "live_history".equals(spKey);
        tvIpAddress.setText(currentWebUrl);

        if (isLive) {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.VISIBLE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.VISIBLE);

            new Thread(() -> {
                Bitmap qrBitmap = null;
                try {
                    qrBitmap = qrCodeManager.createQR(currentWebUrl, 240);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final Bitmap finalQrBitmap = qrBitmap;
                mainHandler.post(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            }).start();

            ivQrCode.setOnClickListener(v ->
                    Toast.makeText(context, "已生成二维码，请扫码", Toast.LENGTH_SHORT).show()
            );
            etName.setHint("请输入名称(选填)");
            etUrl.setHint("请输入地址");
        } else {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.GONE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);
            etName.setHint("请输入节目单名称(选填)");
            etUrl.setHint("请输入EPG节目单地址");
        }

        etName.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    btnConfirm.performClick();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int sel = etName.getSelectionStart();
                    if (sel <= 0) {
                        int nid = etName.getNextFocusLeftId();
                        if (nid != View.NO_ID) {
                            View target = dialogView.findViewById(nid);
                            if (target != null) { target.requestFocus(); return true; }
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int sel = etName.getSelectionEnd();
                    int len = etName.getText() != null ? etName.getText().length() : 0;
                    if (sel >= len) {
                        int nid = etName.getNextFocusRightId();
                        if (nid != View.NO_ID) {
                            View target = dialogView.findViewById(nid);
                            if (target != null) { target.requestFocus(); return true; }
                        }
                    }
                }
            }
            return false;
        });
        etUrl.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    btnConfirm.performClick();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int sel = etUrl.getSelectionStart();
                    if (sel <= 0) {
                        int nid = etUrl.getNextFocusLeftId();
                        if (nid != View.NO_ID) {
                            View target = dialogView.findViewById(nid);
                            if (target != null) { target.requestFocus(); return true; }
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int sel = etUrl.getSelectionEnd();
                    int len = etUrl.getText() != null ? etUrl.getText().length() : 0;
                    if (sel >= len) {
                        int nid = etUrl.getNextFocusRightId();
                        if (nid != View.NO_ID) {
                            View target = dialogView.findViewById(nid);
                            if (target != null) { target.requestFocus(); return true; }
                        }
                    }
                }
            }
            return false;
        });

        btnClear.setBackground(ContextCompat.getDrawable(context, R.drawable.button_focus_selector));
        btnConfirm.setBackground(ContextCompat.getDrawable(context, R.drawable.button_focus_selector));
        btnClose.setBackground(ContextCompat.getDrawable(context, R.drawable.button_focus_selector));

        View.OnTouchListener buttonTouchListener = (v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.requestFocus();
                return false;
            }
            return false;
        };
        btnClear.setOnTouchListener(buttonTouchListener);
        btnConfirm.setOnTouchListener(buttonTouchListener);
        btnClose.setOnTouchListener(buttonTouchListener);

        btnClear.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    btnClear.performClick();
                    return true;
                }
            }
            return false;
        });
        btnConfirm.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    btnConfirm.performClick();
                    return true;
                }
            }
            return false;
        });
        btnClose.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    btnClose.performClick();
                    return true;
                }
            }
            return false;
        });

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(context, sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                if (position < 0 || position >= sources.size()) {
                    android.util.Log.w("SettingsDialog", "[onSwitch] 越界取消 position=" + position + " size=" + sources.size());
                    return;
                }
                int oldPos = adapter.getSelectedPosition();
                String switchedName = sources.get(position).name;
                Toast.makeText(context, "已切换到：" + switchedName, Toast.LENGTH_SHORT).show();
                android.util.Log.i("SettingsDialog", "[onSwitch] 切换默认源 old=" + oldPos + " → new=" + position + " name=" + switchedName + " url=" + sources.get(position).url);

                adapter.setSelectedPosition(position, false);

                if (lvSourceList != null) {
                    int first = lvSourceList.getFirstVisiblePosition();
                    int last = lvSourceList.getLastVisiblePosition();
                    if (oldPos >= first && oldPos <= last) {
                        android.view.View oldChild = lvSourceList.getChildAt(oldPos - first);
                        if (oldChild != null) {
                            oldChild.setSelected(false);
                            android.view.View oldCheck = oldChild.findViewById(R.id.tv_check);
                            if (oldCheck != null) oldCheck.setVisibility(android.view.View.GONE);
                            android.view.View oldLlContent = oldChild.findViewById(R.id.ll_content);
                            if (oldLlContent != null) {
                                oldLlContent.setSelected(false);
                                oldLlContent.post(() -> SettingsItemManager.setAllTextViewsBold(oldLlContent, false));
                            }
                        }
                    }
                    if (position >= first && position <= last) {
                        android.view.View newChild = lvSourceList.getChildAt(position - first);
                        if (newChild != null) {
                            newChild.setSelected(true);
                            android.view.View newCheck = newChild.findViewById(R.id.tv_check);
                            if (newCheck != null) newCheck.setVisibility(android.view.View.VISIBLE);
                            android.view.View newLlContent = newChild.findViewById(R.id.ll_content);
                            if (newLlContent != null) {
                                newLlContent.setSelected(true);
                                newLlContent.post(() -> {
                                    SettingsItemManager.setAllTextViewsBold(newLlContent, true);
                                    android.util.Log.i("SettingsDialog", "[onSwitch] 同步新项UI完成");
                                });
                            }
                        }
                    }
                }

                sourceManager.setDefault(position);
                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            }

            @Override
            public void onDelete(int position) {
                if (position < 0 || position >= sources.size()) return;

                SourceManager.SourceItem item = sources.get(position);

                AlertDialog deleteDialog = new AlertDialog.Builder(context)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            int realIndex = sourceManager.indexOfUrl(item.url);
                            if (realIndex >= 0 && realIndex < sourceManager.size()) {
                                sourceManager.removeSource(realIndex);
                                sources.clear();
                                sources.addAll(sourceManager.getAllSources());
                                int newDefaultPos = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
                                adapter.setSelectedPosition(newDefaultPos);
                                lvSourceList.post(() -> {
                                    adapter.notifyDataSetChanged();
                                    if (newDefaultPos >= 0) {
                                        lvSourceList.setSelection(newDefaultPos);
                                    }
                                    lvSourceList.post(() -> {
                                        View child = lvSourceList.getChildAt(newDefaultPos >= 0 ? newDefaultPos : 0);
                                        if (child != null) {
                                            View llContent = child.findViewById(R.id.ll_content);
                                            if (llContent != null) {
                                                llContent.requestFocus();
                                                return;
                                            }
                                        }
                                        lvSourceList.requestFocus();
                                    });
                                });
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, "删除失败，源未找到", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();

                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                deleteDialog.show();

                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF55576A));
            }
        });

        lvSourceList.setAdapter(adapter);
        lvSourceList.setItemsCanFocus(true);

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(context, "已添加，正在刷新...", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            } else {
                Toast.makeText(context, "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.show();
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.getWindow().getDecorView().post(() -> {
            if (!dialog.isShowing()) return;
            int targetPos = currentDefault >= 0 ? currentDefault : 0;
            lvSourceList.setSelection(targetPos);
            lvSourceList.post(() -> {
                if (!dialog.isShowing()) return;
                int f = lvSourceList.getFirstVisiblePosition();
                android.view.View targetItem = lvSourceList.getChildAt(targetPos - f);
                if (!SubscriptionAdapter.moveFocusToChild(targetItem, R.id.ll_content)) {
                    lvSourceList.requestFocus();
                }
                lvSourceList.postDelayed(() -> {
                    if (!dialog.isShowing()) return;
                    View curFocus = dialog.getWindow().getDecorView().findFocus();
                    if (curFocus != null && curFocus.getId() != R.id.ll_content
                            && curFocus.getId() != R.id.btn_copy && curFocus.getId() != R.id.btn_delete) {
                        int ff = lvSourceList.getFirstVisiblePosition();
                        android.view.View target = lvSourceList.getChildAt(Math.max(0, (currentDefault >= 0 ? currentDefault : 0) - ff));
                        SubscriptionAdapter.moveFocusToChild(target, R.id.ll_content);
                    }
                }, 300);
            });
        });
    }
}