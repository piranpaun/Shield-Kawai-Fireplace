package com.shield.kawaiinavidrome

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class NavidromeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String
)

data class NavidromePlaylist(
    val id: String,
    val name: String,
    val songCount: Int
)

class NavidromeClient(
    context: Context,
    @Volatile var serverUrl: String,
    @Volatile var username: String,
    @Volatile var password: String,
    private val onTrackChanged: (MediaMetadata) -> Unit,
    private val onAudioSessionReady: (Int) -> Unit
) {
    val nfsClient = NfsClient()

    private val routingFactory = DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        RoutingDataSource(nfsClient, http)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(routingFactory))
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                onTrackChanged(mediaMetadata)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                onAudioSessionReady(audioSessionId)
            }
        })
    }

    fun updateCredentials(url: String, user: String, pass: String) {
        serverUrl = url.trim().removeSuffix("/")
        username = user.trim()
        password = pass
    }

    suspend fun loadAndPlayNfsShare(shareSpec: String, shuffle: Boolean = false): Int {
        val files = nfsClient.scanAudioFiles(shareSpec)
        if (files.isEmpty()) return 0

        val ordered = if (shuffle) files.shuffled() else files
        player.clearMediaItems()
        ordered.forEach { file ->
            val cleanTitle = file.name.substringBeforeLast('.')
            val folderName = file.relativePath.substringBeforeLast('/', "Christmas Music")
            val uri = Uri.Builder()
                .scheme("nfs")
                .authority("share")
                .appendPath(file.name)
                .appendQueryParameter("fh", file.fileHandleHex())
                .appendQueryParameter("size", file.size.toString())
                .build()

            val metadata = MediaMetadata.Builder()
                .setTitle(cleanTitle)
                .setArtist(folderName)
                .build()

            val item = MediaItem.Builder()
                .setMediaId(file.relativePath)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()

            player.addMediaItem(item)
        }
        player.prepare()
        player.playWhenReady = true
        return ordered.size
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildAuthParams(): String {
        val salt = UUID.randomUUID().toString().substring(0, 8)
        val token = md5(password + salt)
        return "u=$username&t=$token&s=$salt&v=1.16.1&c=ShieldKawaiiApp&f=json"
    }

    fun getStreamUrl(songId: String): String =
        "$serverUrl/rest/stream?id=$songId&format=raw&${buildAuthParams()}"

    fun getCoverArtUrl(coverId: String): String =
        "$serverUrl/rest/getCoverArt?id=$coverId&size=600&${buildAuthParams()}"

    private suspend fun getJson(endpoint: String, extraParams: String = ""): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val urlStr = "$serverUrl/rest/$endpoint?${buildAuthParams()}$extraParams"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text).optJSONObject("subsonic-response")
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    suspend fun pingServer(): Boolean {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) return false
        val response = getJson("ping") ?: return false
        return response.optString("status") == "ok"
    }

    suspend fun fetchPlaylists(): List<NavidromePlaylist> {
        val response = getJson("getPlaylists") ?: return emptyList()
        val playlistsArray = response.optJSONObject("playlists")?.optJSONArray("playlist")
            ?: return emptyList()

        val result = mutableListOf<NavidromePlaylist>()
        for (i in 0 until playlistsArray.length()) {
            val obj = playlistsArray.getJSONObject(i)
            result.add(
                NavidromePlaylist(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    songCount = obj.optInt("songCount", 0)
                )
            )
        }
        return result
    }

    suspend fun loadAndPlayPlaylist(playlistId: String) {
        val response = getJson("getPlaylist", "&id=$playlistId") ?: return
        val entries = response.optJSONObject("playlist")?.optJSONArray("entry") ?: return
        val tracks = parseTrackArray(entries)
        if (tracks.isNotEmpty()) playTracks(tracks)
    }

    suspend fun loadAndPlayRandomSongs(count: Int = 50) {
        val response = getJson("getRandomSongs", "&size=$count") ?: return
        val songs = response.optJSONObject("randomSongs")?.optJSONArray("song") ?: return
        val tracks = parseTrackArray(songs)
        if (tracks.isNotEmpty()) playTracks(tracks)
    }

    private fun parseTrackArray(jsonArray: JSONArray): List<NavidromeTrack> {
        val tracks = mutableListOf<NavidromeTrack>()
        for (i in 0 until jsonArray.length()) {
            val song = jsonArray.getJSONObject(i)
            val id = song.getString("id")
            tracks.add(
                NavidromeTrack(
                    id = id,
                    title = song.optString("title", "Unknown"),
                    artist = song.optString("artist", "Unknown"),
                    coverArtId = song.optString("coverArt", id)
                )
            )
        }
        return tracks
    }

    fun playTracks(tracks: List<NavidromeTrack>) {
        player.clearMediaItems()
        tracks.forEach { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(Uri.parse(getCoverArtUrl(track.coverArtId)))
                .build()

            val item = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(getStreamUrl(track.id))
                .setMediaMetadata(metadata)
                .build()

            player.addMediaItem(item)
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun nextTrack() = player.seekToNextMediaItem()
    fun prevTrack() = player.seekToPreviousMediaItem()

    fun release() {
        player.release()
        nfsClient.closeReadSocket()
    }
}
