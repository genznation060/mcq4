package com.example

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bridge.AndroidBridge
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MCQStudioMainActivity"
    }

    private var webView: WebView? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<Uri>()
        val clipData = result.data?.clipData
        val dataUri = result.data?.data

        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i).uri)
            }
        } else if (dataUri != null) {
            uris.add(dataUri)
        }

        fileUploadCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        fileUploadCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    finish()
                }
            }
        })

        setContent {
            MyApplicationTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("mcq_studio_webview"),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setupWebView(this) { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                                loadUrl("file:///android_asset/index.html")
                                webView = this
                            }
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, onShowToast: (String) -> Unit) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        wv.addJavascriptInterface(AndroidBridge(this, onShowToast), "AndroidBridge")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "File picker launch failed", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "[JS Console] ${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Native CORS proxy for external HTTP/HTTPS images and thumbnails
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val lower = url.lowercase()
                    val isImage = lower.contains("ytimg.com") || lower.contains("youtube.com") ||
                            lower.contains("/vi/") || lower.endsWith(".png") ||
                            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                            lower.endsWith(".webp") || lower.endsWith(".gif") ||
                            request.requestHeaders["Accept"]?.contains("image") == true

                    if (isImage) {
                        try {
                            val okReq = okhttp3.Request.Builder()
                                .url(url)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                                .build()
                            val response = okHttpClient.newCall(okReq).execute()
                            val body = response.body
                            if (response.isSuccessful && body != null) {
                                val contentType = body.contentType()?.toString() ?: "image/jpeg"
                                val mimeType = contentType.substringBefore(";")
                                val responseHeaders = mutableMapOf<String, String>()
                                responseHeaders["Access-Control-Allow-Origin"] = "*"
                                responseHeaders["Access-Control-Allow-Methods"] = "GET, OPTIONS"
                                responseHeaders["Access-Control-Allow-Headers"] = "*"
                                return WebResourceResponse(
                                    mimeType,
                                    "UTF-8",
                                    200,
                                    "OK",
                                    responseHeaders,
                                    body.byteStream()
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "CORS Proxy could not load $url: ${e.message}")
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
