package com.sparkle.pawidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and pushes RemoteViews for every placed widget, each with its own (or the global) WidgetPrefs and a
 * layout picked for its size: Classic, Compact, Hero or Studio.
 * THREADING: call from Events.EXEC (bitmaps and library queries happen here).
 */
final class WidgetRenderer {
    private static final String TAG = "SparkleRender";

    /** Invisible tap-to-seek strips laid over the progress bar (part_progress.xml). */
    static final int[] SEEK_ZONES = {
            R.id.zone_0, R.id.zone_1, R.id.zone_2, R.id.zone_3, R.id.zone_4,
            R.id.zone_5, R.id.zone_6, R.id.zone_7, R.id.zone_8, R.id.zone_9,
            R.id.zone_10, R.id.zone_11, R.id.zone_12, R.id.zone_13, R.id.zone_14,
            R.id.zone_15, R.id.zone_16, R.id.zone_17, R.id.zone_18, R.id.zone_19,
    };
    private static final String[] FONT_FAMILIES = {null, "sans-serif-condensed", "serif", "monospace"};

    /** Per widget: progress steps last pushed, layout in use, which crossfade slot is showing, and for which track. */
    private static final Map<Integer, Integer> sLastLit = new HashMap<>();
    private static final Map<Integer, Integer> sLayout = new HashMap<>();
    private static final Map<Integer, Integer> sSlot = new HashMap<>();
    private static final Map<Integer, Long> sSlotTrack = new HashMap<>();

    private WidgetRenderer() {}

    // ==================================================================================== sizing

    /**
     * Layout choice, scales and pixel geometry of one widget for its prefs. Scales are solved so every element of
     * the chosen layout fits the widget's height and width; positions of the art and play button are known so the
     * card can light them.
     */
    static final class Size {
        private static final float PAD = 16, MIN_GAP = 8, CONTROL_BOOST = 1.15f;
        private static final boolean SCALABLE = Build.VERSION.SDK_INT >= 31;

        final int kind, layoutRes, wDp, hDp, cardW, cardH, waveW, waveH;
        final float scale, ctrlScale, textScale, artDp, waveDp;
        final int queueRows;
        private final WidgetPrefs prefs;
        private float artX, artY, playX, playY; // dp, for glows

        Size(Context ctx, int wDp, int hDp, WidgetPrefs p) {
            this.wDp = wDp;
            this.hDp = hDp;
            this.prefs = p;
            float d = ctx.getResources().getDisplayMetrics().density;
            int auto = hDp < 190 ? WidgetPrefs.LAYOUT_COMPACT
                    : hDp >= 1.25f * wDp && hDp >= 300 ? WidgetPrefs.LAYOUT_HERO : WidgetPrefs.LAYOUT_CLASSIC;
            int k = p.layout;
            // A chosen layout only applies where it physically fits; otherwise use what Auto would pick.
            if (k == WidgetPrefs.LAYOUT_AUTO
                    || (k == WidgetPrefs.LAYOUT_HERO && (hDp < 300 || hDp < 1.1f * wDp))
                    || (k == WidgetPrefs.LAYOUT_STUDIO && hDp < 220)
                    || (k == WidgetPrefs.LAYOUT_CLASSIC && hDp < 190)) {
                k = auto;
            }
            kind = k;
            float user = p.sizePercent / 100f;
            float s, c, t, art, wave;
            int rows = 0;
            switch (kind) {
                case WidgetPrefs.LAYOUT_COMPACT: {
                    layoutRes = R.layout.widget_compact;
                    float fit = Math.min((hDp - 24) / 84f, (wDp - 160) / 200f);
                    s = clampScale(Math.min(fit * user, fit));
                    c = s;
                    t = s;
                    art = 84 * s;
                    wave = 18 * s;
                    artX = 12;
                    artY = (hDp - art) / 2f;
                    playX = wDp - 12 - (24 * c + 6) - 4 - 24 * c;
                    playY = hDp / 2f;
                    break;
                }
                case WidgetPrefs.LAYOUT_HERO: {
                    layoutRes = R.layout.widget_hero;
                    // Tall widgets keep ~130dp under the player for the up-next queue.
                    boolean wantQueue = p.showUpNext && hDp > 1.6f * wDp;
                    float reserve = wantQueue ? 130 : 0;
                    float fit = 0.6f;
                    for (float x = 1.6f; x >= 0.6f; x -= 0.01f) {
                        float a = Math.min(wDp - 48, hDp - heroNeedWithoutArt(x, ctrl(x, wDp, p), x, p) - reserve);
                        if (a >= 100 * x) {
                            fit = x;
                            break;
                        }
                    }
                    s = clampScale(Math.min(fit * user, fit));
                    c = ctrl(s, wDp, p);
                    t = s;
                    float rest = hDp - heroNeedWithoutArt(s, c, t, p);
                    art = Math.max(64, Math.min(wDp - 48, rest - reserve));
                    float spare = rest - art;
                    if (wantQueue && spare >= 64) rows = (int) ((spare - 22) / (38 * t));
                    wave = 28 * s;
                    artX = (wDp - art) / 2f;
                    artY = PAD;
                    playX = wDp / 2f;
                    playY = PAD + art + heroTextBlock(t, p) + 10 + 28 * c;
                    break;
                }
                case WidgetPrefs.LAYOUT_STUDIO: {
                    layoutRes = R.layout.widget_studio;
                    float fit = 0.6f;
                    for (float x = 1.6f; x >= 0.6f; x -= 0.01f) {
                        if (32 + 64 * x + 12 + 120 <= wDp && hDp - studioNeed(x, ctrl(x, wDp, p), x, p) >= 56 * x) {
                            fit = x;
                            break;
                        }
                    }
                    s = clampScale(Math.min(fit * user, fit));
                    c = ctrl(s, wDp, p);
                    t = s;
                    art = 64 * s;
                    wave = Math.max(40, hDp - studioNeed(s, c, t, p));
                    artX = PAD;
                    artY = PAD + (Math.max(art, columnHeight(t, p, 18, 13, false)) - art) / 2f;
                    playX = wDp / 2f;
                    playY = PAD + Math.max(art, columnHeight(t, p, 18, 13, false)) + 10 + wave + 2 + 26 * 1.2f * t + 6 + 28 * c;
                    break;
                }
                default: {
                    layoutRes = R.layout.widget_player;
                    float fit = 0.6f;
                    for (float x = 1.8f; x >= 0.6f; x -= 0.01f) {
                        if (2 * PAD + 104 * x + 16 + 110 <= wDp && classicNeed(x, ctrl(x, wDp, p), x, p) <= hDp) {
                            fit = x;
                            break;
                        }
                    }
                    s = clampScale(Math.min(fit * user, fit));
                    c = ctrl(s, wDp, p);
                    float tt = Math.min(2f, s * Math.max(1f, user) * 1.08f);
                    while (tt > s && classicNeed(s, c, tt, p) > hDp) tt -= 0.02f;
                    t = Math.max(s, tt);
                    art = 104 * s;
                    wave = 28 * s;
                    float top = Math.max(art, columnHeight(t, p, 20, 14, true));
                    float rest = hDp - 2 * PAD - top - 56 * c - metaHeight(s, t, p) - bottomHeight(s, t, p);
                    float gap = Math.max(MIN_GAP, rest / 2f);
                    artX = PAD;
                    artY = PAD + (top - art) / 2f;
                    playX = wDp / 2f;
                    playY = PAD + top + gap + 28 * c;
                    break;
                }
            }
            if (!SCALABLE) {
                s = c = t = 1f;
                art = kind == WidgetPrefs.LAYOUT_COMPACT ? 84 : kind == WidgetPrefs.LAYOUT_STUDIO ? 64 : 104;
            }
            scale = s;
            ctrlScale = c;
            textScale = t;
            artDp = art;
            waveDp = wave;
            queueRows = rows;
            cardW = Math.max(1, Math.round(wDp * d));
            cardH = Math.max(1, Math.round(hDp * d));
            float waveWidthDp = kind == WidgetPrefs.LAYOUT_COMPACT
                    ? wDp - 24 - art - 12 - 6 - (2 * (24 * c + 6) + 48 * c + 8) : wDp - 2 * PAD;
            waveW = Math.max(1, Math.round(waveWidthDp * d));
            waveH = Math.max(1, Math.round(wave * d));
        }

        private static float clampScale(float v) {
            return Math.max(0.55f, Math.min(1.8f, v));
        }

        /** Control scale for art scale s: boosted, but the whole row must fit the card width. */
        private static float ctrl(float s, int wDp, WidgetPrefs p) {
            boolean side = p.hasSideButtons();
            float glyphs = 26 + 56 + 26 + (side ? 22 + 22 : 0);
            int small = side ? 4 : 2;
            int gaps = (side ? 5 : 3) + 1;
            float maxByWidth = (wDp - 2 * PAD - small * 6 - gaps * 6) / glyphs;
            return Math.min(s * CONTROL_BOOST, maxByWidth);
        }

        private static float columnHeight(float t, WidgetPrefs p, float titleSp, float artistSp, boolean twoLines) {
            return (p.showStatus ? 11 * 1.2f * t + 5 : 0) + titleSp * 1.2f * t * (twoLines ? 1 : 1)
                    + (p.showArtist ? 5 + artistSp * 1.2f * t : 0) + (p.showUpNext && twoLines ? 7 + 11.5f * 1.2f * t : 0);
        }

        private static boolean hasMeta(WidgetPrefs p) {
            return p.showFormat || p.showFavorite || p.showQuickActions;
        }

        private static float metaHeight(float s, float t, WidgetPrefs p) {
            return hasMeta(p) ? Math.max(20 * s + 10, 11 * 1.2f * t + 8 * s) : 0;
        }

        private static float bottomHeight(float s, float t, WidgetPrefs p) {
            return 10 + 28 * s + (p.showTimes ? 4 + 12 * 1.2f * t : 0);
        }

        private static float classicNeed(float s, float c, float t, WidgetPrefs p) {
            float top = Math.max(104 * s, columnHeight(t, p, 20, 14, true));
            return 2 * PAD + top + 2 * MIN_GAP + 56 * c + metaHeight(s, t, p) + bottomHeight(s, t, p);
        }

        private static float heroTextBlock(float t, WidgetPrefs p) {
            return (p.showStatus ? 12 + 11 * 1.2f * t + 5 : 0) + 2 * 22 * 1.15f * t
                    + (p.showArtist ? 5 + 15 * 1.2f * t : 0) + (p.showUpNext ? 7 + 11.5f * 1.2f * t : 0);
        }

        private static float heroNeedWithoutArt(float s, float c, float t, WidgetPrefs p) {
            return 2 * PAD + heroTextBlock(t, p) + 10 + 56 * c + (hasMeta(p) ? 6 + metaHeight(s, t, p) : 0)
                    + 8 + 28 * s + (p.showTimes ? 4 + 12 * 1.2f * t : 0);
        }

        private static float studioNeed(float s, float c, float t, WidgetPrefs p) {
            float top = Math.max(64 * s, columnHeight(t, p, 18, 13, false));
            return 2 * PAD + top + 10 + (p.showTimes ? 2 + 26 * 1.2f * t : 0) + 6 + 56 * c
                    + (hasMeta(p) ? 6 + metaHeight(s, t, p) : 0);
        }

        /** Art rect and play-button center on the card, in bitmap px ({@code pxPerDp} = density x bitmap scale). */
        WidgetGraphicsHelper.CardGeometry geometry(float pxPerDp, int artShape) {
            RectF artRect = new RectF(artX * pxPerDp, artY * pxPerDp, (artX + artDp) * pxPerDp, (artY + artDp) * pxPerDp);
            float artR = WidgetGraphicsHelper.artRadius(artShape, Math.round(artDp * pxPerDp));
            float playR = (kind == WidgetPrefs.LAYOUT_COMPACT ? 24 : 28) * ctrlScale;
            return new WidgetGraphicsHelper.CardGeometry(artRect, artR, playX * pxPerDp, playY * pxPerDp, playR * pxPerDp);
        }

        String geometryKey() {
            return kind + ":" + wDp + "x" + hDp + "@" + scale + "/" + ctrlScale + "/" + textScale + "/" + artDp;
        }

        /** Launcher-reported size: portrait uses (minWidth, maxHeight), landscape (maxWidth, minHeight). */
        static Size of(Context ctx, AppWidgetManager mgr, int id, WidgetPrefs p) {
            Bundle o = mgr.getAppWidgetOptions(id);
            boolean portrait = ctx.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
            int w = o.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
            int h = o.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
            return new Size(ctx, w > 0 ? w : 340, h > 0 ? h : 250, p);
        }
    }

    // ==================================================================================== updates

    static int[] widgetIds(Context ctx) {
        try {
            return AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, SparkleWidgetProvider.class));
        } catch (Throwable th) {
            Log.w(TAG, "getAppWidgetIds failed", th);
            return new int[0];
        }
    }

    /** Full update of every widget at its own size and style. */
    static void updateAll(Context ctx) {
        int[] ids = widgetIds(ctx);
        if (ids.length == 0) return;
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        PlayerState.Snapshot s = PlayerState.get(ctx).snapshot();
        for (int id : ids) {
            try {
                WidgetPrefs p = WidgetPrefs.forWidget(ctx, id).copy();
                Size size = Size.of(ctx, mgr, id, p);
                mgr.updateAppWidget(id, build(ctx, s, p, size, id));
                sLastLit.put(id, litFor(ctx, s, p, size));
                sLayout.put(id, size.layoutRes);
            } catch (Throwable th) {
                Log.e(TAG, "updateAppWidget failed for " + id, th);
            }
        }
        long retry = VisualCache.artRetryDelayMs(s);
        if (retry > 0) Events.retryLater(ctx, retry);
    }

    /** Once-a-second tick: resends only the progress bar, and only when it visibly moved. */
    static void updateProgress(Context ctx) {
        int[] ids = widgetIds(ctx);
        if (ids.length == 0) return;
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        PlayerState.Snapshot s = PlayerState.get(ctx).snapshot();
        for (int id : ids) {
            try {
                Integer layout = sLayout.get(id);
                if (layout == null) continue; // no full update yet in this process: wait for one
                WidgetPrefs p = WidgetPrefs.forWidget(ctx, id).copy();
                Size size = Size.of(ctx, mgr, id, p);
                if (size.layoutRes != layout) continue;
                int lit = litFor(ctx, s, p, size);
                Integer last = sLastLit.get(id);
                if (last != null && last == lit) continue;
                RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);
                rv.setImageViewBitmap(R.id.waveform, VisualCache.progress(ctx, s, p, size.waveW, size.waveH, size.scale, lit,
                        VisualCache.accent(ctx, s, p)));
                mgr.partiallyUpdateAppWidget(id, rv);
                sLastLit.put(id, lit);
            } catch (Throwable th) {
                Log.e(TAG, "progress update failed for " + id, th);
            }
        }
    }

    /** Same views the home screen gets, for the in-app preview. */
    static RemoteViews buildPreview(Context ctx, WidgetPrefs prefs, int wDp, int hDp) {
        WidgetPrefs p = prefs.copy();
        return build(ctx, PlayerState.get(ctx).snapshot(), p, new Size(ctx, wDp, hDp, p), 0);
    }

    static void forget(int widgetId) {
        sLastLit.remove(widgetId);
        sLayout.remove(widgetId);
        sSlot.remove(widgetId);
        sSlotTrack.remove(widgetId);
    }

    private static int litFor(Context ctx, PlayerState.Snapshot s, WidgetPrefs p, Size size) {
        return WidgetGraphicsHelper.litBars(s.progress(), VisualCache.progressSteps(ctx, p, size.waveW, size.scale));
    }

    // ==================================================================================== build

    private static RemoteViews build(Context ctx, PlayerState.Snapshot s, WidgetPrefs p, Size size, int widgetId) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), size.layoutRes);
        boolean modern = Build.VERSION.SDK_INT >= 31;
        int accent = VisualCache.accent(ctx, s, p);
        int accentLight = WidgetGraphicsHelper.lighten(accent, 0.25f);
        float density = ctx.getResources().getDisplayMetrics().density;
        WidgetGraphicsHelper.Ambient ambient = VisualCache.ambient(ctx, s);

        // ---- crossfade: a new track goes into the other slot and fades in. setDisplayedChild replays the
        // fade even for the same slot, so it's only sent when the slot actually changes. Slot 0 always holds
        // the current image too (unless it's the one fading out), so a fresh inflation (launcher restart),
        // which shows slot 0, is right.
        int slot = 0;
        boolean flip = false;
        if (widgetId != 0) {
            int cur = sSlot.containsKey(widgetId) ? sSlot.get(widgetId) : 0;
            Long lastTrack = sSlotTrack.get(widgetId);
            boolean newTrack = lastTrack != null && lastTrack != s.trackKey();
            flip = p.fxCrossfade && newTrack;
            slot = flip ? 1 - cur : cur;
            sSlot.put(widgetId, slot);
            sSlotTrack.put(widgetId, s.trackKey());
        }
        boolean alsoSlot0 = slot == 1 && !flip;

        // ---- card
        Bitmap bg = VisualCache.background(ctx, s, p, size, accent);
        if (bg != null) {
            rv.setImageViewBitmap(slot == 0 ? R.id.card_bg_a : R.id.card_bg_b, bg);
            if (alsoSlot0) rv.setImageViewBitmap(R.id.card_bg_a, bg);
            if (flip) rv.setDisplayedChild(R.id.card_flip, slot);
            rv.setViewVisibility(R.id.card_flip, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.card_flip, View.GONE);
        }
        if (modern) rv.setViewOutlinePreferredRadius(R.id.widget_root, p.cornerDp, TypedValue.COMPLEX_UNIT_DIP);

        // ---- breathing lights (only while playing)
        boolean breathe = modern && p.fxBreathing && s.playing && p.background != WidgetPrefs.BG_CLEAR;
        rv.setViewVisibility(R.id.aurora_a, breathe ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.aurora_b, breathe ? View.VISIBLE : View.GONE);
        if (breathe) {
            boolean dark = p.background == WidgetPrefs.BG_DARK;
            int a = dark ? WidgetGraphicsHelper.withAlpha(accent, 0x47) : WidgetGraphicsHelper.withAlpha(WidgetGraphicsHelper.lighten(ambient.vibrant, 0.1f), 0x80);
            int b = dark ? WidgetGraphicsHelper.withAlpha(accent, 0x33) : WidgetGraphicsHelper.withAlpha(WidgetGraphicsHelper.lighten(ambient.dominant, 0.15f), 0x5C);
            rv.setColorStateList(R.id.aurora_a, "setIndeterminateTintList", ColorStateList.valueOf(a));
            rv.setColorStateList(R.id.aurora_b, "setIndeterminateTintList", ColorStateList.valueOf(b));
        }

        // ---- art (crossfade slot) + vinyl spin
        Bitmap art = VisualCache.art(ctx, s, p.artShape);
        rv.setImageViewBitmap(slot == 0 ? R.id.art_a : R.id.art_b, art);
        if (alsoSlot0) rv.setImageViewBitmap(R.id.art_a, art);
        if (flip) rv.setDisplayedChild(R.id.art_flip, slot);
        rv.setViewVisibility(R.id.vinyl_spin, p.artShape == WidgetPrefs.ART_VINYL && s.playing ? View.VISIBLE : View.GONE);

        applyScale(ctx, rv, size, p);

        // ---- text
        String title, subtitle;
        if (s.hasTrack) {
            title = !s.title.isEmpty() ? s.title : ctx.getString(R.string.unknown_title);
            subtitle = !s.artist.isEmpty() ? s.artist : !s.album.isEmpty() ? s.album : ctx.getString(R.string.unknown_artist);
        } else {
            title = ctx.getString(R.string.idle_title);
            subtitle = ctx.getString(R.string.idle_subtitle);
        }
        rv.setTextViewText(R.id.title, font(title, p));
        rv.setTextViewText(R.id.artist, font(subtitle, p));
        rv.setViewVisibility(R.id.artist, p.showArtist ? View.VISIBLE : View.GONE);

        if (p.showStatus && s.hasTrack) {
            String status = ctx.getString(s.playing ? R.string.status_playing : R.string.status_paused);
            if (s.listSize > 1 && s.posInList >= 0) status += " · " + (s.posInList + 1) + "/" + s.listSize;
            rv.setTextViewText(R.id.status_label, font(status, p));
            rv.setTextColor(R.id.status_label, accentLight);
            rv.setViewVisibility(R.id.status_row, View.VISIBLE);
            boolean bars = modern && p.fxBars && s.playing;
            rv.setViewVisibility(R.id.eq_bars, bars ? View.VISIBLE : View.GONE);
            if (bars) rv.setColorStateList(R.id.eq_bars, "setIndeterminateTintList", ColorStateList.valueOf(accentLight));
        } else {
            rv.setViewVisibility(R.id.status_row, View.GONE);
        }

        // ---- up next (line) / queue (Hero list)
        bindUpNext(ctx, rv, s, p, size, widgetId == 0);

        // ---- controls
        int discPx = Math.round((size.kind == WidgetPrefs.LAYOUT_COMPACT ? 48 : 56) * size.ctrlScale * density);
        rv.setImageViewBitmap(R.id.btn_play, VisualCache.playDisc(ctx, discPx, accent, s.playing));
        rv.setInt(R.id.btn_play, "setBackgroundResource", 0);
        rv.setViewPadding(R.id.btn_play, 0, 0, 0, 0);
        rv.setContentDescription(R.id.btn_play, ctx.getString(s.playing ? R.string.cd_pause : R.string.cd_play));
        bindSideButtons(ctx, rv, s, p, accentLight);

        // ---- meta row: quick actions + format pill + like
        bindMeta(ctx, rv, s, p, size, accentLight);

        // ---- progress + times (+ sleep countdown / output device in the middle)
        rv.setImageViewBitmap(R.id.waveform,
                VisualCache.progress(ctx, s, p, size.waveW, size.waveH, size.scale, litFor(ctx, s, p, size), accent));
        rv.setViewVisibility(R.id.times_row, p.showTimes && size.kind != WidgetPrefs.LAYOUT_COMPACT ? View.VISIBLE : View.GONE);
        applyTimes(rv, s);
        // The device name needs room between the two times; the short sleep countdown always fits.
        bindCenterInfo(ctx, rv, s, p, accentLight, size.wDp >= 280 + 40 * (size.textScale - 1));

        // ---- clicks: transport goes straight to Poweramp; the rest through our receiver (state + icon update)
        PendingIntent open = PA.openPlayerPendingIntent(ctx);
        rv.setOnClickPendingIntent(R.id.art_box, open);
        rv.setOnClickPendingIntent(R.id.title, open);
        rv.setOnClickPendingIntent(R.id.artist, open);
        rv.setOnClickPendingIntent(R.id.status_row, open);
        rv.setOnClickPendingIntent(R.id.btn_prev, PA.commandPendingIntent(ctx, PA.CMD_PREVIOUS));
        rv.setOnClickPendingIntent(R.id.btn_play, PA.commandPendingIntent(ctx, PA.CMD_TOGGLE_PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.btn_next, PA.commandPendingIntent(ctx, PA.CMD_NEXT));
        rv.setOnClickPendingIntent(R.id.btn_favorite, action(ctx, WidgetActionReceiver.ACTION_FAVORITE, 0, 902));
        rv.setOnClickPendingIntent(R.id.btn_eq, PA.openScreenPendingIntent(ctx, PA.ACTION_OPEN_EQ, 300));
        rv.setOnClickPendingIntent(R.id.eq_preset, action(ctx, WidgetActionReceiver.ACTION_EQ_NEXT, 0, 905));
        rv.setOnClickPendingIntent(R.id.btn_library, PA.openScreenPendingIntent(ctx, PA.ACTION_OPEN_LIBRARY, 301));
        rv.setOnClickPendingIntent(R.id.btn_sleep, action(ctx, WidgetActionReceiver.ACTION_SLEEP, 0, 906));
        rv.setOnClickPendingIntent(R.id.btn_vol_down, action(ctx, WidgetActionReceiver.ACTION_VOL_DOWN, 0, 907));
        rv.setOnClickPendingIntent(R.id.btn_vol_up, action(ctx, WidgetActionReceiver.ACTION_VOL_UP, 0, 908));
        rv.setOnClickPendingIntent(R.id.center_info, action(ctx, WidgetActionReceiver.ACTION_SLEEP, 0, 909));
        for (int i = 0; i < SEEK_ZONES.length; i++) {
            rv.setOnClickPendingIntent(SEEK_ZONES[i], action(ctx, WidgetActionReceiver.ACTION_SEEK, i, 1000 + i));
        }
        return rv;
    }

    private static CharSequence font(String text, WidgetPrefs p) {
        String family = p.font >= 0 && p.font < FONT_FAMILIES.length ? FONT_FAMILIES[p.font] : null;
        if (family == null || text.isEmpty()) return text;
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new TypefaceSpan(family), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private static void bindSideButtons(Context ctx, RemoteViews rv, PlayerState.Snapshot s, WidgetPrefs p, int accentLight) {
        if (!p.hasSideButtons()) {
            rv.setViewVisibility(R.id.btn_left, View.GONE);
            rv.setViewVisibility(R.id.btn_right, View.GONE);
            return;
        }
        rv.setViewVisibility(R.id.btn_left, View.VISIBLE);
        rv.setViewVisibility(R.id.btn_right, View.VISIBLE);
        if (p.shuffleRepeat()) {
            rv.setImageViewResource(R.id.btn_left, R.drawable.ic_shuffle);
            rv.setInt(R.id.btn_left, "setColorFilter", s.shuffleOn() ? accentLight : 0xFFFFFFFF);
            rv.setInt(R.id.btn_left, "setImageAlpha", s.shuffleOn() ? 0xFF : 0x80);
            rv.setContentDescription(R.id.btn_left, ctx.getString(s.shuffleOn() ? R.string.cd_shuffle_on : R.string.cd_shuffle_off));
            rv.setOnClickPendingIntent(R.id.btn_left, action(ctx, WidgetActionReceiver.ACTION_SHUFFLE, 0, 900));
            rv.setImageViewResource(R.id.btn_right, s.repeat == PA.REPEAT_SONG ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            rv.setInt(R.id.btn_right, "setColorFilter", s.repeatOn() ? accentLight : 0xFFFFFFFF);
            rv.setInt(R.id.btn_right, "setImageAlpha", s.repeatOn() ? 0xFF : 0x80);
            rv.setContentDescription(R.id.btn_right, ctx.getString(
                    s.repeat == PA.REPEAT_SONG ? R.string.cd_repeat_one : s.repeatOn() ? R.string.cd_repeat_on : R.string.cd_repeat_off));
            rv.setOnClickPendingIntent(R.id.btn_right, action(ctx, WidgetActionReceiver.ACTION_REPEAT, 0, 901));
        } else {
            rv.setImageViewResource(R.id.btn_left, R.drawable.ic_replay10);
            rv.setImageViewResource(R.id.btn_right, R.drawable.ic_forward10);
            for (int id : new int[]{R.id.btn_left, R.id.btn_right}) {
                rv.setInt(id, "setColorFilter", 0xFFFFFFFF);
                rv.setInt(id, "setImageAlpha", 0xCC);
            }
            rv.setContentDescription(R.id.btn_left, ctx.getString(R.string.cd_back10));
            rv.setContentDescription(R.id.btn_right, ctx.getString(R.string.cd_fwd10));
            rv.setOnClickPendingIntent(R.id.btn_left, action(ctx, WidgetActionReceiver.ACTION_SKIP_BACK, 0, 903));
            rv.setOnClickPendingIntent(R.id.btn_right, action(ctx, WidgetActionReceiver.ACTION_SKIP_FWD, 0, 904));
        }
    }

    /** Quick actions are added in priority order only while they fit next to the pill and the heart. */
    private static void bindMeta(Context ctx, RemoteViews rv, PlayerState.Snapshot s, WidgetPrefs p, Size size, int accentLight) {
        float sc = size.scale, t = size.textScale;
        float budget = size.wDp - 32;
        WidgetGraphicsHelper.Spec spec = p.showFormat ? WidgetGraphicsHelper.audioSpec(s) : null;
        if (spec != null) {
            rv.setTextViewText(R.id.spec_pill, font(spec.text, p));
            rv.setInt(R.id.spec_pill, "setBackgroundResource", spec.hiRes ? R.drawable.pill_hires : R.drawable.pill_glass);
            rv.setTextColor(R.id.spec_pill, spec.hiRes ? 0xFF2A1F05 : 0xCCFFFFFF);
            budget -= spec.text.length() * 6.6f * t + 22 * sc;
        }
        rv.setViewVisibility(R.id.spec_pill, spec != null ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.btn_favorite, p.showFavorite ? View.VISIBLE : View.GONE);
        if (p.showFavorite) budget -= 20 * sc + 10;
        rv.setImageViewResource(R.id.btn_favorite, s.favorite() ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        rv.setInt(R.id.btn_favorite, "setColorFilter", s.favorite() ? accentLight : 0xFFFFFFFF);
        rv.setContentDescription(R.id.btn_favorite, ctx.getString(s.favorite() ? R.string.cd_unfavorite : R.string.cd_favorite));

        float icon = 20 * sc + 10 + 4;
        boolean qa = p.showQuickActions;
        boolean eq = qa && p.qaEq && (budget -= icon) >= 0;
        boolean sleep = qa && p.qaSleep && (budget -= icon) >= 0;
        String preset = !eq ? null : !s.eqPresetName.isEmpty() ? s.eqPresetName
                : LibraryData.eqPresets(ctx).isEmpty() ? null : ctx.getString(R.string.eq_presets_label);
        boolean chip = preset != null && (budget -= Math.min(96, preset.length() * 6f * t + 16)) >= 0;
        boolean lib = qa && p.qaLibrary && (budget -= icon) >= 0;
        boolean vol = qa && p.qaVolume && (budget -= 2 * icon) >= 0;
        rv.setViewVisibility(R.id.btn_eq, eq ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.eq_preset, chip ? View.VISIBLE : View.GONE);
        if (chip) rv.setTextViewText(R.id.eq_preset, font(preset, p));
        rv.setViewVisibility(R.id.btn_sleep, sleep ? View.VISIBLE : View.GONE);
        boolean sleeping = s.sleepEndWall > 0;
        rv.setInt(R.id.btn_sleep, "setColorFilter", sleeping ? accentLight : 0xFFFFFFFF);
        rv.setInt(R.id.btn_sleep, "setImageAlpha", sleeping ? 0xFF : 0xCC);
        rv.setViewVisibility(R.id.btn_library, lib ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.btn_vol_down, vol ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.btn_vol_up, vol ? View.VISIBLE : View.GONE);
        boolean any = spec != null || p.showFavorite || eq || sleep || lib || vol;
        rv.setViewVisibility(R.id.meta_row, any && size.kind != WidgetPrefs.LAYOUT_COMPACT ? View.VISIBLE : View.GONE);
    }

    /** Middle of the times row: sleep countdown while a sleep timer runs, otherwise the output device. */
    private static void bindCenterInfo(Context ctx, RemoteViews rv, PlayerState.Snapshot s, WidgetPrefs p, int accentLight,
                                       boolean deviceFits) {
        long now = System.currentTimeMillis();
        if (s.sleepEndWall > now) {
            rv.setViewVisibility(R.id.center_info, View.VISIBLE);
            rv.setImageViewResource(R.id.center_icon, R.drawable.ic_moon);
            rv.setInt(R.id.center_icon, "setColorFilter", accentLight);
            rv.setViewVisibility(R.id.device_label, View.GONE);
            rv.setChronometerCountDown(R.id.sleep_left, true);
            rv.setChronometer(R.id.sleep_left, SystemClock.elapsedRealtime() + (s.sleepEndWall - now), "%s", true);
            rv.setTextColor(R.id.sleep_left, accentLight);
            rv.setViewVisibility(R.id.sleep_left, View.VISIBLE);
        } else if (p.showDevice && s.hasTrack && deviceFits) {
            OutputDevice out = OutputDevice.current(ctx);
            rv.setViewVisibility(R.id.center_info, View.VISIBLE);
            rv.setImageViewResource(R.id.center_icon, out.icon);
            rv.setInt(R.id.center_icon, "setColorFilter", 0x99FFFFFF);
            rv.setTextViewText(R.id.device_label, font(out.label, p));
            rv.setViewVisibility(R.id.device_label, View.VISIBLE);
            rv.setChronometer(R.id.sleep_left, SystemClock.elapsedRealtime(), "%s", false);
            rv.setViewVisibility(R.id.sleep_left, View.GONE);
        } else {
            rv.setViewVisibility(R.id.center_info, View.GONE);
        }
    }

    private static void bindUpNext(Context ctx, RemoteViews rv, PlayerState.Snapshot s, WidgetPrefs p, Size size, boolean preview) {
        boolean wantLine = p.showUpNext && size.kind != WidgetPrefs.LAYOUT_COMPACT;
        boolean wantList = p.showUpNext && size.kind == WidgetPrefs.LAYOUT_HERO && size.queueRows > 0
                && Build.VERSION.SDK_INT >= 31;
        List<LibraryData.Item> next = (wantLine || wantList)
                ? LibraryData.upNext(ctx, s, wantList ? Math.max(1, Math.min(20, size.queueRows + 6)) : 1)
                : java.util.Collections.emptyList();

        if (wantLine && !next.isEmpty() && !wantList) {
            LibraryData.Item n = next.get(0);
            String line = ctx.getString(R.string.up_next_prefix) + "  " + n.title + (n.artist.isEmpty() ? "" : " — " + n.artist);
            rv.setTextViewText(R.id.up_next, font(line, p));
            rv.setViewVisibility(R.id.up_next, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.up_next, View.GONE);
        }

        if (size.kind != WidgetPrefs.LAYOUT_HERO) return;
        rv.setViewVisibility(R.id.queue_static, View.GONE);
        if (wantList && !next.isEmpty() && preview) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(next.size(), size.queueRows); i++) {
                LibraryData.Item n = next.get(i);
                if (sb.length() > 0) sb.append('\n');
                sb.append(i + 1).append("   ").append(n.title).append(n.artist.isEmpty() ? "" : "  ·  " + n.artist);
            }
            rv.setTextViewText(R.id.queue_static, font(sb.toString(), p));
            rv.setTextViewTextSize(R.id.queue_static, TypedValue.COMPLEX_UNIT_SP, 13 * size.textScale);
            rv.setViewVisibility(R.id.queue_static, View.VISIBLE);
            rv.setViewVisibility(R.id.queue_header, View.VISIBLE);
            rv.setViewVisibility(R.id.queue_list, View.GONE);
            rv.setViewVisibility(R.id.hero_tail, View.VISIBLE);
        } else if (wantList && !next.isEmpty()) {
            RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true).setViewTypeCount(1);
            for (int i = 0; i < next.size(); i++) {
                LibraryData.Item n = next.get(i);
                RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.item_queue);
                row.setTextViewText(R.id.queue_index, String.valueOf(i + 1));
                row.setTextViewText(R.id.queue_title, font(n.title, p));
                row.setTextViewText(R.id.queue_artist, font(n.artist, p));
                row.setTextViewTextSize(R.id.queue_title, TypedValue.COMPLEX_UNIT_SP, 13 * size.textScale);
                row.setTextViewTextSize(R.id.queue_artist, TypedValue.COMPLEX_UNIT_SP, 11 * size.textScale);
                row.setOnClickFillInIntent(R.id.queue_item, new Intent().putExtra(WidgetActionReceiver.EXTRA_URI, n.uri.toString()));
                items.addItem(i + 1, row);
            }
            rv.setRemoteAdapter(R.id.queue_list, items.build());
            rv.setPendingIntentTemplate(R.id.queue_list, PendingIntent.getBroadcast(ctx, 950,
                    new Intent(ctx, WidgetActionReceiver.class).setAction(WidgetActionReceiver.ACTION_PLAY_ITEM),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            rv.setViewVisibility(R.id.queue_header, View.VISIBLE);
            rv.setViewVisibility(R.id.queue_list, View.VISIBLE);
            rv.setViewVisibility(R.id.hero_tail, View.GONE);
        } else {
            rv.setViewVisibility(R.id.queue_header, View.GONE);
            rv.setViewVisibility(R.id.queue_list, View.GONE);
            rv.setViewVisibility(R.id.hero_tail, View.VISIBLE);
        }
    }

    /** Resizes art, controls, text and the progress bar for the chosen layout (API 31+; XML sizes below that). */
    private static void applyScale(Context ctx, RemoteViews rv, Size size, WidgetPrefs p) {
        if (Build.VERSION.SDK_INT < 31) return;
        final int sp = TypedValue.COMPLEX_UNIT_SP, dip = TypedValue.COMPLEX_UNIT_DIP;
        float s = size.scale, t = size.textScale, c = size.ctrlScale;
        float d = ctx.getResources().getDisplayMetrics().density;
        int kind = size.kind;

        float titleSp = kind == WidgetPrefs.LAYOUT_COMPACT ? 16 : kind == WidgetPrefs.LAYOUT_HERO ? 22 : kind == WidgetPrefs.LAYOUT_STUDIO ? 18 : 20;
        float artistSp = kind == WidgetPrefs.LAYOUT_HERO ? 15 : kind == WidgetPrefs.LAYOUT_CLASSIC ? 14 : 13;
        rv.setTextViewTextSize(R.id.status_label, sp, 9.5f * t);
        rv.setTextViewTextSize(R.id.title, sp, titleSp * t);
        rv.setTextViewTextSize(R.id.artist, sp, artistSp * t);
        rv.setTextViewTextSize(R.id.up_next, sp, 11.5f * t);
        rv.setTextViewTextSize(R.id.spec_pill, sp, 11 * t);
        rv.setTextViewTextSize(R.id.eq_preset, sp, 10 * t);
        float timeSp = kind == WidgetPrefs.LAYOUT_STUDIO ? 26 : 12;
        for (int id : new int[]{R.id.elapsed_live, R.id.elapsed_static, R.id.remaining_live, R.id.remaining_static}) {
            rv.setTextViewTextSize(id, sp, timeSp * t);
        }
        rv.setTextViewTextSize(R.id.device_label, sp, 11.5f * t);
        rv.setTextViewTextSize(R.id.sleep_left, sp, 11.5f * t);
        rv.setViewLayoutWidth(R.id.center_icon, 12 * t, dip);
        rv.setViewLayoutHeight(R.id.center_icon, 12 * t, dip);
        rv.setViewLayoutWidth(R.id.eq_bars, 11 * t, dip);
        rv.setViewLayoutHeight(R.id.eq_bars, 11 * t, dip);

        rv.setViewLayoutWidth(R.id.art_box, size.artDp, dip);
        rv.setViewLayoutHeight(R.id.art_box, size.artDp, dip);

        // Controls (icon views keep 3dp side padding, so glyph = width - 6dp)
        if (kind == WidgetPrefs.LAYOUT_COMPACT) {
            rv.setViewLayoutHeight(R.id.controls, 56 * c, dip);
            rv.setViewLayoutWidth(R.id.btn_prev, 24 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_next, 24 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_play, 48 * c, dip);
            rv.setViewLayoutHeight(R.id.btn_play, 48 * c, dip);
            rv.setViewLayoutHeight(R.id.seek_area, size.waveDp, dip);
        } else {
            rv.setViewLayoutHeight(R.id.controls, 56 * c, dip);
            rv.setViewLayoutWidth(R.id.btn_left, 22 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_prev, 26 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_next, 26 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_right, 22 * c + 6, dip);
            rv.setViewLayoutWidth(R.id.btn_play, 56 * c, dip);
            rv.setViewLayoutHeight(R.id.btn_play, 56 * c, dip);
            if (kind != WidgetPrefs.LAYOUT_STUDIO) rv.setViewLayoutHeight(R.id.seek_area, size.waveDp, dip);
        }

        // Meta row: quick actions keep 5dp padding (glyph = target - 10dp), heart 4dp (glyph = target - 8dp)
        for (int id : new int[]{R.id.btn_eq, R.id.btn_library, R.id.btn_sleep, R.id.btn_vol_down, R.id.btn_vol_up}) {
            rv.setViewLayoutWidth(id, 20 * s + 10, dip);
            rv.setViewLayoutHeight(id, 20 * s + 10, dip);
        }
        rv.setViewLayoutWidth(R.id.btn_favorite, 20 * s + 8, dip);
        rv.setViewLayoutHeight(R.id.btn_favorite, 20 * s + 8, dip);
        int padH = Math.round(10 * s * d), padV = Math.round(4 * s * d);
        rv.setViewPadding(R.id.spec_pill, padH, padV, padH, padV);
        if (kind == WidgetPrefs.LAYOUT_HERO && size.queueRows > 0) {
            rv.setViewLayoutHeight(R.id.queue_list, size.queueRows * 38f * t + 4, dip);
        }
    }

    private static PendingIntent action(Context ctx, String action, int zone, int requestCode) {
        Intent i = new Intent(ctx, WidgetActionReceiver.class).setAction(action).putExtra(WidgetActionReceiver.EXTRA_ZONE, zone);
        return PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Elapsed / remaining. While playing, Chronometers tick on their own inside the launcher, so the times stay
     * live even when this app's process is asleep. While paused, plain TextViews are shown (a stopped Chronometer
     * would drift whenever the launcher re-inflates it).
     */
    private static void applyTimes(RemoteViews rv, PlayerState.Snapshot s) {
        long pos = s.posMs;
        long dur = s.durMs;
        boolean showRemaining = dur > 0;

        if (s.playing) {
            long nowEl = SystemClock.elapsedRealtime();
            rv.setChronometer(R.id.elapsed_live, nowEl - pos, "%s", true);
            rv.setViewVisibility(R.id.elapsed_live, View.VISIBLE);
            rv.setViewVisibility(R.id.elapsed_static, View.GONE);
            if (showRemaining) {
                // Countdown Chronometers round down; +500 ms makes them round to nearest like the elapsed one,
                // so elapsed + remaining always adds up to the track length.
                rv.setChronometerCountDown(R.id.remaining_live, true);
                rv.setChronometer(R.id.remaining_live, nowEl + (dur - pos) + 500, "-%s", true);
            } else {
                rv.setChronometer(R.id.remaining_live, nowEl, "-%s", false);
            }
            rv.setViewVisibility(R.id.remaining_live, showRemaining ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.remaining_static, View.GONE);
        } else {
            rv.setChronometer(R.id.elapsed_live, SystemClock.elapsedRealtime(), "%s", false);
            rv.setChronometer(R.id.remaining_live, SystemClock.elapsedRealtime(), "-%s", false);
            rv.setTextViewText(R.id.elapsed_static, DateUtils.formatElapsedTime(Math.round(pos / 1000.0)));
            rv.setTextViewText(R.id.remaining_static, "-" + DateUtils.formatElapsedTime(Math.max(0, Math.round((dur - pos) / 1000.0))));
            rv.setViewVisibility(R.id.elapsed_live, View.GONE);
            rv.setViewVisibility(R.id.elapsed_static, View.VISIBLE);
            rv.setViewVisibility(R.id.remaining_live, View.GONE);
            rv.setViewVisibility(R.id.remaining_static, showRemaining ? View.VISIBLE : View.GONE);
        }
    }
}
