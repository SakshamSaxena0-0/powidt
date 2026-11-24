package com.sparkle.pawidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-track visuals: the cover (memory), a small cover copy for the blurred card and the palette (memory + disk),
 * and the art in each shape asked for. Plus per-size/per-style card backgrounds, progress bars and play discs in
 * memory. Everything is keyed by track and style and dropped when the track changes.
 * THREADING: called from Events.EXEC (and the settings preview, also via EXEC).
 */
final class VisualCache {
    private static final String TAG = "SparkleVisuals";
    private static final String ART_FILE = "art_cache_v5.png";
    private static final String COVER_FILE = "cover_small_v5.png";
    private static final String PREFS = "visual_cache";
    /** Art is rendered above its 104dp base so it stays sharp when the layout scales it up. */
    private static final float ART_RENDER_SCALE = 1.6f;
    /** Card background: smooth gradients + grain; render at most this wide and let the ImageView scale it. */
    private static final int MAX_BG_WIDTH = 720;
    /** Poweramp's art provider can fail transiently (e.g. right after an app update): retry a few times. */
    private static final long PLACEHOLDER_RETRY_MS = 4_000;
    private static final int MAX_ART_ATTEMPTS = 4;
    /** After the quick attempts, keep trying (on later updates) at most this often. */
    private static final long SLOW_RETRY_MS = 60_000;

    static final class Track {
        final long key;
        Bitmap cover; // full decoded cover; null without art or when restored from disk
        final Bitmap coverSmall; // null without a cover
        final WidgetGraphicsHelper.Ambient ambient;
        final boolean placeholder;
        final long createdAt;
        final Map<Integer, Bitmap> arts = new HashMap<>();

        Track(long key, Bitmap cover, Bitmap coverSmall, WidgetGraphicsHelper.Ambient ambient, boolean placeholder) {
            this.key = key;
            this.cover = cover;
            this.coverSmall = coverSmall;
            this.ambient = ambient;
            this.placeholder = placeholder;
            this.createdAt = SystemClock.elapsedRealtime();
        }
    }

    private static Track sTrack;
    private static int sArtAttempts;
    private static String sWaveSeed;
    private static final Map<String, Bitmap> sBackgrounds = new HashMap<>();
    private static final Map<String, Bitmap> sWaveBases = new HashMap<>();
    private static final Map<String, float[]> sWaveHeights = new HashMap<>();
    private static final Map<String, Bitmap> sPlayDiscs = new HashMap<>();

    private VisualCache() {}

    static synchronized Track track(Context ctx, PlayerState.Snapshot s) {
        long key = s.trackKey();
        Track t = sTrack;
        if (t != null && t.key == key) {
            long age = SystemClock.elapsedRealtime() - t.createdAt;
            boolean retry = t.placeholder && key >= 0
                    && ((sArtAttempts < MAX_ART_ATTEMPTS && age >= PLACEHOLDER_RETRY_MS) || age >= SLOW_RETRY_MS);
            if (!retry) return t;
        }
        boolean trackChanged = t == null || t.key != key;
        sArtAttempts = trackChanged ? 1 : Math.min(sArtAttempts + 1, MAX_ART_ATTEMPTS);

        SharedPreferences disk = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File artFile = new File(ctx.getFilesDir(), ART_FILE);
        File coverFile = new File(ctx.getFilesDir(), COVER_FILE);
        Track fresh = null;
        if (trackChanged && key >= 0 && disk.getLong("key", Long.MIN_VALUE) == key && artFile.exists() && coverFile.exists()) {
            Bitmap art = BitmapFactory.decodeFile(artFile.getAbsolutePath());
            Bitmap small = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
            if (art != null && small != null) {
                fresh = new Track(key, null, small, new WidgetGraphicsHelper.Ambient(
                        disk.getInt("dominant", 0), disk.getInt("dark", 0), disk.getInt("vibrant", 0)), false);
                fresh.arts.put(disk.getInt("shape", WidgetPrefs.ART_ROUNDED), art);
            }
        }
        if (fresh == null) {
            Bitmap cover = key >= 0 ? PA.loadAlbumArt(ctx, key, artPx(ctx)) : null;
            fresh = new Track(key, cover, WidgetGraphicsHelper.smallCover(cover),
                    WidgetGraphicsHelper.extractAmbient(cover), cover == null);
        }
        // New track, or real art replaced a placeholder: palette and blur source changed, drop dependents.
        if (trackChanged || t.placeholder != fresh.placeholder) {
            sBackgrounds.clear();
            sPlayDiscs.clear();
        }
        sTrack = fresh;
        return fresh;
    }

    private static int artPx(Context ctx) {
        return Math.round(ctx.getResources().getDimension(R.dimen.art_size) * ART_RENDER_SCALE);
    }

    /** The current track's art in {@code shape} (rounded, circle, square or vinyl record). */
    static synchronized Bitmap art(Context ctx, PlayerState.Snapshot s, int shape) {
        Track t = track(ctx, s);
        Bitmap b = t.arts.get(shape);
        if (b != null) return b;
        if (t.cover == null && !t.placeholder && t.key >= 0) {
            t.cover = PA.loadAlbumArt(ctx, t.key, artPx(ctx)); // restored from disk in another shape
        }
        int px = artPx(ctx);
        b = shape == WidgetPrefs.ART_VINYL
                ? WidgetGraphicsHelper.renderVinyl(ctx, t.cover, px)
                : WidgetGraphicsHelper.renderArt(ctx, t.cover, px, WidgetGraphicsHelper.artRadius(shape, px));
        t.arts.put(shape, b);
        if (t.cover != null && t.coverSmall != null) persist(ctx, t, b, shape);
        return b;
    }

    private static void persist(Context ctx, Track t, Bitmap art, int shape) {
        try (FileOutputStream a = new FileOutputStream(new File(ctx.getFilesDir(), ART_FILE));
             FileOutputStream b = new FileOutputStream(new File(ctx.getFilesDir(), COVER_FILE))) {
            art.compress(Bitmap.CompressFormat.PNG, 100, a);
            t.coverSmall.compress(Bitmap.CompressFormat.PNG, 100, b);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("key", t.key).putInt("shape", shape)
                    .putInt("dominant", t.ambient.dominant).putInt("dark", t.ambient.darkMuted)
                    .putInt("vibrant", t.ambient.vibrant)
                    .apply();
        } catch (Throwable th) {
            Log.w(TAG, "art cache write failed", th);
        }
    }

    static synchronized WidgetGraphicsHelper.Ambient ambient(Context ctx, PlayerState.Snapshot s) {
        return track(ctx, s).ambient;
    }

    /**
     * Delay before the next quick art retry for the current track, or -1 when none is due (art loaded, no track,
     * or the quick attempts are used up). Backs off 5 s, 15 s, 45 s.
     */
    static synchronized long artRetryDelayMs(PlayerState.Snapshot s) {
        Track t = sTrack;
        if (t == null || t.key != s.trackKey() || t.key < 0 || !t.placeholder || sArtAttempts >= MAX_ART_ATTEMPTS) return -1;
        return 5_000L * (long) Math.pow(3, Math.max(0, sArtAttempts - 1));
    }

    /**
     * The card for a widget of {@code size} in the current style (rendered at up to {@link #MAX_BG_WIDTH} px and
     * scaled up by the ImageView), or null for the "clear" style.
     */
    static synchronized Bitmap background(Context ctx, PlayerState.Snapshot s, WidgetPrefs prefs,
                                          WidgetRenderer.Size size, int accent) {
        if (prefs.background == WidgetPrefs.BG_CLEAR) return null;
        Track t = track(ctx, s);
        int w = size.cardW, h = size.cardH;
        float scale = Math.min(1f, MAX_BG_WIDTH / (float) Math.max(1, w));
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        String k = bw + "x" + bh + "|" + prefs.backgroundKey() + "|" + accent + "|" + size.geometryKey();
        Bitmap b = sBackgrounds.get(k);
        if (b == null) {
            if (sBackgrounds.size() > 6) sBackgrounds.clear(); // settings sliders can produce many variants
            float d = ctx.getResources().getDisplayMetrics().density * scale; // px per dp in the bitmap
            WidgetGraphicsHelper.CardGeometry g = size.geometry(d, prefs.artShape);
            b = WidgetGraphicsHelper.renderBackground(bw, bh, prefs.cornerDp * d, d, t.ambient, t.coverSmall, g, accent, prefs);
            sBackgrounds.put(k, b);
        }
        return b;
    }

    /** Accent color resolved for this track: album (from the art), wallpaper (Material You), or a fixed color. */
    static synchronized int accent(Context ctx, PlayerState.Snapshot s, WidgetPrefs prefs) {
        if (prefs.accent == WidgetPrefs.ACCENT_ALBUM) return WidgetGraphicsHelper.albumAccent(track(ctx, s).ambient);
        if (prefs.accent == WidgetPrefs.ACCENT_WALLPAPER) {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    return ctx.getColor(android.R.color.system_accent1_200);
                } catch (Throwable ignored) {
                }
            }
            return WidgetPrefs.ACCENT_WHITE;
        }
        return prefs.accent;
    }

    static synchronized Bitmap playDisc(Context ctx, int sizePx, int accent, boolean playing) {
        String k = sizePx + "|" + accent + "|" + playing;
        Bitmap b = sPlayDiscs.get(k);
        if (b == null) {
            if (sPlayDiscs.size() > 8) sPlayDiscs.clear();
            b = WidgetGraphicsHelper.renderPlayDisc(ctx, sizePx, accent, playing);
            sPlayDiscs.put(k, b);
        }
        return b;
    }

    /** px per spec-dp for the progress bar. */
    private static float unit(Context ctx, float scale) {
        return ctx.getResources().getDisplayMetrics().density * scale;
    }

    /** How many discrete steps the progress bar has at this width in the current style. */
    static int progressSteps(Context ctx, WidgetPrefs prefs, int w, float scale) {
        return WidgetGraphicsHelper.progressSteps(prefs.seekStyle, w, unit(ctx, scale));
    }

    /** Progress bitmap for {@code w x h} px at {@code scale}, with {@code lit} steps played. */
    static synchronized Bitmap progress(Context ctx, PlayerState.Snapshot s, WidgetPrefs prefs,
                                        int w, int h, float scale, int lit, int accent) {
        String seed = s.hasTrack ? s.title + "|" + s.durMs : "idle";
        if (!seed.equals(sWaveSeed)) {
            sWaveSeed = seed;
            sWaveBases.clear();
            sWaveHeights.clear();
        }
        float unit = unit(ctx, scale);
        int style = prefs.seekStyle;
        String k = style + ":" + w + "x" + h + "@" + scale;
        float[] heights = sWaveHeights.get(k);
        if (heights == null) {
            heights = WidgetGraphicsHelper.waveformHeights(seed, WidgetGraphicsHelper.barCount(w, unit));
            sWaveHeights.put(k, heights);
        }
        Bitmap base = sWaveBases.get(k);
        if (base == null) {
            if (sWaveBases.size() > 8) sWaveBases.clear();
            base = WidgetGraphicsHelper.renderProgressBase(style, heights, w, h, unit);
            sWaveBases.put(k, base);
        }
        int steps = WidgetGraphicsHelper.progressSteps(style, w, unit);
        return WidgetGraphicsHelper.overlayProgress(style, base, heights, unit, lit, steps, accent, prefs.fxGlow);
    }
}
