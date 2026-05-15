package com.devinai.app

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView

class DevinApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initWebView()
        initCookies()
    }

    private fun initWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private fun initCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
    }
}
