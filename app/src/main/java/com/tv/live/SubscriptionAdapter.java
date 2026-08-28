package com.tv.live;


import com.tv.live.util.LogBridge;
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

/**
 * 🔧 订阅源列表适配器 —— 彻底迁移到 Android 原生焦点机制：
 *
 *   视觉样式（蓝底 + 蓝字 + ✓）不再由代码 setBackgroundColor/setTextColor/setVisibility 手动控制，
 *   而是由 @drawable/subscription_row_content_bg、@color/subscription_row_text、
 *   @color/subscription_check_text 这些 state list selector 根据
 *   state_focused / state_selected / state_activated 自动切换。
 *
 *   selectedPosition 变量仅承担「点击确定按钮时，把当前选中项回调出去」的数据责任，
 *   样式 100% 跟随 Android 原生焦点链，红框焦点移到哪里视觉就到哪里。
 */
public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnActionListener actionListener;
    private android.widget.ListView listViewRef;

    public interface OnActionListener {
        void onSwitch(int position);
        void onDelete(int position);
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    /** 设置「当前选中项」——用于对话框打开时定位到默认源，以及 setItemChecked 激活原生激活态 */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        if (listViewRef != null && position >= 0) {
            listViewRef.setSelection(position);
            // 🔧 配合 CHOICE_MODE_SINGLE 激活 state_activated，让原生 selector 立刻渲染蓝底/蓝字
            if (position < listViewRef.getCount()) {
                listViewRef.setItemChecked(position, true);
            }
            // 🔧 对话框打开时「立刻」同步所有可见行的 activated/selected 状态（不用等下一次 notifyDataSetChanged）
            //  如果此时 ListView 还没有 layout children（dialog 未 show），childCount=0，
            //  先做一次空跑，再 postDelayed 150ms 兜底再 apply 一次（等子 View 已添加）
            applyImmediateRowActivated(listViewRef, position);
            if (listViewRef.getChildCount() == 0) {
                final int pos = position;
                listViewRef.postDelayed(() -> applyImmediateRowActivated(listViewRef, pos), 150);
            }
        }
        notifyDataSetChanged();
    }

    /**
     * 🔧 公共接口：把 selectedPosition 对应的行「立即」应用高亮（蓝底/蓝字/加粗）。
     * 供 SettingsDialog 在 dialog.show() 之后调用——此时 ListView 已经 layout 好所有可见子行。
     * 不需要再 notifyDataSetChanged，因为 drawable state 变化直接触发 selector 重绘。
     */
    public void ensureActivatedImmediate() {
        syncSelectedFromList();
        if (listViewRef != null && selectedPosition >= 0) {
            applyImmediateRowActivated(listViewRef, selectedPosition);
        }
    }

    public int getSelectedPosition() {
        // 优先回写原生 selection（实时、和红框焦点一致），selectedPosition 仅作为 fallback 记忆
        syncSelectedFromList();
        return selectedPosition;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    /** 绑定关联的 ListView，用来读取/同步原生 selection / activated 状态 */
    public void setListView(android.widget.ListView listView) {
        this.listViewRef = listView;
    }

    /** 从 ListView 原生 selection 回写到记忆变量，仅给「确定」按钮读取回调参数用 */
    private void syncSelectedFromList() {
        if (listViewRef == null) return;
        int nativePos = listViewRef.getSelectedItemPosition();
        // 🔧 触摸模式（Touch Mode）下 Android 的 getSelectedItemPosition() 永远返回 -1，
        // 不能把 selectedPosition（我们手动写入的「选中行记忆」）覆盖回 -1。
        // 只有返回了有效的 position（>=0，即遥控器/DPAD 导航模式下）才回写。
        if (nativePos >= 0 && nativePos < getCount()) {
            selectedPosition = nativePos;
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
            // 🔧 焦点态加粗体：叠加的第二层 TextView，靠原生 color selector 在「三态=true」时显蓝字
            holder.tvUrlBold = convertView.findViewById(R.id.tv_url_bold);
            holder.contentLayout = convertView.findViewById(R.id.content_layout);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        // 显示文本：名称 + isDefault 标记 ⭐（独立于焦点高亮）
        StringBuilder display = new StringBuilder(item.name != null ? item.name : "");
        if (item.isDefault) display.append("  ⭐");
        String text = display.toString();
        holder.tvUrl.setText(text);
        // 🔧 焦点态加粗体：叠加层也填相同文本，并固定 setFakeBoldText(true) 兜底中文 ROM bold 不生效
        holder.tvUrlBold.setText(text);
        holder.tvUrlBold.getPaint().setFakeBoldText(true);

        // 🔒 内置源保护：「名称精确匹配」或「URL 相等」双重规则 → 隐藏复制/删除
        {
            String n = item.name != null ? item.name : "";
            String u = item.url != null ? item.url : "";
            boolean nameHit =
                    SourceManager.BUILTIN_NAME_LIVE_1.equals(n)
                 || SourceManager.BUILTIN_NAME_LIVE_2.equals(n)
                 || SourceManager.BUILTIN_NAME_LIVE_3.equals(n)
                 || SourceManager.BUILTIN_NAME_EPG_1.equals(n)
                 || SourceManager.BUILTIN_NAME_EPG_2.equals(n);
            boolean urlHit = !u.isEmpty() && (
                    u.equals(UrlConfig.LIVE_URL)
                 || u.equals(UrlConfig.LIVE_URL_2)
                 || u.equals(UrlConfig.LIVE_URL_3)
                 || u.equals(UrlConfig.EPG_URL)
                 || u.equals(UrlConfig.EPG_URL_2)
            );
            boolean isProtected = nameHit || urlHit;
            int vis = isProtected ? View.GONE : View.VISIBLE;
            holder.btnDelete.setVisibility(vis);
            holder.btnCopy.setVisibility(vis);
            holder.btnDelete.setFocusable(!isProtected);
            holder.btnCopy.setFocusable(!isProtected);
            holder.btnDelete.setClickable(!isProtected);
            holder.btnCopy.setClickable(!isProtected);
        }

        final View finalView = convertView;
        final int finalPosition = position;

        // --- 行根 View 按键：DPAD 上下左右行间导航；ENTER/DPAD_CENTER=切换（确定）当前项 ---
        finalView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    LogBridge.e("SUBSCRIPTION", "finalView onKey ENTER/CENTER: finalPos=" + finalPosition + " v.hasFocus=" + v.hasFocus());
                    syncSelectedFromList();
                    if (selectedPosition < 0 || selectedPosition >= getCount()) selectedPosition = finalPosition;
                    if (actionListener != null) actionListener.onSwitch(selectedPosition);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View target = findChildByPosition(parent, next);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (finalPosition > 0) {
                        int prev = finalPosition - 1;
                        if (listViewRef != null) listViewRef.setSelection(prev);
                        View target = findChildByPosition(parent, prev);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (finalPosition > 0) {
                        int prev = finalPosition - 1;
                        if (listViewRef != null) listViewRef.setSelection(prev);
                        View target = findChildByPosition(parent, prev);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View target = findChildByPosition(parent, next);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                }
            }
            return false;
        });

        // --- btnCopy 按键：ENTER=复制地址；RIGHT=跳到下一个非 btnCopy 的焦点；LEFT=跳回行根 ---
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
                    } else if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View nextItem = findChildByPosition(parent, next);
                        if (nextItem != null) nextItem.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    finalView.requestFocus();
                    return true;
                }
            }
            return false;
        });

        // --- btnDelete 按键：ENTER=删除；LEFT=跳回 btnCopy；RIGHT=切到下一行 ---
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
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View nextItem = findChildByPosition(parent, next);
                        if (nextItem != null) nextItem.requestFocus();
                    }
                    return true;
                }
            }
            return false;
        });

        // --- 点击回调（触摸/鼠标场景）：行点击=切换；btnCopy=复制；btnDelete=删除 ---
        convertView.setOnClickListener(v -> {
            LogBridge.e("SUBSCRIPTION", "convertView onClick: position=" + position + " actionListenerNull=" + (actionListener == null));
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
                if (listViewRef != null) {
                    listViewRef.setSelection(position);
                    listViewRef.setItemChecked(position, true);
                }
                // 🔧 触摸点击后「立刻」把所有可见行的 activated/selected 同步掉，
                //  不等待下一次 notifyDataSetChanged → 用户看到蓝底+蓝字+加粗 0 延迟
                applyImmediateRowActivated(listViewRef, position);
                actionListener.onSwitch(position);
            }
        });
        holder.contentLayout.setOnClickListener(v -> {
            LogBridge.e("SUBSCRIPTION", "contentLayout onClick: position=" + position);
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
                if (listViewRef != null) {
                    listViewRef.setSelection(position);
                    listViewRef.setItemChecked(position, true);
                }
                // 🔧 同上：contentLayout 区域（勾选+名称）被触摸也立即同步高亮
                applyImmediateRowActivated(listViewRef, position);
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

        // 🔧 触摸模式也能立刻显示高亮：
        //   ① 把原生 selection 回写到 selectedPosition（和红框焦点一致）
        //   ② 显式给每一行的 convertView 自己写 activated/selected 状态，
        //      不依赖 ListView.CHOICE_MODE_SINGLE 的 setActivated 回调（触摸模式下该回调不会保证触发）
        //   ③ 覆盖：打开窗口初始态 / 滚动后 View 复用 / notifyDataSetChanged 之后刷新
        syncSelectedFromList();
        boolean isSel = selectedPosition >= 0 && position == selectedPosition;
        convertView.setActivated(isSel);
        convertView.setSelected(isSel);

        return convertView;
    }

    /**
     * 在 ListView 的可见子视图中，找到数据 position 对应的那一项。
     * ListView.getChildAt 的索引是「屏幕可见子视图序号」，不是数据 position，
     * 列表滚动或排序后两者会不一致，因此用 getPositionForView 反查。
     */
    private static View findChildByPosition(ViewGroup parent, int position) {
        if (parent instanceof android.widget.ListView) {
            android.widget.ListView listView = (android.widget.ListView) parent;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child != null && listView.getPositionForView(child) == position) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * 🔧 触摸点击后「立刻」同步所有可见行的 activated/selected 状态。
     * 触摸模式下 Android 的 state_focused 永远=false，
     * 且 ListView.CHOICE_MODE_SINGLE 的 setActivated 不保证在 setItemChecked 后立即生效，
     * 因此手动写一遍，保证「浅蓝底 + 蓝字 + 加粗」0 延迟出现。
     */
    private static void applyImmediateRowActivated(android.widget.ListView listView, int clickedPosition) {
        if (listView == null || clickedPosition < 0) return;
        final int N = listView.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = listView.getChildAt(i);
            if (child == null) continue;
            int pos = listView.getPositionForView(child);
            if (pos < 0) continue;
            boolean isTarget = (pos == clickedPosition);
            child.setActivated(isTarget);
            child.setSelected(isTarget);
        }
    }

    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        TextView tvUrlBold; // 🔧 焦点态加粗体（叠加第二层，三态=true 时显蓝字加粗）
        android.widget.LinearLayout contentLayout;
        Button btnCopy;
        Button btnDelete;
    }
}
