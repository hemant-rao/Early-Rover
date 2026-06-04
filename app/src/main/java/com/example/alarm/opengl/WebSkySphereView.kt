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
import androidx.compose.runtime.DisposableEffect
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

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
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
            val script = String.format(
                Locale.US,
                "javascript:window.updateSolarTimes(%f, %f, %f, %b);",
                currentHour, sunriseHour, sunsetHour, isDark
            )
            webView.evaluateJavascript(script, null)
        }
    )
}
