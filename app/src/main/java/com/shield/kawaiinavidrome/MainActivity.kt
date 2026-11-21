package com.shield.kawaiinavidrome

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: KawaiiFireplaceRenderer
    private lateinit var navidromeClient: NavidromeClient
    private lateinit var configManager: ServerConfigManager
    private var audioBridge: AudioEnergyBridge? = null

    private lateinit var hudContainer: LinearLayout
    private lateinit var ivCoverArt: ImageView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var pbTrackProgress: ProgressBar

    private var playlists: List<NavidromePlaylist> = emptyList()
    private var currentPlaylistName: String = "Random Mix"
    private var currentSceneTheme: SceneTheme = SceneTheme.REALISTIC
    private var currentKawaiiPaletteIndex = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable {
        hudContainer.animate().alpha(0f).setDuration(600).start()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = navidromeClient.player
            val duration = player.duration
            if (duration > 0) {
                pbTrackProgress.progress = ((player.currentPosition * 1000) / duration).toInt()
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        hudContainer = findViewById(R.id.hudContainer)
        ivCoverArt = findViewById(R.id.ivCoverArt)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvArtistName = findViewById(R.id.tvArtistName)
        pbTrackProgress = findViewById(R.id.pbTrackProgress)

        renderer = KawaiiFireplaceRenderer()
        glSurfaceView = findViewById<GLSurfaceView>(R.id.glSurfaceView).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        configManager = ServerConfigManager(this)
        val savedMode = configManager.getSceneMode()
        currentSceneTheme = SceneTheme.entries.firstOrNull { it.id == savedMode } ?: SceneTheme.REALISTIC
        currentKawaiiPaletteIndex = configManager.getThemeIndex().coerceIn(0, KawaiiThemes.list.lastIndex)
        applyVisualTheme()

        val creds = configManager.getCredentials()
        navidromeClient = NavidromeClient(
            context = this,
            serverUrl = creds.serverUrl,
            username = creds.username,
            password = creds.password,
            onTrackChanged = { metadata ->
                tvTrackTitle.text = metadata.title ?: "Unknown Track"
                tvArtistName.text = metadata.artist ?: "Unknown Artist"
                ivCoverArt.load(metadata.artworkUri) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(24f))
                }
                showHudTemporarily()
            },
            onAudioSessionReady = { sessionId -> setupVisualizer(sessionId) }
        )

        if (creds.isValid) {
            autoStartMusic()
        } else {
            showServerConfigDialog(isFirstRun = true)
        }
        uiHandler.post(progressRunnable)
    }

    private fun autoStartMusic() {
        activityScope.launch {
            playlists = navidromeClient.fetchPlaylists()
            val christmasList = playlists.firstOrNull {
                it.name.contains("navidad", ignoreCase = true) ||
                it.name.contains("christmas", ignoreCase = true) ||
                it.name.contains("xmas", ignoreCase = true)
            } ?: playlists.firstOrNull()

            if (christmasList != null) {
                currentPlaylistName = christmasList.name
                updateStatusBadge()
                navidromeClient.loadAndPlayPlaylist(christmasList.id)
            } else {
                currentPlaylistName = "Random (50)"
                updateStatusBadge()
                navidromeClient.loadAndPlayRandomSongs(50)
            }
        }
    }

    private fun applyVisualTheme() {
        renderer.sceneMode = currentSceneTheme
        val kawaiiPalette = KawaiiThemes.list[currentKawaiiPaletteIndex]
        renderer.currentTheme = kawaiiPalette

        val accent = if (currentSceneTheme == SceneTheme.KAWAII) {
            kawaiiPalette.hudAccentHex
        } else {
            currentSceneTheme.accentHex
        }

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

    private fun showMenuDialog() {
        val options = mutableListOf(
            "🔥 Theme: Realistic Cozy 3D",
            "🎨 Theme: Studio Ghibli Style",
            "🧸 Theme: Kawaii Christmas",
            "🌈 Kawaii Palette (${KawaiiThemes.list[currentKawaiiPaletteIndex].name})",
            "🔀 Random Mix (50 tracks)",
            "⚙️ Navidrome Server Settings"
        )
        options.addAll(playlists.map { "🎵 ${it.name} (${it.songCount})" })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Fireplace Themes & Navidrome")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        currentSceneTheme = SceneTheme.REALISTIC
                        configManager.saveSceneMode(currentSceneTheme.id)
                        applyVisualTheme()
                        showHudTemporarily()
                    }
                    1 -> {
                        currentSceneTheme = SceneTheme.GHIBLI
                        configManager.saveSceneMode(currentSceneTheme.id)
                        applyVisualTheme()
                        showHudTemporarily()
                    }
                    2 -> {
                        currentSceneTheme = SceneTheme.KAWAII
                        configManager.saveSceneMode(currentSceneTheme.id)
                        applyVisualTheme()
                        showHudTemporarily()
                    }
                    3 -> {
                        currentSceneTheme = SceneTheme.KAWAII
                        currentKawaiiPaletteIndex = (currentKawaiiPaletteIndex + 1) % KawaiiThemes.list.size
                        configManager.saveSceneMode(currentSceneTheme.id)
                        configManager.saveThemeIndex(currentKawaiiPaletteIndex)
                        applyVisualTheme()
                        showHudTemporarily()
                    }
                    4 -> activityScope.launch {
                        currentPlaylistName = "Random Mix"
                        updateStatusBadge()
                        navidromeClient.loadAndPlayRandomSongs(50)
                    }
                    5 -> showServerConfigDialog(isFirstRun = false)
                    else -> activityScope.launch {
                        val selected = playlists[which - 6]
                        currentPlaylistName = selected.name
                        updateStatusBadge()
                        navidromeClient.loadAndPlayPlaylist(selected.id)
                    }
                }
            }
            .show()
    }

    private fun showServerConfigDialog(isFirstRun: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_config, null)
        val etUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etUser = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPass = dialogView.findViewById<EditText>(R.id.etPassword)
        val tvError = dialogView.findViewById<TextView>(R.id.tvConfigError)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveConfig)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelConfig)

        val current = configManager.getCredentials()
        etUrl.setText(current.serverUrl)
        etUser.setText(current.username)
        etPass.setText(current.password)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .setCancelable(!isFirstRun)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (isFirstRun) {
            btnCancel.visibility = View.GONE
        } else {
            btnCancel.setOnClickListener { dialog.dismiss() }
        }

        btnSave.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()

            if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                tvError.text = "⚠️ Please fill in all fields."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnSave.text = "Connecting..."
            tvError.visibility = View.GONE

            activityScope.launch {
                navidromeClient.updateCredentials(url, user, pass)
                val ok = navidromeClient.pingServer()
                if (ok) {
                    configManager.saveCredentials(url, user, pass)
                    dialog.dismiss()
                    autoStartMusic()
                } else {
                    btnSave.isEnabled = true
                    btnSave.text = "Verify & Connect"
                    tvError.text = "❌ Connection failed. Check URL, username, and password."
                    tvError.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
        etUrl.requestFocus()
    }

    private fun updateStatusBadge() {
        val label = if (currentSceneTheme == SceneTheme.KAWAII) {
            "${currentSceneTheme.displayName} (${KawaiiThemes.list[currentKawaiiPaletteIndex].name})"
        } else {
            currentSceneTheme.displayName
        }
        tvStatusBadge.text = "🔥 $label  •  🎵 $currentPlaylistName"
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        showHudTemporarily()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                event?.startTracking()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                navidromeClient.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                showServerConfigDialog(isFirstRun = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                navidromeClient.nextTrack()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                navidromeClient.prevTrack()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                cycleSceneTheme()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showMenuDialog()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event?.isCanceled == false) {
            navidromeClient.togglePlayPause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            showServerConfigDialog(isFirstRun = false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        showHudTemporarily()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        audioBridge?.release()
        navidromeClient.release()
    }
}
