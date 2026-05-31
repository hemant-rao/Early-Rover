package com.example.alarm.opengl

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalTime
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Celestial3DView(
    modifier: Modifier = Modifier,
    sunriseTime: LocalTime,
    sunsetTime: LocalTime,
    activeAlarms: List<Pair<Int, Int>>, // Provided for backwards compatibility, can be expanded to JS
    isDark: Boolean = isSystemInDarkTheme()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isHtmlReady by remember { mutableStateOf(false) }

    // Recompute current decimal hour continuously in a coroutine
    var currentHour by remember {
        val now = LocalTime.now()
        mutableStateOf(now.hour + (now.minute / 60.0f) + (now.second / 3600.0f))
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            currentHour = now.hour + (now.minute / 60.0f) + (now.second / 3600.0f)
            delay(10000) // Update position every 10 seconds
        }
    }

    val sunriseHour = sunriseTime.hour + (sunriseTime.minute / 60.0f)
    val sunsetHour = sunsetTime.hour + (sunsetTime.minute / 60.0f)

    // Main thread handler for safe state mutations from background JS bridge
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Javascript Interface Bridge
    val jsBridge = remember {
        object {
            @JavascriptInterface
            fun onHtmlReady() {
                mainHandler.post {
                    isHtmlReady = true
                }
            }
        }
    }

    // Dynamic state synchronizer from Kotlin variables straight to WebGL/Three.js
    LaunchedEffect(isHtmlReady, currentHour, sunriseHour, sunsetHour, isDark) {
        if (isHtmlReady) {
            webViewInstance?.post {
                webViewInstance?.evaluateJavascript(
                    "updateSolarTimes($currentHour, $sunriseHour, $sunsetHour, $isDark)",
                    null
                )
            }
        }
    }

    // Lifecycle manager to pause WebGL rendering loop when activity is paused to conserve battery
    DisposableEffect(lifecycleOwner, webViewInstance) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webViewInstance?.post {
                        webViewInstance?.evaluateJavascript("setRenderingEnabled(true)", null)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    webViewInstance?.post {
                        webViewInstance?.evaluateJavascript("setRenderingEnabled(false)", null)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // Ensure correct transparente styling matches our sleek dark card containers
                setBackgroundColor(AndroidColor.TRANSPARENT)
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    
                    // Critical for local HTML loading/communicating with CDN files and file schemes safely
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Safety fallback to toggle ready state
                        isHtmlReady = true
                        evaluateJavascript(
                            "updateSolarTimes($currentHour, $sunriseHour, $sunsetHour, $isDark)",
                            null
                        )
                    }
                }

                // Inject secure Javascript communication bridge
                addJavascriptInterface(jsBridge, "SolarJSBridge")
                
                // Load local compiled orbital trajectory ThreeJS scene
                loadUrl("file:///android_asset/solar_3d.html")
                webViewInstance = this
            }
        },
        update = { webView ->
            webViewInstance = webView
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
            webViewInstance = null
        }
    )
}
