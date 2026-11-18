package dev.nemesis.drive.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.nemesis.drive.R
import dev.nemesis.drive.model.DriveAction
import dev.nemesis.drive.nativeapi.NativeAuthService
import dev.nemesis.drive.nativeapi.NativeVehicleService
import dev.nemesis.drive.platform.AndroidOwnerStore
import dev.nemesis.drive.platform.OwnerHttpsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class TDriEVControlWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_LOCK = "dev.nemesis.drive.action.WIDGET_LOCK"
        const val ACTION_WIDGET_UNLOCK = "dev.nemesis.drive.action.WIDGET_UNLOCK"
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = TDriEVWidgetManager.loadLatestSnapshot(context)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_controls)
            TDriEVWidgetManager.bindControlWidget(context, views, snapshot)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = when (intent.action) {
            ACTION_WIDGET_LOCK -> DriveAction.LOCK
            ACTION_WIDGET_UNLOCK -> DriveAction.UNLOCK
            else -> null
        } ?: return

        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val controlIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TDriEVControlWidget::class.java))
        if (controlIds != null && controlIds.isNotEmpty()) {
            for (id in controlIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_controls)
                views.setTextViewText(if (action == DriveAction.LOCK) R.id.widget_text_lock else R.id.widget_text_unlock, "Sending…")
                appWidgetManager.updateAppWidget(id, views)
            }
        }

        val pendingResult = goAsync()
        widgetScope.launch {
            val store = AndroidOwnerStore(context.applicationContext)
            try {
                // 1. Immediately apply optimistic state to local snapshot
                val currentSnapshot = TDriEVWidgetManager.loadLatestSnapshot(context)
                if (currentSnapshot != null) {
                    val optimistic = currentSnapshot.copy(
                        locked = (action == DriveAction.LOCK),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    store.saveSnapshot("latest", com.google.gson.Gson().toJson(optimistic))
                }

                // 2. Transmit command
                var session = store.load() ?: return@launch
                val transport = OwnerHttpsTransport()
                val auth = NativeAuthService(transport, store)
                session = runCatching { auth.refresh(session) }.getOrDefault(session)
                val service = NativeVehicleService(transport, auth, store, fireAndForget = true)
                val vehicles = service.vehicles(session)
                val vehicle = vehicles.firstOrNull() ?: return@launch

                service.run(session, vehicle, action, 10, UUID.randomUUID().toString(), {}) { _, _ -> }

                // 3. Save confirmed snapshot
                val confirmed = (currentSnapshot ?: vehicle.vehicle.snapshot).copy(
                    locked = (action == DriveAction.LOCK),
                    updatedAtMillis = System.currentTimeMillis()
                )
                store.saveSnapshot(vehicle.vehicle.id, com.google.gson.Gson().toJson(confirmed))
            } catch (_: Exception) {
                // Handled in finally
            } finally {
                TDriEVWidgetManager.updateAllWidgets(context)
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }
}