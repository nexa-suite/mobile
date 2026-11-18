package com.nexa.nexa_buyer_mobile

internal data class BuyerUriParts(
    val scheme: String?,
    val host: String?,
    val encodedPath: String?,
    val fragment: String?,
    val userInfo: String?,
    val port: Int,
)

internal sealed interface BuyerIncomingRoute {
    data class Main(val fcmMessageIds: Map<String, String>) : BuyerIncomingRoute

    data object StripeReturn : BuyerIncomingRoute
}

/** Allows only a launcher wake-up and the exact Stripe callback route. */
internal object BuyerIncomingIntentPolicy {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val FCM_GOOGLE_MESSAGE_ID = "google.message_id"
    const val FCM_MESSAGE_ID = "message_id"
    const val MAX_FCM_MESSAGE_ID_LENGTH = 256

    private const val STRIPE_RETURN_SCHEME = "nexa"
    private const val STRIPE_RETURN_HOST = "stripe-redirect"

    fun sanitize(
        action: String?,
        uri: BuyerUriParts?,
        googleMessageId: Any?,
        messageId: Any?,
    ): BuyerIncomingRoute? {
        if (uri == null && action == ACTION_MAIN) {
            return BuyerIncomingRoute.Main(
                sanitizeFcmMessageIds(googleMessageId, messageId),
            )
        }
        if (action == ACTION_VIEW && isStripeReturnUri(uri)) {
            return BuyerIncomingRoute.StripeReturn
        }
        return null
    }

    private fun isStripeReturnUri(uri: BuyerUriParts?): Boolean =
        uri != null &&
            uri.scheme.equals(STRIPE_RETURN_SCHEME, ignoreCase = true) &&
            uri.host.equals(STRIPE_RETURN_HOST, ignoreCase = true) &&
            uri.encodedPath.isNullOrEmpty() &&
            uri.fragment == null &&
            uri.userInfo == null &&
            uri.port == -1

    private fun sanitizeFcmMessageIds(
        googleMessageId: Any?,
        messageId: Any?,
    ): Map<String, String> = buildMap {
        validMessageId(googleMessageId)?.let { put(FCM_GOOGLE_MESSAGE_ID, it) }
        validMessageId(messageId)?.let { put(FCM_MESSAGE_ID, it) }
    }

    private fun validMessageId(value: Any?): String? {
        val messageId = value as? String ?: return null
        if (
            messageId.isEmpty() ||
            messageId.length > MAX_FCM_MESSAGE_ID_LENGTH ||
            messageId.any { it.isWhitespace() || it.isISOControl() }
        ) {
            return null
        }
        return messageId
    }
}
