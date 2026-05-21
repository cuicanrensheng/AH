package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){
            SharedPreferences sp = context.getSharedPreferences("setting",Context.MODE_PRIVATE);
            boolean isAutoBoot = sp.getBoolean("boot",false);
            if(isAutoBoot){
                Intent mainIntent = new Intent(context,MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(mainIntent);
            }
        }
    }
}
