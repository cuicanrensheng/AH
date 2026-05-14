package com.tv.live

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView

class MainActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        initPlayer()
        loadDefaultChannel()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
    }

    private fun loadDefaultChannel() {
        val item = MediaItem.fromUri(Constant.DEFAULT_M3U)
        player?.setMediaItem(item)
        player?.prepare()
        player?.play()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 上下键切换频道（后续扩展）
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 左右键切换线路（后续扩展）
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // OK键播放/暂停
                if (player?.isPlaying == true) player?.pause() else player?.play()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                // 菜单键打开设置（后续扩展）
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // 单击=OK，双击=菜单，长按=收藏（后续扩展）
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
