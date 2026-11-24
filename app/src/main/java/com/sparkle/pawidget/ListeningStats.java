package com.sparkle.pawidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Listening statistics built from the same Poweramp events the widget already receives: listening time per day,
 * per artist, and play counts per track (a play counts once a track has been heard for 30 s).
 * THREADING: called from Events.EXEC (record) and the settings screen (read); methods are synchronized.
 */
final class ListeningStats {
    private static final String PREFS = "listening_stats";
    private static final long COUNT_AFTER_MS = 30_000;
    private static final long MAX_SPAN_MS = 6 * 3600_000L; // ignore absurd gaps (e.g. missed pause event)
    private static final int KEEP_DAYS = 90, KEEP_ARTISTS = 300, KEEP_TRACKS = 500;

    static final class Entry {
        final String name;
        final long value;

        Entry(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }

    private ListeningStats() {}

    /** Called on every track/status change with the new state. */
    static synchronized void record(Context ctx, PlayerState.Snapshot s) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long since = p.getLong("playingSince", 0);
        long key = p.getLong("curKey", Long.MIN_VALUE);
        String artist = p.getString("curArtist", "");
        String title = p.getString("curTitle", "");
        long trackMs = p.getLong("curTrackMs", 0);
        boolean counted = p.getBoolean("curCounted", false);

        try {
            JSONObject days = json(p, "days"), artists = json(p, "artists"), tracks = json(p, "tracks");
            if (since > 0 && now > since) {
                long ms = Math.min(now - since, MAX_SPAN_MS);
                add(days, dayKey(now), ms);
                if (!artist.isEmpty()) add(artists, artist, ms);
                trackMs += ms;
                if (!counted && trackMs >= COUNT_AFTER_MS && !title.isEmpty()) {
                    add(tracks, title + "\u0001" + artist, 1);
                    counted = true;
                }
            }
            if (s.hasTrack && s.trackKey() != key) {
                key = s.trackKey();
                artist = s.artist;
                title = s.title;
                trackMs = 0;
                counted = false;
            }
            trim(days, KEEP_DAYS, true);
            trim(artists, KEEP_ARTISTS, false);
            trim(tracks, KEEP_TRACKS, false);
            p.edit()
                    .putLong("playingSince", s.playing ? now : 0)
                    .putLong("curKey", key).putString("curArtist", artist).putString("curTitle", title)
                    .putLong("curTrackMs", trackMs).putBoolean("curCounted", counted)
                    .putString("days", days.toString()).putString("artists", artists.toString())
                    .putString("tracks", tracks.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Listening ms for the last {@code n} days, oldest first, including time still running now. */
    static synchronized long[] lastDays(Context ctx, int n) {
        SharedPreferences p = prefs(ctx);
        long[] out = new long[n];
        try {
            JSONObject days = json(p, "days");
            Calendar c = Calendar.getInstance();
            for (int i = n - 1; i >= 0; i--) {
                out[i] = days.optLong(dayKey(c.getTimeInMillis()), 0);
                c.add(Calendar.DAY_OF_YEAR, -1);
            }
        } catch (Throwable ignored) {
        }
        long since = p.getLong("playingSince", 0);
        if (since > 0) out[n - 1] += Math.min(System.currentTimeMillis() - since, MAX_SPAN_MS);
        return out;
    }

    /** Consecutive days (ending today, or yesterday if nothing yet today) with at least a minute of listening. */
    static int streakDays(Context ctx) {
        long[] d = lastDays(ctx, KEEP_DAYS);
        int i = d.length - 1;
        if (d[i] < 60_000) i--;
        int streak = 0;
        for (; i >= 0 && d[i] >= 60_000; i--) streak++;
        return streak;
    }

    static synchronized List<Entry> topArtists(Context ctx, int max) {
        return top(json(prefs(ctx), "artists"), max);
    }

    /** Names are "title\u0001artist". */
    static synchronized List<Entry> topTracks(Context ctx, int max) {
        return top(json(prefs(ctx), "tracks"), max);
    }

    // ------------------------------------------------------------------ helpers

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject json(SharedPreferences p, String key) {
        try {
            return new JSONObject(p.getString(key, "{}"));
        } catch (Throwable th) {
            return new JSONObject();
        }
    }

    private static void add(JSONObject o, String key, long v) throws Exception {
        o.put(key, o.optLong(key, 0) + v);
    }

    private static String dayKey(long wall) {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(wall));
    }

    /** Keeps the newest days (by key) or the biggest values. */
    private static void trim(JSONObject o, int keep, boolean byKey) {
        if (o.length() <= keep) return;
        List<Entry> all = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            all.add(new Entry(k, o.optLong(k)));
        }
        if (byKey) Collections.sort(all, (a, b) -> b.name.compareTo(a.name));
        else Collections.sort(all, (a, b) -> Long.compare(b.value, a.value));
        for (int i = keep; i < all.size(); i++) o.remove(all.get(i).name);
    }

    private static List<Entry> top(JSONObject o, int max) {
        Map<String, Long> sorted = new TreeMap<>();
        List<Entry> all = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            all.add(new Entry(k, o.optLong(k)));
        }
        Collections.sort(all, (a, b) -> Long.compare(b.value, a.value));
        return all.size() > max ? all.subList(0, max) : all;
    }
}
