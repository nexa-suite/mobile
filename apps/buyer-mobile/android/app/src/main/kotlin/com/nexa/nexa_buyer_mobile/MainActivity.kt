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
        if (uri == null && incoming.action == Intent.ACTION_MAIN) {
            return mainIntent()
        }
        if (incoming.action != Intent.ACTION_VIEW || !isStripeReturnUri(uri)) {
            return null
        }
        return Intent(Intent.ACTION_VIEW, uri).setClass(this, MainActivity::class.java)
    }

    private fun mainIntent(): Intent =
        Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)

    private fun isStripeReturnUri(uri: Uri?): Boolean =
        uri != null &&
            uri.scheme.equals(STRIPE_RETURN_SCHEME, ignoreCase = true) &&
            uri.host.equals(STRIPE_RETURN_HOST, ignoreCase = true) &&
            uri.encodedPath.isNullOrEmpty() &&
            uri.fragment == null &&
            uri.userInfo == null &&
            uri.port == -1

    private companion object {
        const val STRIPE_RETURN_SCHEME = "nexa"
        const val STRIPE_RETURN_HOST = "stripe-redirect"
    }
}
