package online.fujinet.go.msx.fujinet

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Hosts the FujiNet web UI served by the in-process runtime (httpService) on
 * loopback. This is the FujiNet-native way to browse hosts and mount disks; it
 * is the same interface as real FujiNet hardware.
 */
class FujiNetWebViewActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
        }
        setContentView(webView)
    }

    companion object {
        const val EXTRA_URL = "url"
        // Matches the FujiNet runtime's `-u 0.0.0.0:8055` web-admin bind
        // (fujinet_android_entry.cpp); reached on loopback.
        const val DEFAULT_URL = "http://127.0.0.1:8055/"
    }
}
