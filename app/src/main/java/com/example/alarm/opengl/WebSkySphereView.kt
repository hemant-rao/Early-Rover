package com.example.alarm.opengl

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalTime
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSkySphereView(
    modifier: Modifier = Modifier,
    currentTime: LocalTime = LocalTime.now(),
    sunriseTime: LocalTime = LocalTime.of(6, 0),
    sunsetTime: LocalTime = LocalTime.of(18, 0),
    isDark: Boolean = isSystemInDarkTheme()
) {
    val currentHour = currentTime.hour + currentTime.minute / 60.0f
    val sunriseHour = sunriseTime.hour + sunriseTime.minute / 60.0f
    val sunsetHour = sunsetTime.hour + sunsetTime.minute / 60.0f

    // Tracks whether the HTML page has finished loading and exposed
    // window.updateSolarTimes(). update() must not run JS before this is set,
    // otherwise evaluateJavascript would no-op or error against a missing global.
    val pageReady = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                // Loading from file:///android_asset/ does not require broad file
                // access; keep it disabled to shrink the WebView attack surface.
                settings.allowFileAccess = false
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mediaPlaybackRequiresUserGesture = false
                
                setBackgroundColor(0) // transparent

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return true
                    }
                }

                // Bridge to inject times after page loads
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHtmlReady() {
                        pageReady.set(true)
                        val script = String.format(
                            Locale.US,
                            "javascript:window.updateSolarTimes(%f, %f, %f, %b);",
                            currentHour, sunriseHour, sunsetHour, isDark
                        )
                        post { evaluateJavascript(script, null) }
                    }
                }, "SolarJSBridge")

                loadUrl("file:///android_asset/sky_sphere.html")
            }
        },
        update = { webView ->
            // Only push updates once the page has loaded and exposed the global
            // function; otherwise the JS call would target a non-existent symbol.
            if (pageReady.get()) {
                val script = String.format(
                    Locale.US,
                    "javascript:window.updateSolarTimes(%f, %f, %f, %b);",
                    currentHour, sunriseHour, sunsetHour, isDark
                )
                webView.evaluateJavascript(script, null)
            }
        }
    )
}
