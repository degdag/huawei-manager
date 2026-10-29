package com.nasser.huaweimanager

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class RouterWebActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: return finish()
        title = intent.getStringExtra("title") ?: "Router"
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val cm = CookieManager.getInstance()
        intent.getStringArrayListExtra("cookies")?.forEach { cm.setCookie(url, it) }
        cm.flush()
        web.webViewClient = WebViewClient()
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
