package org.nightingalelab.focus.phone;

import android.appwidget.*;
import android.content.Context;

public final class WidgetCProvider extends AppWidgetProvider {
  @Override
  public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    WidgetUpdater.updateAll(context);
  }
}
