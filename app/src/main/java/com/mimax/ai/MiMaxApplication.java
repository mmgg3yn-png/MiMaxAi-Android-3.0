package com.mimax.ai;

import android.app.Application;

public class MiMaxApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { ExtensionState.recordCrash(getApplicationContext(), error); } catch (Exception ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
