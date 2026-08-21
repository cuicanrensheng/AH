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
    private OnActionListener actionListener;
    private android.widget.ListView listViewRef;

    private static final String PROTECTED_LIVE_URL = UrlConfig.LIVE_URL;
    private static final String PROTECTED_LIVE_URL_2 = UrlConfig.LIVE_URL_2;
    private static final String PROTECTED_EPG_URL = UrlConfig.EPG_URL;
    private static final String PROTECTED_EPG_URL_2 = UrlConfig.EPG_URL_2;

    private static final int COLOR_SELECTED = 0xFF40A9FF;
    private static final int COLOR_SELECTED_BG = 0x3340A9FF;
    private static final int COLOR_BUTTON_BG = 0xFFB3D9FF;
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

    /**
     * 绑定关联的 ListView，供就地更新高亮使用。
     */
    public void setListView(android.widget.ListView listView) {
        this.listViewRef = listView;
    }

    /**
     * 供外部（ListView 的 OnItemSelectedListener）在焦点于项间移动时调用，
     * 更新选中位置并就地刷新高亮，不重建列表、不打断焦点移动。
     */
    public void notifySelected(int position) {
        if (selectedPosition == position) return;
        selectedPosition = position;
        if (listViewRef != null) {
            updateHighlight(listViewRef);
        } else {
            notifyDataSetChanged();
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
            holder = new ViewHolder();
            holder.tvCheck = convertView.findViewById(R.id.tv_check);
            holder.tvUrl = convertView.findViewById(R.id.tv_url);
            holder.contentLayout = convertView.findViewById(R.id.content_layout);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);

            final View itemView = convertView;
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    // 焦点移动到该项：同步选中状态后就地更新所有可见项样式。
                    // 关键：不调用 notifyDataSetChanged 重建列表，
                    // 否则会打断焦点移动导致高亮永远停在首位。
                    int focusPos = getPositionForItem((android.widget.ListView) parent, itemView);
                    if (focusPos >= 0) {
                        selectedPosition = focusPos;
                    }
                    updateHighlight((ViewGroup) parent);
                }
            });
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        // 🔒 列表项只显示名称，不显示URL。URL 仅在点"复制"按钮时写入剪贴板。
        // 避免屏上直接暴露内置/外接源地址被截屏泄露。
        String displayText = item.name != null ? item.name : "";
        holder.tvUrl.setText(displayText);

        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(PROTECTED_LIVE_URL) || item.url.equals(PROTECTED_LIVE_URL_2)
                 || item.url.equals(PROTECTED_EPG_URL) || item.url.equals(PROTECTED_EPG_URL_2));

        if (isProtected) {
            holder.btnDelete.setVisibility(View.GONE);
            holder.btnCopy.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.btnCopy.setVisibility(View.VISIBLE);
        }

        holder.btnCopy.setFocusable(true);
        holder.btnDelete.setFocusable(true);
        holder.btnDelete.setTextColor(COLOR_NORMAL);
        holder.btnDelete.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL);
        holder.btnDelete.setBackgroundColor(0xFF55576A);
        holder.pendingDelete = false;

        // 根据选中状态设置当前项的样式
        applyItemStyle(convertView, holder, position == selectedPosition);

        final View finalView = convertView;
        final int finalPosition = position;

        finalView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    // 🔧 修复：行根确认键按一次即切换，不再需要按两次。
                    // 原来的两步确认（pendingKeyPosition）在手机上体验差。
                    selectedPosition = finalPosition;
                    if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                        actionListener.onSwitch(finalPosition);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 行根右键：跳到下一行（左右键在行间自由移动）
                    if (finalPosition < getCount() - 1) {
                        selectedPosition = finalPosition + 1;
                        updateHighlight(parent);
                        android.view.View target = findChildByPosition(parent, finalPosition + 1);
                        if (target != null) {
                            target.requestFocus();
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    // 行根左键：跳到上一行
                    if (finalPosition > 0) {
                        selectedPosition = finalPosition - 1;
                        updateHighlight(parent);
                        android.view.View target = findChildByPosition(parent, finalPosition - 1);
                        if (target != null) {
                            target.requestFocus();
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (finalPosition > 0) {
                        // 主动更新选中位置并就地刷新高亮，确保光标移动时高亮同步跟随
                        selectedPosition = finalPosition - 1;
                        updateHighlight(parent);
                        android.view.View target = findChildByPosition(parent, finalPosition - 1);
                        if (target != null) {
                            target.requestFocus();
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (finalPosition < getCount() - 1) {
                        // 主动更新选中位置并就地刷新高亮，确保光标移动时高亮同步跟随
                        selectedPosition = finalPosition + 1;
                        updateHighlight(parent);
                        android.view.View target = findChildByPosition(parent, finalPosition + 1);
                        if (target != null) {
                            target.requestFocus();
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
                            selectedPosition = finalPosition + 1;
                            updateHighlight(parent);
                            android.view.View nextItem = findChildByPosition(parent, finalPosition + 1);
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

        holder.btnDelete.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                holder.btnDelete.setTextColor(COLOR_SELECTED);
                holder.btnDelete.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                holder.btnDelete.setBackgroundColor(COLOR_BUTTON_BG);
            } else {
                holder.pendingDelete = false;
                holder.btnDelete.setTextColor(COLOR_NORMAL);
                holder.btnDelete.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL);
                holder.btnDelete.setBackgroundColor(0xFF55576A);
            }
        });

        holder.btnDelete.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    // 🔧 修复：删除按钮按一次即执行删除确认，不再需要按两次。
                    // 原来的两步确认（pendingDelete）在手机上体验差。
                    if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                        actionListener.onDelete(finalPosition);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    holder.btnCopy.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (finalPosition < getCount() - 1) {
                        selectedPosition = finalPosition + 1;
                        updateHighlight(parent);
                        android.view.View nextItem = findChildByPosition(parent, finalPosition + 1);
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
                actionListener.onSwitch(position);
            }
        });

        // 🛡️ 双重保险：content_layout 也绑同一个 onClickListener
        // 防止某些 ROM / 厂商魔改下触摸事件仍被内层拦截（虽然已把 clickable=false）
        holder.contentLayout.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
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
            holder.pendingDelete = false;
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        return convertView;
    }

    /**
     * 就地更新所有可见项的高亮样式，不重建列表。
     * 焦点移动时调用，遍历 ListView 的可见子视图，把选中项设为高亮，其余恢复为普通。
     */
    private void updateHighlight(ViewGroup parent) {
        if (!(parent instanceof android.widget.ListView)) {
            return;
        }
        android.widget.ListView listView = (android.widget.ListView) parent;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child == null) continue;
            ViewHolder h = (ViewHolder) child.getTag();
            if (h == null) continue;
            int pos = getPositionForItem(listView, child);
            boolean selected = (pos == selectedPosition);
            applyItemStyle(child, h, selected);
        }
    }

    /**
     * 反查某个可见子视图对应的数据 position。
     */
    private int getPositionForItem(android.widget.ListView listView, View child) {
        return listView.getPositionForView(child);
    }

    /**
     * 设置单个列表项的高亮样式。
     */
    private void applyItemStyle(View itemView, ViewHolder holder, boolean isSelected) {
        if (isSelected) {
            holder.tvCheck.setVisibility(View.VISIBLE);
            holder.tvUrl.setTextColor(COLOR_SELECTED);
            itemView.setBackgroundColor(COLOR_SELECTED_BG);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
            holder.tvUrl.setTextColor(COLOR_NORMAL);
            itemView.setBackgroundColor(COLOR_NORMAL_BG);
        }
    }

    /**
     * 在 ListView 的可见子视图中，找到数据 position 对应的那一项。
     * ListView.getChildAt 的索引是「屏幕可见子视图序号」，不是数据 position，
     * 列表滚动或排序后两者会不一致，因此用 getPositionForView 反查。
     */
    private static android.view.View findChildByPosition(ViewGroup parent, int position) {
        if (parent instanceof android.widget.ListView) {
            android.widget.ListView listView = (android.widget.ListView) parent;
            for (int i = 0; i < listView.getChildCount(); i++) {
                android.view.View child = listView.getChildAt(i);
                if (child != null && listView.getPositionForView(child) == position) {
                    return child;
                }
            }
        }
        return null;
    }

    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        android.widget.LinearLayout contentLayout;
        Button btnCopy;
        Button btnDelete;
        boolean pendingDelete;
    }
}