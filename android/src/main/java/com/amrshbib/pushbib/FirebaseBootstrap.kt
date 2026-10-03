package com.amrshbib.pushbib

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

object FirebaseBootstrap {
  private const val APP_NAME = "PUSHBIB"

  @Synchronized
  fun messaging(context: Context, config: JSONObject): FirebaseMessaging {
    val options = FirebaseOptions.Builder()
      .setGcmSenderId(config.getString("sender_id"))
      .setApplicationId(config.getString("app_id"))
      .setApiKey(config.getString("api_key"))
      .apply {
        config.optString("project_id").takeIf { it.isNotEmpty() }?.let { setProjectId(it) }
      }
      .build()

    val existing = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
    val app = when {
      existing == null -> FirebaseApp.initializeApp(context, options, APP_NAME)
      sameOptions(existing.options, options) -> existing
      else -> {
        existing.delete()
        FirebaseApp.initializeApp(context, options, APP_NAME)
      }
    }
    return app.get(FirebaseMessaging::class.java)
  }

  private fun sameOptions(a: FirebaseOptions, b: FirebaseOptions): Boolean =
    a.applicationId == b.applicationId && a.gcmSenderId == b.gcmSenderId && a.apiKey == b.apiKey
}
