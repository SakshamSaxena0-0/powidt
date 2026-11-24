package com.sparkle.pawidget;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * While the process is alive, also listen to Poweramp's regular (non-explicit) broadcasts. This catches TPOS_SYNC
 * (sent when you seek inside Poweramp), which has no explicit variant, and screen on/off for the ticker.
 * Registering for sticky actions also delivers Poweramp's latest track/status right away.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter poweramp = new IntentFilter();
        poweramp.addAction(PA.ACTION_TRACK_CHANGED);
        poweramp.addAction(PA.ACTION_STATUS_CHANGED);
        poweramp.addAction(PA.ACTION_TRACK_POS_SYNC);
        poweramp.addAction(PA.ACTION_PLAYING_MODE_CHANGED);
        poweramp.addAction(PA.ACTION_EQU_CHANGED);
        register(poweramp, true);

        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        register(screen, false);

        // Output device badge: refresh when headphones / Bluetooth / USB DACs come and go (while we're alive).
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.registerAudioDeviceCallback(new android.media.AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
                    refreshSoon();
                }

                @Override
                public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
                    refreshSoon();
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        }
    }

    private final android.os.Handler mMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mRefresh = () -> Events.EXEC.execute(() -> WidgetRenderer.updateAll(this));

    private final long mStartedAt = android.os.SystemClock.elapsedRealtime();

    private void refreshSoon() {
        // Registration immediately reports the current devices; only react to real changes afterwards.
        if (android.os.SystemClock.elapsedRealtime() - mStartedAt < 2_000) return;
        mMain.removeCallbacks(mRefresh);
        mMain.postDelayed(mRefresh, 800); // devices arrive in bursts
    }

    private void register(IntentFilter filter, boolean exported) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Events.handle(context, intent, goAsync());
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, exported ? RECEIVER_EXPORTED : RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }
}
