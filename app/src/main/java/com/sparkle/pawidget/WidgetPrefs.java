package com.sparkle.pawidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * User customization. There is one global style ({@link #get}) and optional per-widget styles
 * ({@link #forWidget}): a widget without its own style follows the global one. The settings screen edits an
 * instance in place and calls {@link #save}; renderers work on a {@link #copy()} per render pass.
 */
final class WidgetPrefs {
    private static final String GLOBAL = "widget_prefs";
    private static final String PER_WIDGET = "widget_prefs_w";
    /** Bump when defaults change in a way existing users should get (see {@link #load}). */
    private static final int VERSION = 3;

    static final int BG_ADAPTIVE = 0, BG_DARK = 1, BG_CLEAR = 2, BG_IMMERSIVE = 3;
    static final int ART_ROUNDED = 0, ART_CIRCLE = 1, ART_SQUARE = 2, ART_VINYL = 3;
    static final int SEEK_WAVEFORM = 0, SEEK_LINE = 1, SEEK_MIRROR = 2, SEEK_DOTS = 3, SEEK_SEGMENTS = 4;
    static final int LAYOUT_AUTO = 0, LAYOUT_CLASSIC = 1, LAYOUT_COMPACT = 2, LAYOUT_HERO = 3, LAYOUT_STUDIO = 4;
    static final int FONT_CLASSIC = 0, FONT_CONDENSED = 1, FONT_EDITORIAL = 2, FONT_MONO = 3;
    static final int SIDE_SHUFFLE_REPEAT = 0, SIDE_SKIP10 = 1, SIDE_NONE = 2;
    /** Accent sentinels: take the color from the album art / the Material You wallpaper palette. */
    static final int ACCENT_ALBUM = 0, ACCENT_WALLPAPER = 1;
    static final int ACCENT_WHITE = 0xFFFFFFFF;
    static final int[] ACCENT_PRESETS = {ACCENT_ALBUM, ACCENT_WALLPAPER, ACCENT_WHITE,
            0xFF8EC5FF, 0xFF7FE0C2, 0xFFFF8FB1, 0xFFFFC46B, 0xFFC3A6FF};

    static final int SIZE_MIN = 80, SIZE_MAX = 130;

    int sizePercent = 100;
    int layout = LAYOUT_AUTO;
    int font = FONT_CLASSIC;
    int background = BG_IMMERSIVE;
    int opacity = 92; // % of the card's alpha
    int tintStrength = 30; // album color strength (glass) / blurred-art brightness (immersive), 0..60
    int cornerDp = 30;
    int artShape = ART_ROUNDED;
    int accent = ACCENT_ALBUM;
    int seekStyle = SEEK_WAVEFORM;
    int sideButtons = SIDE_SHUFFLE_REPEAT;
    boolean showStatus = true;
    boolean showArtist = true;
    boolean showUpNext = true;
    boolean showFormat = true;
    boolean showFavorite = true;
    boolean showDevice = true;
    boolean showTimes = true;
    boolean showQuickActions = true;
    boolean qaEq = true, qaLibrary = true, qaSleep = true, qaVolume = false;
    boolean fxEdge = true, fxGrain = true, fxGlow = true;
    boolean fxBreathing = true, fxCrossfade = true, fxBars = true;

    /** Widget id this style belongs to, or 0 for the global style. */
    private int widgetId;

    private static WidgetPrefs sGlobal;
    private static final Map<Integer, WidgetPrefs> sPerWidget = new HashMap<>();

    /** The global style (edited from the app's launcher screen). */
    static synchronized WidgetPrefs get(Context ctx) {
        if (sGlobal == null) {
            sGlobal = new WidgetPrefs();
            sGlobal.load(ctx.getApplicationContext(), GLOBAL);
        }
        return sGlobal;
    }

    /** The style a widget renders with: its own if it has one, else the global style. */
    static synchronized WidgetPrefs forWidget(Context ctx, int widgetId) {
        WidgetPrefs own = ownStyle(ctx, widgetId);
        return own != null ? own : get(ctx);
    }

    static synchronized boolean hasOwnStyle(Context ctx, int widgetId) {
        return ownStyle(ctx, widgetId) != null;
    }

    /** Gives a widget its own style, starting from the current global one. */
    static synchronized WidgetPrefs createOwnStyle(Context ctx, int widgetId) {
        WidgetPrefs own = ownStyle(ctx, widgetId);
        if (own == null) {
            own = get(ctx).copy();
            own.widgetId = widgetId;
            sPerWidget.put(widgetId, own);
            own.save(ctx);
        }
        return own;
    }

    /** Makes a widget follow the global style again. */
    static synchronized void removeOwnStyle(Context ctx, int widgetId) {
        sPerWidget.remove(widgetId);
        ctx.getApplicationContext().getSharedPreferences(PER_WIDGET + widgetId, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static WidgetPrefs ownStyle(Context ctx, int widgetId) {
        if (widgetId == 0) return null;
        WidgetPrefs own = sPerWidget.get(widgetId);
        if (own == null) {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PER_WIDGET + widgetId, Context.MODE_PRIVATE);
            if (!sp.contains("version")) return null;
            own = new WidgetPrefs();
            own.widgetId = widgetId;
            own.load(ctx.getApplicationContext(), PER_WIDGET + widgetId);
            sPerWidget.put(widgetId, own);
        }
        return own;
    }

    // ------------------------------------------------------------------ theme presets

    static final String[] PRESET_NAMES = {"Aurora", "Midnight", "Vinyl", "Minimal", "Studio Gold", "Neon"};

    /** One-tap looks: each sets the whole visual style (keeps size and which controls are shown). */
    synchronized void applyPreset(int preset) {
        switch (preset) {
            case 0: // Aurora: immersive blurred art that breathes, album-colored everything
                set(BG_IMMERSIVE, 94, 34, 30, ART_ROUNDED, ACCENT_ALBUM, SEEK_WAVEFORM, FONT_CLASSIC, LAYOUT_AUTO);
                fx(true, true, true, true, true, true);
                break;
            case 1: // Midnight: deep dark glass, cool sky accent, crisp
                set(BG_DARK, 96, 30, 26, ART_ROUNDED, 0xFF8EC5FF, SEEK_LINE, FONT_CLASSIC, LAYOUT_AUTO);
                fx(true, true, true, false, true, true);
                break;
            case 2: // Vinyl: spinning record over warm immersive glass, editorial type
                set(BG_IMMERSIVE, 96, 24, 34, ART_VINYL, 0xFFFFC46B, SEEK_WAVEFORM, FONT_EDITORIAL, LAYOUT_AUTO);
                fx(true, true, true, true, true, true);
                break;
            case 3: // Minimal: no card, white, quiet
                set(BG_CLEAR, 85, 22, 28, ART_ROUNDED, ACCENT_WHITE, SEEK_LINE, FONT_CLASSIC, LAYOUT_AUTO);
                fx(false, false, false, false, true, false);
                break;
            case 4: // Studio Gold: dark studio layout, gold accent, mirrored waveform, condensed type
                set(BG_DARK, 96, 30, 24, ART_SQUARE, 0xFFFFC46B, SEEK_MIRROR, FONT_CONDENSED, LAYOUT_STUDIO);
                fx(true, true, true, false, true, true);
                break;
            case 5: // Neon: glass with strong color, pink accent, segmented meter
                set(BG_ADAPTIVE, 92, 56, 30, ART_CIRCLE, 0xFFFF8FB1, SEEK_SEGMENTS, FONT_MONO, LAYOUT_AUTO);
                fx(true, false, true, true, true, true);
                break;
            default:
                break;
        }
    }

    private void set(int bg, int op, int tint, int corner, int art, int acc, int seek, int f, int lay) {
        background = bg;
        opacity = op;
        tintStrength = tint;
        cornerDp = corner;
        artShape = art;
        accent = acc;
        seekStyle = seek;
        font = f;
        layout = lay;
    }

    private void fx(boolean edge, boolean grain, boolean glow, boolean breathing, boolean crossfade, boolean bars) {
        fxEdge = edge;
        fxGrain = grain;
        fxGlow = glow;
        fxBreathing = breathing;
        fxCrossfade = crossfade;
        fxBars = bars;
    }

    // ------------------------------------------------------------------ persistence

    /** Key covering everything in prefs that changes the card background bitmap. */
    synchronized String backgroundKey() {
        return background + "/" + opacity + "/" + tintStrength + "/" + cornerDp + "/" + artShape
                + "/" + accent + "/" + fxEdge + fxGrain + fxGlow;
    }

    boolean shuffleRepeat() {
        return sideButtons == SIDE_SHUFFLE_REPEAT;
    }

    boolean hasSideButtons() {
        return sideButtons != SIDE_NONE;
    }

    synchronized void resetToDefaults() {
        int id = widgetId;
        copyFrom(new WidgetPrefs());
        widgetId = id;
    }

    synchronized WidgetPrefs copy() {
        WidgetPrefs c = new WidgetPrefs();
        c.copyFrom(this);
        return c;
    }

    private void copyFrom(WidgetPrefs o) {
        sizePercent = o.sizePercent; layout = o.layout; font = o.font;
        background = o.background; opacity = o.opacity; tintStrength = o.tintStrength; cornerDp = o.cornerDp;
        artShape = o.artShape; accent = o.accent; seekStyle = o.seekStyle; sideButtons = o.sideButtons;
        showStatus = o.showStatus; showArtist = o.showArtist; showUpNext = o.showUpNext; showFormat = o.showFormat;
        showFavorite = o.showFavorite; showDevice = o.showDevice; showTimes = o.showTimes;
        showQuickActions = o.showQuickActions; qaEq = o.qaEq; qaLibrary = o.qaLibrary; qaSleep = o.qaSleep; qaVolume = o.qaVolume;
        fxEdge = o.fxEdge; fxGrain = o.fxGrain; fxGlow = o.fxGlow;
        fxBreathing = o.fxBreathing; fxCrossfade = o.fxCrossfade; fxBars = o.fxBars;
        widgetId = o.widgetId;
    }

    synchronized void save(Context ctx) {
        String file = widgetId == 0 ? GLOBAL : PER_WIDGET + widgetId;
        ctx.getApplicationContext().getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                .putInt("version", VERSION)
                .putInt("sizePercent", sizePercent).putInt("layout", layout).putInt("font", font)
                .putInt("background", background).putInt("opacity", opacity).putInt("tintStrength", tintStrength)
                .putInt("cornerDp", cornerDp).putInt("artShape", artShape).putInt("accent", accent)
                .putInt("seekStyle", seekStyle).putInt("sideButtons", sideButtons)
                .putBoolean("showStatus", showStatus).putBoolean("showArtist", showArtist)
                .putBoolean("showUpNext", showUpNext).putBoolean("showFormat", showFormat)
                .putBoolean("showFavorite", showFavorite).putBoolean("showDevice", showDevice)
                .putBoolean("showTimes", showTimes).putBoolean("showQuickActions", showQuickActions)
                .putBoolean("qaEq", qaEq).putBoolean("qaLibrary", qaLibrary).putBoolean("qaSleep", qaSleep)
                .putBoolean("qaVolume", qaVolume)
                .putBoolean("fxEdge", fxEdge).putBoolean("fxGrain", fxGrain).putBoolean("fxGlow", fxGlow)
                .putBoolean("fxBreathing", fxBreathing).putBoolean("fxCrossfade", fxCrossfade).putBoolean("fxBars", fxBars)
                .apply();
    }

    private void load(Context ctx, String file) {
        SharedPreferences p = ctx.getSharedPreferences(file, Context.MODE_PRIVATE);
        sizePercent = p.getInt("sizePercent", sizePercent);
        layout = p.getInt("layout", layout);
        font = p.getInt("font", font);
        background = p.getInt("background", background);
        opacity = p.getInt("opacity", opacity);
        tintStrength = p.getInt("tintStrength", tintStrength);
        cornerDp = p.getInt("cornerDp", cornerDp);
        artShape = p.getInt("artShape", artShape);
        accent = p.getInt("accent", accent);
        seekStyle = p.getInt("seekStyle", seekStyle);
        sideButtons = p.getInt("sideButtons",
                p.getBoolean("showShuffleRepeat", true) ? SIDE_SHUFFLE_REPEAT : SIDE_NONE); // v2 boolean -> v3 choice
        showStatus = p.getBoolean("showStatus", showStatus);
        showArtist = p.getBoolean("showArtist", showArtist);
        showUpNext = p.getBoolean("showUpNext", showUpNext);
        showFormat = p.getBoolean("showFormat", showFormat);
        showFavorite = p.getBoolean("showFavorite", showFavorite);
        showDevice = p.getBoolean("showDevice", showDevice);
        showTimes = p.getBoolean("showTimes", showTimes);
        showQuickActions = p.getBoolean("showQuickActions", showQuickActions);
        qaEq = p.getBoolean("qaEq", qaEq);
        qaLibrary = p.getBoolean("qaLibrary", qaLibrary);
        qaSleep = p.getBoolean("qaSleep", qaSleep);
        qaVolume = p.getBoolean("qaVolume", qaVolume);
        fxEdge = p.getBoolean("fxEdge", fxEdge);
        fxGrain = p.getBoolean("fxGrain", fxGrain);
        fxGlow = p.getBoolean("fxGlow", fxGlow);
        fxBreathing = p.getBoolean("fxBreathing", fxBreathing);
        fxCrossfade = p.getBoolean("fxCrossfade", fxCrossfade);
        fxBars = p.getBoolean("fxBars", fxBars);

        int version = p.getInt("version", 1);
        if (p.contains("background") && version < VERSION) {
            // v1 -> v2: the flat "album glass" default became the immersive card.
            if (version < 2 && background == BG_ADAPTIVE) {
                background = BG_IMMERSIVE;
                opacity = Math.max(opacity, 92);
            }
            save(ctx);
        }
    }
}
