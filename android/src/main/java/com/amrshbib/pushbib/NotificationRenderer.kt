package com.amrshbib.pushbib

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object NotificationRenderer {
  const val EXTRA_EVENT = "pushbib_event"
  const val DEFAULT_CHANNEL_ID = "pushbib_default"

  private const val META_DEFAULT_ICON = "com.amrshbib.pushbib.default_notification_icon"
  private const val META_DEFAULT_COLOR = "com.amrshbib.pushbib.default_notification_color"
  private const val META_DEFAULT_CHANNEL_NAME = "com.amrshbib.pushbib.default_channel_name"
  private const val IMAGE_TIMEOUT_MS = 8000
  private const val MAX_REDIRECTS = 5
  private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
  private const val MAX_IMAGE_EDGE_PX = 1024

  private val executor = Executors.newSingleThreadExecutor()

  fun render(context: Context, payload: NotificationPayload) {
    val appContext = context.applicationContext
    executor.execute {
      try {
        renderBlocking(appContext, payload)
      } catch (e: Exception) {
        android.util.Log.w("Pushbib", "render failed: ${e.message}")
      }
    }
  }

  private fun renderBlocking(context: Context, p: NotificationPayload) {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) {
      android.util.Log.i("Pushbib", "notifications disabled — dropping display")
      return
    }

    ensureDefaultChannel(context)
    val channelId = resolveChannel(context, p)

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(resolveSmallIcon(context, p.smallIcon))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)

    (p.title ?: appName(context)).let { builder.setContentTitle(it) }
    p.body?.let {
      builder.setContentText(it)
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
    }
    p.subtitle?.let { builder.setSubText(it) }
    resolveColor(context, p.color)?.let { builder.setColor(it) }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      builder.setSound(resolveSound(context, p.sound))
      builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
    }

    fetchBitmap(p.largeIcon)?.let { builder.setLargeIcon(it) }
    fetchBitmap(p.imageUrl)?.let { image ->
      builder.setStyle(
        NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(p.body),
      )
      if (p.largeIcon == null) builder.setLargeIcon(image)
    }

    builder.setContentIntent(clickIntent(context, p, null, p.notificationId))
    p.buttons.forEachIndexed { index, button ->
      builder.addAction(0, button.text, clickIntent(context, p, button.id, p.notificationId + index + 1))
    }

    try {
      nm.notify(p.collapseId, p.notificationId, builder.build())
    } catch (e: SecurityException) {
      android.util.Log.w("Pushbib", "notify blocked: ${e.message}")
    }
  }

  private fun clickIntent(context: Context, p: NotificationPayload, actionId: String?, requestCode: Int): PendingIntent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent().setPackage(context.packageName)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val event = JSONObject()
      .put("notification", p.toJson())
      .putOpt("actionId", actionId)
      .put("notificationId", p.notificationId)
    launch.putExtra(EXTRA_EVENT, event.toString())
    return PendingIntent.getActivity(
      context,
      requestCode,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun ensureDefaultChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(DEFAULT_CHANNEL_ID) != null) return
    val name = metaString(context, META_DEFAULT_CHANNEL_NAME) ?: "Notifications"
    manager.createNotificationChannel(
      NotificationChannel(DEFAULT_CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH),
    )
  }

  private fun resolveChannel(context: Context, p: NotificationPayload): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return DEFAULT_CHANNEL_ID

    PushbibChannels.ensureChannel(context, p.channel)?.let { return it }

    val requested = p.channelId
    if (requested.isNullOrEmpty()) return DEFAULT_CHANNEL_ID
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return if (manager.getNotificationChannel(requested) != null) requested else DEFAULT_CHANNEL_ID
  }

  internal fun resolveSmallIcon(context: Context, name: String?): Int {
    val res = context.resources
    val pkg = context.packageName
    name?.let {
      res.getIdentifier(it, "drawable", pkg).takeIf { id -> id != 0 }?.let { id -> return id }
      res.getIdentifier(it, "mipmap", pkg).takeIf { id -> id != 0 }?.let { id -> return id }
    }
    metaResource(context, META_DEFAULT_ICON)?.let { return it }
    context.applicationInfo.icon.takeIf { it != 0 }?.let { return it }
    return android.R.drawable.ic_dialog_info
  }

  internal fun resolveColor(context: Context, hex: String?): Int? {
    hex?.let { runCatching { return Color.parseColor(it) } }
    metaString(context, META_DEFAULT_COLOR)?.let { runCatching { return Color.parseColor(it) } }
    return null
  }

  private fun resolveSound(context: Context, name: String?) =
    name?.let {
      val id = context.resources.getIdentifier(it, "raw", context.packageName)
      if (id != 0) android.net.Uri.parse("android.resource://${context.packageName}/$id") else null
    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

  private fun fetchBitmap(url: String?): Bitmap? {
    if (url.isNullOrEmpty()) return null
    val bytes = download(url) ?: return null
    return decodeScaled(bytes, url)
  }

  // HttpURLConnection drops a redirect that changes scheme, so an http URL that
  // answers 301 to https hands back the redirect body instead of the picture and
  // the decode fails with nothing useful in the log. Follow the hops ourselves.
  private fun download(url: String): ByteArray? {
    var target = url
    repeat(MAX_REDIRECTS) {
      var conn: HttpURLConnection? = null
      try {
        conn = (URL(target).openConnection() as HttpURLConnection).apply {
          connectTimeout = IMAGE_TIMEOUT_MS
          readTimeout = IMAGE_TIMEOUT_MS
          instanceFollowRedirects = false
          setRequestProperty("Accept", "image/*")
        }
        val code = conn.responseCode
        if (code in 300..399) {
          val next = conn.getHeaderField("Location")
          if (next.isNullOrEmpty()) {
            android.util.Log.w("Pushbib", "image redirect with no Location ($target)")
            return null
          }
          target = URL(URL(target), next).toString()
          return@repeat
        }
        if (code !in 200..299) {
          android.util.Log.w("Pushbib", "image fetch got HTTP $code ($target)")
          return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.size > MAX_IMAGE_BYTES) {
          android.util.Log.w("Pushbib", "image is ${bytes.size} bytes, over the ${MAX_IMAGE_BYTES} cap ($target)")
          return null
        }
        return bytes
      } catch (e: Exception) {
        android.util.Log.w("Pushbib", "image fetch failed ($target): ${e.message}")
        return null
      } finally {
        conn?.disconnect()
      }
    }
    android.util.Log.w("Pushbib", "image fetch gave up after $MAX_REDIRECTS redirects ($url)")
    return null
  }

  // A full-size photo decoded as-is is several megabytes, and the notification
  // travels to the system over Binder, which drops anything that large — the push
  // then arrives with no picture at all. Decode it down first.
  private fun decodeScaled(bytes: ByteArray, url: String): Bitmap? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        android.util.Log.w("Pushbib", "image is not a picture we can decode ($url)")
        return null
      }
      var sample = 1
      while (bounds.outWidth / sample > MAX_IMAGE_EDGE_PX || bounds.outHeight / sample > MAX_IMAGE_EDGE_PX) {
        sample *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: run {
          android.util.Log.w("Pushbib", "image could not be decoded ($url)")
          null
        }
    } catch (e: OutOfMemoryError) {
      android.util.Log.w("Pushbib", "image too large to decode ($url): ${e.message}")
      null
    }
  }

  internal fun appName(context: Context): String =
    context.applicationInfo.loadLabel(context.packageManager).toString()

  internal fun metaString(context: Context, key: String): String? = metaData(context)?.getString(key)

  private fun metaResource(context: Context, key: String): Int? =
    metaData(context)?.getInt(key, 0)?.takeIf { it != 0 }

  private fun metaData(context: Context) = try {
    context.packageManager
      .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
      .metaData
  } catch (e: Exception) {
    null
  }
}
