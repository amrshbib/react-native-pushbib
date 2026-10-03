package com.amrshbib.pushbib

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object PushbibChannels {

  private const val PREFS_FILE = "pushbib_sdk"
  private const val PREF_SYNCED_VERSION = "channels_version"

  class Definition(
    val key: String?,
    val runtimeId: String,
    val previousRuntimeId: String?,
    val nativeId: String?,
    val name: String,
    val description: String?,
    val importance: String,
    val soundMode: String,
    val soundName: String?,
    val vibrationMode: String,
    val vibrationPattern: LongArray?,
    val ledMode: String,
    val ledColor: String?,
    val lockscreenVisibility: String,
    val showBadge: Boolean,
    val bypassDnd: Boolean,
    val groupKey: String?,
    val groupName: String?,
  )

  fun sync(context: Context, channelsJson: String?, version: String?): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0
    val definitions = parseList(channelsJson)
    if (definitions.isEmpty()) return 0

    val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    if (!version.isNullOrEmpty() && prefs.getString(PREF_SYNCED_VERSION, null) == version) return 0

    for (definition in definitions) apply(context, definition)

    if (!version.isNullOrEmpty()) prefs.edit().putString(PREF_SYNCED_VERSION, version).apply()
    return definitions.size
  }

  fun ensureChannel(context: Context, json: JSONObject?): String? {
    if (json == null) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val definition = parse(json) ?: return null
    apply(context, definition)
    return definition.runtimeId
  }

  fun listChannels(context: Context): JSONArray {
    val out = JSONArray()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return out
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    for (channel in manager.notificationChannels) {
      out.put(
        JSONObject()
          .put("id", channel.id)
          .put("name", channel.name?.toString())
          .put("importance", channel.importance)
          .put("blocked", channel.importance == NotificationManager.IMPORTANCE_NONE)
          .put("showBadge", channel.canShowBadge())
          .putOpt("group", channel.group),
      )
    }
    return out
  }

  fun soundUri(context: Context, mode: String, name: String?): Uri? = when (mode.lowercase()) {
    "off" -> null
    "custom" -> customSoundUri(context, name)
    else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
  }

  private fun customSoundUri(context: Context, name: String?): Uri? {
    if (name.isNullOrEmpty()) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val id = context.resources.getIdentifier(name, "raw", context.packageName)
    if (id != 0) return Uri.parse("android.resource://${context.packageName}/$id")
    android.util.Log.w("Pushbib", "channel sound \"$name\" is not bundled in res/raw — using the default tone")
    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
  }

  private fun apply(context: Context, d: Definition) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (!d.groupKey.isNullOrEmpty()) {
      manager.createNotificationChannelGroup(
        NotificationChannelGroup(d.groupKey, d.groupName ?: d.groupKey),
      )
    }

    val existing = manager.getNotificationChannel(d.runtimeId)
    if (existing != null) {
      existing.name = d.name
      existing.description = d.description
      manager.createNotificationChannel(existing)
      deletePrevious(manager, d)
      return
    }

    val channel = NotificationChannel(d.runtimeId, d.name, importanceOf(d.importance)).apply {
      description = d.description
      setShowBadge(d.showBadge)
      lockscreenVisibility = visibilityOf(d.lockscreenVisibility)

      when (d.vibrationMode) {
        "off" -> enableVibration(false)
        "custom" -> {
          enableVibration(true)
          if (d.vibrationPattern != null && d.vibrationPattern.isNotEmpty()) {
            vibrationPattern = d.vibrationPattern
          }
        }
        else -> enableVibration(true)
      }

      when (d.ledMode) {
        "off" -> enableLights(false)
        "custom" -> {
          enableLights(true)
          if (!d.ledColor.isNullOrEmpty()) runCatching { lightColor = Color.parseColor(d.ledColor) }
        }
        else -> enableLights(true)
      }

      if (!d.groupKey.isNullOrEmpty()) group = d.groupKey
      if (d.bypassDnd) setBypassDnd(true)

      val uri = soundUri(context, d.soundMode, d.soundName)
      if (uri == null) {
        setSound(null, null)
      } else {
        setSound(
          uri,
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
      }
    }

    manager.createNotificationChannel(channel)
    deletePrevious(manager, d)
  }

  private fun deletePrevious(manager: NotificationManager, d: Definition) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val previous = d.previousRuntimeId ?: return
    if (previous == d.runtimeId) return
    runCatching { manager.deleteNotificationChannel(previous) }
  }

  private fun parseList(json: String?): List<Definition> {
    if (json.isNullOrEmpty()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    val out = ArrayList<Definition>(array.length())
    for (i in 0 until array.length()) {
      parse(array.optJSONObject(i))?.let { out.add(it) }
    }
    return out
  }

  private fun parse(json: JSONObject?): Definition? {
    if (json == null) return null
    val nativeId = json.pick("nativeId", "native_id")
    val runtimeId = json.pick("runtimeId", "runtime_id") ?: nativeId ?: return null
    val key = json.pick("key")

    val soundObject = json.optJSONObject("sound")
    val legacySound = if (soundObject == null) json.pick("sound") else null
    val soundName = if (soundObject != null) soundObject.pick("name") else legacySound?.takeIf { it != "none" }
    val soundMode = when {
      soundObject != null -> soundObject.mode(if (soundName.isNullOrEmpty()) "default" else "custom")
      legacySound == null -> "default"
      legacySound == "none" -> "off"
      else -> "custom"
    }

    val vibrationObject = json.optJSONObject("vibration")
    val vibrationPattern =
      if (vibrationObject != null) vibrationObject.longs("pattern")
      else json.longs("vibrationPattern", "vibration_pattern")
    val vibrationMode = when {
      vibrationObject != null -> vibrationObject.mode(if (vibrationPattern == null) "default" else "custom")
      !json.flag("vibrate", true) -> "off"
      vibrationPattern == null -> "default"
      else -> "custom"
    }

    val ledObject = json.optJSONObject("led")
    val ledColor = if (ledObject != null) ledObject.pick("color") else json.pick("ledColor", "led_color")
    val ledMode = when {
      ledObject != null -> ledObject.mode(if (ledColor.isNullOrEmpty()) "off" else "custom")
      ledColor.isNullOrEmpty() -> "off"
      else -> "custom"
    }

    return Definition(
      key = key,
      runtimeId = runtimeId,
      previousRuntimeId = json.pick("previousRuntimeId", "previous_runtime_id"),
      nativeId = nativeId,
      name = json.pick("name") ?: key ?: runtimeId,
      description = json.pick("description"),
      importance = json.pick("importance") ?: "high",
      soundMode = soundMode,
      soundName = soundName,
      vibrationMode = vibrationMode,
      vibrationPattern = vibrationPattern,
      ledMode = ledMode,
      ledColor = ledColor,
      lockscreenVisibility = json.pick("lockscreenVisibility", "lockscreen_visibility") ?: "public",
      showBadge = json.flag("showBadge", true, "show_badge"),
      bypassDnd = json.flag("bypassDnd", false, "bypass_dnd"),
      groupKey = json.pick("groupKey", "group_key"),
      groupName = json.pick("groupName", "group_name"),
    )
  }

  private fun importanceOf(value: String): Int = when (value.lowercase()) {
    "none", "min", "low" -> NotificationManager.IMPORTANCE_LOW
    "medium", "default" -> NotificationManager.IMPORTANCE_DEFAULT
    "urgent" -> NotificationManager.IMPORTANCE_MAX
    else -> NotificationManager.IMPORTANCE_HIGH
  }

  private fun visibilityOf(value: String): Int = when (value.lowercase()) {
    "secret" -> android.app.Notification.VISIBILITY_SECRET
    "private" -> android.app.Notification.VISIBILITY_PRIVATE
    else -> android.app.Notification.VISIBILITY_PUBLIC
  }

  private fun JSONObject.pick(vararg keys: String): String? {
    for (key in keys) {
      if (!has(key) || isNull(key)) continue
      val value = optString(key, "")
      if (value.isNotEmpty()) return value
    }
    return null
  }

  private fun JSONObject.mode(fallback: String): String {
    val value = pick("mode") ?: return fallback
    return when (value.lowercase()) {
      "off" -> "off"
      "default" -> "default"
      "custom" -> "custom"
      else -> fallback
    }
  }

  private fun JSONObject.flag(camel: String, fallback: Boolean, vararg others: String): Boolean {
    for (key in listOf(camel, *others)) {
      if (has(key) && !isNull(key)) return optBoolean(key, fallback)
    }
    return fallback
  }

  private fun JSONObject.longs(vararg keys: String): LongArray? {
    for (key in keys) {
      val array = optJSONArray(key) ?: continue
      if (array.length() == 0) continue
      return LongArray(array.length()) { array.optLong(it, 0L) }
    }
    return null
  }
}
