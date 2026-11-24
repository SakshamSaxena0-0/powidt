package com.sparkle.pawidget;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;

/**
 * The subset of the official Poweramp API (github.com/maxmpz/powerampapi, PowerampAPI.java / PowerampAPIHelper.java)
 * that this widget uses.
 */
final class PA {
    private static final String TAG = "SparklePA";

    static final String DEFAULT_PACKAGE = "com.maxmpz.audioplayer";
    /** Broadcast receiver that accepts API commands (Poweramp build 855+). */
    static final String API_RECEIVER_NAME = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";
    static final String ACTIVITY_PLAYER_UI = "com.maxmpz.audioplayer.PlayerUIActivity";
    /** Target of ACTION_OPEN_EQ / ACTION_OPEN_LIBRARY. */
    static final String ACTIVITY_STARTUP = "com.maxmpz.audioplayer.MainActivity";
    static final String ACTION_OPEN_EQ = "com.maxmpz.audioplayer.ACTION_OPEN_EQ";
    static final String ACTION_OPEN_LIBRARY = "com.maxmpz.audioplayer.ACTION_OPEN_LIBRARY";

    static final String ACTION_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND";
    static final String ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED";
    static final String ACTION_TRACK_CHANGED_EXPLICIT = "com.maxmpz.audioplayer.TRACK_CHANGED_EXPLICIT";
    static final String ACTION_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED";
    static final String ACTION_STATUS_CHANGED_EXPLICIT = "com.maxmpz.audioplayer.STATUS_CHANGED_EXPLICIT";
    static final String ACTION_TRACK_POS_SYNC = "com.maxmpz.audioplayer.TPOS_SYNC";
    /** Sticky; repeat/shuffle mode. No explicit variant exists. */
    static final String ACTION_PLAYING_MODE_CHANGED = "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED";

    static final Uri AA_ROOT_URI = Uri.parse("content://com.maxmpz.audioplayer.aa");

    // Extras
    static final String EXTRA_COMMAND = "cmd";
    static final String EXTRA_PACKAGE = "pak";
    static final String EXTRA_SOURCE = "src";
    static final String EXTRA_TRACK = "track";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_PAUSED = "paused";
    static final String EXTRA_POSITION = "pos"; // seconds
    static final String EXTRA_TIMESTAMP = "ts"; // System.currentTimeMillis()
    static final String EXTRA_SHUFFLE = "shuffle";
    static final String EXTRA_REPEAT = "repeat";
    static final String EXTRA_RATING = "rating";

    // Track bundle keys
    static final String TRACK_ID = "id";
    static final String TRACK_REAL_ID = "realId";
    static final String TRACK_TITLE = "title";
    static final String TRACK_ARTIST = "artist";
    static final String TRACK_ALBUM = "album";
    static final String TRACK_DURATION = "dur"; // seconds
    static final String TRACK_DURATION_MS = "durMs"; // build 948+
    static final String TRACK_SUPPORTS_CAT_NAV = "supportsCatNav";
    static final String TRACK_PATH = "path";
    static final String TRACK_FILE_TYPE = "fileType";
    static final String TRACK_CODEC = "codec";
    static final String TRACK_SAMPLE_RATE = "sampleRate";
    static final String TRACK_BITRATE = "bitRate";
    static final String TRACK_BITS_PER_SAMPLE = "bitsPerSample";
    static final String TRACK_RATING = "rating"; // 0 none, 1 unlike, 2-4 stars, 5 like (build 995+)
    static final String TRACK_POS_IN_LIST = "posInList"; // 0-based
    static final String TRACK_LIST_SIZE = "listSize";
    static final String TRACK_CAT_URI = "catUri"; // Uri of the list the track plays from

    // States
    static final int STATE_STOPPED = 0;

    // Modes
    static final int SHUFFLE_NONE = 0;
    static final int SHUFFLE_SONGS = 2; // songs of the current list shuffled
    static final int REPEAT_NONE = 0;
    static final int REPEAT_ON = 1;
    static final int REPEAT_ADVANCE = 2;
    static final int REPEAT_SONG = 3;

    static final int RATING_NONE = 0;
    static final int RATING_LIKE = 5;

    // Commands
    static final int CMD_TOGGLE_PLAY_PAUSE = 1;
    static final int CMD_NEXT = 4;
    static final int CMD_PREVIOUS = 5;
    static final int CMD_REPEAT = 8; // with EXTRA_REPEAT: set that mode
    static final int CMD_SHUFFLE = 9; // with EXTRA_SHUFFLE: set that mode
    static final int CMD_SEEK = 15;
    static final int CMD_POS_SYNC = 16;
    static final int CMD_SET_RATING = 24; // with EXTRA_RATING 0..5
    static final int CMD_SLEEP_TIMER = 17; // with EXTRA_SECONDS (0 = off), EXTRA_PLAY_TO_END
    static final int CMD_OPEN_TO_PLAY = 20; // with data = track content uri
    static final int CMD_SEEK_JUMP_FORWARD = 22; // +10 s
    static final int CMD_SEEK_JUMP_BACKWARD = 23; // -10 s
    static final int CMD_SET_EQU_PRESET = 50; // with EXTRA_ID (long)

    static final String EXTRA_SECONDS = "seconds";
    static final String EXTRA_PLAY_TO_END = "play_to_end";
    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";

    /** Sent by Poweramp when the equalizer preset changes (non-sticky; reaches us while our process is alive). */
    static final String ACTION_EQU_CHANGED = "com.maxmpz.audioplayer.EQU_CHANGED";
    /** Asks Poweramp to let this app read its library (queue, lists, EQ presets). */
    static final String ACTION_ASK_FOR_DATA_PERMISSION = "com.maxmpz.audioplayer.ACTION_ASK_FOR_DATA_PERMISSION";
    static final String API_ACTIVITY_NAME = "com.maxmpz.audioplayer.PowerampAPIActivity";
    static final Uri DATA_ROOT = Uri.parse("content://com.maxmpz.audioplayer.data");

    private static volatile String sPackage;

    private PA() {}

    /** Resolves the installed Poweramp package (falls back to the default package name). */
    static String packageName(Context ctx) {
        String pak = sPackage;
        if (pak != null) return pak;
        try {
            ResolveInfo info = ctx.getPackageManager().resolveService(new Intent(ACTION_API_COMMAND), 0);
            if (info != null && info.serviceInfo != null) {
                pak = sPackage = info.serviceInfo.packageName;
                return pak;
            }
        } catch (Throwable th) {
            Log.w(TAG, "resolve failed", th);
        }
        return DEFAULT_PACKAGE; // not cached, so a later install is picked up
    }

    static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(packageName(ctx), 0);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    static Intent command(Context ctx, int cmd) {
        return new Intent(ACTION_API_COMMAND)
                .setComponent(new ComponentName(packageName(ctx), API_RECEIVER_NAME))
                .putExtra(EXTRA_COMMAND, cmd)
                .putExtra(EXTRA_PACKAGE, ctx.getPackageName())
                .putExtra(EXTRA_SOURCE, "widget");
    }

    static void send(Context ctx, Intent intent) {
        try {
            ctx.sendBroadcast(intent);
        } catch (Throwable th) {
            Log.e(TAG, "send failed " + intent, th);
        }
    }

    /** Widget button -> broadcast straight to Poweramp's API receiver (no hop through this app). */
    static PendingIntent commandPendingIntent(Context ctx, int cmd) {
        return PendingIntent.getBroadcast(ctx, cmd, command(ctx, cmd),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openPlayerPendingIntent(Context ctx) {
        Intent launch = null;
        try {
            launch = ctx.getPackageManager().getLaunchIntentForPackage(packageName(ctx));
        } catch (Throwable ignored) {
        }
        if (launch == null) {
            if (isInstalled(ctx)) {
                launch = new Intent().setComponent(new ComponentName(packageName(ctx), ACTIVITY_PLAYER_UI));
            } else {
                launch = new Intent(ctx, SetupActivity.class);
            }
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 100, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Shows Poweramp's "allow access to data" prompt for this app (call from a foreground activity). */
    static void askForDataPermission(android.app.Activity activity) {
        try {
            activity.startActivity(new Intent(ACTION_ASK_FOR_DATA_PERMISSION)
                    .setComponent(new ComponentName(packageName(activity), API_ACTIVITY_NAME))
                    .putExtra(EXTRA_PACKAGE, activity.getPackageName()));
        } catch (Throwable th) {
            Log.w(TAG, "ask for data permission failed", th);
            send(activity, new Intent(ACTION_ASK_FOR_DATA_PERMISSION)
                    .setComponent(new ComponentName(packageName(activity), API_RECEIVER_NAME))
                    .putExtra(EXTRA_PACKAGE, activity.getPackageName()));
        }
    }

    /** Opens a Poweramp screen (equalizer, library) via its documented startActivity actions. */
    static PendingIntent openScreenPendingIntent(Context ctx, String action, int requestCode) {
        Intent i = new Intent(action)
                .setComponent(new ComponentName(packageName(ctx), ACTIVITY_STARTUP))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Album art from Poweramp's provider, subsampled close to the requested size.
     * Mirrors PowerampAPIHelper.getAlbumArt().
     */
    static Bitmap loadAlbumArt(Context ctx, long realId, int targetPx) {
        if (realId < 0) return null;
        Uri uri = AA_ROOT_URI.buildUpon().appendEncodedPath("files").appendEncodedPath(Long.toString(realId)).build();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                Log.w(TAG, "album art: no descriptor for " + uri);
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            if (pfd.getStatSize() > 0) { // a pipe can't be read twice, so only subsample real files
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
                opts.inJustDecodeBounds = false;
                int sample = 1;
                int w = opts.outWidth >> 1, h = opts.outHeight >> 1;
                while (w >= targetPx && h >= targetPx) {
                    sample <<= 1;
                    w >>= 1;
                    h >>= 1;
                }
                opts.inSampleSize = sample;
            }
            Bitmap b = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
            if (b == null) Log.w(TAG, "album art: decode failed, size=" + pfd.getStatSize() + " uri=" + uri);
            return b;
        } catch (FileNotFoundException e) {
            Log.i(TAG, "album art: none for " + uri + " (" + e.getMessage() + ")");
            return null; // track has no art
        } catch (Throwable th) {
            Log.w(TAG, "album art failed", th);
            return null;
        }
    }
}
