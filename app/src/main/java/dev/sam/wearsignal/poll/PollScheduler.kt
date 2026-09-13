package dev.sam.wearsignal.poll

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import java.util.concurrent.TimeUnit

/**
 * Schedules the next poll. Prefers exact alarms (1/5/15 min user choice); falls back to a
 * 15-minute WorkManager periodic job if exact alarms are not permitted.
 *
 * When app notifications are enabled, alarms are only scheduled while the phone is disconnected.
 * When the phone reconnects, all polling alarms are cancelled so the watch CPU stays asleep.
 */
object PollScheduler {

  private val TAG = Log.tag(PollScheduler::class)
  private const val WORK_NAME = "poll-fallback"

  private fun pollPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, PollReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  /**
   * Evaluates phone connectivity: if phone is connected, disarms alarms; if disconnected,
   * arms the next alarm. Called on user setting change or after a poll completes.
   */
  fun scheduleNext(context: Context) {
    if (!AppDeps.account.isLinked || !AppDeps.account.backgroundPollingEnabled) {
      cancel(context)
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      if (PhoneConnectionMonitor.isPhoneConnected(context)) {
        Log.i(TAG, "Phone is connected; alarms not scheduled (relying on phone notification forwarding)")
        cancel(context)
      } else {
        Log.i(TAG, "Phone is disconnected; arming recurring poll schedule")
        armAlarm(context)
      }
    }
  }

  /**
   * Called by PhoneConnectionListenerService when a peer disconnects.
   * Only triggers if app notifications (backgroundPollingEnabled) are turned on!
   */
  fun onPhoneDisconnected(context: Context) {
    if (!AppDeps.account.isLinked || !AppDeps.account.backgroundPollingEnabled) {
      Log.d(TAG, "Phone disconnected, but app notifications are disabled; ignoring")
      return
    }
    if (AppDeps.account.phoneConnectedOverride == true) {
      Log.i(TAG, "Phone disconnected, but phoneConnectedOverride is true; ignoring")
      return
    }

    Log.i(TAG, "Phone disconnected with notifications enabled: triggering immediate poll & arming schedule")
    // Trigger an immediate expedited poll for any messages waiting while phone was disconnecting
    val request = OneTimeWorkRequestBuilder<PollWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .build()
    WorkManager.getInstance(context).enqueue(request)

    armAlarm(context)
  }

  /**
   * Called by PhoneConnectionListenerService when a peer reconnects.
   * Disarms any pending alarms since the phone will bridge notifications.
   */
  fun onPhoneConnected(context: Context) {
    if (AppDeps.account.phoneConnectedOverride == false) {
      Log.i(TAG, "Phone connected, but phoneConnectedOverride is false; ignoring")
      return
    }
    Log.i(TAG, "Phone connected: canceling recurring background alarms")
    cancel(context)
  }

  /** Arms the recurring alarm / WorkManager fallback for the user's chosen interval. */
  fun armAlarm(context: Context) {
    if (!AppDeps.account.isLinked || !AppDeps.account.backgroundPollingEnabled) {
      cancel(context)
      return
    }

    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intervalMs = AppDeps.account.pollIntervalMinutes * 60_000L

    if (alarmManager.canScheduleExactAlarms()) {
      alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + intervalMs,
        pollPendingIntent(context)
      )
      Log.i(TAG, "Scheduled exact poll in ${AppDeps.account.pollIntervalMinutes} min")
    } else {
      Log.w(TAG, "Exact alarms not permitted; using 15-min WorkManager fallback")
      val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }

  /** Cancels any pending recurring poll (alarm + WorkManager fallback). */
  fun cancel(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(pollPendingIntent(context))
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    Log.i(TAG, "Background polling cancelled")
  }
}
