package com.sparkle.pawidget;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** "Your listening" card for the settings screen: today / week / streak tiles, a 7-day chart, top artists and tracks. */
final class StatsPanel {
    private final Context ctx;
    private final LinearLayout root;
    private final int accent;

    StatsPanel(Context ctx, int accent) {
        this.ctx = ctx;
        this.accent = accent;
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        refresh();
    }

    View view() {
        return root;
    }

    void refresh() {
        root.removeAllViews();
        long[] week = ListeningStats.lastDays(ctx, 7);
        long weekTotal = 0, max = 1;
        for (long v : week) {
            weekTotal += v;
            max = Math.max(max, v);
        }
        if (weekTotal < 60_000 && ListeningStats.topArtists(ctx, 1).isEmpty()) {
            root.addView(text(ctx.getString(R.string.stats_empty), 14, 0x99D6E0F0, false));
            return;
        }

        // Tiles
        LinearLayout tiles = new LinearLayout(ctx);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(tile(ctx.getString(R.string.stats_today), duration(week[6])), weight());
        tiles.addView(tile(ctx.getString(R.string.stats_week), duration(weekTotal)), weight());
        int streak = ListeningStats.streakDays(ctx);
        tiles.addView(tile(ctx.getString(R.string.stats_streak), streak + (streak == 1 ? " day" : " days")), weight());
        root.addView(tiles);

        // 7-day bars
        LinearLayout chart = new LinearLayout(ctx);
        chart.setOrientation(LinearLayout.HORIZONTAL);
        chart.setGravity(Gravity.BOTTOM);
        chart.setPadding(0, dp(14), 0, 0);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -6);
        SimpleDateFormat dayName = new SimpleDateFormat("EEEEE", Locale.getDefault());
        for (int i = 0; i < 7; i++) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            FrameLayout barBox = new FrameLayout(ctx);
            View bar = new View(ctx);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{i == 6 ? accent : WidgetGraphicsHelper.withAlpha(accent, 0xB3), WidgetGraphicsHelper.withAlpha(accent, 0x40)});
            g.setCornerRadius(dp(6));
            bar.setBackground(g);
            int h = Math.max(dp(4), Math.round(dp(72) * week[i] / (float) max));
            barBox.addView(bar, new FrameLayout.LayoutParams(dp(18), h, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            col.addView(barBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)));
            TextView label = text(dayName.format(c.getTime()), 11, i == 6 ? 0xFFFFFFFF : 0x80D6E0F0, i == 6);
            label.setGravity(Gravity.CENTER);
            col.addView(label);
            chart.addView(col, weight());
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        root.addView(chart);

        // Top lists
        List<ListeningStats.Entry> artists = ListeningStats.topArtists(ctx, 5);
        if (!artists.isEmpty()) {
            root.addView(header(ctx.getString(R.string.stats_top_artists)));
            long top = Math.max(1, artists.get(0).value);
            for (int i = 0; i < artists.size(); i++) {
                ListeningStats.Entry e = artists.get(i);
                root.addView(row(i + 1, e.name, duration(e.value), e.value / (float) top));
            }
        }
        List<ListeningStats.Entry> tracks = ListeningStats.topTracks(ctx, 5);
        if (!tracks.isEmpty()) {
            root.addView(header(ctx.getString(R.string.stats_top_tracks)));
            long top = Math.max(1, tracks.get(0).value);
            for (int i = 0; i < tracks.size(); i++) {
                ListeningStats.Entry e = tracks.get(i);
                String[] parts = e.name.split("\u0001", 2);
                String label = parts[0] + (parts.length > 1 && !parts[1].isEmpty() ? "  ·  " + parts[1] : "");
                root.addView(row(i + 1, label, e.value + (e.value == 1 ? " play" : " plays"), e.value / (float) top));
            }
        }
    }

    private View tile(String label, String value) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(14));
        t.setBackground(bg);
        t.addView(text(label.toUpperCase(Locale.getDefault()), 10, 0x99D6E0F0, true));
        TextView v = text(value, 18, 0xFFFFFFFF, true);
        v.setPadding(0, dp(4), 0, 0);
        t.addView(v);
        return t;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private View header(String s) {
        TextView t = text(s.toUpperCase(Locale.getDefault()), 11, 0x99D6E0F0, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    /** "1  Name ............ 2h 10m" with a faint accent bar showing the share. */
    private View row(int rank, String name, String value, float share) {
        FrameLayout box = new FrameLayout(ctx);
        View bar = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setColor(WidgetGraphicsHelper.withAlpha(accent, 0x26));
        g.setCornerRadius(dp(8));
        bar.setBackground(g);
        box.addView(bar, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * Math.max(0.06f, share));
            if (bar.getLayoutParams().width != w) {
                bar.getLayoutParams().width = w;
                bar.post(bar::requestLayout);
            }
        });
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(8), dp(7), dp(8), dp(7));
        TextView r = text(String.valueOf(rank), 12, 0x80D6E0F0, true);
        r.setMinWidth(dp(18));
        line.addView(r);
        TextView n = text(name, 14, 0xFFE6ECF7, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(text(value, 12, 0x99D6E0F0, false));
        box.addView(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        box.setLayoutParams(lp);
        return box;
    }

    private static String duration(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + "m";
        return (min / 60) + "h " + (min % 60) + "m";
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
