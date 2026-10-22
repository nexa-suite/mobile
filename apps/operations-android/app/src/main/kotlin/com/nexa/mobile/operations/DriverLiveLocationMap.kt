package com.nexa.mobile.operations

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryCoordinates
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverLiveLocation
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R as FulfillmentR
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import org.json.JSONObject

private const val DRIVER_MAP_PAGE_URL =
    "https://appassets.androidplatform.net/assets/driver-map/index.html"
private const val MAX_DRIVER_MAP_LOCATION_AGE_MILLIS = 120_000L

@SuppressLint("MissingOnRenderProcessGone", "SetJavaScriptEnabled")
@Composable
internal fun DriverLiveLocationMap(
    location: DriverLiveLocation,
    modifier: Modifier = Modifier,
    destinationCoordinates: DriverDeliveryCoordinates? = null,
    tileUrlTemplate: String = BuildConfig.MAP_TILE_URL_TEMPLATE
) {
    val offlineMessage = stringResource(FulfillmentR.string.driver_map_offline)
    val providerNotConfiguredMessage =
        stringResource(FulfillmentR.string.driver_map_provider_not_configured)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState = remember(lifecycleOwner) {
        DriverMapLifecycleState(
            isResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    DisposableEffect(lifecycleOwner, lifecycleState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> lifecycleState.pause()
                Lifecycle.Event.ON_RESUME -> lifecycleState.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleState.resume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleState.webView?.let(lifecycleState::release)
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(240.dp),
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                setBackgroundColor(Color.rgb(237, 241, 244))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString =
                        "NexaOperationsMobile/${BuildConfig.VERSION_NAME} " +
                        "(https://github.com/nexa-suite/mobile)"
                }
                val initialLocation = location.takeIf { lifecycleState.isResumed }
                if (!lifecycleState.isResumed) {
                    lifecycleState.blockedSampleId = location.sampleId
                }
                val pageState = DriverMapPageState(
                    location = initialLocation,
                    destinationCoordinates = destinationCoordinates,
                    tileUrlTemplate = tileUrlTemplate,
                    offlineMessage = offlineMessage,
                    providerNotConfiguredMessage = providerNotConfiguredMessage
                )
                tag = pageState
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url
                        if (isAppAssetUrl(url)) {
                            return assetLoader.shouldInterceptRequest(url) ?: blockedMapResponse()
                        }
                        if (!request.isForMainFrame && request.method == "GET" &&
                            pageState.tileRequestPolicy.allows(url.toString())
                        ) {
                            return null
                        }
                        return blockedMapResponse()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (url == DRIVER_MAP_PAGE_URL) {
                            pageState.pageReady = true
                            if (lifecycleState.isResumed) {
                                view.applyMapState(pageState)
                            } else {
                                pageState.location = null
                                pageState.destinationCoordinates = null
                                view.clearDriverLocation()
                            }
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail
                    ): Boolean = true
                }
                loadUrl(DRIVER_MAP_PAGE_URL)
            }.also { lifecycleState.webView = it }
        },
        update = { webView ->
            val pageState = webView.tag as? DriverMapPageState ?: return@AndroidView
            if (!lifecycleState.isResumed) return@AndroidView
            if (lifecycleState.blockedSampleId == location.sampleId) {
                pageState.location = null
                pageState.destinationCoordinates = null
                if (pageState.pageReady) webView.clearDriverLocation()
                return@AndroidView
            }
            lifecycleState.blockedSampleId = null
            pageState.location = location
            pageState.destinationCoordinates = destinationCoordinates
            pageState.tileUrlTemplate = tileUrlTemplate
            pageState.offlineMessage = offlineMessage
            pageState.providerNotConfiguredMessage = providerNotConfiguredMessage
            pageState.tileRequestPolicy = DriverMapTileRequestPolicy(tileUrlTemplate)
            if (pageState.pageReady) webView.applyMapState(pageState)
        },
        onRelease = { webView ->
            lifecycleState.release(webView)
        }
    )
}

private class DriverMapPageState(
    var location: DriverLiveLocation?,
    var destinationCoordinates: DriverDeliveryCoordinates?,
    var tileUrlTemplate: String,
    var offlineMessage: String,
    var providerNotConfiguredMessage: String,
    var tileRequestPolicy: DriverMapTileRequestPolicy = DriverMapTileRequestPolicy(tileUrlTemplate),
    var pageReady: Boolean = false
)

private class DriverMapLifecycleState(var isResumed: Boolean) {
    var webView: WebView? = null
    var blockedSampleId: String? = null

    fun pause() {
        isResumed = false
        val view = webView ?: return
        val pageState = view.tag as? DriverMapPageState
        blockedSampleId = pageState?.location?.sampleId
        pageState?.location = null
        pageState?.destinationCoordinates = null
        if (pageState?.pageReady == true) view.clearDriverLocation()
        view.onPause()
        view.pauseTimers()
    }

    fun resume() {
        isResumed = true
        webView?.onResume()
        webView?.resumeTimers()
    }

    fun release(view: WebView) {
        if (webView !== view) return
        webView = null
        releaseMapWebView(view)
    }
}

private fun WebView.applyMapState(state: DriverMapPageState) {
    val location = state.location
    if (location == null) {
        clearDriverLocation()
        return
    }
    if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
        !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
        !location.accuracyMeters.isFinite() || location.accuracyMeters < 0.0 ||
        !location.isFreshForDisplay()
    ) {
        clearDriverLocation()
        return
    }
    val destination = state.destinationCoordinates?.takeIf { coordinates ->
        coordinates.latitude >= java.math.BigDecimal.valueOf(-90) &&
            coordinates.latitude <= java.math.BigDecimal.valueOf(90) &&
            coordinates.longitude >= java.math.BigDecimal.valueOf(-180) &&
            coordinates.longitude <= java.math.BigDecimal.valueOf(180)
    }
    val destinationCall = destination?.let { coordinates ->
        "window.setDeliveryDestination(" +
            "${coordinates.latitude.toPlainString()}," +
            "${coordinates.longitude.toPlainString()});"
    } ?: "window.clearDeliveryDestination();"
    evaluateJavascript(
        "window.setMapMessages(" +
            "${JSONObject.quote(state.offlineMessage)}," +
            "${JSONObject.quote(state.providerNotConfiguredMessage)});" +
            "window.setTileUrlTemplate(${JSONObject.quote(state.tileUrlTemplate)});" +
            "window.setDriverLocation(" +
            "${location.latitude},${location.longitude},${location.accuracyMeters});" +
            destinationCall,
        null
    )
}

private fun DriverLiveLocation.isFreshForDisplay(): Boolean {
    val parsedCapturedAt = runCatching { Instant.parse(capturedAt) }.getOrNull() ?: return false
    val parsedExpiresAt = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return false
    val now = Instant.now()
    val ageMillis = runCatching { Duration.between(parsedCapturedAt, now).toMillis() }.getOrNull()
        ?: return false
    return !parsedCapturedAt.isAfter(now) && parsedExpiresAt.isAfter(now) &&
        ageMillis in 0..MAX_DRIVER_MAP_LOCATION_AGE_MILLIS
}

private fun WebView.clearDriverLocation() {
    evaluateJavascript("if(window.clearDriverLocation){window.clearDriverLocation();}", null)
}

private fun releaseMapWebView(webView: WebView) {
    runCatching { webView.clearDriverLocation() }
    webView.stopLoading()
    webView.onPause()
    webView.pauseTimers()
    webView.clearHistory()
    webView.clearCache(true)
    webView.destroy()
}

private fun isAppAssetUrl(url: Uri): Boolean =
    url.scheme == "https" && url.host == "appassets.androidplatform.net" &&
        url.path?.startsWith("/assets/") == true &&
        url.userInfo == null && url.query == null && url.fragment == null

private fun blockedMapResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked by driver map request policy",
    emptyMap(),
    ByteArrayInputStream(byteArrayOf())
)
