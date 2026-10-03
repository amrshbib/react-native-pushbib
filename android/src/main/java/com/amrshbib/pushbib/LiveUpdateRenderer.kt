package com.amrshbib.pushbib

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.Executors

object LiveUpdateRenderer {
  const val CHANNEL_ID = "pushbib_live_updates"

  private const val META_CHANNEL_NAME = "com.amrshbib.pushbib.live_update_channel_name"
  private const val PREFS = "pushbib_sdk"
  private const val SEQ_PREFIX = "live_seq_"
  private const val STREAM_PREFIX = "live_stream_"

  private const val DEFAULT_ACCENT = 0xFF1A73E8.toInt()

  private const val DISMISSAL_GRACE_SECONDS = 5L

  private const val CANCEL_SETTLE_MS = 400L

  private val executor = Executors.newSingleThreadExecutor()

  const val OUTCOME_SHOWN = "shown"
  const val OUTCOME_ENDED = "ended"
  const val OUTCOME_STALE = "stale-sequence"
  const val OUTCOME_NOTIFICATIONS_DISABLED = "notifications-disabled"
  const val OUTCOME_BLOCKED = "notify-blocked"
  const val OUTCOME_ERROR = "render-error"
  const val OUTCOME_OPTED_OUT = "opted-out"
  const val OUTCOME_LOCAL_DISPLAY_OFF = "local-display-off"

  fun render(context: Context, payload: LiveUpdatePayload, onOutcome: ((String) -> Unit)? = null) {
    val appContext = context.applicationContext
    executor.execute {
      var outcome = OUTCOME_ERROR
      try {
        outcome = renderBlocking(appContext, payload)
      } catch (e: Exception) {
        android.util.Log.w("Pushbib", "live update render failed: ${e.message}")
      } finally {
        runCatching { onOutcome?.invoke(outcome) }
      }
    }
  }

  private fun renderBlocking(context: Context, p: LiveUpdatePayload): String {
    if (!claimSequence(context, p)) {
      android.util.Log.i(
        "Pushbib",
        "live update ${p.activityId} seq=${p.sequenceNumber} stream=${p.streamId} is stale — ignoring",
      )
      return OUTCOME_STALE
    }

    val nm = NotificationManagerCompat.from(context)

    if (p.isEnd) {
      endActivity(context, nm, p)
      return OUTCOME_ENDED
    }

    if (!nm.areNotificationsEnabled()) {
      android.util.Log.w(
        "Pushbib",
        "live update ${p.activityId} dropped: notifications are disabled for this app — " +
          "grant POST_NOTIFICATIONS (Pushbib.Notifications.requestPermission())",
      )
      return OUTCOME_NOTIFICATIONS_DISABLED
    }

    ensureChannel(context)

    if (Build.VERSION.SDK_INT >= 36) {
      return if (notifyPromoted(context, nm, p)) OUTCOME_SHOWN else OUTCOME_BLOCKED
    }

    val builder = baseBuilder(context, p)
      .setOngoing(true)
      .setAutoCancel(false)
      .setOnlyAlertOnce(!p.alerting)

    p.progress?.let { builder.setProgress(100, it, false) }
    if (p.progress == null && p.indeterminate) builder.setProgress(0, 0, true)

    return if (notify(context, nm, p, builder)) OUTCOME_SHOWN else OUTCOME_BLOCKED
  }

  @androidx.annotation.RequiresApi(36)
  private fun notifyPromoted(context: Context, nm: NotificationManagerCompat, p: LiveUpdatePayload): Boolean {
    val style = android.app.Notification.ProgressStyle()
    if (p.progress != null) {
      style.setProgressSegments(listOf(android.app.Notification.ProgressStyle.Segment(100)))
      style.setProgress(p.progress)
    } else {
      style.setProgressIndeterminate(true)
    }

    val title = p.title ?: p.body ?: NotificationRenderer.appName(context)
    val builder = android.app.Notification.Builder(context, CHANNEL_ID)
      .setSmallIcon(NotificationRenderer.resolveSmallIcon(context, null))
      .setContentTitle(title)
      .setStyle(style)
      .setOngoing(true)
      .setAutoCancel(false)
      .setOnlyAlertOnce(!p.alerting)
      .setCategory(android.app.Notification.CATEGORY_PROGRESS)
      .setContentIntent(clickIntent(context, p))
      .setShortCriticalText(chipText(p))
      .setColorized(true)
      .setFlag(android.app.Notification.FLAG_PROMOTED_ONGOING, true)

    if (p.body != null && p.body != title) builder.setContentText(p.body)
    builder.setColor(NotificationRenderer.resolveColor(context, null) ?: DEFAULT_ACCENT)
    p.staleDate?.let { stale ->
      if (System.currentTimeMillis() / 1000 > stale) builder.setSubText("Updating…")
    }

    val notification = builder.build()

    if (!notification.hasPromotableCharacteristics()) {
      android.util.Log.i("Pushbib", "live update ${p.activityId}: not promotable — showing as an ordinary ongoing card")
    } else if (!canPostPromoted(context)) {
      android.util.Log.i(
        "Pushbib",
        "live update ${p.activityId}: promotion not allowed for this app " +
          "(Settings ▸ Notifications ▸ Live Updates) — showing as an ordinary ongoing card",
      )
    }

    return try {
      nm.notify(p.activityId, p.notificationId, notification)
      true
    } catch (e: SecurityException) {
      android.util.Log.w("Pushbib", "live update notify blocked: ${e.message}")
      false
    }
  }

  @androidx.annotation.RequiresApi(36)
  private fun canPostPromoted(context: Context): Boolean =
    runCatching {
      (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .canPostPromotedNotifications()
    }.getOrDefault(false)

  private fun chipText(p: LiveUpdatePayload): String {
    p.progress?.let { return "$it%" }
    val text = p.title ?: p.body ?: return "Live"
    return if (text.length <= 12) text else text.take(12).trimEnd()
  }

  private fun endActivity(context: Context, nm: NotificationManagerCompat, p: LiveUpdatePayload) {
    val nowSeconds = System.currentTimeMillis() / 1000
    val keepVisible = p.dismissalDate != null && p.dismissalDate > nowSeconds + DISMISSAL_GRACE_SECONDS

    if (!keepVisible || (p.title == null && p.body == null)) {
      nm.cancel(p.activityId, p.notificationId)
      val stranded = runCatching {
        Thread.sleep(CANCEL_SETTLE_MS)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
          .activeNotifications.any { it.id == p.notificationId && it.tag == p.activityId }
      }.getOrDefault(false)
      if (stranded) {
        android.util.Log.w(
          "Pushbib",
          "live update ${p.activityId}: END cancel(tag=${p.activityId}, id=${p.notificationId}) " +
            "did not remove the card — it is still active",
        )
      }
      clearSequence(context, p.activityId)
      return
    }

    if (!nm.areNotificationsEnabled()) {
      clearSequence(context, p.activityId)
      return
    }

    ensureChannel(context)
    val builder = baseBuilder(context, p)
      .setOngoing(false)
      .setAutoCancel(true)
      .setOnlyAlertOnce(!p.alerting)
    notify(context, nm, p, builder)
    clearSequence(context, p.activityId)
  }

  private fun baseBuilder(context: Context, p: LiveUpdatePayload): NotificationCompat.Builder {
    val title = p.title ?: NotificationRenderer.appName(context)
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(NotificationRenderer.resolveSmallIcon(context, null))
      .setContentTitle(title)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setWhen(System.currentTimeMillis())
      .setShowWhen(false)
      .setContentIntent(clickIntent(context, p))

    p.body?.let {
      builder.setContentText(it)
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
    }
    NotificationRenderer.resolveColor(context, null)?.let { builder.setColor(it) }

    p.staleDate?.let { stale ->
      if (System.currentTimeMillis() / 1000 > stale) builder.setSubText("Updating…")
    }
    return builder
  }

  private fun notify(
    context: Context,
    nm: NotificationManagerCompat,
    p: LiveUpdatePayload,
    builder: NotificationCompat.Builder,
  ): Boolean {
    return try {
      nm.notify(p.activityId, p.notificationId, builder.build())
      true
    } catch (e: SecurityException) {
      android.util.Log.w("Pushbib", "live update notify blocked: ${e.message}")
      false
    }
  }

  private fun clickIntent(context: Context, p: LiveUpdatePayload): PendingIntent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent().setPackage(context.packageName)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val event = JSONObject()
      .put("notification", p.toJson())
      .put("liveUpdate", p.toJson())
      .put("notificationId", p.notificationId)
    launch.putExtra(NotificationRenderer.EXTRA_EVENT, event.toString())
    return PendingIntent.getActivity(
      context,
      p.notificationId,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val name = NotificationRenderer.metaString(context, META_CHANNEL_NAME) ?: "Live updates"
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
        setShowBadge(false)
      },
    )
  }

  private fun claimSequence(context: Context, p: LiveUpdatePayload): Boolean {
    if (p.sequenceNumber <= 0L) return true

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val seqKey = SEQ_PREFIX + p.activityId
    val streamKey = STREAM_PREFIX + p.activityId

    val knownStream = prefs.getString(streamKey, null)
    val streamChanged = p.streamId != null && p.streamId != knownStream
    if (streamChanged) {
      prefs.edit().putString(streamKey, p.streamId).putLong(seqKey, p.sequenceNumber).apply()
      return true
    }

    val last = prefs.getLong(seqKey, 0L)
    if (!p.isEnd && p.sequenceNumber <= last) return false

    prefs.edit().apply {
      putLong(seqKey, p.sequenceNumber)
      if (p.streamId != null) putString(streamKey, p.streamId)
      apply()
    }
    return true
  }

  private fun clearSequence(context: Context, activityId: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .remove(SEQ_PREFIX + activityId)
      .remove(STREAM_PREFIX + activityId)
      .apply()
  }
}
