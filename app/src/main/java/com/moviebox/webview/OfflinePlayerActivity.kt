package com.moviebox.webview

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Fullscreen player for downloaded videos — works with no internet connection. */
class OfflinePlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private var resumePosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        videoView = VideoView(this)
        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        setContentView(root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        intent.getStringExtra(EXTRA_TITLE)?.let { title = it }

        resumePosition = savedInstanceState?.getInt(STATE_POSITION) ?: 0
        val controller = MediaController(this).apply { setAnchorView(videoView) }
        videoView.setMediaController(controller)
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener {
            if (resumePosition > 0) videoView.seekTo(resumePosition)
            videoView.start()
            controller.show(3000)
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "This file can't be played", Toast.LENGTH_LONG).show()
            finish()
            true
        }
        videoView.setOnCompletionListener { finish() }
    }

    override fun onPause() {
        resumePosition = videoView.currentPosition
        videoView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resumePosition > 0) {
            videoView.seekTo(resumePosition)
            videoView.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POSITION, videoView.currentPosition)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "position"
    }
}
