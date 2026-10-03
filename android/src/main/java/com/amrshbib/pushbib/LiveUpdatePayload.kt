package com.amrshbib.pushbib

import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class LiveUpdatePayload private constructor(
  val activityId: String,
  val action: String,
  val notificationId: Int,
  val sequenceNumber: Long,
  val streamId: String?,
  val title: String?,
  val body: String?,
  val progress: Int?,
  val indeterminate: Boolean,
  val staleDate: Long?,
  val dismissalDate: Long?,
  val alerting: Boolean,
  val contentState: JSONObject?,
  val data: Map<String, String>,
) {
  val isEnd: Boolean get() = action == ACTION_END
  val isStart: Boolean get() = action == ACTION_START

  fun toJson(): JSONObject = JSONObject().apply {
    put("activityId", activityId)
    put("action", action)
    put("notificationId", notificationId)
    put("sequenceNumber", sequenceNumber)
    putOpt("streamId", streamId)
    put("alerting", alerting)
    putOpt("title", title)
    putOpt("body", body)
    progress?.let { put("progress", it) }
    if (indeterminate) put("indeterminate", true)
    staleDate?.let { put("staleDate", it) }
    dismissalDate?.let { put("dismissalDate", it) }
    putOpt("contentState", contentState)
    put("data", JSONObject(data as Map<*, *>))
  }

  companion object {
    const val TYPE = "LIVE_UPDATE"
    const val ACTION_START = "START"
    const val ACTION_UPDATE = "UPDATE"
    const val ACTION_END = "END"

    private val CONTROL_KEYS = setOf(
      "pushbib_type", "pushbib_live_payload", "activity_id",
      "notification_id", "action", "sequence_number", "stream_id",
    )

    fun isLiveUpdate(message: RemoteMessage): Boolean = message.data["pushbib_type"] == TYPE

    fun fromRemoteMessage(message: RemoteMessage): LiveUpdatePayload? {
      val data = message.data
      if (data["pushbib_type"] != TYPE) return null

      val activityId = data["activity_id"]?.takeIf { it.isNotEmpty() } ?: return null
      val action = data["action"]?.uppercase()?.takeIf {
        it == ACTION_START || it == ACTION_UPDATE || it == ACTION_END
      } ?: return null

      val live = data["pushbib_live_payload"]?.let { runCatching { JSONObject(it) }.getOrNull() }
      val contentState = live?.optJSONObject("contentState")
      val alert = live?.optJSONObject("alert")

      val notificationId = data["notification_id"]?.toIntOrNull()
        ?: live?.optInt("notificationId", 0)?.takeIf { it != 0 }
        ?: (activityId.hashCode() and 0x7FFFFFFF)

      val progressRaw = contentState?.optInt("progress", -1) ?: -1

      return LiveUpdatePayload(
        activityId = activityId,
        action = action,
        notificationId = notificationId,
        sequenceNumber = data["sequence_number"]?.toLongOrNull()
          ?: live?.optLong("sequenceNumber", 0L) ?: 0L,
        streamId = data["stream_id"]?.takeIf { it.isNotEmpty() }
          ?: live?.optNonEmpty("streamId"),
        title = alert?.optNonEmpty("title") ?: contentState?.optNonEmpty("title"),
        body = alert?.optNonEmpty("body")
          ?: contentState?.optNonEmpty("body")
          ?: contentState?.optNonEmpty("message")
          ?: contentState?.optNonEmpty("status"),
        progress = progressRaw.takeIf { it in 0..100 },
        indeterminate = contentState?.optBoolean("indeterminate", false) ?: false,
        staleDate = live?.optLong("staleDate", 0L)?.takeIf { it > 0 },
        dismissalDate = live?.optLong("dismissalDate", 0L)?.takeIf { it > 0 },
        alerting = alert != null,
        contentState = contentState,
        data = HashMap<String, String>().apply {
          for ((k, v) in data) if (k !in CONTROL_KEYS) put(k, v)
        },
      )
    }

    private fun JSONObject.optNonEmpty(key: String): String? =
      optString(key, "").takeIf { it.isNotEmpty() }
  }
}
