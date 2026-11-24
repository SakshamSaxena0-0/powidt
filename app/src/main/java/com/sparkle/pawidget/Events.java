package com.sparkle.pawidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single place where every broadcast (Poweramp, screen, widget taps) turns into state changes and widget updates. */
final class Events {
    private static final String TAG = "SparkleEvents";
    /** One worker thread keeps events ordered and keeps art decoding off the main thread. */
    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private Events() {}

    static void handle(Context context, Intent intent, BroadcastReceiver.PendingResult pending) {
        final Context ctx = context.getApplicationContext();
        EXEC.execute(() -> {
            try {
                process(ctx, intent);
            } catch (Throwable th) {
                Log.e(TAG, "event failed " + intent, th);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    private static void process(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        PlayerState state = PlayerState.get(ctx);

        switch (action) {
            case PA.ACTION_TRACK_CHANGED:
            case PA.ACTION_TRACK_CHANGED_EXPLICIT:
                if (state.applyTrack(intent)) {
                    ListeningStats.record(ctx, state.snapshot());
                    commit(ctx, state);
                }
                break;

            case PA.ACTION_STATUS_CHANGED:
            case PA.ACTION_STATUS_CHANGED_EXPLICIT:
                if (state.applyStatus(intent)) {
                    ListeningStats.record(ctx, state.snapshot());
                    commit(ctx, state);
                }
                break;

            case PA.ACTION_EQU_CHANGED: {
                // "If no name extra exists, it's not a preset" (manual EQ edits): keep the last preset shown.
                String name = intent.getStringExtra(PA.EXTRA_NAME);
                long id = intent.getLongExtra(PA.EXTRA_ID, -1);
                if (name != null && state.applyEqPreset(id, name)) commit(ctx, state);
                break;
            }

            case WidgetActionReceiver.ACTION_SKIP_BACK:
            case WidgetActionReceiver.ACTION_SKIP_FWD: {
                boolean fwd = WidgetActionReceiver.ACTION_SKIP_FWD.equals(action);
                PA.send(ctx, PA.command(ctx, fwd ? PA.CMD_SEEK_JUMP_FORWARD : PA.CMD_SEEK_JUMP_BACKWARD));
                state.seekBy(fwd ? 10_000 : -10_000);
                commit(ctx, state);
                break;
            }

            case WidgetActionReceiver.ACTION_SLEEP: {
                int minutes = state.cycleSleepTimer();
                PA.send(ctx, PA.command(ctx, PA.CMD_SLEEP_TIMER)
                        .putExtra(PA.EXTRA_SECONDS, minutes * 60).putExtra(PA.EXTRA_PLAY_TO_END, false));
                commit(ctx, state);
                // Clear the moon/countdown when the timer runs out.
                if (minutes > 0) refreshAt(ctx, System.currentTimeMillis() + minutes * 60_000L + 2_000);
                break;
            }

            case WidgetActionReceiver.ACTION_VOL_UP:
            case WidgetActionReceiver.ACTION_VOL_DOWN: {
                android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            WidgetActionReceiver.ACTION_VOL_UP.equals(action) ? android.media.AudioManager.ADJUST_RAISE
                                    : android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI);
                }
                break;
            }

            case WidgetActionReceiver.ACTION_EQ_NEXT: {
                LibraryData.Preset next = LibraryData.nextPreset(ctx, state.snapshot().eqPresetId);
                if (next != null) {
                    PA.send(ctx, PA.command(ctx, PA.CMD_SET_EQU_PRESET).putExtra(PA.EXTRA_ID, next.id));
                    state.applyEqPreset(next.id, next.name);
                    commit(ctx, state);
                }
                break;
            }

            case WidgetActionReceiver.ACTION_PLAY_ITEM: {
                String uri = intent.getStringExtra(WidgetActionReceiver.EXTRA_URI);
                if (uri != null) PA.send(ctx, PA.command(ctx, PA.CMD_OPEN_TO_PLAY).setData(android.net.Uri.parse(uri)));
                break;
            }

            case PA.ACTION_TRACK_POS_SYNC: // sent by Poweramp on seek, or in reply to CMD_POS_SYNC
                if (state.applyPositionSync(intent.getIntExtra(PA.EXTRA_POSITION, -1))) commit(ctx, state);
                break;

            case PA.ACTION_PLAYING_MODE_CHANGED:
                if (state.applyMode(intent)) commit(ctx, state);
                break;

            // Optimistic update; if Poweramp settles on a different mode, its PLAYING_MODE_CHANGED broadcast reaches
            // App's dynamic receiver (this process is alive right after the tap) and corrects the icon.
            case WidgetActionReceiver.ACTION_SHUFFLE:
                PA.send(ctx, PA.command(ctx, PA.CMD_SHUFFLE).putExtra(PA.EXTRA_SHUFFLE, state.toggleShuffle()));
                commit(ctx, state);
                break;

            case WidgetActionReceiver.ACTION_REPEAT:
                PA.send(ctx, PA.command(ctx, PA.CMD_REPEAT).putExtra(PA.EXTRA_REPEAT, state.cycleRepeat()));
                commit(ctx, state);
                break;

            case WidgetActionReceiver.ACTION_FAVORITE:
                PA.send(ctx, PA.command(ctx, PA.CMD_SET_RATING).putExtra(PA.EXTRA_RATING, state.toggleFavorite()));
                commit(ctx, state);
                break;

            case Intent.ACTION_SCREEN_ON:
                if (state.isPlaying()) PA.send(ctx, PA.command(ctx, PA.CMD_POS_SYNC)); // fresh position from Poweramp
                WidgetRenderer.updateProgress(ctx);
                break;

            case WidgetActionReceiver.ACTION_SEEK: {
                long dur = state.durationMs();
                if (dur <= 0) break;
                int zone = intent.getIntExtra(WidgetActionReceiver.EXTRA_ZONE, 0);
                float fraction = (zone + 0.5f) / WidgetRenderer.SEEK_ZONES.length;
                int seconds = (int) (fraction * dur / 1000);
                PA.send(ctx, PA.command(ctx, PA.CMD_SEEK).putExtra(PA.EXTRA_POSITION, seconds));
                state.seekTo(seconds * 1000L);
                commit(ctx, state);
                break;
            }

            case WidgetActionReceiver.ACTION_TICK:
                WidgetRenderer.updateProgress(ctx);
                break;

            case WidgetActionReceiver.ACTION_RETRY_ART:
                WidgetRenderer.updateAll(ctx);
                break;

            default:
                break;
        }
        Ticker.evaluate(ctx);
    }

    private static void commit(Context ctx, PlayerState state) {
        state.save(ctx);
        WidgetRenderer.updateAll(ctx);
    }

    /** Full widget refresh at a wall-clock time (e.g. when the sleep timer ends). */
    static void refreshAt(Context ctx, long wallTime) {
        Context app = ctx.getApplicationContext();
        android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(app, 9,
                new Intent(app, WidgetActionReceiver.class).setAction(WidgetActionReceiver.ACTION_RETRY_ART),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(android.app.AlarmManager.RTC, wallTime, pi);
    }

    /**
     * Re-runs a full update later (used to retry album art that failed to load). An alarm, not a Handler: right
     * after a widget update the process is cached and frozen, so an in-process timer would never fire.
     */
    static void retryLater(Context ctx, long delayMs) {
        Context app = ctx.getApplicationContext();
        android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(app, 8,
                new Intent(app, WidgetActionReceiver.class).setAction(WidgetActionReceiver.ACTION_RETRY_ART),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + delayMs, pi);
    }

    /** Pulls Poweramp's sticky broadcasts (latest track + status). Call on the worker thread. */
    static void refreshFromSticky(Context ctx) {
        PlayerState state = PlayerState.get(ctx);
        Intent track = sticky(ctx, PA.ACTION_TRACK_CHANGED);
        Intent status = sticky(ctx, PA.ACTION_STATUS_CHANGED);
        boolean changed = false;

        // Apply the older event first so the newer one decides the final position.
        long trackTs = track != null ? track.getLongExtra(PA.EXTRA_TIMESTAMP, 0) : 0;
        long statusTs = status != null ? status.getLongExtra(PA.EXTRA_TIMESTAMP, 0) : 0;
        if (track != null && status != null && statusTs < trackTs) {
            changed |= state.applyStatus(status);
            changed |= state.applyTrack(track);
        } else {
            if (track != null) changed |= state.applyTrack(track);
            if (status != null) changed |= state.applyStatus(status);
        }
        if (status == null) changed |= state.markNotRunning();
        Intent mode = sticky(ctx, PA.ACTION_PLAYING_MODE_CHANGED);
        if (mode != null) changed |= state.applyMode(mode);
        if (changed) state.save(ctx);
    }

    private static Intent sticky(Context ctx, String action) {
        try {
            IntentFilter f = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.registerReceiver(null, f, Context.RECEIVER_EXPORTED);
            }
            return ctx.registerReceiver(null, f);
        } catch (Throwable th) {
            Log.w(TAG, "sticky read failed " + action, th);
            return null;
        }
    }
}
