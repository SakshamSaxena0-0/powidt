package com.sparkle.pawidget;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads Poweramp's library provider (content://com.maxmpz.audioplayer.data): the tracks after the current one in
 * the playing list ("up next") and the equalizer presets. Needs Poweramp's one-time data permission; every call
 * fails soft (empty result) without it.
 */
final class LibraryData {
    private static final String TAG = "SparkleLibrary";

    static final class Item {
        final String title, artist;
        /** Content uri that OPEN_TO_PLAY accepts to start this exact entry. */
        final Uri uri;

        Item(String title, String artist, Uri uri) {
            this.title = title;
            this.artist = artist;
            this.uri = uri;
        }
    }

    static final class Preset {
        final long id;
        final String name;

        Preset(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static String sUpNextKey;
    private static List<Item> sUpNext = Collections.emptyList();
    private static long sUpNextAt;
    private static List<Preset> sPresets;
    private static long sPresetsAt;

    private LibraryData() {}

    /** True when Poweramp lets us query its library. */
    static boolean hasAccess(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(PA.DATA_ROOT.buildUpon().appendEncodedPath("eq_presets")
                .appendQueryParameter("lim", "1").build(), new String[]{"eq_presets._id"}, null, null, null)) {
            return c != null;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * Up to {@code max} tracks after the current one, in the list's own order (Poweramp's sort). Empty when
     * shuffle is on (the next track isn't predictable), without permission, or at the end of the list.
     */
    static synchronized List<Item> upNext(Context ctx, PlayerState.Snapshot s, int max) {
        if (!s.hasTrack || s.catUri.isEmpty() || s.posInList < 0 || s.shuffleOn()) return Collections.emptyList();
        Uri list = Uri.parse(s.catUri).buildUpon().clearQuery().build(); // drop shuffle/sort params, as the API example does
        String key = list + "#" + s.posInList + "/" + s.listSize + "/" + max;
        long now = System.currentTimeMillis();
        if (key.equals(sUpNextKey) && now - sUpNextAt < 5 * 60_000L) return sUpNext;

        List<Item> out = new ArrayList<>();
        boolean queue = list.getPath() != null && list.getPath().startsWith("/queue");
        String idCol = queue ? "queue._id" : "folder_files._id";
        try (Cursor c = ctx.getContentResolver().query(list,
                new String[]{idCol + " AS _id", "title_tag", "artist"}, null, null, null)) { // null order = Poweramp's list order
            if (c != null && c.moveToPosition(s.posInList + 1)) {
                do {
                    long id = c.getLong(0);
                    String title = c.getString(1);
                    String artist = c.getString(2);
                    out.add(new Item(title != null ? title : "", artist != null ? artist : "",
                            list.buildUpon().appendEncodedPath(Long.toString(id)).build()));
                } while (out.size() < max && c.moveToNext());
            }
        } catch (SecurityException se) {
            // no data permission yet
        } catch (Throwable th) {
            Log.w(TAG, "up next query failed for " + list, th);
        }
        sUpNextKey = key;
        sUpNext = out;
        sUpNextAt = now;
        return out;
    }

    static synchronized List<Preset> eqPresets(Context ctx) {
        long now = System.currentTimeMillis();
        if (sPresets != null && now - sPresetsAt < 10 * 60_000L) return sPresets;
        List<Preset> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(PA.DATA_ROOT.buildUpon().appendEncodedPath("eq_presets").build(),
                new String[]{"eq_presets._id", "eq_presets.name"}, null, null, "eq_presets.name COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name != null && !name.isEmpty()) out.add(new Preset(c.getLong(0), name));
            }
        } catch (Throwable th) {
            // no permission / not available
        }
        sPresets = out;
        sPresetsAt = now;
        return out;
    }

    /** The preset after {@code currentId} (wrapping), or the first one. Null when presets aren't readable. */
    static Preset nextPreset(Context ctx, long currentId) {
        List<Preset> all = eqPresets(ctx);
        if (all.isEmpty()) return null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == currentId) return all.get((i + 1) % all.size());
        }
        return all.get(0);
    }

    static String presetName(Context ctx, long id) {
        for (Preset p : eqPresets(ctx)) if (p.id == id) return p.name;
        return null;
    }

    /** Forget cached lists (after permission changes or library edits). */
    static synchronized void invalidate() {
        sUpNextKey = null;
        sPresets = null;
    }
}
