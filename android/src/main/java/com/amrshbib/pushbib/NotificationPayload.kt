package com.amrshbib.pushbib

import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

class NotificationPayload private constructor(
  val title: String?,
  val subtitle: String?,
  val body: String?,
  val imageUrl: String?,
  val largeIcon: String?,
  val channelId: String?,
  val channel: JSONObject?,
  val sound: String?,
  val smallIcon: String?,
  val color: String?,
  val collapseId: String?,
  val url: String?,
  val buttons: List<Button>,
  val data: Map<String, String>,
  val notificationId: Int,
  val showForeground: Boolean,
  val showBackground: Boolean,
) {
  class Button(val id: String, val text: String)

  fun toJson(): JSONObject = JSONObject().apply {
    put("notificationId", notificationId)
    put("showForeground", showForeground)
    put("showBackground", showBackground)
    putOpt("title", title)
    putOpt("subtitle", subtitle)
    putOpt("body", body)
    putOpt("imageUrl", imageUrl)
    putOpt("largeIcon", largeIcon)
    putOpt("channelId", channelId)
    putOpt("channel", channel)
    putOpt("sound", sound)
    putOpt("smallIcon", smallIcon)
    putOpt("color", color)
    putOpt("collapseId", collapseId)
    putOpt("url", url)
    put("buttons", JSONArray().apply { buttons.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } })
    put("data", JSONObject(data as Map<*, *>))
  }

  companion object {
    private val CONTROL_KEYS =
      setOf("pushbib_payload", "url", "buttons", "largeIcon", "showForeground", "showBackground")

    private fun flag(raw: String?, default: Boolean = true): Boolean =
      when (raw?.trim()?.lowercase()) {
        null, "" -> default
        "false", "0", "no" -> false
        else -> true
      }

    fun fromRemoteMessage(message: RemoteMessage): NotificationPayload? {
      val data = message.data
      val display = data["pushbib_payload"]?.let { runCatching { JSONObject(it) }.getOrNull() }
      val notif = message.notification
      if (display == null && notif == null) return null

      val custom = HashMap<String, String>()
      for ((k, v) in data) if (k !in CONTROL_KEYS) custom[k] = v

      return NotificationPayload(
        title = display?.optNonEmpty("title") ?: notif?.title,
        subtitle = display?.optNonEmpty("subtitle"),
        body = display?.optNonEmpty("body") ?: notif?.body,
        imageUrl = display?.optNonEmpty("imageUrl") ?: notif?.imageUrl?.toString(),
        largeIcon = data["largeIcon"],
        channelId = display?.optNonEmpty("channelId") ?: notif?.channelId,
        channel = display?.optJSONObject("channel"),
        sound = display?.optNonEmpty("sound") ?: notif?.sound,
        smallIcon = display?.optNonEmpty("smallIcon") ?: notif?.icon,
        color = display?.optNonEmpty("color") ?: notif?.color,
        collapseId = display?.optNonEmpty("collapseId") ?: message.collapseKey,
        url = data["url"],
        buttons = parseButtons(data["buttons"]),
        data = custom,
        notificationId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
        showForeground = flag(data["showForeground"]),
        showBackground = flag(data["showBackground"]),
      )
    }

    fun fromJson(json: JSONObject): NotificationPayload = NotificationPayload(
      title = json.optNonEmpty("title"),
      subtitle = json.optNonEmpty("subtitle"),
      body = json.optNonEmpty("body"),
      imageUrl = json.optNonEmpty("imageUrl"),
      largeIcon = json.optNonEmpty("largeIcon"),
      channelId = json.optNonEmpty("channelId"),
      channel = json.optJSONObject("channel"),
      sound = json.optNonEmpty("sound"),
      smallIcon = json.optNonEmpty("smallIcon"),
      color = json.optNonEmpty("color"),
      collapseId = json.optNonEmpty("collapseId"),
      url = json.optNonEmpty("url"),
      buttons = parseButtons(json.optJSONArray("buttons")),
      data = json.optJSONObject("data")?.let { obj ->
        val out = HashMap<String, String>()
        obj.keys().forEach { k -> out[k] = obj.optString(k) }
        out
      } ?: emptyMap(),
      notificationId = json.optInt("notificationId", (System.currentTimeMillis() and 0x7FFFFFFF).toInt()),
      showForeground = json.optBoolean("showForeground", true),
      showBackground = json.optBoolean("showBackground", true),
    )

    private fun parseButtons(raw: String?): List<Button> =
      raw?.let { runCatching { parseButtons(JSONArray(it)) }.getOrDefault(emptyList()) } ?: emptyList()

    private fun parseButtons(arr: JSONArray?): List<Button> {
      if (arr == null) return emptyList()
      val out = ArrayList<Button>(arr.length())
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val text = o.optString("text", o.optString("id"))
        if (text.isNotEmpty()) out.add(Button(o.optString("id", i.toString()), text))
      }
      return out
    }

    private fun JSONObject.optNonEmpty(key: String): String? =
      optString(key, "").takeIf { it.isNotEmpty() }
  }
}
