package com.sparkle.pawidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Internal widget actions: shuffle/repeat/favorite toggles, tap-to-seek zones and the fallback progress alarm. */
public class WidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION_SEEK = "com.sparkle.pawidget.SEEK";
    static final String ACTION_TICK = "com.sparkle.pawidget.TICK";
    static final String ACTION_SHUFFLE = "com.sparkle.pawidget.SHUFFLE";
    static final String ACTION_REPEAT = "com.sparkle.pawidget.REPEAT";
    static final String ACTION_FAVORITE = "com.sparkle.pawidget.FAVORITE";
    static final String ACTION_RETRY_ART = "com.sparkle.pawidget.RETRY_ART";
    static final String ACTION_SKIP_BACK = "com.sparkle.pawidget.SKIP_BACK";
    static final String ACTION_SKIP_FWD = "com.sparkle.pawidget.SKIP_FWD";
    static final String ACTION_SLEEP = "com.sparkle.pawidget.SLEEP";
    static final String ACTION_VOL_UP = "com.sparkle.pawidget.VOL_UP";
    static final String ACTION_VOL_DOWN = "com.sparkle.pawidget.VOL_DOWN";
    static final String ACTION_EQ_NEXT = "com.sparkle.pawidget.EQ_NEXT";
    static final String ACTION_PLAY_ITEM = "com.sparkle.pawidget.PLAY_ITEM";
    static final String EXTRA_URI = "uri";
    static final String EXTRA_ZONE = "zone";

    @Override
    public void onReceive(Context context, Intent intent) {
        Events.handle(context, intent, goAsync());
    }
}
