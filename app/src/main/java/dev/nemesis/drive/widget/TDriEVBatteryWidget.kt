package dev.nemesis.drive.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.nemesis.drive.R

class TDriEVBatteryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = TDriEVWidgetManager.loadLatestSnapshot(context)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_battery)
            TDriEVWidgetManager.bindBatteryWidget(context, views, snapshot)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}