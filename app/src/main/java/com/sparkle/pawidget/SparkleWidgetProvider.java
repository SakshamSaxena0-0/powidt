package com.sparkle.pawidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

public class SparkleWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle newOptions) {
        refresh(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) {
            WidgetPrefs.removeOwnStyle(context, id);
            WidgetRenderer.forget(id);
        }
    }

    @Override
    public void onDisabled(Context context) {
        Ticker.evaluate(context);
    }

    private void refresh(Context context) {
        final PendingResult pending = goAsync();
        final Context ctx = context.getApplicationContext();
        Events.EXEC.execute(() -> {
            try {
                Events.refreshFromSticky(ctx);
                WidgetRenderer.updateAll(ctx);
                Ticker.evaluate(ctx);
            } finally {
                pending.finish();
            }
        });
    }
}
