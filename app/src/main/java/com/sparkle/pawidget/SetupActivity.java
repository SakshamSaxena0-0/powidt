package com.sparkle.pawidget;

import android.app.Activity;
import android.app.NotificationManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The app: a live widget preview pinned on top; listening stats, themes, customization and setup below.
 * Launched from a widget's "Settings" (APPWIDGET_CONFIGURE) it styles that widget, optionally with its own style.
 * Every change is saved immediately, shown in the preview, and pushed to the home-screen widgets.
 */
public class SetupActivity extends Activity {
    private static final int PREVIEW_INSET = 10; // dp
    private static final int PREVIEW_CARD_HEIGHT = 286; // dp, when no widget is placed yet
    private static final int APP_ACCENT = 0xFF8FB8FF;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** The style being edited: the global one, or the configured widget's own style. */
    private WidgetPrefs mPrefs;
    /** Widget being configured (0 = launched from the app icon). */
    private int mWidgetId;

    private FrameLayout mPreviewHost;
    private View mPreviewView;
    private boolean mPreviewBuilding, mPreviewDirty, mPreviewStale = true;

    private StatsPanel mStats;
    private TextView mPowerampStatus, mLiveStatus, mLibraryStatus;
    private Button mLiveButton, mLibraryButton;

    private final List<Runnable> mBinders = new ArrayList<>();
    private View mOpacityRow, mTintRow, mQuickCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(in.getAction())) {
            mWidgetId = in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
            // Accept right away: the widget is added even if the user just backs out.
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId));
        }
        mPrefs = mWidgetId != 0 && WidgetPrefs.hasOwnStyle(this, mWidgetId)
                ? WidgetPrefs.forWidget(this, mWidgetId) : WidgetPrefs.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(28), pad, 0);

        TextView title = text(getString(mWidgetId != 0 ? R.string.editing_widget : R.string.app_name), 24, 0xFFFFFFFF, true);
        title.setPadding(dp(4), 0, 0, dp(10));
        root.addView(title);
        if (mWidgetId != 0) root.addView(ownStyleRow());

        mPreviewHost = new FrameLayout(this);
        GradientDrawable wall = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF35558A, 0xFF101828, 0xFF0A0C14, 0xFF5A1E2A});
        wall.setCornerRadius(dp(30));
        mPreviewHost.setBackground(wall);
        mPreviewHost.setClipToOutline(true);
        root.addView(mPreviewHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(PREVIEW_CARD_HEIGHT + 2 * PREVIEW_INSET)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(32));
        if (mWidgetId == 0) buildStats(col);
        buildCustomize(col);
        buildSetup(col);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(16));
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        for (Runnable r : mBinders) r.run();
    }

    /** Configure mode: switch between following the main style and this widget's own. */
    private View ownStyleRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, dp(4), dp(10));
        Switch sw = new Switch(this);
        sw.setText(R.string.own_style);
        sw.setTextColor(0xFFDDE6F5);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setChecked(WidgetPrefs.hasOwnStyle(this, mWidgetId));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on) mPrefs = WidgetPrefs.createOwnStyle(this, mWidgetId);
            else {
                WidgetPrefs.removeOwnStyle(this, mWidgetId);
                mPrefs = WidgetPrefs.get(this);
            }
            for (Runnable r : mBinders) r.run();
            onPrefsChanged();
        });
        box.addView(sw);
        box.addView(hint(getString(R.string.own_style_hint)));
        return box;
    }

    // ------------------------------------------------------------------ stats

    private void buildStats(LinearLayout col) {
        col.addView(sectionHeader(getString(R.string.section_stats)));
        LinearLayout card = card(col, null);
        mStats = new StatsPanel(this, APP_ACCENT);
        card.addView(mStats.view());
    }

    // ------------------------------------------------------------------ customize

    private void buildCustomize(LinearLayout col) {
        col.addView(sectionHeader(getString(R.string.section_customize)));

        LinearLayout themes = card(col, getString(R.string.opt_presets));
        themes.addView(presetChips());
        themes.addView(hint(getString(R.string.opt_presets_hint)));

        LinearLayout layout = card(col, getString(R.string.opt_layout));
        layout.addView(segmented(new String[]{getString(R.string.layout_auto), getString(R.string.layout_classic),
                        getString(R.string.layout_compact), getString(R.string.layout_hero), getString(R.string.layout_studio)},
                new int[]{WidgetPrefs.LAYOUT_AUTO, WidgetPrefs.LAYOUT_CLASSIC, WidgetPrefs.LAYOUT_COMPACT,
                        WidgetPrefs.LAYOUT_HERO, WidgetPrefs.LAYOUT_STUDIO},
                () -> mPrefs.layout, v -> mPrefs.layout = v));
        layout.addView(hint(getString(R.string.opt_layout_hint)));

        LinearLayout size = card(col, getString(R.string.opt_size));
        size.addView(slider(null, WidgetPrefs.SIZE_MIN, WidgetPrefs.SIZE_MAX, "%",
                () -> mPrefs.sizePercent, v -> mPrefs.sizePercent = v));
        size.addView(hint(getString(R.string.opt_size_hint)));

        LinearLayout cardOpts = card(col, getString(R.string.opt_card));
        cardOpts.addView(segmented(
                new String[]{getString(R.string.bg_immersive), getString(R.string.bg_adaptive), getString(R.string.bg_dark), getString(R.string.bg_clear)},
                new int[]{WidgetPrefs.BG_IMMERSIVE, WidgetPrefs.BG_ADAPTIVE, WidgetPrefs.BG_DARK, WidgetPrefs.BG_CLEAR},
                () -> mPrefs.background, v -> mPrefs.background = v));
        cardOpts.addView(hint(getString(R.string.opt_card_hint)));
        mOpacityRow = slider(getString(R.string.opt_opacity), 0, 100, "%", () -> mPrefs.opacity, v -> mPrefs.opacity = v);
        mTintRow = slider(getString(R.string.opt_tint), 0, 60, "%", () -> mPrefs.tintStrength, v -> mPrefs.tintStrength = v);
        cardOpts.addView(mOpacityRow);
        cardOpts.addView(mTintRow);
        cardOpts.addView(slider(getString(R.string.opt_corners), 0, 40, "dp", () -> mPrefs.cornerDp, v -> mPrefs.cornerDp = v));
        mBinders.add(this::updateDependents);

        LinearLayout art = card(col, getString(R.string.opt_art));
        art.addView(segmented(new String[]{getString(R.string.art_rounded), getString(R.string.art_circle),
                        getString(R.string.art_square), getString(R.string.art_vinyl)},
                () -> mPrefs.artShape, v -> mPrefs.artShape = v));

        LinearLayout font = card(col, getString(R.string.opt_font));
        font.addView(segmented(new String[]{getString(R.string.font_classic), getString(R.string.font_condensed),
                        getString(R.string.font_editorial), getString(R.string.font_mono)},
                () -> mPrefs.font, v -> mPrefs.font = v));

        LinearLayout accent = card(col, getString(R.string.opt_accent));
        accent.addView(accentChips());
        accent.addView(hint(getString(R.string.opt_accent_hint)));

        LinearLayout progress = card(col, getString(R.string.opt_progress));
        progress.addView(segmented(new String[]{getString(R.string.seek_wave), getString(R.string.seek_mirror),
                        getString(R.string.seek_dots), getString(R.string.seek_segments), getString(R.string.seek_line)},
                new int[]{WidgetPrefs.SEEK_WAVEFORM, WidgetPrefs.SEEK_MIRROR, WidgetPrefs.SEEK_DOTS,
                        WidgetPrefs.SEEK_SEGMENTS, WidgetPrefs.SEEK_LINE},
                () -> mPrefs.seekStyle, v -> mPrefs.seekStyle = v));

        LinearLayout side = card(col, getString(R.string.opt_side));
        side.addView(segmented(new String[]{getString(R.string.side_shuffle), getString(R.string.side_skip), getString(R.string.side_none)},
                () -> mPrefs.sideButtons, v -> mPrefs.sideButtons = v));

        LinearLayout show = card(col, getString(R.string.opt_show));
        show.addView(toggle(getString(R.string.show_status), () -> mPrefs.showStatus, v -> mPrefs.showStatus = v));
        show.addView(toggle(getString(R.string.show_artist), () -> mPrefs.showArtist, v -> mPrefs.showArtist = v));
        show.addView(toggle(getString(R.string.show_upnext), () -> mPrefs.showUpNext, v -> mPrefs.showUpNext = v));
        show.addView(toggle(getString(R.string.show_format), () -> mPrefs.showFormat, v -> mPrefs.showFormat = v));
        show.addView(toggle(getString(R.string.show_like), () -> mPrefs.showFavorite, v -> mPrefs.showFavorite = v));
        show.addView(toggle(getString(R.string.show_device), () -> mPrefs.showDevice, v -> mPrefs.showDevice = v));
        show.addView(toggle(getString(R.string.show_times), () -> mPrefs.showTimes, v -> mPrefs.showTimes = v));
        show.addView(toggle(getString(R.string.show_quick), () -> mPrefs.showQuickActions, v -> mPrefs.showQuickActions = v));

        LinearLayout quick = card(col, getString(R.string.opt_quick));
        mQuickCard = quick;
        quick.addView(toggle(getString(R.string.qa_eq), () -> mPrefs.qaEq, v -> mPrefs.qaEq = v));
        quick.addView(toggle(getString(R.string.qa_sleep), () -> mPrefs.qaSleep, v -> mPrefs.qaSleep = v));
        quick.addView(toggle(getString(R.string.qa_library), () -> mPrefs.qaLibrary, v -> mPrefs.qaLibrary = v));
        quick.addView(toggle(getString(R.string.qa_volume), () -> mPrefs.qaVolume, v -> mPrefs.qaVolume = v));

        LinearLayout motion = card(col, getString(R.string.opt_motion));
        motion.addView(toggle(getString(R.string.fx_breathing), () -> mPrefs.fxBreathing, v -> mPrefs.fxBreathing = v));
        motion.addView(toggle(getString(R.string.fx_crossfade), () -> mPrefs.fxCrossfade, v -> mPrefs.fxCrossfade = v));
        motion.addView(toggle(getString(R.string.fx_bars), () -> mPrefs.fxBars, v -> mPrefs.fxBars = v));

        LinearLayout fx = card(col, getString(R.string.opt_effects));
        fx.addView(toggle(getString(R.string.fx_edge), () -> mPrefs.fxEdge, v -> mPrefs.fxEdge = v));
        fx.addView(toggle(getString(R.string.fx_glow), () -> mPrefs.fxGlow, v -> mPrefs.fxGlow = v));
        fx.addView(toggle(getString(R.string.fx_grain), () -> mPrefs.fxGrain, v -> mPrefs.fxGrain = v));

        Button reset = button(getString(R.string.btn_reset), false);
        reset.setOnClickListener(v -> {
            mPrefs.resetToDefaults();
            for (Runnable r : mBinders) r.run();
            onPrefsChanged();
        });
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) reset.getLayoutParams();
        rlp.bottomMargin = dp(8);
        col.addView(reset, rlp);
    }

    private void updateDependents() {
        setEnabledDeep(mOpacityRow, mPrefs.background != WidgetPrefs.BG_CLEAR);
        setEnabledDeep(mTintRow, mPrefs.background == WidgetPrefs.BG_ADAPTIVE || mPrefs.background == WidgetPrefs.BG_IMMERSIVE);
        if (mQuickCard != null) setEnabledDeep(mQuickCard, mPrefs.showQuickActions);
    }

    private boolean mPushPending;
    private final Runnable mPushToWidgets = () -> {
        mPushPending = false;
        Events.EXEC.execute(() -> WidgetRenderer.updateAll(getApplicationContext()));
    };

    /** Save, refresh the preview now, and update home-screen widgets shortly (debounced for sliders). */
    private void onPrefsChanged() {
        mPrefs.save(this);
        updateDependents();
        mPreviewStale = true;
        requestPreview();
        mMain.removeCallbacks(mPushToWidgets);
        mPushPending = true;
        mMain.postDelayed(mPushToWidgets, 300);
    }

    // ------------------------------------------------------------------ setup

    private void buildSetup(LinearLayout col) {
        col.addView(sectionHeader(getString(R.string.section_setup)));

        LinearLayout c1 = card(col, getString(R.string.step_poweramp));
        mPowerampStatus = text("", 15, 0xFFDDE6F5, false);
        c1.addView(mPowerampStatus);
        Button openPa = button(getString(R.string.btn_open_poweramp), true);
        openPa.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(PA.packageName(this));
            if (i != null) startActivity(i);
            else Toast.makeText(this, R.string.poweramp_missing, Toast.LENGTH_SHORT).show();
        });
        c1.addView(openPa);

        LinearLayout lib = card(col, getString(R.string.step_library));
        mLibraryStatus = text("", 15, 0xFFDDE6F5, false);
        lib.addView(mLibraryStatus);
        mLibraryButton = button(getString(R.string.btn_library_access), true);
        mLibraryButton.setOnClickListener(v -> {
            LibraryData.invalidate();
            PA.askForDataPermission(this);
        });
        lib.addView(mLibraryButton);

        if (mWidgetId == 0) {
            LinearLayout c2 = card(col, getString(R.string.step_widget));
            c2.addView(hint(getString(R.string.widget_hint)));
            Button add = button(getString(R.string.btn_add_widget), true);
            add.setOnClickListener(v -> requestPin());
            c2.addView(add);
        }

        LinearLayout c3 = card(col, getString(R.string.step_live));
        mLiveStatus = text("", 15, 0xFFDDE6F5, false);
        c3.addView(mLiveStatus);
        c3.addView(hint(getString(R.string.live_hint)));
        mLiveButton = button(getString(R.string.btn_live), true);
        mLiveButton.setOnClickListener(v -> openListenerSettings());
        c3.addView(mLiveButton);

        TextView tip = text(getString(R.string.tip_resume), 13, 0x99D6E0F0, false);
        tip.setPadding(dp(4), dp(4), dp(4), 0);
        col.addView(tip);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PA.isInstalled(this)) {
            String build = "";
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(PA.packageName(this), 0);
                build = " (build " + pi.versionName + ")";
            } catch (Throwable ignored) {
            }
            mPowerampStatus.setText(getString(R.string.poweramp_found) + build);
        } else {
            mPowerampStatus.setText(R.string.poweramp_missing);
        }
        boolean live = isListenerGranted();
        mLiveStatus.setText(live ? R.string.live_on : R.string.live_off);
        mLiveButton.setText(live ? R.string.btn_live_manage : R.string.btn_live);

        final Context app = getApplicationContext();
        Events.EXEC.execute(() -> {
            Events.refreshFromSticky(app);
            ListeningStats.record(app, PlayerState.get(app).snapshot()); // fold in time played so far
            LibraryData.invalidate();
            boolean access = LibraryData.hasAccess(app);
            WidgetRenderer.updateAll(app);
            Ticker.evaluate(app);
            runOnUiThread(() -> {
                mLibraryStatus.setText(access ? R.string.library_on : R.string.library_off);
                mLibraryButton.setVisibility(access ? View.GONE : View.VISIBLE);
                if (mStats != null) mStats.refresh();
                mPreviewStale = true;
            });
        });
        mMain.post(mPreviewTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMain.removeCallbacks(mPreviewTick);
        if (mPushPending) { // flush a pending slider change
            mMain.removeCallbacks(mPushToWidgets);
            mPushToWidgets.run();
        }
    }

    // ------------------------------------------------------------------ preview

    /** Rebuilds the preview once a second so times/progress move like on the home screen. */
    private final Runnable mPreviewTick = new Runnable() {
        @Override
        public void run() {
            requestPreview();
            mMain.postDelayed(this, 1000);
        }
    };

    /** Builds the preview off the main thread; coalesces bursts (e.g. dragging a slider). */
    private void requestPreview() {
        if (mPreviewBuilding) {
            mPreviewDirty = true;
            return;
        }
        mPreviewBuilding = true;
        final Context app = getApplicationContext();
        float density = getResources().getDisplayMetrics().density;
        int hostW = mPreviewHost.getWidth() > 0 ? mPreviewHost.getWidth()
                : getResources().getDisplayMetrics().widthPixels - dp(32);
        int availW = Math.round((hostW - dp(2 * PREVIEW_INSET)) / density);

        // Mirror the widget on the home screen (its exact size and shape) when one is placed.
        int[] placed = widgetSizeDp();
        final int wDp = placed != null ? Math.min(placed[0], availW) : availW;
        final int hDp = placed != null ? Math.min(placed[1], 520) : PREVIEW_CARD_HEIGHT;
        int side = dp(PREVIEW_INSET) + Math.max(0, Math.round((availW - wDp) * density / 2f));
        mPreviewHost.setPadding(side, dp(PREVIEW_INSET), side, dp(PREVIEW_INSET));
        ViewGroup.LayoutParams lp = mPreviewHost.getLayoutParams();
        int wantH = dp(hDp + 2 * PREVIEW_INSET);
        if (lp.height != wantH) {
            lp.height = wantH;
            mPreviewHost.setLayoutParams(lp);
            mPreviewStale = true;
        }
        final WidgetPrefs prefs = mPrefs.copy();
        Events.EXEC.execute(() -> {
            RemoteViews rv = WidgetRenderer.buildPreview(app, prefs, wDp, hDp);
            runOnUiThread(() -> {
                showPreview(rv);
                mPreviewBuilding = false;
                if (mPreviewDirty) {
                    mPreviewDirty = false;
                    requestPreview();
                }
            });
        });
    }

    private void showPreview(RemoteViews rv) {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (mPreviewView == null || mPreviewStale || mPreviewView.getTag() == null
                    || (int) mPreviewView.getTag() != rv.getLayoutId()) {
                View fresh = rv.apply(this, mPreviewHost);
                fresh.setTag(rv.getLayoutId());
                if (mPreviewView != null) mPreviewHost.removeView(mPreviewView);
                mPreviewView = fresh;
                mPreviewHost.addView(fresh);
                mPreviewStale = false;
            } else {
                rv.reapply(this, mPreviewView); // once-a-second refresh: update in place
            }
        } catch (Throwable th) {
            android.util.Log.e("SparkleSetup", "preview failed", th);
        }
    }

    /** Portrait size (dp) of the configured widget, else the first placed one; null when none. */
    private int[] widgetSizeDp() {
        // Debug builds: `am start ... --ei previewW 320 --ei previewH 343` previews an arbitrary widget size.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            int w = getIntent().getIntExtra("previewW", 0), h = getIntent().getIntExtra("previewH", 0);
            if (w > 0 && h > 0) return new int[]{w, h};
        }
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            int id = mWidgetId;
            if (id == 0) {
                int[] ids = mgr.getAppWidgetIds(new ComponentName(this, SparkleWidgetProvider.class));
                if (ids.length == 0) return null;
                id = ids[0];
            }
            Bundle o = mgr.getAppWidgetOptions(id);
            int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (Throwable th) {
            return null;
        }
    }

    // ------------------------------------------------------------------ system actions

    private void requestPin() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        if (mgr.isRequestPinAppWidgetSupported()) {
            mgr.requestPinAppWidget(new ComponentName(this, SparkleWidgetProvider.class), null, null);
        } else {
            Toast.makeText(this, R.string.widget_hint, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isListenerGranted() {
        ComponentName cn = new ComponentName(this, LiveProgressService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(cn);
        }
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private void openListenerSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, LiveProgressService.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (Throwable th) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    // ------------------------------------------------------------------ controls

    interface IntGetter { int get(); }
    interface IntSetter { void set(int v); }
    interface BoolGetter { boolean get(); }
    interface BoolSetter { void set(boolean v); }

    /** Horizontally scrolling theme chips. */
    private View presetChips() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(6));
        int[][] swatches = {{0xFF6A3FA0, 0xFFE0709E}, {0xFF0E1420, 0xFF8EC5FF}, {0xFF1A1A1E, 0xFFFFC46B},
                {0xFF2A2F3A, 0xFFFFFFFF}, {0xFF14110A, 0xFFE2C27A}, {0xFF3A1240, 0xFFFF8FB1}};
        for (int i = 0; i < WidgetPrefs.PRESET_NAMES.length; i++) {
            final int preset = i;
            TextView chip = text(WidgetPrefs.PRESET_NAMES[i], 14, 0xFFFFFFFF, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, swatches[i % swatches.length]);
            bg.setCornerRadius(dp(20));
            bg.setStroke(dp(1), 0x33FFFFFF);
            chip.setBackground(bg);
            chip.setShadowLayer(6, 0, 1, 0x99000000);
            chip.setOnClickListener(v -> {
                mPrefs.applyPreset(preset);
                for (Runnable r : mBinders) r.run();
                onPrefsChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
        }
        hs.addView(row);
        return hs;
    }

    /** Label + value on one line, slider below. */
    private View slider(String label, int min, int max, String unit, IntGetter get, IntSetter set) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(label != null ? label : "", 15, 0xFFDDE6F5, false);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", 15, 0xFFFFFFFF, true);
        row.addView(value);
        box.addView(row);

        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setPadding(dp(4), dp(10), dp(4), dp(6));
        box.addView(bar);

        mBinders.add(() -> {
            bar.setProgress(get.get());
            value.setText(get.get() + unit);
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!fromUser) return;
                set.set(v);
                value.setText(v + unit);
                onPrefsChanged();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return box;
    }

    private View segmented(String[] options, IntGetter get, IntSetter set) {
        int[] values = new int[options.length];
        for (int i = 0; i < values.length; i++) values[i] = i;
        return segmented(options, values, get, set);
    }

    /** Pill-shaped segmented choice; {@code values[i]} is the pref value of option i. */
    private View segmented(String[] options, int[] values, IntGetter get, IntSetter set) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(dp(22));
        group.setBackground(bg);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        glp.topMargin = dp(6);
        glp.bottomMargin = dp(4);
        group.setLayoutParams(glp);

        int textSp = options.length >= 5 ? 12 : options.length == 4 ? 13 : 14;
        TextView[] items = new TextView[options.length];
        Runnable bind = () -> {
            for (int i = 0; i < items.length; i++) {
                boolean on = values[i] == get.get();
                GradientDrawable sel = new GradientDrawable();
                sel.setColor(on ? 0xF2FFFFFF : 0x00000000);
                sel.setCornerRadius(dp(19));
                items[i].setBackground(sel);
                items[i].setTextColor(on ? 0xFF0B1020 : 0xCCFFFFFF);
            }
        };
        for (int i = 0; i < options.length; i++) {
            final int value = values[i];
            TextView item = text(options[i], textSp, 0xCCFFFFFF, true);
            item.setGravity(Gravity.CENTER);
            item.setMaxLines(1);
            item.setOnClickListener(v -> {
                set.set(value);
                bind.run();
                onPrefsChanged();
            });
            group.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            items[i] = item;
        }
        mBinders.add(bind);
        return group;
    }

    private View toggle(String label, BoolGetter get, BoolSetter set) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(0xFFDDE6F5);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setMinHeight(dp(44));
        mBinders.add(() -> sw.setChecked(get.get()));
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (checked == get.get()) return; // programmatic re-bind
            set.set(checked);
            onPrefsChanged();
        });
        return sw;
    }

    private View accentChips() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));
        int[] colors = WidgetPrefs.ACCENT_PRESETS;
        View[] chips = new View[colors.length];
        Runnable bind = () -> {
            for (int i = 0; i < chips.length; i++) {
                GradientDrawable d = chipDrawable(colors[i]);
                boolean on = colors[i] == mPrefs.accent;
                d.setStroke(on ? dp(3) : dp(1), on ? 0xFFFFFFFF : 0x33FFFFFF);
                chips[i].setBackground(d);
            }
        };
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            String label = color == WidgetPrefs.ACCENT_ALBUM ? "A" : color == WidgetPrefs.ACCENT_WALLPAPER ? "W" : "";
            TextView chip = text(label, 13, 0xFFFFFFFF, true);
            chip.setGravity(Gravity.CENTER);
            chip.setShadowLayer(4, 0, 1, 0x99000000);
            chip.setContentDescription(color == WidgetPrefs.ACCENT_ALBUM ? getString(R.string.accent_album)
                    : color == WidgetPrefs.ACCENT_WALLPAPER ? getString(R.string.accent_wallpaper) : null);
            chip.setOnClickListener(v -> {
                mPrefs.accent = color;
                bind.run();
                onPrefsChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMarginEnd(dp(5));
            row.addView(chip, lp);
            chips[i] = chip;
        }
        mBinders.add(bind);
        return row;
    }

    /** Circle chip; "album" is a multicolor gradient, "wallpaper" shows the Material You palette. */
    private GradientDrawable chipDrawable(int color) {
        GradientDrawable d;
        if (color == WidgetPrefs.ACCENT_ALBUM) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6FA8FF, 0xFFE0709E, 0xFFFFC46B});
        } else if (color == WidgetPrefs.ACCENT_WALLPAPER) {
            int[] c = {0xFF7A8BA8, 0xFF9AA8C0, 0xFFB8C4D8};
            if (Build.VERSION.SDK_INT >= 31) {
                c = new int[]{getColor(android.R.color.system_accent1_200), getColor(android.R.color.system_accent2_200),
                        getColor(android.R.color.system_accent3_200)};
            }
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        } else {
            d = new GradientDrawable();
            d.setColor(color);
        }
        d.setShape(GradientDrawable.OVAL);
        return d;
    }

    // ------------------------------------------------------------------ view helpers

    private TextView sectionHeader(String s) {
        TextView t = text(s, 13, 0x99D6E0F0, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(6), dp(14), 0, dp(8));
        return t;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(card, lp);
        if (heading != null) {
            TextView h = text(heading, 17, 0xFFFFFFFF, true);
            h.setPadding(0, 0, 0, dp(4));
            card.addView(h);
        }
        return card;
    }

    private TextView hint(String s) {
        TextView t = text(s, 13, 0x99D6E0F0, false);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private Button button(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(filled ? 0xFF0A0E1A : 0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? 0xF2FFFFFF : 0x1AFFFFFF);
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                filled ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setPadding(dp(22), 0, dp(22), 0);
        return b;
    }

    /** Dims the row once (alpha on the root only) and enables/disables it and all its children. */
    private static void setEnabledDeep(View v, boolean enabled) {
        v.setAlpha(enabled ? 1f : 0.35f);
        setEnabledTree(v, enabled);
    }

    private static void setEnabledTree(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledTree(g.getChildAt(i), enabled);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
