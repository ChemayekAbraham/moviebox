package com.moviebox.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class VideoWebChromeClient(
    private val activity: Activity,
    private val mainContainer: View,
    /** Bottom tab bar (Home / Me...). Its elevation would draw it over the fullscreen video. */
    private val bottomNav: View,
    private val fullscreenContainer: FrameLayout,
    private val progressBar: ProgressBar,
    private val onFileChooser: (ValueCallback<Array<Uri>>?, Intent?) -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    val isFullscreen: Boolean
        get() = customView != null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        if (newProgress < 100) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = newProgress
        } else {
            progressBar.visibility = View.GONE
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        originalOrientation = activity.requestedOrientation

        mainContainer.visibility = View.GONE
        bottomNav.visibility = View.GONE
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Enter immersive fullscreen
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setFullscreenSystemUi(true)
    }

    override fun onHideCustomView() {
        if (customView == null) return

        fullscreenContainer.removeView(customView)
        fullscreenContainer.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE

        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null

        // Exit immersive fullscreen
        activity.requestedOrientation = originalOrientation
        setFullscreenSystemUi(false)
    }

    private fun setFullscreenSystemUi(fullscreen: Boolean) {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            onFileChooser(filePathCallback, intent)
            return true
        } catch (e: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            return false
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        // Block all popup windows to eliminate ad tabs/popunders
        return false
    }
}
