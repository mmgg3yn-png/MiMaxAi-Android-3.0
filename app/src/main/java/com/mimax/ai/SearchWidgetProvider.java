package com.mimax.ai;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class SearchWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Store.init(context);
        Theme t=Theme.get(Store.theme()); if(Store.flag("ccOn",false))t=Theme.custom(t);
        for(int id:ids){
            RemoteViews v=new RemoteViews(context.getPackageName(),R.layout.widget_search);
            v.setInt(R.id.search_widget_root,"setBackgroundColor",t.surface);
            v.setTextColor(R.id.search_widget_label,t.tx);
            v.setInt(R.id.search_widget_button,"setBackgroundColor",t.ac);
            v.setTextColor(R.id.search_widget_button,Theme.lum(t.ac)>0.58f?0xFF10131A:0xFFFFFFFF);
            Intent open=new Intent(context,BrowserActivity.class);
            open.putExtra("search_widget",true);
            PendingIntent pi=PendingIntent.getActivity(context,4101,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(R.id.search_widget_button,pi);
            v.setOnClickPendingIntent(R.id.search_widget_label,pi);
            manager.updateAppWidget(id,v);
        }
    }
}
