package com.amrshbib.pushbib

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PushbibEmitter {
  const val EVENT_PUSH_TOKEN = "Pushbib#pushToken"
  const val EVENT_CLICK = "Pushbib#click"
  const val EVENT_RECEIVED = "Pushbib#received"
  const val EVENT_FOREGROUND_WILL_DISPLAY = "Pushbib#foregroundWillDisplay"

  const val EVENT_LIVE_UPDATE = "Pushbib#liveUpdate"

  private const val QUEUE_CAP = 20
  private const val FOREGROUND_DECISION_TIMEOUT_MS = 5000L

  @Volatile var reactContext: ReactApplicationContext? = null
  @Volatile var jsListening = false
  @Volatile var foregroundHandling = false

  private val queue = ArrayList<Pair<String, JSONObject>>()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val pendingDisplays = ConcurrentHashMap<String, PendingDisplay>()

  private class PendingDisplay(val context: Context, val payload: NotificationPayload, val timeout: Runnable)

  fun onReactContextGone(context: ReactApplicationContext) {
    if (reactContext === context) {
      reactContext = null
      jsListening = false
      foregroundHandling = false
    }
  }

  fun canAskJs(): Boolean {
    val ctx = reactContext
    return foregroundHandling && jsListening && ctx != null && ctx.hasActiveReactInstance()
  }

  fun emitOrQueue(name: String, body: JSONObject) {
    val ctx = reactContext
    if (jsListening && ctx != null && ctx.hasActiveReactInstance()) {
      emit(ctx, name, body)
    } else {
      synchronized(queue) {
        if (queue.size >= QUEUE_CAP) queue.removeAt(0)
        queue.add(name to body)
      }
    }
  }

  fun flushQueue() {
    val ctx = reactContext ?: return
    if (!ctx.hasActiveReactInstance()) return
    val drained: List<Pair<String, JSONObject>>
    synchronized(queue) {
      drained = ArrayList(queue)
      queue.clear()
    }
    for ((name, body) in drained) emit(ctx, name, body)
  }

  private fun emit(ctx: ReactApplicationContext, name: String, body: JSONObject) {
    try {
      ctx
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(name, jsonToWritableMap(body))
    } catch (e: Exception) {
      android.util.Log.w("Pushbib", "emit($name) failed: ${e.message}")
    }
  }

  fun askForegroundDisplay(context: Context, payload: NotificationPayload) {
    val id = UUID.randomUUID().toString()
    val timeout = Runnable { completeDisplay(id, true) }
    pendingDisplays[id] = PendingDisplay(context.applicationContext, payload, timeout)
    mainHandler.postDelayed(timeout, FOREGROUND_DECISION_TIMEOUT_MS)
    emitOrQueue(
      EVENT_FOREGROUND_WILL_DISPLAY,
      JSONObject().put("completionId", id).put("notification", payload.toJson()),
    )
  }

  fun completeDisplay(completionId: String, display: Boolean) {
    val pending = pendingDisplays.remove(completionId) ?: return
    mainHandler.removeCallbacks(pending.timeout)
    if (display) NotificationRenderer.render(pending.context, pending.payload)
  }

  fun jsonToWritableMap(json: JSONObject): WritableMap {
    val map = Arguments.createMap()
    for (key in json.keys()) {
      when (val value = json.opt(key)) {
        null, JSONObject.NULL -> map.putNull(key)
        is JSONObject -> map.putMap(key, jsonToWritableMap(value))
        is JSONArray -> map.putArray(key, jsonToWritableArray(value))
        is Boolean -> map.putBoolean(key, value)
        is Int -> map.putInt(key, value)
        is Long -> map.putDouble(key, value.toDouble())
        is Number -> map.putDouble(key, value.toDouble())
        else -> map.putString(key, value.toString())
      }
    }
    return map
  }

  private fun jsonToWritableArray(json: JSONArray): WritableArray {
    val arr = Arguments.createArray()
    for (i in 0 until json.length()) {
      when (val value = json.opt(i)) {
        null, JSONObject.NULL -> arr.pushNull()
        is JSONObject -> arr.pushMap(jsonToWritableMap(value))
        is JSONArray -> arr.pushArray(jsonToWritableArray(value))
        is Boolean -> arr.pushBoolean(value)
        is Int -> arr.pushInt(value)
        is Long -> arr.pushDouble(value.toDouble())
        is Number -> arr.pushDouble(value.toDouble())
        else -> arr.pushString(value.toString())
      }
    }
    return arr
  }
}
