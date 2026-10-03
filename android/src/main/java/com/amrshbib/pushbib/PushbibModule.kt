package com.amrshbib.pushbib

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

class PushbibModule(private val context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context), ActivityEventListener, LifecycleEventListener, PermissionListener {

  companion object {
    const val NAME = "Pushbib"
    private const val PREFS_FILE = "pushbib_sdk"
    private const val PERMISSION_REQUEST_CODE = 4471
  }

  private var permissionPromise: Promise? = null

  init {
    PushbibEmitter.reactContext = context
    context.addActivityEventListener(this)
    context.addLifecycleEventListener(this)
  }

  override fun getName() = NAME

  override fun invalidate() {
    context.removeActivityEventListener(this)
    context.removeLifecycleEventListener(this)
    PushbibEmitter.onReactContextGone(context)
    super.invalidate()
  }

  @ReactMethod
  fun addListener(eventName: String?) {
    PushbibEmitter.jsListening = true
    PushbibEmitter.flushQueue()
  }

  @ReactMethod
  fun removeListeners(count: Double) {
  }

  @ReactMethod
  fun getDeviceInfo(promise: Promise) {
    val pkg = context.packageName
    val appVersion = try {
      context.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) {
      null
    }
    val locale = Locale.getDefault()
    val language = if (locale.country.isNullOrEmpty()) locale.language else "${locale.language}-${locale.country}"
    val deviceId = try {
      Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
      null
    }
    promise.resolve(Arguments.createMap().apply {
      putString("platform", "android")
      putString("deviceId", deviceId)
      putString("osName", "Android")
      putString("osVersion", Build.VERSION.RELEASE)
      putString("model", Build.MODEL)
      putString("bundleId", pkg)
      putString("appVersion", appVersion)
      putString("language", language)
      putString("country", locale.country)
      putString("timezone", TimeZone.getDefault().id)
    })
  }

  @ReactMethod
  fun getStableDeviceId(promise: Promise) {
    try {
      val prefs = prefs()
      val cached = prefs.getString("stable_device_id", null)
      if (!cached.isNullOrEmpty()) {
        promise.resolve(cached)
        return
      }
      val androidId = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      } catch (e: Exception) {
        null
      }
      val bad = setOf("", "0", "0000000000000000", "9774d56d682e549c", "unknown", "null", "undefined")
      val chosen =
        if (!androidId.isNullOrEmpty() && !bad.contains(androidId.lowercase(Locale.ROOT))) androidId
        else "nfd-" + java.util.UUID.randomUUID().toString()
      prefs.edit().putString("stable_device_id", chosen).apply()
      promise.resolve(chosen)
    } catch (e: Exception) {
      promise.resolve(null)
    }
  }

  @ReactMethod
  fun prefsGet(key: String, promise: Promise) {
    promise.resolve(prefs().getString(key, null))
  }

  @ReactMethod
  fun prefsSet(key: String, value: String?, promise: Promise) {
    prefs().edit().apply {
      if (value == null) remove(key) else putString(key, value)
    }.apply()
    promise.resolve(null)
  }

  @ReactMethod
  fun getPushToken(configJson: String?, promise: Promise) {
    val raw = configJson
      ?: prefs().getString("config_cache", null)?.let { cached ->
        runCatching { JSONObject(cached).optJSONObject("android")?.toString() }.getOrNull()
      }
    if (raw.isNullOrEmpty()) {
      promise.reject(
        "pushbib/no-android-config",
        "Android Firebase client config missing — upload the Firebase service-account JSON in the dashboard (App Settings).",
      )
      return
    }
    try {
      val messaging = FirebaseBootstrap.messaging(context.applicationContext, JSONObject(raw))
      messaging.token.addOnCompleteListener { task ->
        if (task.isSuccessful && !task.result.isNullOrEmpty()) {
          promise.resolve(task.result)
        } else {
          promise.reject(
            "pushbib/token-failed",
            task.exception?.message ?: "FCM getToken returned no token (is Google Play services available?)",
            task.exception,
          )
        }
      }
    } catch (e: Exception) {
      promise.reject("pushbib/firebase-init-failed", e.message, e)
    }
  }

  @ReactMethod
  fun syncChannels(channelsJson: String?, version: String?, promise: Promise) {
    try {
      promise.resolve(PushbibChannels.sync(context.applicationContext, channelsJson, version))
    } catch (e: Exception) {
      android.util.Log.w("Pushbib", "channel sync failed: ${e.message}")
      promise.resolve(0)
    }
  }

  @ReactMethod
  fun listChannels(promise: Promise) {
    try {
      promise.resolve(PushbibChannels.listChannels(context.applicationContext).toString())
    } catch (e: Exception) {
      promise.resolve("[]")
    }
  }

  @ReactMethod
  fun openChannelSettings(channelId: String?, promise: Promise) {
    try {
      val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelId.isNullOrEmpty()) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
          .setData(android.net.Uri.parse("package:${context.packageName}"))
      }
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      (context.currentActivity ?: context).startActivity(intent)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.resolve(false)
    }
  }

  @ReactMethod
  fun hasPermission(promise: Promise) {
    promise.resolve(NotificationManagerCompat.from(context).areNotificationsEnabled())
  }

  @ReactMethod
  fun requestPermission(promise: Promise) {
    if (Build.VERSION.SDK_INT < 33) {
      promise.resolve(NotificationManagerCompat.from(context).areNotificationsEnabled())
      return
    }
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      promise.resolve(true)
      return
    }
    val activity = context.currentActivity as? PermissionAwareActivity
    if (activity == null) {
      promise.resolve(false)
      return
    }
    permissionPromise = promise
    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE, this)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
    if (requestCode != PERMISSION_REQUEST_CODE) return false
    permissionPromise?.resolve(
      grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED,
    )
    permissionPromise = null
    return true
  }

  @ReactMethod
  fun setForegroundHandling(enabled: Boolean) {
    PushbibEmitter.foregroundHandling = enabled
  }

  @ReactMethod
  fun completeNotificationDisplay(completionId: String, display: Boolean) {
    PushbibEmitter.completeDisplay(completionId, display)
  }

  @ReactMethod
  fun clearAllNotifications() {
    NotificationManagerCompat.from(context).cancelAll()
  }

  @ReactMethod
  fun removeNotification(id: Double) {
    NotificationManagerCompat.from(context).cancel(id.toInt())
  }

  @ReactMethod
  fun removeGroupedNotifications(group: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val active = if (Build.VERSION.SDK_INT >= 23) manager?.activeNotifications else null
    if (active == null) {
      // No way to enumerate what is showing on this API level — the group id doubles as a
      // notification id for single-notification groups, so cancel by tag as a best effort.
      NotificationManagerCompat.from(context).cancel(group, 0)
      return
    }
    for (notification in active) {
      if (notification.notification?.group == group) {
        NotificationManagerCompat.from(context).cancel(notification.tag, notification.id)
      }
    }
  }

  // OneSignal's OSNotificationPermission: 0 not determined, 1 denied, 2 authorized
  @ReactMethod
  fun permissionNative(promise: Promise) {
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      promise.resolve(2)
      return
    }
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      promise.resolve(1)
      return
    }
    promise.resolve(if (Build.VERSION.SDK_INT >= 33) 0 else 1)
  }

  @ReactMethod
  fun setBadgeCount(count: Double) {
  }

  @ReactMethod
  fun getInitialClickEvent(promise: Promise) {
    promise.resolve(consumeClickFromIntent(context.currentActivity?.intent))
  }

  override fun onNewIntent(intent: Intent) {
    val raw = consumeClickFromIntent(intent) ?: return
    runCatching { PushbibEmitter.emitOrQueue(PushbibEmitter.EVENT_CLICK, JSONObject(raw)) }
  }

  override fun onHostResume() {
    val raw = consumeClickFromIntent(context.currentActivity?.intent) ?: return
    runCatching { PushbibEmitter.emitOrQueue(PushbibEmitter.EVENT_CLICK, JSONObject(raw)) }
  }

  override fun onHostPause() {}
  override fun onHostDestroy() {}

  override fun onActivityResult(activity: android.app.Activity, requestCode: Int, resultCode: Int, data: Intent?) {}

  @Synchronized
  private fun consumeClickFromIntent(intent: Intent?): String? {
    val raw = intent?.getStringExtra(NotificationRenderer.EXTRA_EVENT) ?: return null
    intent.removeExtra(NotificationRenderer.EXTRA_EVENT)
    cancelClickedNotification(raw)
    return raw
  }

  private fun cancelClickedNotification(rawEvent: String) {
    runCatching {
      val id = JSONObject(rawEvent).optInt("notificationId", 0)
      if (id != 0) NotificationManagerCompat.from(context).cancel(id)
    }
  }

  private fun prefs() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
