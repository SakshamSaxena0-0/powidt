package com.sparkle.pawidget;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Optional helper. When the user grants notification access, Android keeps this service bound, which keeps the
 * process (and the once-a-second seek bar ticker) alive. Notifications themselves are ignored.
 */
public class LiveProgressService extends NotificationListenerService {
    private static volatile boolean sConnected;

    static boolean isConnected() {
        return sConnected;
    }

    @Override
    public void onListenerConnected() {
        sConnected = true;
        if (PlayerState.get(this).isPlaying()) PA.send(this, PA.command(this, PA.CMD_POS_SYNC));
        Ticker.evaluate(this);
    }

    @Override
    public void onListenerDisconnected() {
        sConnected = false;
        Ticker.evaluate(this);
        try {
            requestRebind(new ComponentName(this, LiveProgressService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Not used.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not used.
    }
}
