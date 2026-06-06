package com.tv.live.manager;

import android.content.Context;
import android.widget.ListView;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

/**
 * 列表控件管理器：统一创建4类列表适配器
 */
public class WidgetInitManager {
    private final Context context;

    public WidgetInitManager(Context context) {
        this.context = context;
    }

    public ChannelListManager createChannelListManager(ListView lv) {
        return new ChannelListManager(context, lv);
    }
    public GroupListManager createGroupListManager(ListView lv) {
        return new GroupListManager(context, lv);
    }
    public EpgManagerWrapper createEpgManagerWrapper(ListView lv) {
        return new EpgManagerWrapper(context, lv);
    }
    public DateListManager createDateListManager(ListView lv) {
        DateListManager manager = new DateListManager(context, lv);
        manager.initDate();
        return manager;
    }
}
