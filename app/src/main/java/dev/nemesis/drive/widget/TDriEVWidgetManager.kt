package dev.nemesis.drive.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.google.gson.Gson
import dev.nemesis.drive.MainActivity
import dev.nemesis.drive.R
import dev.nemesis.drive.model.VehicleSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TDriEVWidgetManager {

    fun loadLatestSnapshot(context: Context): VehicleSnapshot? {
        val prefs = context.getSharedPreferences("vehicle_snapshots", Context.MODE_PRIVATE)
        val json = prefs.getString("latest_snapshot", null) ?: return null
        return runCatching { Gson().fromJson(json, VehicleSnapshot::class.java) }.getOrNull()
    }

    fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val snapshot = loadLatestSnapshot(context)

        // 1. Battery Widget
        val batteryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TDriEVBatteryWidget::class.java))
        if (batteryIds != null && batteryIds.isNotEmpty()) {
            for (id in batteryIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_battery)
                bindBatteryWidget(context, views, snapshot)
                appWidgetManager.updateAppWidget(id, views)
            }
        }

        // 2. Control Widget
        val controlIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TDriEVControlWidget::class.java))
        if (controlIds != null && controlIds.isNotEmpty()) {
            for (id in controlIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_controls)
                bindControlWidget(context, views, snapshot)
                appWidgetManager.updateAppWidget(id, views)
            }
        }

        // 3. Status Widget
        val statusIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TDriEVStatusWidget::class.java))
        if (statusIds != null && statusIds.isNotEmpty()) {
            for (id in statusIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_status)
                bindStatusWidget(context, views, snapshot)
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    fun bindBatteryWidget(context: Context, views: RemoteViews, snapshot: VehicleSnapshot?) {
        val appIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        if (snapshot != null) {
            views.setTextViewText(R.id.widget_battery_percent, "${snapshot.batteryPercent ?: "—"}%")
            views.setTextViewText(R.id.widget_range, "${snapshot.rangeKm ?: "—"} km range")
            views.setTextViewText(R.id.widget_lock_status, if (snapshot.locked == true) "Locked" else if (snapshot.locked == false) "Unlocked" else "Ready")
            views.setTextViewText(R.id.widget_charging, if (snapshot.charging == true) "Charging" else if (snapshot.cableConnected == true) "Plugged in" else "Disconnected")
            val time = snapshot.updatedAtMillis?.let { formatTime(it) } ?: "Synced"
            views.setTextViewText(R.id.widget_timestamp, "Updated · $time")
        } else {
            views.setTextViewText(R.id.widget_battery_percent, "—%")
            views.setTextViewText(R.id.widget_range, "Open TDriEV to sync")
            views.setTextViewText(R.id.widget_lock_status, "Ready")
            views.setTextViewText(R.id.widget_charging, "Disconnected")
        }
    }

    fun bindControlWidget(context: Context, views: RemoteViews, snapshot: VehicleSnapshot?) {
        val appIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        val lockIntent = Intent(context, TDriEVControlWidget::class.java).apply {
            action = TDriEVControlWidget.ACTION_WIDGET_LOCK
        }
        val lockPending = PendingIntent.getBroadcast(context, 101, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_lock, lockPending)

        val unlockIntent = Intent(context, TDriEVControlWidget::class.java).apply {
            action = TDriEVControlWidget.ACTION_WIDGET_UNLOCK
        }
        val unlockPending = PendingIntent.getBroadcast(context, 102, unlockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_unlock, unlockPending)

        if (snapshot != null) {
            val lockText = if (snapshot.locked == true) "Locked" else if (snapshot.locked == false) "Unlocked" else "Ready"
            val soc = snapshot.batteryPercent ?: "—"
            views.setTextViewText(R.id.widget_status_pill, "$soc% · $lockText")
        } else {
            views.setTextViewText(R.id.widget_status_pill, "TDriEV Ready")
        }
    }

    fun bindStatusWidget(context: Context, views: RemoteViews, snapshot: VehicleSnapshot?) {
        val appIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        if (snapshot != null) {
            val odo = snapshot.odometerKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) } ?: "— km"
            views.setTextViewText(R.id.widget_odometer, odo)
            views.setTextViewText(R.id.widget_doors_status, if (snapshot.doorsClosed == true) "Doors closed" else if (snapshot.doorsClosed == false) "Door open" else "Doors secure")
            views.setTextViewText(R.id.widget_ignition, if (snapshot.ignitionOn == true) "Running" else "Parked · Ignition off")

            val lat = snapshot.latitude
            val lon = snapshot.longitude
            if (lat != null && lon != null) {
                val mapUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Tiago.ev)")
                val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                val mapPending = PendingIntent.getActivity(context, 201, mapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_btn_navigate, mapPending)
                views.setTextViewText(R.id.widget_text_location, "Directions to Car")
            } else {
                views.setOnClickPendingIntent(R.id.widget_btn_navigate, pendingIntent)
                views.setTextViewText(R.id.widget_text_location, "Open TDriEV Map")
            }
        } else {
            views.setTextViewText(R.id.widget_odometer, "— km")
            views.setTextViewText(R.id.widget_doors_status, "Ready")
            views.setTextViewText(R.id.widget_ignition, "Parked")
            views.setOnClickPendingIntent(R.id.widget_btn_navigate, pendingIntent)
            views.setTextViewText(R.id.widget_text_location, "Open TDriEV")
        }
    }

    private fun formatTime(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}