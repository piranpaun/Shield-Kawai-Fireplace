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
import android.widget.RadioButton
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
    private var currentSourceLabel: String = "NFS: Christmas Music"

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

        val warmAccent = 0xFFFFB703.toInt()
        pbTrackProgress.progressTintList = ColorStateList.valueOf(warmAccent)
        tvStatusBadge.setTextColor(warmAccent)
        (hudContainer.background as? GradientDrawable)?.setStroke(4, warmAccent)

        renderer = KawaiiFireplaceRenderer(this)
        glSurfaceView = findViewById<GLSurfaceView>(R.id.glSurfaceView).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        configManager = ServerConfigManager(this)
        val creds = configManager.getCredentials()

        navidromeClient = NavidromeClient(
            context = this,
            serverUrl = creds.serverUrl,
            username = creds.username,
            password = creds.password,
            onTrackChanged = { metadata ->
                tvTrackTitle.text = metadata.title ?: "Christmas Track"
                tvArtistName.text = metadata.artist ?: currentSourceLabel
                if (metadata.artworkUri != null) {
                    ivCoverArt.load(metadata.artworkUri) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(24f))
                    }
                } else {
                    ivCoverArt.setImageResource(R.drawable.fireplace_base)
                }
                showHudTemporarily()
            },
            onAudioSessionReady = { sessionId -> setupVisualizer(sessionId) }
        )

        autoStartMusic()
        uiHandler.post(progressRunnable)
    }

    private fun autoStartMusic() {
        if (configManager.isUseNfs()) {
            startNfsPlayback(shuffle = false)
        } else {
            val creds = configManager.getCredentials()
            if (creds.isValid) {
                startNavidromePlayback()
            } else {
                showServerConfigDialog(isFirstRun = false)
            }
        }
    }

    private fun startNfsPlayback(shuffle: Boolean) {
        val share = configManager.getNfsShare()
        currentSourceLabel = "NFS: ${share.substringAfter(':')}"
        tvStatusBadge.text = "📂 $currentSourceLabel"
        tvTrackTitle.text = "Connecting to NFS..."
        tvArtistName.text = share
        showHudTemporarily()

        activityScope.launch {
            try {
                val count = navidromeClient.loadAndPlayNfsShare(share, shuffle = shuffle)
                if (count > 0) {
                    tvStatusBadge.text = "📂 $currentSourceLabel ($count tracks)"
                } else {
                    tvTrackTitle.text = "No audio files found on NFS"
                    tvArtistName.text = "Press D-Pad Down to configure"
                }
            } catch (e: Exception) {
                tvTrackTitle.text = "NFS Connection Error"
                tvArtistName.text = e.message ?: share
            }
            showHudTemporarily()
        }
    }

    private fun startNavidromePlayback() {
        activityScope.launch {
            playlists = navidromeClient.fetchPlaylists()
            val christmasList = playlists.firstOrNull {
                it.name.contains("navidad", ignoreCase = true) ||
                it.name.contains("christmas", ignoreCase = true) ||
                it.name.contains("xmas", ignoreCase = true)
            } ?: playlists.firstOrNull()

            if (christmasList != null) {
                currentSourceLabel = "Navidrome: ${christmasList.name}"
                tvStatusBadge.text = "🎵 $currentSourceLabel"
                navidromeClient.loadAndPlayPlaylist(christmasList.id)
            } else {
                currentSourceLabel = "Navidrome: Random Mix"
                tvStatusBadge.text = "🎵 $currentSourceLabel"
                navidromeClient.loadAndPlayRandomSongs(50)
            }
        }
    }

    private fun showMenuDialog() {
        val nfsShare = configManager.getNfsShare()
        val options = mutableListOf(
            "📂 Play NFS Share ($nfsShare)",
            "🔀 Shuffle NFS Share",
            "⚙️ Configure NFS / Navidrome Source",
            "🎲 Navidrome Random Mix (50 tracks)"
        )
        options.addAll(playlists.map { "🎵 Navidrome: ${it.name} (${it.songCount})" })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Music Source & Playlists")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        configManager.saveUseNfs(true)
                        startNfsPlayback(shuffle = false)
                    }
                    1 -> {
                        configManager.saveUseNfs(true)
                        startNfsPlayback(shuffle = true)
                    }
                    2 -> showServerConfigDialog(isFirstRun = false)
                    3 -> {
                        configManager.saveUseNfs(false)
                        activityScope.launch {
                            currentSourceLabel = "Navidrome: Random Mix"
                            tvStatusBadge.text = "🎵 $currentSourceLabel"
                            navidromeClient.loadAndPlayRandomSongs(50)
                        }
                    }
                    else -> {
                        configManager.saveUseNfs(false)
                        activityScope.launch {
                            val selected = playlists[which - 4]
                            currentSourceLabel = "Navidrome: ${selected.name}"
                            tvStatusBadge.text = "🎵 $currentSourceLabel"
                            navidromeClient.loadAndPlayPlaylist(selected.id)
                        }
                    }
                }
            }
            .show()
    }

    private fun showServerConfigDialog(isFirstRun: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_config, null)
        val rbNfs = dialogView.findViewById<RadioButton>(R.id.rbSourceNfs)
        val rbNavi = dialogView.findViewById<RadioButton>(R.id.rbSourceNavidrome)
        val etNfs = dialogView.findViewById<EditText>(R.id.etNfsShare)
        val etUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etUser = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPass = dialogView.findViewById<EditText>(R.id.etPassword)
        val tvError = dialogView.findViewById<TextView>(R.id.tvConfigError)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveConfig)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelConfig)

        val current = configManager.getCredentials()
        etNfs.setText(configManager.getNfsShare())
        etUrl.setText(current.serverUrl)
        etUser.setText(current.username)
        etPass.setText(current.password)

        if (configManager.isUseNfs()) rbNfs.isChecked = true else rbNavi.isChecked = true

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .setCancelable(!isFirstRun)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val useNfs = rbNfs.isChecked
            val nfsPath = etNfs.text.toString().trim()
            val url = etUrl.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()

            if (useNfs) {
                if (nfsPath.isBlank() || !nfsPath.contains(':')) {
                    tvError.text = "⚠️ Enter NFS path as host:/folder (e.g. 192.168.1.53:/Alex/Christmas Music)"
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                configManager.saveNfsShare(nfsPath)
                configManager.saveUseNfs(true)
                dialog.dismiss()
                startNfsPlayback(shuffle = false)
            } else {
                if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                    tvError.text = "⚠️ Fill in Navidrome URL, username, and password."
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
                        configManager.saveUseNfs(false)
                        dialog.dismiss()
                        startNavidromePlayback()
                    } else {
                        btnSave.isEnabled = true
                        btnSave.text = "Connect & Play"
                        tvError.text = "❌ Navidrome connection failed."
                        tvError.visibility = View.VISIBLE
                    }
                }
            }
        }

        dialog.show()
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
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
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
