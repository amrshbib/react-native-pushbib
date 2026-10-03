package com.amrshbib.pushbib

import android.app.ActivityManager
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class PushbibMessagingService : FirebaseMessagingService() {

  override fun onMessageReceived(message: RemoteMessage) {
    if (LiveUpdatePayload.isLiveUpdate(message)) {
      handleLiveUpdate(message)
      return
    }

    val payload = NotificationPayload.fromRemoteMessage(message) ?: return

    PushbibEmitter.emitOrQueue(
      PushbibEmitter.EVENT_RECEIVED,
      JSONObject().put("notification", payload.toJson()),
    )

    if (isOptedOut()) return

    val foreground = isAppInForeground()

    if (foreground && !payload.showForeground) return
    if (!foreground && !payload.showBackground) return

    if (foreground && !localDisplayEnabled("show_foreground")) return
    if (!foreground && !localDisplayEnabled("show_background")) return

    if (foreground && PushbibEmitter.canAskJs()) {
      PushbibEmitter.askForegroundDisplay(applicationContext, payload)
    } else {
      NotificationRenderer.render(applicationContext, payload)
    }
  }

  private fun handleLiveUpdate(message: RemoteMessage) {
    val payload = LiveUpdatePayload.fromRemoteMessage(message) ?: return

    if (isOptedOut()) {
      emitLiveUpdate(payload, LiveUpdateRenderer.OUTCOME_OPTED_OUT)
      return
    }

    if (!localDisplayEnabled("show_live_update")) {
      emitLiveUpdate(payload, LiveUpdateRenderer.OUTCOME_LOCAL_DISPLAY_OFF)
      return
    }

    LiveUpdateRenderer.render(applicationContext, payload) { outcome ->
      emitLiveUpdate(payload, outcome)
    }
  }

  private fun emitLiveUpdate(payload: LiveUpdatePayload, outcome: String) {
    PushbibEmitter.emitOrQueue(
      PushbibEmitter.EVENT_LIVE_UPDATE,
      JSONObject()
        .put("liveUpdate", payload.toJson())
        .put("outcome", outcome)
        .put(
          "displayed",
          outcome == LiveUpdateRenderer.OUTCOME_SHOWN || outcome == LiveUpdateRenderer.OUTCOME_ENDED,
        ),
    )
  }

  override fun onNewToken(token: String) {
    PushbibEmitter.emitOrQueue(PushbibEmitter.EVENT_PUSH_TOKEN, JSONObject().put("token", token))
  }

  private fun isOptedOut(): Boolean =
    getSharedPreferences("pushbib_sdk", Context.MODE_PRIVATE).getString("opted_out", null) == "1"

  private fun localDisplayEnabled(key: String): Boolean =
    getSharedPreferences("pushbib_sdk", Context.MODE_PRIVATE).getString(key, null) != "0"

  private fun isAppInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }
}
