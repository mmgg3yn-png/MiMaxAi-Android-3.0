package com.mimax.ai;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Shared palette bridge for app-owned screens; external website content remains site-controlled. */
public final class AppPalette {
    private AppPalette(){}
    private static Theme theme(Activity a){
        Store.init(a);
        Theme t=Theme.get(Store.theme());
        return Store.flag("ccOn",false)?Theme.custom(t):t;
    }
    public static void apply(Activity activity,View root){
        if(activity==null||root==null)return;
        Theme t=theme(activity);
        int base=t.base,surface=t.surface,surface2=t.surface2,text=t.tx,muted=t.mut,accent=t.ac;
        root.setBackgroundColor(base);
        activity.getWindow().setStatusBarColor(base);
        activity.getWindow().setNavigationBarColor(base);
        style(root,base,surface,surface2,text,muted,accent,Theme.lum(accent)>0.58f?0xFF10131A:Color.WHITE);
    }
    private static void style(View v,int base,int surface,int surface2,int text,int muted,int accent,int onAccent){
        if(v instanceof Button){
            Button b=(Button)v;b.setTextColor(onAccent);
            GradientDrawable bg=new GradientDrawable();bg.setColor(accent);bg.setCornerRadius(18);
            b.setBackground(bg);
        }else if(v instanceof EditText){
            EditText e=(EditText)v;e.setTextColor(text);e.setHintTextColor(muted);
            GradientDrawable bg=new GradientDrawable();bg.setColor(surface2);bg.setCornerRadius(12);bg.setStroke(1,muted & 0x55FFFFFF);
            e.setBackground(bg);
        }else if(v instanceof TextView){
            ((TextView)v).setTextColor(text);
        }else if(v.getBackground() instanceof ColorDrawable){
            int c=((ColorDrawable)v.getBackground()).getColor();
            if(c==Color.WHITE||c==0xFFF2F4F7||c==0xFFF1F4F8||c==0xFFF4F6F8||c==0xFF17212D)
                v.setBackgroundColor(surface);
        }
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)style(g.getChildAt(i),base,surface,surface2,text,muted,accent,onAccent);
        }
    }
}
