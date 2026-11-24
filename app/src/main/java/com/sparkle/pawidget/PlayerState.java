package com.sparkle.pawidget;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

/**
 * What Poweramp is playing, rebuilt from its broadcasts and persisted so the widget survives process death.
 * Position is stored as (posMs at anchorWall) and extrapolated while playing.
 */
final class PlayerState {
    private static final String TAG = "SparkleState";
    private static final String PREFS = "player_state";
    private static PlayerState sInstance;

    private String title = "";
    private String artist = "";
    private String album = "";
    private String path = "";
    private String codec = "";
    private int fileType = -1;
    private int sampleRate;
    private int bitRate;
    private int bitsPerSample;
    private int rating;
    private int shuffle;
    private int repeat;
    private int posInList = -1;
    private String catUri = "";
    private long sleepEndWall; // 0 = no sleep timer
    private long eqPresetId = -1;
    private String eqPresetName = "";
    private int listSize;
    private long realId = -1;
    private long durMs;
    private boolean playing;
    private boolean hasTrack;
    private long posMs;
    private long anchorWall;
    private long lastTrackTs;
    private long lastStatusTs;

    /** Immutable view used for rendering. */
    static final class Snapshot {
        final String title, artist, album, path, codec;
        final int fileType, sampleRate, bitRate, bitsPerSample, rating, shuffle, repeat, posInList, listSize;
        final String catUri, eqPresetName;
        final long sleepEndWall, eqPresetId;
        final long realId, durMs, posMs;
        final boolean playing, hasTrack;

        Snapshot(PlayerState s, long nowWall) {
            title = s.title;
            artist = s.artist;
            album = s.album;
            path = s.path;
            codec = s.codec;
            fileType = s.fileType;
            sampleRate = s.sampleRate;
            bitRate = s.bitRate;
            bitsPerSample = s.bitsPerSample;
            rating = s.rating;
            shuffle = s.shuffle;
            repeat = s.repeat;
            posInList = s.posInList;
            listSize = s.listSize;
            catUri = s.catUri;
            eqPresetName = s.eqPresetName;
            eqPresetId = s.eqPresetId;
            sleepEndWall = s.sleepEndWall > nowWall ? s.sleepEndWall : 0;
            realId = s.realId;
            durMs = s.durMs;
            playing = s.playing;
            hasTrack = s.hasTrack;
            posMs = s.positionAt(nowWall);
        }

        float progress() {
            if (durMs <= 0) return 0f;
            return Math.max(0f, Math.min(1f, posMs / (float) durMs));
        }

        /** Stable identity of the current track for caches. */
        long trackKey() {
            return hasTrack ? realId : -1;
        }

        boolean shuffleOn() {
            return shuffle != PA.SHUFFLE_NONE;
        }

        boolean repeatOn() {
            return repeat == PA.REPEAT_ON || repeat == PA.REPEAT_ADVANCE || repeat == PA.REPEAT_SONG;
        }

        /** Heart is filled for "like" and 2-5 star ratings; 1 is Poweramp's "unlike". */
        boolean favorite() {
            return rating >= 2;
        }
    }

    static synchronized PlayerState get(Context ctx) {
        if (sInstance == null) {
            sInstance = new PlayerState();
            sInstance.load(ctx.getApplicationContext());
        }
        return sInstance;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(this, System.currentTimeMillis());
    }

    synchronized boolean isPlaying() {
        return playing;
    }

    synchronized long durationMs() {
        return durMs;
    }

    private long positionAt(long wall) {
        long p = posMs;
        if (playing) p += Math.max(0, wall - anchorWall);
        if (durMs > 0 && p > durMs) p = durMs;
        return Math.max(0, p);
    }

    private static long sanitizeTs(long ts) {
        long now = System.currentTimeMillis();
        return (ts <= 0 || ts > now) ? now : ts;
    }

    /** @return true if anything changed. */
    synchronized boolean applyTrack(Intent intent) {
        Bundle t = intent.getBundleExtra(PA.EXTRA_TRACK);
        if (t == null) {
            // Since Poweramp build 948 every track field is also exposed as a top-level extra.
            Bundle extras = intent.getExtras();
            if (extras == null || !extras.containsKey(PA.TRACK_TITLE)) return false;
            t = extras;
        }
        long rawTs = intent.getLongExtra(PA.EXTRA_TIMESTAMP, 0);
        if (rawTs > 0 && rawTs <= lastTrackTs) return false; // duplicate (explicit + sticky copies of one event)
        long ts = sanitizeTs(rawTs);

        long newId = t.getLong(PA.TRACK_REAL_ID, t.getLong(PA.TRACK_ID, -1));
        title = nz(t.getString(PA.TRACK_TITLE));
        artist = nz(t.getString(PA.TRACK_ARTIST));
        album = nz(t.getString(PA.TRACK_ALBUM));
        path = nz(t.getString(PA.TRACK_PATH));
        codec = nz(t.getString(PA.TRACK_CODEC));
        fileType = t.getInt(PA.TRACK_FILE_TYPE, -1);
        sampleRate = t.getInt(PA.TRACK_SAMPLE_RATE, 0);
        bitRate = t.getInt(PA.TRACK_BITRATE, 0);
        bitsPerSample = t.getInt(PA.TRACK_BITS_PER_SAMPLE, 0);
        rating = t.getInt(PA.TRACK_RATING, 0);
        posInList = t.getInt(PA.TRACK_POS_IN_LIST, -1);
        listSize = t.getInt(PA.TRACK_LIST_SIZE, 0);
        Object cu = t.get(PA.TRACK_CAT_URI);
        catUri = cu != null ? cu.toString() : "";
        long d = t.getInt(PA.TRACK_DURATION_MS, 0);
        if (d <= 0) d = t.getInt(PA.TRACK_DURATION, 0) * 1000L;
        durMs = Math.max(0, d);
        Log.d(TAG, "track codec=" + codec + " type=" + fileType + " rate=" + sampleRate + " bitRate=" + bitRate
                + " bits=" + bitsPerSample + " rating=" + rating);

        int pos = intent.getIntExtra(PA.EXTRA_POSITION, -1); // top-level extra, seconds
        if (pos >= 0) {
            posMs = pos * 1000L;
            anchorWall = ts;
        } else if (newId != realId) {
            posMs = 0;
            anchorWall = ts;
        }
        realId = newId;
        hasTrack = true;
        lastTrackTs = ts;
        return true;
    }

    synchronized boolean applyStatus(Intent intent) {
        long rawTs = intent.getLongExtra(PA.EXTRA_TIMESTAMP, 0);
        if (rawTs > 0 && rawTs <= lastStatusTs) return false;
        long ts = sanitizeTs(rawTs);

        long current = positionAt(ts);
        boolean paused = intent.getBooleanExtra(PA.EXTRA_PAUSED, true);
        int state = intent.getIntExtra(PA.EXTRA_STATE, -1);
        int pos = intent.getIntExtra(PA.EXTRA_POSITION, -1);

        if (pos >= 0) posMs = pos * 1000L;
        else if (state == PA.STATE_STOPPED) posMs = 0;
        else posMs = current;
        anchorWall = ts;
        playing = !paused;
        lastStatusTs = ts;
        return true;
    }

    synchronized boolean applyMode(Intent intent) {
        int s = intent.getIntExtra(PA.EXTRA_SHUFFLE, shuffle);
        int r = intent.getIntExtra(PA.EXTRA_REPEAT, repeat);
        if (s == shuffle && r == repeat) return false;
        shuffle = s;
        repeat = r;
        return true;
    }

    synchronized boolean applyPositionSync(int seconds) {
        if (seconds < 0) return false;
        posMs = seconds * 1000L;
        anchorWall = System.currentTimeMillis();
        return true;
    }

    synchronized void seekTo(long ms) {
        posMs = Math.max(0, ms);
        anchorWall = System.currentTimeMillis();
    }

    /** Sleep timer: cycles off -> 15 -> 30 -> 60 min -> off. Returns the minutes to send (0 = off). */
    synchronized int cycleSleepTimer() {
        long now = System.currentTimeMillis();
        long left = sleepEndWall > now ? sleepEndWall - now : 0;
        int next;
        if (left <= 0) next = 15;
        else if (left <= 15 * 60_000L + 5_000) next = 30;
        else if (left <= 30 * 60_000L + 5_000) next = 60;
        else next = 0;
        sleepEndWall = next == 0 ? 0 : now + next * 60_000L;
        return next;
    }

    synchronized boolean applyEqPreset(long id, String name) {
        if (id == eqPresetId && (name == null || name.equals(eqPresetName))) return false;
        eqPresetId = id;
        if (name != null) eqPresetName = name;
        return true;
    }

    synchronized void seekBy(long deltaMs) {
        long now = System.currentTimeMillis();
        posMs = positionAt(now) + deltaMs;
        if (durMs > 0) posMs = Math.min(posMs, durMs);
        posMs = Math.max(0, posMs);
        anchorWall = now;
    }

    /** Toggles shuffle off <-> "shuffle songs"; returns the mode to send to Poweramp. */
    synchronized int toggleShuffle() {
        shuffle = shuffle != PA.SHUFFLE_NONE ? PA.SHUFFLE_NONE : PA.SHUFFLE_SONGS;
        return shuffle;
    }

    /** Cycles repeat: off -> list -> one -> off; returns the mode to send to Poweramp. */
    synchronized int cycleRepeat() {
        if (repeat == PA.REPEAT_SONG) repeat = PA.REPEAT_NONE;
        else if (repeat == PA.REPEAT_ON || repeat == PA.REPEAT_ADVANCE) repeat = PA.REPEAT_SONG;
        else repeat = PA.REPEAT_ON;
        return repeat;
    }

    /** Heart toggle: rated -> cleared, otherwise -> "like". Returns the rating to send to Poweramp. */
    synchronized int toggleFavorite() {
        rating = rating >= 2 ? PA.RATING_NONE : PA.RATING_LIKE;
        return rating;
    }

    /** Poweramp isn't running (no sticky status): show as paused at the last known position. */
    synchronized boolean markNotRunning() {
        if (!playing) return false;
        posMs = positionAt(System.currentTimeMillis());
        anchorWall = System.currentTimeMillis();
        playing = false;
        return true;
    }

    synchronized void save(Context ctx) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", title)
                .putString("artist", artist)
                .putString("album", album)
                .putString("path", path)
                .putString("codec", codec)
                .putInt("fileType", fileType)
                .putInt("sampleRate", sampleRate)
                .putInt("bitRate", bitRate)
                .putInt("bitsPerSample", bitsPerSample)
                .putInt("rating", rating)
                .putInt("shuffle", shuffle)
                .putInt("repeat", repeat)
                .putInt("posInList", posInList)
                .putInt("listSize", listSize)
                .putString("catUri", catUri)
                .putLong("sleepEndWall", sleepEndWall)
                .putLong("eqPresetId", eqPresetId)
                .putString("eqPresetName", eqPresetName)
                .putLong("realId", realId)
                .putLong("durMs", durMs)
                .putBoolean("playing", playing)
                .putBoolean("hasTrack", hasTrack)
                .putLong("posMs", posMs)
                .putLong("anchorWall", anchorWall)
                .putLong("lastTrackTs", lastTrackTs)
                .putLong("lastStatusTs", lastStatusTs)
                .apply();
    }

    private void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        title = p.getString("title", "");
        artist = p.getString("artist", "");
        album = p.getString("album", "");
        path = p.getString("path", "");
        codec = p.getString("codec", "");
        fileType = p.getInt("fileType", -1);
        sampleRate = p.getInt("sampleRate", 0);
        bitRate = p.getInt("bitRate", 0);
        bitsPerSample = p.getInt("bitsPerSample", 0);
        rating = p.getInt("rating", 0);
        shuffle = p.getInt("shuffle", 0);
        repeat = p.getInt("repeat", 0);
        posInList = p.getInt("posInList", -1);
        listSize = p.getInt("listSize", 0);
        catUri = p.getString("catUri", "");
        sleepEndWall = p.getLong("sleepEndWall", 0);
        eqPresetId = p.getLong("eqPresetId", -1);
        eqPresetName = p.getString("eqPresetName", "");
        realId = p.getLong("realId", -1);
        durMs = p.getLong("durMs", 0);
        playing = p.getBoolean("playing", false);
        hasTrack = p.getBoolean("hasTrack", false);
        posMs = p.getLong("posMs", 0);
        anchorWall = p.getLong("anchorWall", System.currentTimeMillis());
        lastTrackTs = p.getLong("lastTrackTs", 0);
        lastStatusTs = p.getLong("lastStatusTs", 0);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
