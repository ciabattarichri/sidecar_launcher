package dev.vq.sidecarlauncher

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/**
 * Google sign-in on the EmbeddedSetup page; grabs the `oauth_token` cookie and exchanges it.
 * If Google refuses the embedded browser, use `phonedash google-token <oauth_token>` from the PC.
 */
class LoginActivity : ComponentActivity() {
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.setAcceptCookie(true)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = check()
        }
        setContentView(web)
        web.loadUrl("https://accounts.google.com/EmbeddedSetup")
    }

    private fun check() {
        if (done) return
        val token = CookieManager.getInstance().getCookie("https://accounts.google.com")
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("oauth_token=") }
            ?.removePrefix("oauth_token=") ?: return
        done = true
        thread {
            val msg = runCatching { GoogleHome.signIn(token) }.exceptionOrNull()?.message
            runOnUiThread {
                Toast.makeText(this, msg ?: tr("Google Home collegato", "Google Home connected"), Toast.LENGTH_LONG).show()
                CookieManager.getInstance().removeAllCookies(null)
                finish()
            }
        }
    }
}
