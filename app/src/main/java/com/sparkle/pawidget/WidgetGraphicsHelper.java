package com.sparkle.pawidget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

import java.util.Locale;
import java.util.Random;

/** Pure drawing helpers for the widget. No caching here (see VisualCache). */
final class WidgetGraphicsHelper {
    /** Card base color (alpha comes from the opacity setting). */
    static final int BASE_GLASS = 0xFF14181D;
    private static final int DEFAULT_DOMINANT = 0xFF3B4A63;
    private static final int DEFAULT_DARK = 0xFF1A2130;
    private static final int DEFAULT_VIBRANT = 0xFF5D7FB8;

    private static Bitmap sNoise;

    private WidgetGraphicsHelper() {}

    // ================================================================ album art

    /** Corner radius for an art bitmap of {@code sizePx} in the chosen shape (rounded = 20/104 of the side). */
    static float artRadius(int shape, int sizePx) {
        switch (shape) {
            case WidgetPrefs.ART_CIRCLE:
            case WidgetPrefs.ART_VINYL: return sizePx / 2f;
            case WidgetPrefs.ART_SQUARE: return sizePx * 0.04f;
            default: return sizePx * 20f / 104f;
        }
    }

    /** Center-crops the cover into the given rounded shape, with a hairline inner rim for definition. */
    static Bitmap renderArt(Context ctx, Bitmap cover, int sizePx, float radiusPx) {
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        RectF r = new RectF(0, 0, sizePx, sizePx);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (cover != null && cover.getWidth() > 0 && cover.getHeight() > 0) {
            p.setShader(centerCropShader(cover, sizePx, sizePx));
        } else {
            Drawable ph = ctx.getDrawable(R.drawable.art_placeholder);
            Bitmap tmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(tmp);
            tc.drawColor(0xFF1B2230);
            if (ph != null) {
                ph.setBounds(0, 0, sizePx, sizePx);
                ph.draw(tc);
            }
            p.setShader(new BitmapShader(tmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(r, radiusPx, radiusPx, p);

        // Hairline rim: lighter at the top, fading out — reads as a glass edge on dark and bright covers alike.
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1f, sizePx / 200f);
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(0, 0, 0, sizePx, 0x40FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        RectF ri = new RectF(sw / 2, sw / 2, sizePx - sw / 2, sizePx - sw / 2);
        c.drawRoundRect(ri, Math.max(0, radiusPx - sw / 2), Math.max(0, radiusPx - sw / 2), rim);
        return out;
    }

    /**
     * Vinyl record: dark disc with fine grooves and a couple of track gaps, a static reflection, the cover as the
     * center label, and a spindle hole. (A separate rotating sheen overlays it while playing.)
     */
    static Bitmap renderVinyl(Context ctx, Bitmap cover, int sizePx) {
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = sizePx / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(r, r, r, new int[]{0xFF26262B, 0xFF141417, 0xFF09090B}, new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        p.setShader(null);

        // Grooves
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(Math.max(0.6f, sizePx / 600f));
        float step = Math.max(2f, sizePx / 150f);
        int i = 0;
        for (float rr = r * 0.46f; rr < r * 0.97f; rr += step, i++) {
            g.setColor(i % 2 == 0 ? 0x14FFFFFF : 0x0AFFFFFF);
            c.drawCircle(r, r, rr, g);
        }
        g.setStrokeWidth(Math.max(1f, sizePx / 260f));
        g.setColor(0x33000000);
        c.drawCircle(r, r, r * 0.62f, g);
        c.drawCircle(r, r, r * 0.8f, g);

        // Static reflection: two opposite soft wedges
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        sheen.setShader(new android.graphics.SweepGradient(r, r,
                new int[]{0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0.05f, 0.13f, 0.22f, 0.55f, 0.63f, 0.72f, 1f}));
        c.drawCircle(r, r, r, sheen);

        // Label = cover
        float lr = r * 0.42f;
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int ls = Math.round(lr * 2);
        if (cover != null && cover.getWidth() > 0) {
            BitmapShader bs = centerCropShader(cover, ls, ls);
            Matrix m = new Matrix();
            bs.getLocalMatrix(m);
            m.postTranslate(r - lr, r - lr);
            bs.setLocalMatrix(m);
            label.setShader(bs);
        } else {
            label.setColor(0xFFC9A46A);
        }
        c.drawCircle(r, r, lr, label);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, sizePx / 200f));
        ring.setColor(0x66000000);
        c.drawCircle(r, r, lr, ring);

        // Spindle hole
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(0xFF0B0B0D);
        c.drawCircle(r, r, r * 0.035f, hole);
        ring.setColor(0x40FFFFFF);
        ring.setStrokeWidth(Math.max(1f, sizePx / 400f));
        c.drawCircle(r, r, r * 0.035f, ring);

        // Rim light
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1f, sizePx / 220f);
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(0, 0, 0, sizePx, 0x4DFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r - sw / 2, rim);
        return out;
    }

    /** Square, 128px center crop of the cover: source for the blurred card (small enough to keep and persist). */
    static Bitmap smallCover(Bitmap cover) {
        if (cover == null) return null;
        Bitmap out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setShader(centerCropShader(cover, 128, 128));
        new Canvas(out).drawRect(0, 0, 128, 128, p);
        return out;
    }

    private static BitmapShader centerCropShader(Bitmap src, int w, int h) {
        BitmapShader bs = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        bs.setLocalMatrix(m);
        return bs;
    }

    // ================================================================ ambient colors

    static final class Ambient {
        final int dominant, darkMuted, vibrant;

        Ambient(int dominant, int darkMuted, int vibrant) {
            this.dominant = dominant;
            this.darkMuted = darkMuted;
            this.vibrant = vibrant;
        }

        static final Ambient DEFAULT = new Ambient(DEFAULT_DOMINANT, DEFAULT_DARK, DEFAULT_VIBRANT);
    }

    /** Palette on a 64x64 downscale: fast and stable. */
    static Ambient extractAmbient(Bitmap cover) {
        if (cover == null) return Ambient.DEFAULT;
        try {
            Bitmap small = Bitmap.createScaledBitmap(cover, 64, 64, true);
            Palette p = Palette.from(small).maximumColorCount(16).generate();
            int dominant = p.getDominantColor(DEFAULT_DOMINANT);
            int dark = p.getDarkMutedColor(p.getDarkVibrantColor(darken(dominant, 0.5f)));
            int vibrant = p.getVibrantColor(p.getLightVibrantColor(p.getMutedColor(dominant)));
            if (small != cover) small.recycle();
            return new Ambient(dominant, dark, vibrant);
        } catch (Throwable th) {
            return Ambient.DEFAULT;
        }
    }

    // ================================================================ card background

    /** Where the art and the play button sit on the card, in background-bitmap pixels (for glows/shadows). */
    static final class CardGeometry {
        final RectF art;
        final float artRadius, playCx, playCy, playR;

        CardGeometry(RectF art, float artRadius, float playCx, float playCy, float playR) {
            this.art = art;
            this.artRadius = artRadius;
            this.playCx = playCx;
            this.playCy = playCy;
            this.playR = playR;
        }
    }

    /**
     * The card. Layers, bottom to top: base (blurred cover / glass gradient / flat dark), color mesh, bottom
     * scrim, art shadow + colored glow, play-button glow, top sheen, film grain — all masked to the rounded card —
     * then a light-catching rim. The whole card is composited at the opacity setting. Null for "clear".
     */
    static Bitmap renderBackground(int w, int h, float radius, float density, Ambient a, Bitmap coverSmall,
                                   CardGeometry g, int accent, WidgetPrefs prefs) {
        if (prefs.background == WidgetPrefs.BG_CLEAR) return null;
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        RectF rect = new RectF(0, 0, w, h);
        float strength = prefs.tintStrength / 60f; // 0..1

        int outer = c.saveLayerAlpha(rect, Math.round(255 * prefs.opacity / 100f));
        c.saveLayer(rect, null);

        // --- base
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (prefs.background == WidgetPrefs.BG_IMMERSIVE && coverSmall != null) {
            Bitmap blur = blurredCrop(coverSmall, w, h);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.45f);
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(blur, null, rect, p);
            p.setColorFilter(null);
            // Darken for contrast; the "album color" slider lets more of the art through.
            c.drawColor(Color.argb(Math.round(255 * (0.64f - 0.34f * strength)), 6, 8, 12));
        } else if (prefs.background == WidgetPrefs.BG_DARK) {
            c.drawColor(BASE_GLASS);
        } else {
            // Album glass (or immersive without a cover): diagonal gradient from the palette.
            float s = prefs.background == WidgetPrefs.BG_IMMERSIVE ? Math.max(0.35f, strength) : strength * 0.75f + 0.05f;
            p.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{blend(BASE_GLASS, a.dominant, s), blend(BASE_GLASS, a.dominant, s * 0.36f),
                            blend(BASE_GLASS, a.darkMuted, s * 0.45f)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(rect, p);
            p.setShader(null);
        }

        if (prefs.background != WidgetPrefs.BG_DARK) {
            // --- color mesh: vibrant light from the art's corner, a second tone from the opposite corner
            float m = prefs.background == WidgetPrefs.BG_IMMERSIVE ? 0.34f : 0.22f;
            radial(c, w * 0.18f, h * 0.16f, Math.max(w, h) * 0.75f, withAlpha(a.vibrant, Math.round(255 * m)));
            radial(c, w * 0.95f, h * 0.92f, Math.max(w, h) * 0.7f, withAlpha(a.dominant, Math.round(255 * m * 0.7f)));
            // --- bottom scrim keeps the progress bar and times crisp
            p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
            c.drawRect(rect, p);
            p.setShader(null);
        }

        if (prefs.fxGlow && g != null) {
            // --- art: colored glow + soft drop shadow (the art itself is drawn on top by the widget)
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(withAlpha(lighten(a.vibrant, 0.1f), 0x8C));
            glow.setMaskFilter(new BlurMaskFilter(Math.max(1f, 18 * density), BlurMaskFilter.Blur.NORMAL));
            RectF ga = new RectF(g.art);
            ga.inset(g.art.width() * 0.06f, g.art.height() * 0.06f);
            ga.offset(0, 6 * density);
            c.drawRoundRect(ga, g.artRadius, g.artRadius, glow);
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(0x73000000);
            shadow.setMaskFilter(new BlurMaskFilter(Math.max(1f, 9 * density), BlurMaskFilter.Blur.NORMAL));
            RectF sa = new RectF(g.art);
            sa.offset(0, 4 * density);
            c.drawRoundRect(sa, g.artRadius, g.artRadius, shadow);
            // --- play button: accent bloom
            radial(c, g.playCx, g.playCy + g.playR * 0.15f, g.playR * 2.1f, withAlpha(accent, 0x70));
        }

        if (prefs.fxEdge) {
            // --- top sheen
            p.setShader(new LinearGradient(0, 0, 0, h * 0.4f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(rect, p);
            p.setShader(null);
        }

        if (prefs.fxGrain) {
            // --- film grain: breaks up banding and gives the glass a tactile surface
            Paint n = new Paint();
            n.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            c.drawRect(rect, n);
        }

        // --- mask everything to the rounded card (antialiased)
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRoundRect(rect, radius, radius, mask);
        c.restore();

        if (prefs.fxEdge) {
            // --- rim light: bright where light hits (top-left), barely there at the bottom-right
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            float sw = Math.max(1f, 1.1f * density);
            rim.setStrokeWidth(sw);
            rim.setShader(new LinearGradient(0, 0, w * 0.6f, h,
                    new int[]{0x66FFFFFF, 0x14FFFFFF, 0x08FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            RectF ri = new RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
            c.drawRoundRect(ri, Math.max(0, radius - sw / 2), Math.max(0, radius - sw / 2), rim);
        }
        c.restoreToCount(outer);
        return out;
    }

    private static void radial(Canvas c, float cx, float cy, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(cx, cy, Math.max(1f, r), new int[]{color, withAlpha(color, Color.alpha(color) / 3), 0},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    /** Cover cropped to the card's aspect at ~80px and box-blurred (3 passes): smooth when scaled up. */
    private static Bitmap blurredCrop(Bitmap small, int w, int h) {
        int bw = 80, bh = Math.max(8, Math.round(80f * h / w));
        Bitmap crop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Zoom in a little so the blur has no edge falloff.
        BitmapShader bs = centerCropShader(small, bw, bh);
        Matrix m = new Matrix();
        bs.getLocalMatrix(m);
        m.postScale(1.25f, 1.25f, bw / 2f, bh / 2f);
        bs.setLocalMatrix(m);
        p.setShader(bs);
        new Canvas(crop).drawRect(0, 0, bw, bh, p);
        int[] px = new int[bw * bh];
        crop.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, bw, bh, 7, true);
            boxBlur(px, bw, bh, 7, false);
        }
        crop.setPixels(px, 0, bw, 0, 0, bw, bh);
        return crop;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int[] out = new int[px.length];
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[idx(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF; n++;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = px[idx(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int rem = px[idx(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
        System.arraycopy(out, 0, px, 0, px.length);
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** 96px tile of faint light/dark specks. */
    private static synchronized Bitmap noise() {
        if (sNoise == null) {
            int n = 96;
            int[] px = new int[n * n];
            Random r = new Random(42);
            for (int i = 0; i < px.length; i++) {
                int a = r.nextInt(18);
                px[i] = r.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
            }
            sNoise = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        return sNoise;
    }

    // ================================================================ play button

    /**
     * Lit play disc: accent gradient (lighter on top), a soft specular highlight, a hairline rim, and the
     * play/pause glyph in a color that contrasts with the accent.
     */
    static Bitmap renderPlayDisc(Context ctx, int sizePx, int accent, boolean playing) {
        sizePx = Math.max(8, sizePx);
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = sizePx / 2f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        fill.setShader(new LinearGradient(0, 0, 0, sizePx, lighten(accent, 0.16f), darken(accent, 0.78f), Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, fill);

        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        hl.setShader(new RadialGradient(r, r * 0.35f, r * 0.95f, new int[]{0x59FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, hl);

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1f, sizePx / 90f);
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(0, 0, 0, sizePx, 0x80FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r - sw / 2, rim);

        Drawable glyph = ctx.getDrawable(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(glyphOn(accent));
            int gs = Math.round(sizePx * 0.42f);
            int o = (sizePx - gs) / 2;
            glyph.setBounds(o, o, o + gs, o + gs);
            glyph.draw(c);
        }
        return out;
    }

    // ================================================================ waveform

    /** Bars that fit: 2-unit pill bars with 2-unit gaps. {@code unit} = px per spec-dp (density x scale). */
    static int barCount(int widthPx, float unit) {
        float pitch = 4f * unit;
        return Math.max(8, (int) ((widthPx + 2f * unit) / pitch));
    }

    /**
     * Deterministic pseudo-waveform seeded by the track (title + duration): every song gets its own stable
     * silhouette with no audio decoding. Values are 0..1.
     */
    static float[] waveformHeights(String seed, int n) {
        long h = 1125899906842597L;
        for (int i = 0; i < seed.length(); i++) h = 31 * h + seed.charAt(i);
        Random rnd = new Random(h);

        double p1 = rnd.nextDouble() * Math.PI * 2, p2 = rnd.nextDouble() * Math.PI * 2, p3 = rnd.nextDouble() * Math.PI * 2;
        double f1 = 0.8 + rnd.nextDouble() * 1.2, f2 = 2.5 + rnd.nextDouble() * 2.5, f3 = 6 + rnd.nextDouble() * 5;
        float[] raw = new float[n];
        for (int i = 0; i < n; i++) {
            double x = n == 1 ? 0 : i / (double) (n - 1);
            double env = 0.52 + 0.22 * Math.sin(2 * Math.PI * f1 * x + p1)
                    + 0.12 * Math.sin(2 * Math.PI * f2 * x + p2)
                    + 0.06 * Math.sin(2 * Math.PI * f3 * x + p3);
            double v = env * (0.55 + 0.45 * rnd.nextDouble());
            double edge = Math.min(1.0, Math.min(x, 1 - x) / 0.07); // soft intro/outro
            raw[i] = (float) (v * (0.35 + 0.65 * edge));
        }
        float[] out = new float[n];
        float max = 0f;
        for (int i = 0; i < n; i++) {
            float a = raw[Math.max(0, i - 1)], b = raw[i], c = raw[Math.min(n - 1, i + 1)];
            out[i] = 0.2f * a + 0.6f * b + 0.2f * c;
            max = Math.max(max, out[i]);
        }
        for (int i = 0; i < n; i++) out[i] = Math.max(0.12f, Math.min(1f, out[i] / (max <= 0 ? 1 : max)));
        return out;
    }

    /** The cached "unplayed" waveform: soft white pill bars resting on a thin baseline. */
    static Bitmap renderWaveformBase(float[] heights, int w, int h, float unit) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        WaveGeometry g = new WaveGeometry(heights.length, w, h, unit);
        p.setColor(0x3DFFFFFF);
        for (int i = 0; i < heights.length; i++) c.drawRoundRect(g.bar(i, heights[i]), g.barW / 2f, g.barW / 2f, p);
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(g.baseline(heights.length), g.baseH / 2f, g.baseH / 2f, p);
        return out;
    }

    /**
     * Copy of the base with bars [0, lit) lit by an accent gradient that brightens toward the playhead, a soft
     * bloom behind the playhead (when glow is on) and a bright dot riding the baseline.
     */
    static Bitmap overlayPlayed(Bitmap base, float[] heights, float unit, int lit, int accent, boolean glow) {
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        if (lit <= 0) return out;
        Canvas c = new Canvas(out);
        WaveGeometry g = new WaveGeometry(heights.length, base.getWidth(), base.getHeight(), unit);
        int n = Math.min(lit, heights.length);
        float end = g.x0 + n * g.pitch - (g.pitch - g.barW);

        // Erase the unplayed bars we're about to repaint, so translucent white doesn't muddy the accent.
        Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRect(0, 0, end + 1, base.getHeight(), clear);

        if (glow) {
            float gy = g.barsBottom - g.maxBar * 0.35f;
            radial(c, end, gy, 11 * unit, withAlpha(accent, 0x66));
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(g.x0, 0, Math.max(g.x0 + 1, end), 0,
                new int[]{withAlpha(accent, 0xB3), accent, lighten(accent, 0.55f)}, new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        for (int i = 0; i < n; i++) c.drawRoundRect(g.bar(i, heights[i]), g.barW / 2f, g.barW / 2f, p);
        c.drawRoundRect(g.baseline(n), g.baseH / 2f, g.baseH / 2f, p);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(end - g.barW / 2f, g.baseTop + g.baseH / 2f, 2.2f * unit, dot);
        return out;
    }

    private static final class WaveGeometry {
        final float barW, pitch, x0, baseH, baseTop, barsBottom, maxBar, h;

        WaveGeometry(int n, int w, int h, float unit) {
            this.h = h;
            barW = 2f * unit;
            pitch = 4f * unit;
            x0 = (w - (n * pitch - (pitch - barW))) / 2f; // center the bar run
            baseH = 1.5f * unit;
            baseTop = h - baseH - 1.2f * unit; // room for the playhead dot
            barsBottom = baseTop - 1.5f * unit;
            maxBar = barsBottom - unit;
        }

        RectF bar(int i, float height) {
            float bh = Math.max(barW, height * maxBar);
            float x = x0 + i * pitch;
            return new RectF(x, barsBottom - bh, x + barW, barsBottom);
        }

        /** Baseline from the first bar to the end of bar {@code count - 1}. */
        RectF baseline(int count) {
            return new RectF(x0, baseTop, x0 + count * pitch - (pitch - barW), baseTop + baseH);
        }
    }

    /** Bars to light for this progress: a bar lights as soon as playback reaches it. */
    static int litBars(float progress, int n) {
        if (progress <= 0f) return 0;
        return Math.min(n, (int) Math.ceil(progress * n));
    }

    // ================================================================ line progress (alternative to waveform)

    /** Steps the line can show: one per spec-dp of width. */
    static int lineSteps(int widthPx, float unit) {
        return Math.max(1, (int) (widthPx / unit));
    }

    static Bitmap renderLineBase(int w, int h, float unit) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33FFFFFF);
        float th = 3f * unit, top = (h - th) / 2f, knobR = 6f * unit;
        c.drawRoundRect(new RectF(knobR, top, w - knobR, top + th), th / 2f, th / 2f, p);
        return out;
    }

    /** Accent-gradient fill up to the playhead and a white knob with an accent halo. */
    static Bitmap overlayLine(Bitmap base, float unit, int lit, int steps, int accent, boolean glow) {
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        int w = base.getWidth(), h = base.getHeight();
        float th = 3f * unit, top = (h - th) / 2f, knobR = 6f * unit;
        float end = knobR + (w - 2 * knobR) * Math.min(1f, Math.max(0, lit) / (float) steps);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (end > knobR) {
            p.setShader(new LinearGradient(knobR, 0, end, 0, withAlpha(accent, 0xB3), lighten(accent, 0.4f), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(knobR, top, end, top + th), th / 2f, th / 2f, p);
        }
        if (glow) radial(c, end, h / 2f, knobR * 2.4f, withAlpha(accent, 0x80));
        Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        knob.setColor(0xFFFFFFFF);
        c.drawCircle(end, h / 2f, knobR * 0.75f, knob);
        return out;
    }

    // ================================================================ progress style dispatch

    /** Discrete steps of the progress bar in this style (bars, dot columns, segments, or ~1dp for the line). */
    static int progressSteps(int style, int w, float unit) {
        switch (style) {
            case WidgetPrefs.SEEK_LINE: return lineSteps(w, unit);
            case WidgetPrefs.SEEK_SEGMENTS: return segmentCount(w, unit);
            default: return barCount(w, unit);
        }
    }

    static Bitmap renderProgressBase(int style, float[] heights, int w, int h, float unit) {
        switch (style) {
            case WidgetPrefs.SEEK_LINE: return renderLineBase(w, h, unit);
            case WidgetPrefs.SEEK_MIRROR: return renderMirror(heights, w, h, unit, 0, 0, false, null);
            case WidgetPrefs.SEEK_DOTS: return renderDots(heights, w, h, unit, 0, 0, false, null);
            case WidgetPrefs.SEEK_SEGMENTS: return renderSegments(w, h, unit, 0, 0, false, null);
            default: return renderWaveformBase(heights, w, h, unit);
        }
    }

    static Bitmap overlayProgress(int style, Bitmap base, float[] heights, float unit, int lit, int steps, int accent, boolean glow) {
        switch (style) {
            case WidgetPrefs.SEEK_LINE: return overlayLine(base, unit, lit, steps, accent, glow);
            case WidgetPrefs.SEEK_MIRROR: return renderMirror(heights, base.getWidth(), base.getHeight(), unit, lit, accent, glow, base);
            case WidgetPrefs.SEEK_DOTS: return renderDots(heights, base.getWidth(), base.getHeight(), unit, lit, accent, glow, base);
            case WidgetPrefs.SEEK_SEGMENTS: return renderSegments(base.getWidth(), base.getHeight(), unit, lit, accent, glow, base);
            default: return overlayPlayed(base, heights, unit, lit, accent, glow);
        }
    }

    private static Paint playedPaint(float x0, float end, int accent) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(x0, 0, Math.max(x0 + 1, end), 0,
                new int[]{withAlpha(accent, 0xB3), accent, lighten(accent, 0.55f)}, new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        return p;
    }

    private static Bitmap blankOrCopy(Bitmap base, int w, int h) {
        return base != null ? base.copy(Bitmap.Config.ARGB_8888, true)
                : Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
    }

    private static void clearTo(Canvas c, float end, int h) {
        Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRect(0, 0, end + 1, h, clear);
    }

    /** Waveform mirrored around a center line (like a DAW clip). Draws the unplayed base when {@code base} is null. */
    private static Bitmap renderMirror(float[] heights, int w, int h, float unit, int lit, int accent, boolean glow, Bitmap base) {
        Bitmap out = blankOrCopy(base, w, h);
        Canvas c = new Canvas(out);
        int n = heights.length;
        float barW = 2f * unit, pitch = 4f * unit, x0 = (w - (n * pitch - (pitch - barW))) / 2f, cy = h / 2f, maxHalf = h / 2f - unit;
        int count;
        Paint p;
        if (base == null) {
            count = n;
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x3DFFFFFF);
        } else {
            count = Math.min(lit, n);
            if (count <= 0) return out;
            float end = x0 + count * pitch - (pitch - barW);
            clearTo(c, end, h);
            if (glow) radial(c, end, cy, 12 * unit, withAlpha(accent, 0x66));
            p = playedPaint(x0, end, accent);
        }
        for (int i = 0; i < count; i++) {
            float hh = Math.max(barW / 2f, heights[i] * maxHalf);
            float x = x0 + i * pitch;
            c.drawRoundRect(new RectF(x, cy - hh, x + barW, cy + hh), barW / 2f, barW / 2f, p);
        }
        return out;
    }

    /** Dot-matrix columns: each bar drawn as a stack of dots. */
    private static Bitmap renderDots(float[] heights, int w, int h, float unit, int lit, int accent, boolean glow, Bitmap base) {
        Bitmap out = blankOrCopy(base, w, h);
        Canvas c = new Canvas(out);
        int n = heights.length;
        float pitch = 4f * unit, dotR = 1.05f * unit, rowStep = 3.4f * unit;
        float x0 = (w - (n * pitch - (pitch - 2 * dotR))) / 2f + dotR;
        float bottom = h - dotR - unit;
        int rows = Math.max(1, (int) ((bottom - dotR) / rowStep) + 1);
        int count;
        Paint p;
        if (base == null) {
            count = n;
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x40FFFFFF);
        } else {
            count = Math.min(lit, n);
            if (count <= 0) return out;
            float end = x0 + (count - 1) * pitch + dotR;
            clearTo(c, end, h);
            if (glow) radial(c, end, bottom - rows * rowStep * 0.3f, 12 * unit, withAlpha(accent, 0x66));
            p = playedPaint(x0, end, accent);
        }
        for (int i = 0; i < count; i++) {
            int k = Math.max(1, Math.round(heights[i] * rows));
            float x = x0 + i * pitch;
            for (int j = 0; j < k; j++) c.drawCircle(x, bottom - j * rowStep, dotR, p);
        }
        return out;
    }

    static int segmentCount(int w, float unit) {
        float segW = 7f * unit, gap = 2.5f * unit;
        return Math.max(6, (int) ((w + gap) / (segW + gap)));
    }

    /** Level-meter segments along one row. */
    private static Bitmap renderSegments(int w, int h, float unit, int lit, int accent, boolean glow, Bitmap base) {
        Bitmap out = blankOrCopy(base, w, h);
        Canvas c = new Canvas(out);
        float segW = 7f * unit, gap = 2.5f * unit, segH = 5f * unit;
        int n = segmentCount(w, unit);
        float x0 = (w - (n * (segW + gap) - gap)) / 2f, top = (h - segH) / 2f;
        int count;
        Paint p;
        if (base == null) {
            count = n;
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x2EFFFFFF);
        } else {
            count = Math.min(lit, n);
            if (count <= 0) return out;
            float end = x0 + count * (segW + gap) - gap;
            clearTo(c, end, h);
            if (glow) radial(c, end - segW / 2, h / 2f, 12 * unit, withAlpha(accent, 0x70));
            p = playedPaint(x0, end, accent);
        }
        for (int i = 0; i < count; i++) {
            float x = x0 + i * (segW + gap);
            c.drawRoundRect(new RectF(x, top, x + segW, top + segH), segH / 2.5f, segH / 2.5f, p);
        }
        return out;
    }

    // ================================================================ audio spec pill

    static final class Spec {
        final String text;
        final boolean hiRes;

        Spec(String text, boolean hiRes) {
            this.text = text;
            this.hiRes = hiRes;
        }
    }

    /**
     * "FLAC • 44.1kHz", "MP3 • 320kbps", or for 24-bit / >48kHz lossless "HI-RES · FLAC 24/96".
     * Null when Poweramp didn't report a format.
     */
    static Spec audioSpec(PlayerState.Snapshot s) {
        if (!s.hasTrack) return null;
        String fmt = formatName(s);
        if (fmt == null) return null;
        boolean lossless = isLossless(fmt);
        int rate = s.sampleRate > 0 && s.sampleRate < 1000 ? s.sampleRate * 1000 : s.sampleRate;
        if (lossless && (rate > 48_000 || s.bitsPerSample >= 24)) {
            String detail = s.bitsPerSample > 0 ? s.bitsPerSample + "/" + khzNumber(rate) : khzNumber(rate) + "kHz";
            return new Spec("HI-RES · " + fmt + " " + detail, true);
        }
        String detail = null;
        if (lossless && rate > 0) detail = khzNumber(rate) + "kHz";
        else if (s.bitRate > 0) detail = kbps(s.bitRate);
        else if (rate > 0) detail = khzNumber(rate) + "kHz";
        return new Spec(detail != null ? fmt + " • " + detail : fmt, false);
    }

    private static String formatName(PlayerState.Snapshot s) {
        String codec = s.codec.trim().toLowerCase(Locale.ROOT);
        if (!codec.isEmpty()) {
            if (codec.contains("flac")) return "FLAC";
            if (codec.contains("alac")) return "ALAC";
            if (codec.contains("opus")) return "OPUS";
            if (codec.contains("vorbis")) return "OGG";
            if (codec.contains("aac")) return "AAC";
            if (codec.contains("mp3") || codec.contains("layer 3") || codec.contains("layer3")) return "MP3";
            if (codec.contains("dsd")) return "DSD";
            if (codec.contains("ape") || codec.contains("monkey")) return "APE";
            if (codec.contains("wavpack")) return "WV";
        }
        switch (s.fileType) {
            case 0: case 11: return "MP3";
            case 1: return "FLAC";
            case 2: case 3: case 10: return "AAC";
            case 4: case 30: return "OGG";
            case 5: return "WMA";
            case 6: return "WAV";
            case 7: return "TTA";
            case 8: return "APE";
            case 9: return "WV";
            case 14: return "MPC";
            case 15: case 16: return "AIFF";
            case 18: return "OPUS";
            case 19: case 20: return "DSD";
            case 21: return "MKA";
            case 22: return "TAK";
            default: break;
        }
        int dot = s.path.lastIndexOf('.');
        if (dot >= 0 && dot < s.path.length() - 1) {
            String ext = s.path.substring(dot + 1).toUpperCase(Locale.ROOT);
            if (ext.length() <= 5 && ext.matches("[A-Z0-9]+")) return ext;
        }
        return null;
    }

    private static boolean isLossless(String fmt) {
        switch (fmt) {
            case "FLAC": case "ALAC": case "WAV": case "APE": case "WV": case "TTA": case "TAK": case "AIFF": case "DSD":
                return true;
            default:
                return false;
        }
    }

    private static String khzNumber(int hz) {
        float k = hz / 1000f;
        return k == Math.floor(k) ? String.valueOf((int) k) : String.format(Locale.ROOT, "%.1f", k);
    }

    private static String kbps(int bitRate) {
        int k = bitRate >= 10_000 ? Math.round(bitRate / 1000f) : bitRate; // bps or kbps
        return k + "kbps";
    }

    // ================================================================ color

    /** Album accent: the vibrant swatch, lifted toward white until it reads clearly on dark glass. */
    static int albumAccent(Ambient a) {
        int c = a.vibrant | 0xFF000000;
        for (int i = 0; i < 6 && luminance(c) < 0.42f; i++) c = blend(c, 0xFFFFFFFF, 0.2f);
        return c;
    }

    /** Glyph color that stays legible on an accent-colored disc. */
    static int glyphOn(int accent) {
        return luminance(accent) > 0.42f ? 0xFF121417 : 0xFFFFFFFF;
    }

    static int lighten(int color, float f) {
        return blend(color | 0xFF000000, 0xFFFFFFFF, f);
    }

    /** Scales RGB by {@code f} (0..1). */
    static int darken(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** RGB mix of {@code color} into {@code base} at {@code amount}; keeps base alpha. */
    static int blend(int base, int color, float amount) {
        float inv = 1f - amount;
        return Color.argb(Color.alpha(base),
                Math.round(Color.red(base) * inv + Color.red(color) * amount),
                Math.round(Color.green(base) * inv + Color.green(color) * amount),
                Math.round(Color.blue(base) * inv + Color.blue(color) * amount));
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
