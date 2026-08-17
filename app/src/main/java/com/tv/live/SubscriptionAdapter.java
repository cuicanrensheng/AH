package com.tv.live;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private static final String TAG = "SubscriptionAdapter";

    private int selectedPosition = -1;
    private OnActionListener actionListener;
    private Runnable upBoundaryListener;    // 列表顶时按UP的外部回调（跳到输入框等）
    private Runnable downBoundaryListener;  // 列表底时按DOWN的外部回调（跳到btn_clear等）

    private static final String PROTECTED_LIVE_URL = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    private static final String PROTECTED_LIVE_URL_2 = "https://gitee.com/qf_1111/iptv/raw/master/iptvedqw.m3u";
    private static final String PROTECTED_EPG_URL = "https://e.erw.cc/all.xml.gz";
    private static final String PROTECTED_EPG_URL_2 = "https://epg.catvod.com/epg.xml";
    private static final String PROTECTED_EPG_URL_3 = "http://epg.51zmt.top:8000/e.xml.gz";

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

    public void setBoundaryListeners(Runnable onUpBoundary, Runnable onDownBoundary) {
        this.upBoundaryListener = onUpBoundary;
        this.downBoundaryListener = onDownBoundary;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    /**
     * 仅更新selectedPosition成员变量，不触发notifyDataSetChanged()重建视图。
     * 用于"切换默认源"时避免可见View被销毁→焦点丢失闪烁。
     * 调用方需手动同步更新当前屏幕上可见的旧/新item的selected态（setSelected）。
     */
    public void setSelectedPosition(int position, boolean notify) {
        selectedPosition = position;
        if (notify) {
            notifyDataSetChanged();
        }
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
            holder.llContent = convertView.findViewById(R.id.ll_content);
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
                (item.url.equals(PROTECTED_LIVE_URL) || item.url.equals(PROTECTED_LIVE_URL_2) ||
                 item.url.equals(PROTECTED_EPG_URL) || item.url.equals(PROTECTED_EPG_URL_2) || item.url.equals(PROTECTED_EPG_URL_3));

        if (isProtected) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
        }

        boolean contentHasFocus = holder.llContent.hasFocus();
        if (contentHasFocus) {
            holder.tvCheck.setVisibility(View.VISIBLE);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
        }

        holder.btnCopy.setFocusable(true);
        holder.btnCopy.setFocusableInTouchMode(true);
        holder.btnDelete.setFocusable(true);
        holder.btnDelete.setFocusableInTouchMode(true);

        // 强制清除按钮默认背景和tint，确保自定义selector生效
        holder.btnCopy.setBackground(androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.button_focus_selector));
        holder.btnDelete.setBackground(androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.button_focus_selector));

        final View finalContentView = holder.llContent;
        final int finalPosition = position;
        final TextView finalTvCheck = holder.tvCheck;

        finalContentView.setOnFocusChangeListener((v, focused) -> {
            android.util.Log.i(TAG, "[焦点] ll_content pos=" + finalPosition + " focused=" + focused + " name=" + (item.name != null ? item.name : "null"));
            if (focused) {
                selectedPosition = finalPosition;
                finalTvCheck.setVisibility(View.VISIBLE);
                // ⭐ 修复：用 post 保证 setSelected/requestLayout 后再设置，避免 ROM 重 applyTextAppearance 覆盖
                v.post(() -> setAllTextViewsBold(v, true));
            } else {
                finalTvCheck.setVisibility(View.GONE);
                // ⭐ 修复：只有失焦项已不是选中项时才取消加粗；若是选中项临时失焦（如弹出对话框）保持加粗
                if (finalPosition != selectedPosition) {
                    v.post(() -> setAllTextViewsBold(v, false));
                } else {
                    // 选中项临时失焦：对号隐藏但文字保持加粗，视觉稳定
                    v.post(() -> setAllTextViewsBold(v, true));
                }
            }
        });

        finalContentView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    // 单次确认直接触发切换，不再需要"按两次"
                    if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                        selectedPosition = finalPosition;
                        actionListener.onSwitch(finalPosition);
                    }
                    return true;
                }
            }
            return false;
        });

        holder.btnCopy.setOnFocusChangeListener((v, hasFocus) ->
            android.util.Log.i(TAG, "[焦点] btn_copy pos=" + position + " focused=" + hasFocus));
        holder.btnDelete.setOnFocusChangeListener((v, hasFocus) ->
            android.util.Log.i(TAG, "[焦点] btn_delete pos=" + position + " focused=" + hasFocus));

        holder.btnCopy.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                    android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
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
                }
            }
            return false;
        });

        // 统一的"切换默认源"触发方法（触摸点击、ENTER键、performClick都走这里）
        java.lang.Runnable triggerSwitch = () -> {
            if (actionListener == null) {
                android.util.Log.w(TAG, "[切换] 取消：actionListener==null pos=" + finalPosition);
                return;
            }
            if (finalPosition < 0 || finalPosition >= getCount()) {
                android.util.Log.w(TAG, "[切换] 取消：越界 pos=" + finalPosition + " count=" + getCount());
                return;
            }
            android.util.Log.i(TAG, "[切换] 触发 onSwitch pos=" + finalPosition + " name=" +
                    ((getItem(finalPosition) != null && getItem(finalPosition).name != null) ? getItem(finalPosition).name : "null"));
            selectedPosition = finalPosition;
            actionListener.onSwitch(finalPosition);
        };

        // 解决focusableInTouchMode=true导致手机第一次触摸只抢焦点不触发onClick的问题
        // 额外解决：ListView在MOVE时默认拦截触摸，导致子View收到ACTION_CANCEL而不是ACTION_UP
        // 策略：
        //   DOWN → 抢焦点 + requestDisallowInterceptTouchEvent(true)禁止父容器ListView抢整个触摸序列
        //   UP   → 直接同步执行triggerSwitch切换默认源
        //   CANCEL→ 兜底：如果之前发过Disallow也执行一次（万一父容器仍然强制拦截）
        finalContentView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (!v.isFocused()) v.requestFocus();
                // 关键：禁止父容器（AbsListView）拦截MOVE/UP，确保整个触摸序列都发到ll_content
                android.view.ViewParent vp = v.getParent();
                if (vp != null) {
                    vp.requestDisallowInterceptTouchEvent(true);
                    android.util.Log.i(TAG, "[触摸] DOWN pos=" + finalPosition + " → 已Disallow父容器拦截");
                } else {
                    android.util.Log.i(TAG, "[触摸] DOWN pos=" + finalPosition + " focusedNow=" + v.isFocused());
                }
                return true; // 消费DOWN
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                android.util.Log.i(TAG, "[触摸] UP pos=" + finalPosition + " → 执行切换 triggerSwitch");
                triggerSwitch.run();
                if (!v.isFocused()) v.requestFocus();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                android.util.Log.w(TAG, "[触摸] CANCEL pos=" + finalPosition + "（被父容器拦截了）→ 兜底执行切换");
                // 兜底：如果ListView通过其他手段抢走并触发ACTION_CANCEL，也尽量执行一次切换
                triggerSwitch.run();
                return true;
            }
            return false;
        });

        finalContentView.setOnClickListener(v -> {
            // 系统其他路径触发的点击（非触摸/非ENTER），走同一切换逻辑
            android.util.Log.i(TAG, "[点击] onClick(pos=" + finalPosition + ")");
            triggerSwitch.run();
        });

        holder.btnCopy.setOnClickListener(v -> {
            if (item.url != null && !item.url.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // btnCopy也有focusableInTouchMode=true，防止手机第一次触摸只抢焦点不触发onClick
        holder.btnCopy.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (!v.isFocused()) v.requestFocus();
                android.view.ViewParent vp = v.getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.performClick(); // 兜底
                return true;
            }
            return false;
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });
        // btnDelete也有focusableInTouchMode=true，防止手机第一次触摸只抢焦点不触发onClick
        holder.btnDelete.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (!v.isFocused()) v.requestFocus();
                android.view.ViewParent vp = v.getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.performClick(); // 兜底
                return true;
            }
            return false;
        });

        // ⭐【修复字体加粗残留根因】每次 getView（包括 convertView 复用）都根据 selectedPosition
        // 显式重置整个 ll_content 树的加粗状态。否则滚动回收后旧 convertView 的加粗/普通
        // 状态会保留（如之前选中pos=5加粗，回收后给pos=3用，pos=3会被误加粗且无法取消）。
        final boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            holder.tvCheck.setVisibility(View.VISIBLE);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
        }
        final View rootForBold = holder.llContent;
        rootForBold.post(() -> setAllTextViewsBold(rootForBold, isSelected));

        return convertView;
    }

    private static String keyCodeName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "CENTER";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_BACK: return "BACK";
            default: return "KEY_" + keyCode;
        }
    }

    // 按firstVisiblePosition计算相邻项可见位置，跨可见区时先setSelection再post移动焦点
    // direction: -1=UP, +1=DOWN
    // 返回true表示已消费（已处理或已到边界）
    static boolean handleVerticalDpad(ViewGroup parent, int adapterPosition,
                                      int direction, int preferredChildId,
                                      Runnable onUpBoundary, Runnable onDownBoundary) {
        // 向上遍历找 AbsListView（ListView的父类）。用final引用让lambda可捕获。
        android.widget.AbsListView foundLv = null;
        android.view.View p = parent;
        while (p != null) {
            if (p instanceof android.widget.AbsListView) { foundLv = (android.widget.AbsListView) p; break; }
            if (p.getParent() instanceof android.view.View) p = (android.view.View) p.getParent(); else break;
        }
        final android.widget.AbsListView lv = foundLv;
        if (lv == null) return false;
        int targetPos = adapterPosition + direction;
        int total = lv.getAdapter() != null ? lv.getAdapter().getCount() : 0;
        if (targetPos < 0 || targetPos >= total) {
            android.util.Log.i(TAG, "[导航] 边界 adapterPos=" + adapterPosition + " dir=" + direction + " -> 交给外部");
            Runnable boundaryCb = (direction < 0) ? onUpBoundary : onDownBoundary;
            if (boundaryCb != null) boundaryCb.run();
            return true;
        }
        int first = lv.getFirstVisiblePosition();
        int last = lv.getLastVisiblePosition();
        final int finalTarget = targetPos;
        final int finalId = preferredChildId;
        if (targetPos >= first && targetPos <= last) {
            android.view.View item = lv.getChildAt(targetPos - first);
            if (moveFocusToChild(item, preferredChildId)) {
                android.util.Log.i(TAG, "[导航] 直接移 pos=" + adapterPosition + " -> " + targetPos + " id=" + preferredChildId);
                return true;
            }
        }
        // 目标在可见区外：先setSelection滚动进来，再post移焦点
        android.util.Log.i(TAG, "[导航] 先滚动 pos=" + adapterPosition + " -> " + targetPos + " (first=" + first + " last=" + last + ")");
        lv.setSelection(targetPos);
        lv.post(() -> {
            int f = lv.getFirstVisiblePosition();
            android.view.View item = lv.getChildAt(finalTarget - f);
            if (!moveFocusToChild(item, finalId)) {
                android.util.Log.w(TAG, "[导航] 滚动后仍无法移焦点 pos=" + finalTarget);
                lv.requestFocus();
            }
        });
        return true;
    }

    // 将焦点移到指定item内的preferredChildId（若不可见fallback到ll_content）
    public static boolean moveFocusToChild(android.view.View item, int preferredChildId) {
        if (item == null) return false;
        android.view.View target = item.findViewById(preferredChildId);
        if (target != null && target.isFocusable() && target.getVisibility() == android.view.View.VISIBLE) {
            target.requestFocus();
            return true;
        }
        android.view.View llContent = item.findViewById(R.id.ll_content);
        if (llContent != null && llContent.isFocusable()) {
            llContent.requestFocus();
            return true;
        }
        return false;
    }

    private static class ViewHolder {
        LinearLayout llContent;
        TextView tvCheck;
        TextView tvUrl;
        Button btnCopy;
        Button btnDelete;
    }

    /**
     * 递归给传入View下（含自身）所有TextView设置加粗或恢复普通字重。
     * 保留原有字体族，仅修改style（BOLD/NORMAL），避免中文ROM字体跳变。
     *
     * @param root 起始View（ll_content根容器，也可能本身就是TextView）
     * @param bold true=加粗；false=恢复普通
     */
    private static void setAllTextViewsBold(android.view.View root, boolean bold) {
        if (root == null) return;
        if (root instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) root;
            android.graphics.Typeface base = tv.getTypeface();
            if (base == null) base = android.graphics.Typeface.DEFAULT;
            tv.setTypeface(android.graphics.Typeface.create(base,
                    bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            return;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                setAllTextViewsBold(group.getChildAt(i), bold);
            }
        }
    }
}