package com.tv.live;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private int pendingKeyPosition = -1;
    private OnActionListener actionListener;

    private static final String PROTECTED_LIVE_URL = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    private static final String PROTECTED_EPG_URL = "https://e.erw.cc/all.xml.gz";

    private static final int COLOR_SELECTED = 0xFF40A9FF;
    private static final int COLOR_SELECTED_BG = 0x3340A9FF;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_NORMAL_BG = 0x333545;

    public interface OnActionListener {
        void onSwitch(int position);
        void onDelete(int position);
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
            holder = new ViewHolder();
            holder.tvCheck = convertView.findViewById(R.id.tv_check);
            holder.tvUrl = convertView.findViewById(R.id.tv_url);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        String displayText = item.name;
        if (item.url != null && !item.url.isEmpty()) {
            displayText += "\n" + item.url;
        } else {
            displayText += "\n(未找到链接地址)";
        }
        holder.tvUrl.setText(displayText);

        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(PROTECTED_LIVE_URL) || item.url.equals(PROTECTED_EPG_URL));

        if (isProtected) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
        }

        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            holder.tvCheck.setVisibility(View.VISIBLE);
            holder.tvUrl.setTextColor(COLOR_SELECTED);
            convertView.setBackgroundColor(COLOR_SELECTED_BG);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
            holder.tvUrl.setTextColor(COLOR_NORMAL);
            convertView.setBackgroundColor(COLOR_NORMAL_BG);
        }

        holder.btnCopy.setFocusable(true);
        holder.btnCopy.setFocusableInTouchMode(true);
        holder.btnDelete.setFocusable(true);
        holder.btnDelete.setFocusableInTouchMode(true);

        final View finalView = convertView;
        final int finalPosition = position;

        finalView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                selectedPosition = finalPosition;
                notifyDataSetChanged();
            }
        });

        finalView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (pendingKeyPosition != finalPosition) {
                        pendingKeyPosition = finalPosition;
                        selectedPosition = finalPosition;
                        notifyDataSetChanged();
                        android.widget.Toast.makeText(getContext(), "再次按确认键切换", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    } else {
                        if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                            actionListener.onSwitch(finalPosition);
                        }
                        pendingKeyPosition = -1;
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    holder.btnCopy.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (finalPosition > 0) {
                        android.view.View prevItem = parent.getChildAt(finalPosition - 1);
                        if (prevItem != null) {
                            prevItem.requestFocus();
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (finalPosition < getCount() - 1) {
                        android.view.View nextItem = parent.getChildAt(finalPosition + 1);
                        if (nextItem != null) {
                            nextItem.requestFocus();
                        }
                    }
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    return true;
                }
            }
            return false;
        });

        holder.btnCopy.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                    android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (holder.btnDelete.getVisibility() == View.VISIBLE) {
                        holder.btnDelete.requestFocus();
                    } else {
                        if (finalPosition < getCount() - 1) {
                            android.view.View nextItem = parent.getChildAt(finalPosition + 1);
                            if (nextItem != null) {
                                nextItem.requestFocus();
                            }
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    finalView.requestFocus();
                    return true;
                }
            }
            return false;
        });

        holder.btnDelete.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                        actionListener.onDelete(finalPosition);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    holder.btnCopy.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (finalPosition < getCount() - 1) {
                        android.view.View nextItem = parent.getChildAt(finalPosition + 1);
                        if (nextItem != null) {
                            nextItem.requestFocus();
                        }
                    }
                    return true;
                }
            }
            return false;
        });

        convertView.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
                pendingKeyPosition = -1;
                actionListener.onSwitch(position);
            }
        });

        holder.btnCopy.setOnClickListener(v -> {
            if (item.url != null && !item.url.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        return convertView;
    }

    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        Button btnCopy;
        Button btnDelete;
    }
}