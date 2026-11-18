package com.nexa.nexa_buyer_mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.flutter.embedding.android.FlutterFragmentActivity

class MainActivity : FlutterFragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val safeIntent = sanitizeIncomingIntent(intent)
        setIntent(safeIntent ?: mainIntent())
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(newIntent: Intent) {
        val safeIntent = sanitizeIncomingIntent(newIntent) ?: return
        setIntent(safeIntent)
        super.onNewIntent(safeIntent)
    }

    private fun sanitizeIncomingIntent(incoming: Intent): Intent? {
        val uri = incoming.data
        val (googleMessageId, messageId) = readFcmMessageIdCandidates(incoming, uri)
        val route = BuyerIncomingIntentPolicy.sanitize(
            action = incoming.action,
            uri = uri?.let {
                BuyerUriParts(
                    scheme = it.scheme,
                    host = it.host,
                    encodedPath = it.encodedPath,
                    fragment = it.fragment,
                    userInfo = it.userInfo,
                    port = it.port,
                )
            },
            googleMessageId = googleMessageId,
            messageId = messageId,
        ) ?: return null

        return when (route) {
            is BuyerIncomingRoute.Main -> mainIntent().apply {
                route.fcmMessageIds.forEach { (key, value) -> putExtra(key, value) }
            }
            BuyerIncomingRoute.StripeReturn ->
                Intent(Intent.ACTION_VIEW, uri).setClass(this, MainActivity::class.java)
        }
    }

    private fun readFcmMessageIdCandidates(
        incoming: Intent,
        uri: Uri?,
    ): Pair<String?, String?> {
        if (incoming.action != Intent.ACTION_MAIN || uri != null) return null to null
        return readStringExtra(incoming, BuyerIncomingIntentPolicy.FCM_GOOGLE_MESSAGE_ID) to
            readStringExtra(incoming, BuyerIncomingIntentPolicy.FCM_MESSAGE_ID)
    }

    private fun readStringExtra(incoming: Intent, key: String): String? = try {
        incoming.extras?.getString(key)
    } catch (_: RuntimeException) {
        null
    }

    private fun mainIntent(): Intent = Intent(this, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)

}
