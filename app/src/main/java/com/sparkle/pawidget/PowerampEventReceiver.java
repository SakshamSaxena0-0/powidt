package com.sparkle.pawidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives Poweramp's TRACK_CHANGED_EXPLICIT / STATUS_CHANGED_EXPLICIT even when this app isn't running. */
public class PowerampEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Events.handle(context, intent, goAsync());
    }
}
