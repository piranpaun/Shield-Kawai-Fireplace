package com.shield.kawaiinavidrome

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KawaiiFireplaceDreamService : DreamService() {
    private val dreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: KawaiiFireplaceRenderer
    private lateinit var configManager: ServerConfigManager
    private var navidromeClient: NavidromeClient? = null
    private var audioBridge: AudioEnergyBridge? = null

    private lateinit var hudContainer: LinearLayout
    private lateinit var ivCoverArt: ImageView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var pbTrackProgress: ProgressBar

    private var currentSceneTheme: SceneTheme = SceneTheme.REALISTIC
    private var currentKawaiiPaletteIndex = 0
    private var currentPlaylistName = "Screensaver"

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable {
        hudContainer.animate().alpha(0f).setDuration(800).start()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = navidromeClient?.player
            val duration = player?.duration ?: 0L
            if (duration > 0) {
                pbTrackProgress.progress = ((player!!.currentPosition * 1000) / duration).toInt()
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        setContentView(R.layout.activity_main)

        hudContainer = findViewById(R.id.hudContainer)
        ivCoverArt = findViewById(R.id.ivCoverArt)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvArtistName = findViewById(R.id.tvArtistName)
        pbTrackProgress = findViewById(R.id.pbTrackProgress)

        configManager = ServerConfigManager(this)
        val savedMode = configManager.getSceneMode()
        currentSceneTheme = SceneTheme.entries.firstOrNull { it.id == savedMode } ?: SceneTheme.REALISTIC
        currentKawaiiPaletteIndex = configManager.getThemeIndex().coerceIn(0, KawaiiThemes.list.lastIndex)

        renderer = KawaiiFireplaceRenderer()
        glSurfaceView = findViewById<GLSurfaceView>(R.id.glSurfaceView).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        applyVisualTheme()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        glSurfaceView.onResume()

        val creds = configManager.getCredentials()
        if (creds.isValid) {
            navidromeClient = NavidromeClient(
                context = this,
                serverUrl = creds.serverUrl,
                username = creds.username,
                password = creds.password,
                onTrackChanged = { metadata ->
                    tvTrackTitle.text = metadata.title ?: "Cozy Fireplace"
                    tvArtistName.text = metadata.artist ?: "Navidrome"
                    ivCoverArt.load(metadata.artworkUri) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(24f))
                    }
                    showHudTemporarily()
                },
                onAudioSessionReady = { sessionId -> setupVisualizer(sessionId) }
            )

            dreamScope.launch {
                val playlists = navidromeClient?.fetchPlaylists().orEmpty()
                val targetList = playlists.firstOrNull {
                    it.name.contains("navidad", ignoreCase = true) ||
                    it.name.contains("christmas", ignoreCase = true)
                } ?: playlists.firstOrNull()

                if (targetList != null) {
                    currentPlaylistName = targetList.name
                    updateStatusBadge()
                    navidromeClient?.loadAndPlayPlaylist(targetList.id)
                } else {
                    currentPlaylistName = "Random Mix"
                    updateStatusBadge()
                    navidromeClient?.loadAndPlayRandomSongs(50)
                }
            }
            uiHandler.post(progressRunnable)
        } else {
            tvTrackTitle.text = "Ambient Fireplace Mode"
            tvArtistName.text = "Configure Navidrome in the main app"
            showHudTemporarily()
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        glSurfaceView.onPause()
        uiHandler.removeCallbacksAndMessages(null)
        audioBridge?.release()
        audioBridge = null
        navidromeClient?.release()
        navidromeClient = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dreamScope.cancel()
    }

    private fun applyVisualTheme() {
        renderer.sceneMode = currentSceneTheme
        val kawaiiPalette = KawaiiThemes.list[currentKawaiiPaletteIndex]
        renderer.currentTheme = kawaiiPalette
        val accent = if (currentSceneTheme == SceneTheme.KAWAII) kawaiiPalette.hudAccentHex else currentSceneTheme.accentHex
        pbTrackProgress.progressTintList = ColorStateList.valueOf(accent)
        tvStatusBadge.setTextColor(accent)
        (hudContainer.background as? GradientDrawable)?.setStroke(4, accent)
        updateStatusBadge()
    }

    private fun cycleSceneTheme() {
        val nextId = (currentSceneTheme.id + 1) % SceneTheme.entries.size
        currentSceneTheme = SceneTheme.entries.first { it.id == nextId }
        configManager.saveSceneMode(currentSceneTheme.id)
        applyVisualTheme()
        showHudTemporarily()
    }

    private fun updateStatusBadge() {
        tvStatusBadge.text = "🌙 ${currentSceneTheme.displayName}  •  🎵 $currentPlaylistName"
    }

    private fun setupVisualizer(sessionId: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioBridge?.release()
            audioBridge = AudioEnergyBridge(sessionId) { bass, treble ->
                renderer.bassEnergy = bass
                renderer.trebleEnergy = treble
            }
        }
    }

    private fun showHudTemporarily() {
        uiHandler.removeCallbacks(hideHudRunnable)
        hudContainer.animate().alpha(1f).setDuration(250).start()
        uiHandler.postDelayed(hideHudRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            showHudTemporarily()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    navidromeClient?.togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    navidromeClient?.nextTrack()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    navidromeClient?.prevTrack()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    cycleSceneTheme()
                    return true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
