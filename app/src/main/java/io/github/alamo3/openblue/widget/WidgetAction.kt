package io.github.alamo3.openblue.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.api.BluelinkClient
import io.github.alamo3.openblue.api.Brand
import io.github.alamo3.openblue.api.UsaBluelinkClient
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.forCar
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.work.CommandWorker
import io.github.alamo3.openblue.work.Notifier
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A tap on one of the widget's buttons: hand the work to [WidgetCommandWorker]. */
class WidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val what = parameters[WHAT] ?: return
        val action = actionFor(what)
        if (action != null) {
            SecureStore(context).widgetPending = "${action.name}|${System.currentTimeMillis()}"
            CarWidget.updateAll(context)
        }
        enqueue(context, what)
    }

    companion object {
        /** Hands [what] to [WidgetCommandWorker]; [settingsJson] are the climate settings to start with. */
        fun enqueue(context: Context, what: String, settingsJson: String? = null) {
            val req = OneTimeWorkRequestBuilder<WidgetCommandWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        WidgetCommandWorker.KEY_WHAT to what,
                        WidgetCommandWorker.KEY_SETTINGS to settingsJson,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("widget-$what", ExistingWorkPolicy.KEEP, req)
        }

        val WHAT = ActionParameters.Key<String>("what")
        const val LOCK = "lock"
        const val CLIMATE_START = "climateStart"
        const val CLIMATE_STOP = "climateStop"
        const val REFRESH = "refresh"

        fun actionFor(what: String) = when (what) {
            LOCK -> CommandProgress.Action.LOCK
            CLIMATE_START -> CommandProgress.Action.CLIMATE_START
            CLIMATE_STOP -> CommandProgress.Action.CLIMATE_STOP
            else -> null
        }
    }
}

/**
 * Sends the widget's command to the car, then leaves waiting for the answer to [CommandWorker]
 * (which posts the result as a notification and redraws the widget), the same as from the app.
 */
class WidgetCommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val what = inputData.getString(KEY_WHAT) ?: return Result.failure()
        val store = SecureStore(applicationContext)
        val v = store.vehicle
        if (v == null || store.username == null) {
            store.widgetPending = null
            CarWidget.updateAll(applicationContext)
            return Result.success()
        }
        val api = BluelinkClient(store)
        val isUs = store.brand == Brand.HYUNDAI_USA
        val usa = if (isUs) UsaBluelinkClient(store) else null
        val car = v.nickname.ifBlank { "Your car" }
        val action = WidgetAction.actionFor(what)
        val chosen = inputData.getString(KEY_SETTINGS)?.let { runCatching { Json.decodeFromString<ClimateSettings>(it) }.getOrNull() }
        // The picker sets the "starting" note itself; draw it.
        if (action != null) CarWidget.updateAll(applicationContext)
        try {
            if (action == null) {
                // Refresh: what Bluelink already has, without waking the car.
                store.cachedStatus = (if (isUs) usa!!.cachedStatus(v.id) else api.cachedStatus(v.id)).toString()
            } else {
                val settings = (chosen ?: store.lastClimate ?: ClimateSettings()).forCar(store.caps ?: VehicleCaps())
                val tx = when (action) {
                    CommandProgress.Action.LOCK -> if (isUs) usa!!.lock(v.id) else api.lock(v.id)
                    CommandProgress.Action.CLIMATE_STOP -> if (isUs) usa!!.stopClimate(v.id) else api.stopClimate(v.id)
                    else -> if (isUs) usa!!.startClimate(v, settings) else api.startClimate(v, settings)
                }
                if (isUs) {
                    // USA v1: poll here instead of CommandWorker (which speaks the Canada API).
                    usa!!.awaitResult(v.id, tx)
                    runCatching { store.cachedStatus = usa.cachedStatus(v.id).toString() }
                } else {
                    CommandWorker.enqueue(applicationContext, v.id, tx, action, car)
                    return Result.success()  // CommandWorker clears the pending note when the car answers
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (action != null) {
                Notifier.post(
                    applicationContext, Notifier.CHANNEL_COMMANDS, what.hashCode(),
                    "$car: ${action.doing.lowercase()} didn't work", e.message ?: "Couldn't reach Bluelink.",
                )
            }
        }
        store.widgetPending = null
        CarWidget.updateAll(applicationContext)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifier.createChannels(applicationContext)
        return ForegroundInfo(4202, Notifier.working(applicationContext, "Sending to the car"))
    }

    companion object {
        const val KEY_WHAT = "what"
        const val KEY_SETTINGS = "settings"
    }
}
