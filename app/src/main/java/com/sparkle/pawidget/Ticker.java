package com.sparkle.pawidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Advances the waveform once a second while Poweramp plays and the screen is on (a new bitmap is only sent when
 * another bar lights up).
 * The loop only lives as long as this process does: with notification access granted (LiveProgressService) that is
 * permanent; without it, an inexact fallback alarm nudges the bar every 30 s or so.
 */
final class Ticker {
    private static final long TICK_MS = 1000;
    private static final long FALLBACK_SCREEN_ON_MS = 30_000;
    private static final long FALLBACK_SCREEN_OFF_MS = 120_000;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Context sCtx;
    private static boolean sRunning;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (sCtx == null || !shouldRun(sCtx)) {
                sRunning = false;
                return;
            }
            final Context ctx = sCtx;
            Events.EXEC.execute(() -> WidgetRenderer.updateProgress(ctx)); // bitmaps are built off the main thread
            HANDLER.postDelayed(this, TICK_MS);
        }
    };

    private Ticker() {}

    /** Re-checks whether the loop should run. Safe from any thread. */
    static void evaluate(Context context) {
        final Context app = context.getApplicationContext();
        HANDLER.post(() -> {
            sCtx = app;
            boolean run = shouldRun(app);
            if (run && !sRunning) {
                sRunning = true;
                HANDLER.removeCallbacks(TICK);
                HANDLER.postDelayed(TICK, TICK_MS);
            } else if (!run && sRunning) {
                sRunning = false;
                HANDLER.removeCallbacks(TICK);
            }
            scheduleFallback(app);
        });
    }

    private static boolean shouldRun(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return PlayerState.get(ctx).isPlaying()
                && pm != null && pm.isInteractive()
                && WidgetRenderer.widgetIds(ctx).length > 0;
    }

    private static void scheduleFallback(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7,
                new Intent(ctx, WidgetActionReceiver.class).setAction(WidgetActionReceiver.ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean needed = PlayerState.get(ctx).isPlaying()
                && !LiveProgressService.isConnected()
                && WidgetRenderer.widgetIds(ctx).length > 0;
        if (!needed) {
            am.cancel(pi);
            return;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        long delay = pm != null && pm.isInteractive() ? FALLBACK_SCREEN_ON_MS : FALLBACK_SCREEN_OFF_MS;
        // Non-wakeup and inexact: never wakes the phone just for the widget.
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pi);
    }
}
