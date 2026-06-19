package com.tv.live;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public class DateAdapter extends BaseAdapter {
    private Context ctx;
    private List<String> list;
    public DateAdapter(Context ctx, List<String> list) {
        this.ctx = ctx;
        this.list = list;
    }
    @Override
    public int getCount() { return list.size(); }
    @Override
    public Object getItem(int position) { return list.get(position); }
    @Override
    public long getItemId(int position) { return position; }
    @Override
    public View getView(int position, View v, ViewGroup parent) {
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_date, parent, false);
        }
        TextView tv = v.findViewById(R.id.tv_date);
        tv.setText(list.get(position));
        return v;
    }
}
